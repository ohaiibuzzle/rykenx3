'use strict';

const $ = (id) => document.getElementById(id);
const UI_MS = 100;       // UI / chart refresh period
const GAP_MS = 1000;     // a longer pause between frames is a gap, not something to integrate over
const CSV_HEADER = 'time,elapsed_s,voltage_V,current_A,power_W,dplus_V,dminus_V,cc1_V,cc2_V,'
  + 'temperature_raw,phone_power,charge_Ah,energy_Wh';

// ---------------------------------------------------------------------------
// Chart history: 100 ms averages of V/I/P. When full, neighbouring points are
// merged (halving resolution) so an entire session always fits.
class History {
  constructor(capacity = 20000) {
    this.cap = capacity;
    this.t = new Float64Array(capacity);
    this.v = new Float32Array(capacity);
    this.i = new Float32Array(capacity);
    this.p = new Float32Array(capacity);
    this.clear();
  }

  clear() {
    this.n = 0;
    this.bucketMs = 100;
    this.acc = null;
  }

  add(t, s) {
    const b = Math.floor(t / this.bucketMs);
    if (this.acc && this.acc.b !== b) this._flush();
    if (!this.acc) this.acc = { b, t: 0, v: 0, i: 0, p: 0, n: 0 };
    const a = this.acc;
    a.t += t; a.v += s.voltage; a.i += s.current; a.p += s.power; a.n++;
  }

  _flush() {
    if (this.n === this.cap) this._compact();
    const a = this.acc;
    const k = this.n++;
    this.t[k] = a.t / a.n; this.v[k] = a.v / a.n; this.i[k] = a.i / a.n; this.p[k] = a.p / a.n;
    this.acc = null;
  }

  _compact() {
    const half = this.n >> 1;
    for (const arr of [this.t, this.v, this.i, this.p]) {
      for (let k = 0; k < half; k++) arr[k] = (arr[2 * k] + arr[2 * k + 1]) / 2;
    }
    this.n = half;
    this.bucketMs *= 2;
  }

  // first index with t >= value
  lowerBound(value) {
    let lo = 0, hi = this.n;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (this.t[mid] < value) lo = mid + 1; else hi = mid;
    }
    return lo;
  }
}

// ---------------------------------------------------------------------------
// State
const state = {
  meter: null,
  connecting: false,
  reconnectDevice: null, // device to re-open automatically after an unplug
  windowSec: 120,
  hoverPx: null,
  view: { t0: 0, t1: 1 },
};

const session = {
  reset() {
    this.start = performance.now();
    this.lastT = null;
    this.last = null;
    this.frames = 0;
    this.charge = 0; // Ah
    this.energy = 0; // Wh
    this.activeMs = 0;
    this.min = { voltage: Infinity, current: Infinity, power: Infinity };
    this.max = { voltage: -Infinity, current: -Infinity, power: -Infinity };
    this.fps = 0;
    this._fpsFrames = 0;
    this._fpsT = performance.now();
    hist.clear();
  },
};

const rec = {
  active: false,
  saved: true,
  rows: [],
  bytes: 0,
  interval: 0.2,
  startT: 0,
  startedAt: null,
  lastRowT: -Infinity,
  lastT: null,
  charge: 0,
  energy: 0,
};

const hist = new History();
session.reset();

// ---------------------------------------------------------------------------
// Data path (runs for every frame, ~167/s)
function onMeasurement(e) {
  const { t, sample: s } = e.detail;
  const dt = session.lastT === null ? Infinity : t - session.lastT;
  if (dt < GAP_MS) {
    const h = dt / 3.6e6;
    session.charge += s.current * h;
    session.energy += s.power * h;
    session.activeMs += dt;
  }
  session.lastT = t;
  session.last = s;
  session.frames++;
  session._fpsFrames++;
  for (const k of ['voltage', 'current', 'power']) {
    if (s[k] < session.min[k]) session.min[k] = s[k];
    if (s[k] > session.max[k]) session.max[k] = s[k];
  }
  hist.add(t - session.start, s);
  if (rec.active) recordRow(t, s);
}

function recordRow(t, s) {
  const dt = rec.lastT === null ? Infinity : t - rec.lastT;
  if (dt < GAP_MS) {
    const h = dt / 3.6e6;
    rec.charge += s.current * h;
    rec.energy += s.power * h;
  }
  rec.lastT = t;
  if (rec.interval && t - rec.lastRowT < rec.interval * 1000) return;
  rec.lastRowT = t;
  const row = [
    localIso(new Date()), ((t - rec.startT) / 1000).toFixed(3),
    g6(s.voltage), g6(s.current), g6(s.power),
    g6(s.dplus), g6(s.dminus), g6(s.cc1), g6(s.cc2),
    s.temperature ?? '', g6(s.phonePower), g6(rec.charge), g6(rec.energy),
  ].join(',');
  rec.rows.push(row);
  rec.bytes += row.length + 1;
  rec.saved = false;
}

// ---------------------------------------------------------------------------
// Connection
async function connect({ force = false, device = null } = {}) {
  if (state.connecting || state.meter) return;
  state.connecting = true;
  hideBanner();
  setStatus('warn', 'Connecting…');
  updateButtons();
  let m = null;
  try {
    if (device) {
      m = new Ryken.Meter(device);
    } else {
      const known = await Ryken.Meter.known();
      m = known.length === 1 ? known[0] : await Ryken.Meter.request();
    }
    m.addEventListener('measurement', onMeasurement);
    const info = await m.connect({ force });
    state.meter = m;
    state.reconnectDevice = null;
    if (!device) session.reset(); // an automatic reconnect continues the session
    $('device-info').textContent = `${m.device.productName || 'RK-X3'} · SN ${info.serial} · HW ${info.hardware} · FW ${info.firmware}`;
  } catch (err) {
    if (m) {
      m.removeEventListener('measurement', onMeasurement);
      await m.close();
    }
    if (err.name === 'NotFoundError' || err.message === 'No meter selected.') {
      setStatus('', 'Idle');
    } else {
      setStatus('err', 'Not connected');
      showBanner(err.message, err.busy
        ? { label: 'Take over', title: 'Send a disconnect to the meter first, then connect', run: () => connect({ force: true, device }) }
        : null);
    }
  } finally {
    state.connecting = false;
    updateButtons();
  }
}

async function disconnect({ keepRecording = false } = {}) {
  const m = state.meter;
  if (!m) return;
  state.meter = null;
  if (rec.active && !keepRecording) toggleRecording();
  m.removeEventListener('measurement', onMeasurement);
  await m.close();
  $('device-info').textContent = 'Not connected';
  setStatus('', 'Disconnected');
  updateButtons();
}

if (Ryken.Meter.supported) {
  navigator.hid.addEventListener('disconnect', async (e) => {
    if (!state.meter || e.device !== state.meter.device) return;
    const device = state.meter.device;
    await disconnect({ keepRecording: true });
    state.reconnectDevice = device;
    setStatus('err', 'Unplugged');
    showBanner('The meter was unplugged. It will reconnect automatically when you plug it back in'
      + (rec.active ? ' and recording will continue.' : '.'), null, 'info');
  });
  navigator.hid.addEventListener('connect', (e) => {
    const d = e.device;
    if (!state.reconnectDevice || state.meter || d.vendorId !== Ryken.VID || d.productId !== Ryken.PID) return;
    setTimeout(() => connect({ device: d }), 500);
  });
}

window.addEventListener('pagehide', () => {
  if (state.meter) state.meter.close();
});
window.addEventListener('beforeunload', (e) => {
  if (!rec.saved && rec.rows.length) e.preventDefault();
});

// ---------------------------------------------------------------------------
// Recording
function toggleRecording() {
  if (rec.active) {
    rec.active = false;
  } else {
    if (rec.rows.length && !rec.saved && !confirm('Discard the current unsaved recording and start a new one?')) return;
    Object.assign(rec, {
      active: true, saved: true, rows: [], bytes: 0,
      interval: Number($('rec-interval').value),
      startT: performance.now(), startedAt: new Date(),
      lastRowT: -Infinity, lastT: null, charge: 0, energy: 0,
    });
  }
  updateButtons();
}

function downloadCsv() {
  const blob = new Blob([CSV_HEADER + '\n' + rec.rows.join('\n') + '\n'], { type: 'text/csv' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `ryken_${stamp(rec.startedAt)}.csv`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  rec.saved = true;
}

function discardRecording() {
  if (!rec.saved && !confirm(`Discard ${rec.rows.length.toLocaleString()} unsaved rows?`)) return;
  Object.assign(rec, { active: false, saved: true, rows: [], bytes: 0, charge: 0, energy: 0, startedAt: null });
  updateButtons();
}

// ---------------------------------------------------------------------------
// UI
function setStatus(kind, text) {
  $('status-dot').className = 'dot ' + kind;
  $('status-text').textContent = text;
}

function showBanner(text, action, kind = '') {
  const b = $('banner');
  b.className = 'banner ' + kind;
  b.replaceChildren();
  const span = document.createElement('span');
  span.textContent = text;
  b.append(span);
  if (action) {
    const btn = document.createElement('button');
    btn.textContent = action.label;
    if (action.title) btn.title = action.title;
    btn.addEventListener('click', action.run);
    b.append(btn);
  }
  b.hidden = false;
}

function hideBanner() {
  $('banner').hidden = true;
}

function updateButtons() {
  const connected = !!state.meter;
  $('btn-connect').hidden = connected;
  $('btn-connect').disabled = state.connecting || !Ryken.Meter.supported;
  $('btn-disconnect').hidden = !connected;
  $('btn-rec').disabled = !connected && !rec.active;
  $('btn-rec').textContent = rec.active ? 'Stop recording' : 'Start recording';
  $('btn-rec').classList.toggle('recording', rec.active);
  $('rec-badge').hidden = !rec.active;
  $('rec-interval').disabled = rec.active;
  $('btn-download').disabled = rec.rows.length === 0;
  $('btn-discard').disabled = rec.rows.length === 0 && !rec.active;
}

function render() {
  const now = performance.now();
  if (now - session._fpsT >= 1000) {
    session.fps = Math.round(session._fpsFrames * 1000 / (now - session._fpsT));
    session._fpsFrames = 0;
    session._fpsT = now;
  }
  if (state.meter) {
    const stale = session.lastT === null || now - session.lastT > 1500;
    setStatus(stale ? 'warn' : 'live', stale ? 'Waiting for data…' : `Live · ${session.fps} fps`);
  }

  const s = session.last;
  const fix = (v, d) => (v === undefined || v === null || !isFinite(v) ? '–' : v.toFixed(d));
  $('v-now').textContent = fix(s?.voltage, 3);
  $('i-now').textContent = fix(s?.current, 4);
  $('p-now').textContent = fix(s?.power, 3);
  $('v-min').textContent = fix(session.min.voltage, 3);
  $('v-max').textContent = fix(session.max.voltage, 3);
  $('i-min').textContent = fix(session.min.current, 4);
  $('i-max').textContent = fix(session.max.current, 4);
  const avgW = session.activeMs > 0 ? session.energy / (session.activeMs / 3.6e6) : NaN;
  $('p-avg').textContent = fix(avgW, 3);
  $('p-max').textContent = fix(session.max.power, 3);

  const mWh = session.energy * 1000;
  $('e-now').textContent = mWh >= 10000 ? (mWh / 1000).toFixed(3) : mWh.toFixed(2);
  $('e-unit').textContent = mWh >= 10000 ? 'Wh' : 'mWh';
  $('c-now').textContent = `${(session.charge * 1000).toFixed(2)} mAh`;
  const elapsed = session.frames ? clock((now - session.start) / 1000) : '00:00:00';
  $('elapsed').textContent = elapsed;

  for (const [id, key] of [['dp', 'dplus'], ['dm', 'dminus'], ['cc1', 'cc1'], ['cc2', 'cc2']]) {
    const v = s?.[key];
    $(id).textContent = v === undefined ? '–' : `${v.toFixed(3)} V`;
    $(id + '-bar').style.width = v === undefined ? '0' : `${Math.min(v / 5, 1) * 100}%`;
  }
  $('cc1-state').textContent = s ? ccState(s.cc1) : '';
  $('cc2-state').textContent = s ? ccState(s.cc2) : '';

  $('s-elapsed').textContent = elapsed;
  $('s-charge').textContent = `${(session.charge * 1000).toFixed(3)} mAh`;
  $('s-energy').textContent = `${mWh.toFixed(3)} mWh`;
  $('s-power').textContent = `${fix(avgW, 3)} / ${fix(session.max.power, 3)} W`;
  $('s-vrange').textContent = `${fix(session.min.voltage, 3)} – ${fix(session.max.voltage, 3)} V`;
  $('s-irange').textContent = `${fix(session.min.current, 4)} – ${fix(session.max.current, 4)} A`;
  $('s-temp').textContent = s ? (s.temperature ?? 'n/a') : '–';
  $('s-phone').textContent = s ? s.phonePower.toFixed(1) : '–';
  $('s-frames').textContent = `${session.frames.toLocaleString()} · ${state.meter ? session.fps : 0}/s`;

  $('rec-rows').textContent = rec.rows.length.toLocaleString();
  $('rec-time').textContent = rec.startedAt ? clock(((rec.active ? now : rec.lastT ?? now) - rec.startT) / 1000) : '–';
  $('rec-energy').textContent = rec.startedAt ? `${(rec.energy * 1000).toFixed(3)} mWh · ${(rec.charge * 1000).toFixed(3)} mAh` : '–';
  $('rec-size').textContent = rec.bytes > 1e6 ? `${(rec.bytes / 1e6).toFixed(1)} MB` : `${Math.ceil(rec.bytes / 1024)} KB`;
  if ($('btn-download').disabled === (rec.rows.length > 0)) updateButtons();

  drawCharts(now);
}

// ---------------------------------------------------------------------------
// Charts
const PAD = { l: 58, r: 10, t: 22 };
const charts = [...document.querySelectorAll('.chart')].map((el) => {
  const key = el.dataset.key;
  return {
    el, key,
    canvas: el.querySelector('canvas'),
    colorVar: { v: '--volt', i: '--amp', p: '--watt' }[key],
    fromZero: key !== 'v',
    minSpan: { v: 0.1, i: 0.01, p: 0.05 }[key],
    showTime: key === 'p',
  };
});
let palette = {};
function readPalette() {
  const cs = getComputedStyle(document.documentElement);
  palette = Object.fromEntries(['--volt', '--amp', '--watt', '--muted', '--grid', '--text', '--mono']
    .map((k) => [k, cs.getPropertyValue(k).trim()]));
}
readPalette();
matchMedia('(prefers-color-scheme: light)').addEventListener('change', readPalette);

function drawCharts(now) {
  const nowT = now - session.start;
  let t1 = state.meter ? nowT : (hist.n ? hist.t[hist.n - 1] : nowT);
  let t0;
  if (state.windowSec) {
    t0 = t1 - state.windowSec * 1000;
  } else {
    t0 = hist.n ? Math.min(hist.t[0], t1 - 10000) : t1 - 10000;
  }
  t1 = Math.max(t1, t0 + 1000);
  state.view = { t0, t1 };

  let hover = null;
  if (state.hoverPx !== null && hist.n) {
    const w = charts[0].canvas.clientWidth - PAD.l - PAD.r;
    const t = t0 + ((state.hoverPx - PAD.l) / w) * (t1 - t0);
    let k = hist.lowerBound(t);
    if (k >= hist.n || (k > 0 && t - hist.t[k - 1] < hist.t[k] - t)) k--;
    const px = PAD.l + ((hist.t[k] - t0) / (t1 - t0)) * w;
    if (k >= 0 && hist.t[k] >= t0 && hist.t[k] <= t1 && Math.abs(px - state.hoverPx) <= 12) hover = k;
  }
  for (const ch of charts) drawChart(ch, t0, t1, hover);

  const tip = $('tooltip');
  if (hover === null) {
    tip.hidden = true;
  } else {
    tip.hidden = false;
    tip.innerHTML = `<b>${clock(hist.t[hover] / 1000, true)}</b><br>`
      + `<span style="color:var(--volt)">${hist.v[hover].toFixed(3)} V</span><br>`
      + `<span style="color:var(--amp)">${hist.i[hover].toFixed(4)} A</span><br>`
      + `<span style="color:var(--watt)">${hist.p[hover].toFixed(3)} W</span>`;
    const W = $('charts').clientWidth;
    const left = state.hoverPx + 16 + tip.offsetWidth > W ? state.hoverPx - 16 - tip.offsetWidth : state.hoverPx + 16;
    tip.style.left = `${left}px`;
  }
}

function drawChart(ch, t0, t1, hover) {
  const { canvas } = ch;
  const dpr = window.devicePixelRatio || 1;
  const W = canvas.clientWidth;
  const H = canvas.clientHeight;
  if (!W || !H) return;
  if (canvas.width !== Math.round(W * dpr) || canvas.height !== Math.round(H * dpr)) {
    canvas.width = Math.round(W * dpr);
    canvas.height = Math.round(H * dpr);
  }
  const ctx = canvas.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, W, H);
  const B = ch.showTime ? 20 : 6;
  const w = W - PAD.l - PAD.r;
  const h = H - PAD.t - B;
  const arr = hist[ch.key];
  const ts = hist.t;
  const color = palette[ch.colorVar];

  let a = hist.lowerBound(t0);
  const b = hist.lowerBound(t1 + 1);
  if (a > 0) a--; // keep the line continuous at the left edge

  let lo = Infinity, hi = -Infinity;
  for (let k = a; k < b; k++) {
    if (arr[k] < lo) lo = arr[k];
    if (arr[k] > hi) hi = arr[k];
  }
  if (!isFinite(lo)) { lo = 0; hi = ch.minSpan * 4; }
  if (ch.fromZero && lo >= 0) lo = 0;
  if (hi - lo < ch.minSpan) {
    const mid = (hi + lo) / 2;
    lo = mid - ch.minSpan / 2;
    hi = mid + ch.minSpan / 2;
    if (ch.fromZero && lo < 0) { hi -= lo; lo = 0; }
  }
  const step = niceStep(hi - lo, h < 110 ? 3 : 4);
  lo = Math.floor(lo / step + 1e-9) * step;
  hi = Math.ceil(hi / step - 1e-9) * step;
  if (hi <= lo) hi = lo + step;
  const y = (v) => PAD.t + h - ((v - lo) / (hi - lo)) * h;
  const x = (t) => PAD.l + ((t - t0) / (t1 - t0)) * w;

  // y grid + labels
  ctx.font = `11px ${palette['--mono'] || 'monospace'}`;
  ctx.lineWidth = 1;
  ctx.textBaseline = 'middle';
  ctx.textAlign = 'right';
  const dec = decimalsFor(step);
  for (let v = lo; v <= hi + step / 2; v += step) {
    const yy = Math.round(y(v)) + 0.5;
    ctx.strokeStyle = palette['--grid'];
    ctx.beginPath(); ctx.moveTo(PAD.l, yy); ctx.lineTo(PAD.l + w, yy); ctx.stroke();
    ctx.fillStyle = palette['--muted'];
    ctx.fillText(v.toFixed(dec), PAD.l - 8, yy);
  }

  // time grid (+ labels on the bottom chart)
  const spanS = (t1 - t0) / 1000;
  const tStep = timeStep(spanS, Math.max(2, Math.floor(w / 90)));
  ctx.textAlign = 'center';
  ctx.textBaseline = 'alphabetic';
  for (let s = Math.ceil(t0 / 1000 / tStep) * tStep; s <= t1 / 1000; s += tStep) {
    const xx = Math.round(x(s * 1000)) + 0.5;
    ctx.strokeStyle = palette['--grid'];
    ctx.beginPath(); ctx.moveTo(xx, PAD.t); ctx.lineTo(xx, PAD.t + h); ctx.stroke();
    if (ch.showTime && s >= 0) {
      ctx.fillStyle = palette['--muted'];
      ctx.fillText(clock(s), xx, PAD.t + h + 15);
    }
  }

  // data: one min/max column per pixel so spikes survive downsampling
  if (b > a) {
    const cols = [];
    let col = null;
    for (let k = a; k < b; k++) {
      const c = Math.floor(((ts[k] - t0) / (t1 - t0)) * w);
      if (!col || col.c !== c) {
        col = { c, first: arr[k], min: arr[k], max: arr[k], last: arr[k] };
        cols.push(col);
      } else {
        if (arr[k] < col.min) col.min = arr[k];
        if (arr[k] > col.max) col.max = arr[k];
        col.last = arr[k];
      }
    }
    ctx.save();
    ctx.beginPath();
    ctx.rect(PAD.l, PAD.t - 1, w, h + 2);
    ctx.clip();
    ctx.beginPath();
    cols.forEach((cc, idx) => {
      const X = PAD.l + cc.c + 0.5;
      if (idx === 0) ctx.moveTo(X, y(cc.first)); else ctx.lineTo(X, y(cc.first));
      if (cc.min !== cc.max) { ctx.lineTo(X, y(cc.min)); ctx.lineTo(X, y(cc.max)); }
      ctx.lineTo(X, y(cc.last));
    });
    ctx.strokeStyle = color;
    ctx.lineWidth = 1.5;
    ctx.lineJoin = 'round';
    ctx.stroke();
    if (ch.fromZero) {
      ctx.lineTo(PAD.l + cols[cols.length - 1].c + 0.5, y(lo));
      ctx.lineTo(PAD.l + cols[0].c + 0.5, y(lo));
      ctx.closePath();
      ctx.globalAlpha = 0.12;
      ctx.fillStyle = color;
      ctx.fill();
      ctx.globalAlpha = 1;
    }
    ctx.restore();
  }

  if (hover !== null) {
    const X = Math.round(x(ts[hover])) + 0.5;
    ctx.strokeStyle = palette['--muted'];
    ctx.setLineDash([3, 3]);
    ctx.beginPath(); ctx.moveTo(X, PAD.t); ctx.lineTo(X, PAD.t + h); ctx.stroke();
    ctx.setLineDash([]);
    ctx.fillStyle = color;
    ctx.beginPath(); ctx.arc(X, y(arr[hover]), 3.5, 0, Math.PI * 2); ctx.fill();
  }
}

function niceStep(range, count) {
  const raw = range / count;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const n = raw / mag;
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * mag;
}

function decimalsFor(step) {
  const e = Math.floor(Math.log10(step));
  const extra = Math.abs(step / 10 ** e - 2.5) < 1e-9 ? 1 : 0;
  return Math.max(0, -e + extra);
}

function timeStep(spanS, maxTicks) {
  const steps = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400, 21600, 43200, 86400];
  return steps.find((s) => spanS / s <= maxTicks) || 86400;
}

// ---------------------------------------------------------------------------
// Formatting helpers
function clock(sec, withMs = false) {
  const neg = sec < 0;
  sec = Math.abs(sec);
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = Math.floor(sec % 60);
  const p2 = (n) => String(n).padStart(2, '0');
  let out = h ? `${h}:${p2(m)}:${p2(s)}` : `${p2(m)}:${p2(s)}`;
  if (withMs) out += `.${String(Math.floor((sec % 1) * 10))}`;
  return (neg ? '-' : '') + out;
}

function ccState(v) {
  // USB-C sink-side CC voltage (vRd) -> current advertised by the source
  if (v < 0.2) return 'open';
  if (v <= 0.66) return 'USB default';
  if (v <= 1.23) return '1.5 A';
  if (v <= 2.04) return '3.0 A';
  return '?';
}

// like Python's "%.6g": up to 6 significant digits, no trailing zeros
function g6(v) {
  return String(Number(v.toPrecision(6)));
}

function localIso(d) {
  const p = (n, w = 2) => String(n).padStart(w, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T`
    + `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(d.getMilliseconds(), 3)}`;
}

function stamp(d) {
  return localIso(d).replace(/[-:]/g, '').replace('T', '_').slice(0, 15);
}

// ---------------------------------------------------------------------------
// Wiring
$('btn-connect').addEventListener('click', () => connect());
$('btn-disconnect').addEventListener('click', () => {
  state.reconnectDevice = null;
  disconnect();
});
$('btn-rec').addEventListener('click', toggleRecording);
$('btn-download').addEventListener('click', downloadCsv);
$('btn-discard').addEventListener('click', discardRecording);
$('btn-reset').addEventListener('click', () => session.reset());
$('window-select').addEventListener('click', (e) => {
  const btn = e.target.closest('button');
  if (!btn) return;
  state.windowSec = Number(btn.dataset.window);
  for (const b of $('window-select').children) b.classList.toggle('on', b === btn);
});
$('charts').addEventListener('mousemove', (e) => {
  const r = charts[0].canvas.getBoundingClientRect();
  const px = e.clientX - r.left;
  state.hoverPx = px >= PAD.l && px <= r.width - PAD.r ? px : null;
});
$('charts').addEventListener('mouseleave', () => { state.hoverPx = null; });

if (!Ryken.Meter.supported) {
  showBanner(window.isSecureContext
    ? 'This browser has no WebHID support. Use Chrome, Edge or Opera on a desktop computer.'
    : 'WebHID needs a secure page. Open this page over https:// (or http://localhost).');
  setStatus('err', 'Unsupported browser');
}
updateButtons();
setInterval(render, UI_MS);
render();
