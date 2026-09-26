#!/usr/bin/env python3
"""Logger / reader for the RYKEN RK-X3 USB power meter (see ../PROTOCOL.md).

Usage:
  python ryken.py info                     # handshake only: IDs, versions, claimed status
  python ryken.py log [-o out.csv] [-i 0.2] [-d SECONDS] [--sniff pd|ufcs]
  python ryken.py read out.csv [--rows N]  # summarise a saved log

Requires: pip install hidapi   (only needed for `info` / `log`)
"""
import argparse
import csv
import datetime as dt
import struct
import sys
import time

VID, PID = 0x2E3C, 0xAF03
HOST_HEAD, DEV_HEAD = 0xAA, 0x55

CMD_HANDSHAKE, CMD_SAMPLING, CMD_PROTOCOL = 0x00, 0x02, 0x03
CMD_DISCONNECT, CMD_HEARTBEAT, CMD_CLAIM = 0x05, 0x06, 0xFF
RSP_MEASURE, RSP_PROTOCOL = 0x20, 0x30

TEMP_ABSENT = 10000
HEARTBEAT_S = 1.0

CSV_FIELDS = ["time", "elapsed_s", "voltage_V", "current_A", "power_W",
              "dplus_V", "dminus_V", "cc1_V", "cc2_V", "temperature_raw",
              "phone_power", "charge_Ah", "energy_Wh"]


# --- protocol ---------------------------------------------------------------

def crc16(data):
    r = 0x0011
    for b in data:
        r ^= b << 8
        for _ in range(8):
            r = ((r << 1) ^ 0x2507) if r & 0x8000 else (r << 1)
            r &= 0xFFFF
    return (r + 1) & 0xFFFF


def build_frame(cmd, payload=b""):
    body = bytes([HOST_HEAD, len(payload) + 5, cmd]) + bytes(payload)
    return body + crc16(body).to_bytes(2, "big")


def split_frame(report):
    """Return (cmd, frame_bytes) for a device report, or None if it's not a valid frame."""
    if len(report) < 5 or report[0] != DEV_HEAD:
        return None
    n = report[1]
    if n < 5 or n > len(report):
        return None
    frame = bytes(report[:n])
    if crc16(frame[:-2]) != int.from_bytes(frame[-2:], "big"):
        return None
    return frame[2], frame


def parse_measurement(f):
    v, i = struct.unpack_from(">ff", f, 3)
    dp, dm, cc1, cc2, temp, phone = struct.unpack_from(">6H", f, 11)
    return {
        "voltage_V": v, "current_A": i, "power_W": v * i,
        "dplus_V": dp / 1000, "dminus_V": dm / 1000,
        "cc1_V": cc1 / 1000, "cc2_V": cc2 / 1000,
        "temperature_raw": None if temp == TEMP_ABSENT else temp,
        "phone_power": phone / 10,
    }


class Meter:
    def __init__(self, serial=None):
        import hid  # imported lazily so `read` works without hidapi
        self.dev = hid.device()
        self.dev.open(VID, PID, serial)
        self.claimed = False

    def send(self, cmd, payload=b""):
        self.dev.write([0x00] + list(build_frame(cmd, payload)))

    def read(self, timeout_ms=100):
        r = self.dev.read(65, timeout_ms)
        return split_frame(bytes(r)) if r else None

    def handshake(self, claim):
        now = dt.datetime.now()
        ts = bytes([now.year % 100, now.month, now.day, now.hour, now.minute])
        self.send(CMD_HANDSHAKE, ts)
        deadline = time.time() + 3
        while time.time() < deadline:
            got = self.read(200)
            if got and got[0] == CMD_HANDSHAKE:
                break
        else:
            raise RuntimeError("no handshake reply from meter")
        f = got[1]
        status, mcu, sn, hw, sw = f[3], f[4:16], f[16:20], f[20], f[21]
        sig = int.from_bytes(f[22:24], "big")
        info = {
            "status": "claimed by another host" if status == 1 else "free",
            "mcu_id": mcu.hex(), "serial": sn.hex(),
            "hardware": f"V{hw // 10}.{hw % 10}",
            "firmware": f"V{sw // 100}.{sw % 100:02}",
            "signature_ok": sig == crc16(bytes([status]) + mcu + sn + ts),
        }
        if claim:
            if status == 1:
                raise RuntimeError("meter is already claimed by another host")
            if not info["signature_ok"]:
                raise RuntimeError("handshake signature mismatch")
            self.send(CMD_CLAIM, b"ready" + sig.to_bytes(2, "big"))
            self.claimed = True
        return info

    def frames(self, duration=None):
        """Yield (monotonic_time, cmd, frame) from a claimed meter, sending heartbeats."""
        start = last_hb = time.monotonic()
        while not duration or time.monotonic() - start < duration:
            if time.monotonic() - last_hb >= HEARTBEAT_S:
                self.send(CMD_HEARTBEAT, b"\x01")
                last_hb = time.monotonic()
            got = self.read(100)
            if got:
                yield (time.monotonic(),) + got

    def close(self, sniffing=False):
        try:
            if self.claimed:
                if sniffing:
                    self.send(CMD_PROTOCOL, bytes([0, 0]))
                self.send(CMD_DISCONNECT, b"\x01")
        finally:
            self.dev.close()


# --- commands -----------------------------------------------------------------

def cmd_info(args):
    m = Meter(args.serial)
    try:
        for k, v in m.handshake(claim=False).items():
            print(f"{k:13s} {v}")
    finally:
        m.close()


def cmd_log(args):
    out = args.output or dt.datetime.now().strftime("ryken_%Y%m%d_%H%M%S.csv")
    m = Meter(args.serial)
    sniff_file = None
    try:
        info = m.handshake(claim=True)
        print(f"Connected: serial {info['serial']}, HW {info['hardware']}, FW {info['firmware']}")
        if args.sniff:
            m.send(CMD_PROTOCOL, bytes([1, 0 if args.sniff == "pd" else 1]))
            sniff_path = out.rsplit(".", 1)[0] + f"_{args.sniff}.csv"
            sniff_file = open(sniff_path, "w", newline="")
            sniff_csv = csv.writer(sniff_file)
            sniff_csv.writerow(["time", "payload_hex"])
            print(f"Sniffing {args.sniff.upper()} -> {sniff_path}")

        with open(out, "w", newline="") as fh:
            w = csv.DictWriter(fh, CSV_FIELDS)
            w.writeheader()
            print(f"Logging to {out}  (Ctrl-C to stop)")
            start = last_frame = last_row = time.monotonic()
            charge = energy = 0.0
            frames = rows = 0
            for now, cmd, f in m.frames(args.duration):
                if cmd == RSP_PROTOCOL and sniff_file:
                    sniff_csv.writerow([dt.datetime.now().isoformat(timespec="milliseconds"), f[3:-2].hex()])
                    continue
                if cmd != RSP_MEASURE:
                    continue
                s = parse_measurement(f)
                # the meter doesn't send Ah/Wh, so integrate every frame on the host
                dt_h = (now - last_frame) / 3600
                last_frame = now
                if frames:
                    charge += s["current_A"] * dt_h
                    energy += s["power_W"] * dt_h
                frames += 1
                if args.interval and now - last_row < args.interval:
                    continue
                last_row = now
                row = {"time": dt.datetime.now().isoformat(timespec="milliseconds"),
                       "elapsed_s": round(now - start, 3), **s,
                       "charge_Ah": charge, "energy_Wh": energy}
                w.writerow({k: fmt(v) for k, v in row.items()})
                rows += 1
                print(f"\r{row['elapsed_s']:9.1f}s  {s['voltage_V']:7.3f} V  {s['current_A']:7.4f} A  "
                      f"{s['power_W']:8.3f} W  D+ {s['dplus_V']:.3f}  D- {s['dminus_V']:.3f}  "
                      f"CC1 {s['cc1_V']:.3f}  CC2 {s['cc2_V']:.3f}  {charge:.6f} Ah  {energy:.6f} Wh",
                      end="", flush=True)
    except KeyboardInterrupt:
        pass
    finally:
        m.close(sniffing=bool(args.sniff))
        if sniff_file:
            sniff_file.close()
    print(f"\n{rows} rows ({frames} frames) written to {out}")


def fmt(v):
    if v is None:
        return ""
    return f"{v:.6g}" if isinstance(v, float) else v


def cmd_read(args):
    with open(args.file, newline="") as fh:
        rows = list(csv.DictReader(fh))
    if not rows:
        print("empty log")
        return
    print(f"{args.file}: {len(rows)} rows, {rows[0]['time']} -> {rows[-1]['time']} "
          f"({float(rows[-1]['elapsed_s']):.1f} s)\n")
    print(f"{'field':16s}{'min':>12s}{'avg':>12s}{'max':>12s}")
    for k in CSV_FIELDS[2:11]:
        vals = [float(r[k]) for r in rows if r.get(k) not in (None, "")]
        if not vals:
            print(f"{k:16s}{'n/a':>12s}")
            continue
        print(f"{k:16s}{min(vals):12.4f}{sum(vals) / len(vals):12.4f}{max(vals):12.4f}")
    print(f"\ntotal charge  {float(rows[-1]['charge_Ah']):.6f} Ah")
    print(f"total energy  {float(rows[-1]['energy_Wh']):.6f} Wh")
    if args.rows:
        print()
        cols = ["elapsed_s", "voltage_V", "current_A", "power_W", "dplus_V", "dminus_V", "cc1_V", "cc2_V"]
        print("  ".join(f"{c:>10s}" for c in cols))
        for r in rows[-args.rows:]:
            print("  ".join(f"{r[c]:>10s}" for c in cols))


def main():
    p = argparse.ArgumentParser(description="RYKEN RK-X3 logger")
    p.add_argument("--serial", help="USB serial of the meter (default: first found)")
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("info", help="show device info without claiming it")
    lg = sub.add_parser("log", help="stream measurements to CSV")
    lg.add_argument("-o", "--output", help="CSV path (default: ryken_<timestamp>.csv)")
    lg.add_argument("-i", "--interval", type=float, default=0.2,
                    help="seconds between CSV rows; 0 = every frame (~167/s). Default 0.2")
    lg.add_argument("-d", "--duration", type=float, help="stop after N seconds")
    lg.add_argument("--sniff", choices=["pd", "ufcs"], help="also capture protocol messages")
    rd = sub.add_parser("read", help="summarise a CSV log")
    rd.add_argument("file")
    rd.add_argument("--rows", type=int, default=0, help="also print the last N rows")
    args = p.parse_args()
    {"info": cmd_info, "log": cmd_log, "read": cmd_read}[args.command](args)


if __name__ == "__main__":
    try:
        main()
    except RuntimeError as e:
        sys.exit(f"error: {e}")
