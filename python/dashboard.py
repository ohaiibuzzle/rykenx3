#!/usr/bin/env python3
"""Live terminal dashboard for the RYKEN RK-X3 USB power meter.

Usage:  python dashboard.py [--serial SN] [--window SECONDS]
Quit with Ctrl-C (the meter is released cleanly).
"""
import argparse
import collections
import sys
import time

from rich import box
from rich.console import Group
from rich.layout import Layout
from rich.live import Live
from rich.panel import Panel
from rich.table import Table
from rich.text import Text

import ryken

UI_HZ = 10
BLOCKS = " ▁▂▃▄▅▆▇█"


class Stats:
    def __init__(self, window_s):
        self.start = time.monotonic()
        self.last = None
        self.last_t = None
        self.frames = 0
        self.charge = self.energy = 0.0
        self.lo = {}
        self.hi = {}
        self.recent = collections.deque()  # frame timestamps over the last second
        n = int(window_s * UI_HZ)
        self.hist = {k: collections.deque(maxlen=n) for k in ("voltage_V", "current_A", "power_W")}

    def add(self, t, s):
        if self.last_t is not None:
            dt_h = (t - self.last_t) / 3600
            self.charge += s["current_A"] * dt_h
            self.energy += s["power_W"] * dt_h
        self.last_t, self.last = t, s
        self.frames += 1
        for k in self.hist:
            self.lo[k] = min(self.lo.get(k, s[k]), s[k])
            self.hi[k] = max(self.hi.get(k, s[k]), s[k])
        self.recent.append(t)
        while self.recent and t - self.recent[0] > 1:
            self.recent.popleft()

    def tick(self):
        if self.last:
            for k, h in self.hist.items():
                h.append(self.last[k])

    @property
    def elapsed(self):
        return time.monotonic() - self.start


def sparkline(values, width):
    vals = list(values)[-width:]
    if not vals:
        return ""
    lo, hi = min(vals), max(vals)
    span = (hi - lo) or 1
    return "".join(BLOCKS[1 + int((v - lo) / span * 7)] for v in vals)


def chart(values, width, height):
    """Multi-row bar chart using eighth-blocks; returns (rows, lo, hi)."""
    vals = list(values)[-width:]
    if not vals:
        return [""] * height, 0, 0
    lo, hi = min(vals), max(vals)
    if hi - lo < 1e-6:
        lo, hi = lo - 0.5, hi + 0.5
    lo = max(0.0, lo)
    levels = [round((v - lo) / (hi - lo) * height * 8) for v in vals]
    rows = []
    for r in range(height - 1, -1, -1):
        rows.append("".join(BLOCKS[max(0, min(8, lv - r * 8))] for lv in levels))
    return rows, lo, hi


def cc_state(v):
    # USB-C sink-side CC voltage ranges (vRd) -> advertised source current
    if v < 0.2:
        return "open"
    if v <= 0.66:
        return "USB default"
    if v <= 1.23:
        return "1.5 A"
    if v <= 2.04:
        return "3.0 A"
    return "?"


def card(title, key, value, unit, color, st, width):
    body = Text()
    body.append(f"{value:.4f}" if unit == "A" else f"{value:.3f}", style=f"bold {color}")
    body.append(f" {unit}\n", style=color)
    body.append(f"min {st.lo.get(key, 0):.3f}  max {st.hi.get(key, 0):.3f}\n", style="dim")
    body.append(sparkline(st.hist[key], max(10, width - 4)), style=color)
    return Panel(body, title=title, border_style=color, box=box.ROUNDED)


def render(st, info, width, height):
    s = st.last or {k: 0.0 for k in ("voltage_V", "current_A", "power_W", "dplus_V",
                                      "dminus_V", "cc1_V", "cc2_V", "phone_power")} | {"temperature_raw": None}
    layout = Layout()
    layout.split_column(
        Layout(name="head", size=3),
        Layout(name="cards", size=6),
        Layout(name="chart", ratio=1),
        Layout(name="bottom", size=8),
        Layout(name="foot", size=1),
    )

    fps = len(st.recent)
    live = "[green]● LIVE[/]" if fps else "[red]● NO DATA[/]"
    mins, secs = divmod(int(st.elapsed), 60)
    hrs, mins = divmod(mins, 60)
    layout["head"].update(Panel(
        f"[bold]RYKEN RK-X3[/]  sn {info['serial']}  HW {info['hardware']}  FW {info['firmware']}"
        f"   {live}  {fps} fps   ⏱ {hrs:02}:{mins:02}:{secs:02}",
        box=box.ROUNDED, border_style="bright_black"))

    cw = width // 3
    layout["cards"].split_row(
        Layout(card("Voltage", "voltage_V", s["voltage_V"], "V", "yellow", st, cw)),
        Layout(card("Current", "current_A", s["current_A"], "A", "cyan", st, cw)),
        Layout(card("Power", "power_W", s["power_W"], "W", "magenta", st, cw)),
    )

    ch = max(3, height - 3 - 6 - 8 - 1 - 2)
    cwid = max(10, width - 12)
    rows, lo, hi = chart(st.hist["power_W"], cwid, ch)
    ct = Text()
    for i, row in enumerate(rows):
        label = f"{hi:7.2f} " if i == 0 else f"{lo:7.2f} " if i == len(rows) - 1 else " " * 8
        ct.append(label, style="dim")
        ct.append(row + "\n", style="magenta")
    secs_shown = min(len(st.hist["power_W"]), cwid) / UI_HZ
    layout["chart"].update(Panel(ct, title=f"Power (W) · last {secs_shown:.0f} s",
                                 border_style="magenta", box=box.ROUNDED))

    lines = Table(box=None, expand=True, show_header=False, padding=(0, 1))
    lines.add_column(style="bold", width=4)
    lines.add_column(justify="right", width=7)
    lines.add_column(ratio=1)
    lines.add_column(style="dim", width=11)
    for name, key, color, note in (
        ("D+", "dplus_V", "green", ""), ("D−", "dminus_V", "green", ""),
        ("CC1", "cc1_V", "blue", cc_state(s["cc1_V"])), ("CC2", "cc2_V", "blue", cc_state(s["cc2_V"])),
    ):
        v = s[key]
        bar_w = max(4, width // 2 - 38)  # panel inner width minus the fixed columns
        filled = int(min(v, 5.0) / 5.0 * bar_w)
        lines.add_row(name, f"{v:.3f} V",
                      Text("█" * filled, style=color) + Text("░" * (bar_w - filled), style="bright_black"),
                      note)

    sess = Table(box=None, expand=True, show_header=False, padding=(0, 1))
    sess.add_column(style="dim")
    sess.add_column(justify="right", style="bold")
    avg_w = st.energy / (st.elapsed / 3600) if st.elapsed > 1 else 0
    temp = s["temperature_raw"]
    sess.add_row("Charge", f"{st.charge * 1000:.3f} mAh")
    sess.add_row("Energy", f"{st.energy * 1000:.3f} mWh")
    sess.add_row("Avg / peak power", f"{avg_w:.3f} / {st.hi.get('power_W', 0):.3f} W")
    sess.add_row("Temperature (raw)", "n/a" if temp is None else str(temp))
    sess.add_row("Phone power", f"{s['phone_power']:.1f}")
    sess.add_row("Frames", f"{st.frames:,}")

    layout["bottom"].split_row(
        Layout(Panel(lines, title="Data lines", border_style="green", box=box.ROUNDED)),
        Layout(Panel(sess, title="Session", border_style="blue", box=box.ROUNDED)),
    )
    layout["foot"].update(Text(" Ctrl-C to quit · Ah/Wh counted since dashboard start", style="dim"))
    return layout


def main():
    p = argparse.ArgumentParser(description="RYKEN RK-X3 live dashboard")
    p.add_argument("--serial", help="USB serial of the meter (default: first found)")
    p.add_argument("--window", type=float, default=120, help="history kept for charts, seconds")
    args = p.parse_args()

    meter = ryken.Meter(args.serial)
    try:
        info = meter.handshake(claim=True)
        st = Stats(args.window)
        with Live(screen=True, auto_refresh=False) as live:
            last_ui = 0.0
            for t, cmd, f in meter.frames():
                if cmd == ryken.RSP_MEASURE:
                    st.add(t, ryken.parse_measurement(f))
                if t - last_ui >= 1 / UI_HZ:
                    last_ui = t
                    st.tick()
                    c = live.console
                    live.update(render(st, info, c.width, c.height), refresh=True)
    except KeyboardInterrupt:
        pass
    finally:
        meter.close()


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as e:
        sys.exit(f"error: {e}")
