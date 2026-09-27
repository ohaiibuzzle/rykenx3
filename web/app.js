'use strict';

const $ = (id) => document.getElementById(id);
const t = I18n.t;
const UI_MS = 100;       // UI / chart refresh period
const GAP_MS = 1000;     // a longer pause between frames is a gap, not something to integrate over
const FLUSH_MS = 2000;   // how often new log rows are written to the Store
const CSV_HEADER = 'time,elapsed_s,voltage_V,current_A,power_W,dplus_V,dminus_V,cc1_V,cc2_V,'
  + 'temperature_raw,phone_power,charge_Ah,energy_Wh';

// ---------------------------------------------------------------------------
// Settings (theme, palette, row interval; language lives in I18n)
const prefs = {
  get(key, fallback) {
    try { return localStorage.getItem('ryken.' + key) ?? fallback; } catch (_) { return fallback; }
  },
  set(key, value) {
    try { localStorage.setItem('ryken.' + key, value); } catch (_) { /* storage unavailable */ }
  },
};

// ---------------------------------------------------------------------------
// State
const state = {
  meter: null,
  connecting: false,
  reconnectDevice: null, // device to re-open automatically after an unplug
  deviceLabel: '',
  idleStatus: ['', 'status.idle'],
  banner: null,
  restore: null, // {meta, rows} of an unexported session found in storage at startup
  interval: 0.2, // s between logged rows, 0 = every frame
  windowSec: 120,
  hoverPx: null,
  view: { t0: 0, t1: 1 },
};

// The session is also the log: every measurement feeds the stats and charts, and a
// CSV row is kept every `state.interval` seconds from the moment the session starts.
const session = {
  reset() {
    this.start = performance.now();
    this.startedAt = new Date();
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
    this.rows = [];
    this.pending = []; // rows not yet written to the Store
    this.bytes = 0;
    this.lastRowT = -Infinity;
    this.recording = true; // when off, frames still feed the stats and charts but no rows are kept
    this.exported = true; // nothing to lose yet
    this.device = '';
    this.source = null; // {name} of an opened CSV or {key} for a restored session
    hist.clear();
  },

  meta() {
    return {
      startedAt: this.startedAt.toISOString(),
      device: this.device,
      rows: this.rows.length,
      exported: this.exported,
    };
  },
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
  logRow(t, s);
}

function logRow(t, s) {
  if (!session.recording) return;
  if (state.interval && t - session.lastRowT < state.interval * 1000) return;
  session.lastRowT = t;
  const row = [
    localIso(new Date()), ((t - session.start) / 1000).toFixed(3),
    g6(s.voltage), g6(s.current), g6(s.power),
    g6(s.dplus), g6(s.dminus), g6(s.cc1), g6(s.cc2),
    s.temperature ?? '', g6(s.phonePower), g6(session.charge), g6(session.energy),
  ].join(',');
  session.rows.push(row);
  session.pending.push(row);
  session.bytes += row.length + 1;
  session.exported = false;
}

function flushLog() {
  if (!session.pending.length) return;
  const rows = session.pending;
  session.pending = [];
  Store.append(rows, session.meta());
}

// Start a fresh live session (and a fresh stored copy of it).
function startSession() {
  session.reset();
  session.device = state.deviceLabel;
  state.restore = null;
  Store.begin(session.meta());
}

// Rows that would be lost by starting over: the current session's and a pending restore's.
function confirmDiscard() {
  const n = (session.exported ? 0 : session.rows.length) + (state.restore ? state.restore.rows.length : 0);
  if (n && !confirm(t('confirm.discard', { n: fmtInt(n) }))) return false;
  if (state.restore) {
    state.restore = null;
    hideBanner();
  }
  return true;
}

// ---------------------------------------------------------------------------
// Connection
const ERROR_KEYS = { 'no-answer': 'err.noAnswer', 'in-use': 'err.inUse', 'bad-signature': 'err.signature' };

async function connect({ force = false, device = null } = {}) {
  if (state.connecting || state.meter) return;
  state.connecting = true;
  hideBanner();
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
    state.deviceLabel = `${m.device.productName || 'RK-X3'} · SN ${info.serial} · HW ${info.hardware} · FW ${info.firmware}`;
    if (!device) startSession(); // an automatic reconnect continues the session
  } catch (err) {
    if (m) {
      m.removeEventListener('measurement', onMeasurement);
      await m.close();
    }
    if (err.name === 'NotFoundError' || err.message === 'No meter selected.') {
      state.idleStatus = ['', 'status.idle'];
    } else {
      state.idleStatus = ['err', 'status.notConnected'];
      showBanner({
        ...(ERROR_KEYS[err.code] ? { key: ERROR_KEYS[err.code] } : { text: err.message }),
        actions: err.busy
          ? [{ label: 'banner.takeOver', title: 'banner.takeOverTitle', run: () => connect({ force: true, device }) }]
          : [],
      });
    }
  } finally {
    state.connecting = false;
    updateButtons();
  }
}

async function disconnect() {
  const m = state.meter;
  if (!m) return;
  state.meter = null;
  m.removeEventListener('measurement', onMeasurement);
  await m.close();
  flushLog();
  state.idleStatus = ['', 'status.disconnected'];
  updateButtons();
}

if (Ryken.Meter.supported) {
  navigator.hid.addEventListener('disconnect', async (e) => {
    if (!state.meter || e.device !== state.meter.device) return;
    const device = state.meter.device;
    await disconnect();
    state.reconnectDevice = device;
    showBanner({ key: 'banner.unplugged', kind: 'info' });
  });
  navigator.hid.addEventListener('connect', (e) => {
    const d = e.device;
    if (!state.reconnectDevice || state.meter || d.vendorId !== Ryken.VID || d.productId !== Ryken.PID) return;
    setTimeout(() => connect({ device: d }), 500);
  });
}

window.addEventListener('pagehide', () => {
  flushLog();
  if (state.meter) state.meter.close();
});

// ---------------------------------------------------------------------------
// UI
// Banner spec: {key, params (object or function), text, kind, actions: [{label, title, run}]}
function showBanner(spec) {
  state.banner = spec;
  renderBanner();
}

function hideBanner() {
  state.banner = null;
  $('banner').hidden = true;
}

function renderBanner() {
  const spec = state.banner;
  if (!spec) return;
  const b = $('banner');
  b.className = 'banner ' + (spec.kind || '');
  b.replaceChildren();
  const span = document.createElement('span');
  const params = typeof spec.params === 'function' ? spec.params() : spec.params;
  span.textContent = spec.key ? t(spec.key, params) : spec.text;
  b.append(span);
  for (const action of spec.actions || []) {
    const btn = document.createElement('button');
    btn.textContent = t(action.label);
    if (action.title) btn.title = t(action.title);
    btn.addEventListener('click', action.run);
    b.append(btn);
  }
  b.hidden = false;
}

function updateButtons() {
  const connected = !!state.meter;
  $('btn-connect').hidden = connected;
  $('btn-connect').disabled = state.connecting || !Ryken.Meter.supported;
  $('btn-disconnect').hidden = !connected;
}

function renderStatus(now) {
  let st;
  if (!Ryken.Meter.supported) st = ['err', 'status.unsupported'];
  else if (state.connecting) st = ['warn', 'status.connecting'];
  else if (state.meter) {
    const stale = session.lastT === null || now - session.lastT > 1500;
    st = stale ? ['warn', 'status.waiting'] : ['live', 'status.live', { fps: session.fps }];
  } else if (state.reconnectDevice) st = ['err', 'status.unplugged'];
  else if (session.source) {
    st = ['', 'status.viewing', { name: session.source.name ?? t(session.source.key) }];
  } else st = state.idleStatus;
  $('status-dot').className = 'dot ' + st[0];
  $('status-text').textContent = t(st[1], st[2]);
  $('device-info').textContent = state.meter || session.source ? session.device || '' : t('device.none');
}

function render() {
  const now = performance.now();
  if (now - session._fpsT >= 1000) {
    session.fps = Math.round(session._fpsFrames * 1000 / (now - session._fpsT));
    session._fpsFrames = 0;
    session._fpsT = now;
  }
  renderStatus(now);

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
  const endT = state.meter ? now : session.lastT ?? session.start;
  const elapsed = clock(session.frames ? (endT - session.start) / 1000 : 0);
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
  $('s-temp').textContent = s ? (s.temperature ?? t('stat.na')) : '–';
  $('s-phone').textContent = s ? s.phonePower.toFixed(1) : '–';
  $('s-frames').textContent = `${fmtInt(session.frames)} · ${state.meter ? session.fps : 0}/s`;

  const n = session.rows.length;
  $('log-rows').textContent = fmtInt(n);
  $('log-size').textContent = session.bytes > 1e6
    ? `${(session.bytes / 1e6).toFixed(1)} MB` : `${Math.ceil(session.bytes / 1024)} KB`;
  const logState = !n ? 'empty' : session.exported ? 'exported' : 'unexported';
  $('log-state').className = 'pill ' + logState;
  $('log-state').textContent = t('log.' + logState);
  $('btn-export').disabled = !n;
  $('log-recording').checked = session.recording;
  $('log-recording-label').textContent = t(session.recording ? 'log.recording' : 'log.paused');

  drawCharts(now);
}

// ---------------------------------------------------------------------------
// Settings menu
function applySettings() {
  const root = document.documentElement;
  const values = { theme: prefs.get('theme', 'system'), palette: prefs.get('palette', 'default'), lang: I18n.lang };
  if (values.theme === 'system') delete root.dataset.theme; else root.dataset.theme = values.theme;
  if (values.palette === 'default') delete root.dataset.palette; else root.dataset.palette = values.palette;
  for (const group of document.querySelectorAll('.choices')) {
    for (const btn of group.children) {
      const on = btn.dataset.value === values[group.dataset.setting];
      btn.classList.toggle('on', on);
      btn.setAttribute('aria-pressed', on);
    }
  }
  readPalette();
}

$('settings').addEventListener('click', (e) => {
  const btn = e.target.closest('.choices button');
  if (!btn) return;
  const key = btn.parentElement.dataset.setting;
  if (key === 'lang') {
    I18n.setLang(btn.dataset.value);
    renderBanner();
  } else {
    prefs.set(key, btn.dataset.value);
  }
  applySettings();
  render();
});
document.addEventListener('click', (e) => {
  if (!e.target.closest('#settings')) $('settings').open = false;
});
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && $('settings').open) {
    $('settings').open = false;
    $('settings').querySelector('summary').focus();
  }
});

// ---------------------------------------------------------------------------
// Wiring
$('btn-connect').addEventListener('click', () => {
  if (confirmDiscard()) connect();
});
$('btn-disconnect').addEventListener('click', () => {
  state.reconnectDevice = null;
  disconnect();
});
$('btn-reset').addEventListener('click', () => {
  if (!confirmDiscard()) return;
  if (state.meter) {
    startSession();
  } else {
    session.reset();
    Store.clear();
  }
});
$('btn-export').addEventListener('click', exportSession);
$('btn-open').addEventListener('click', () => {
  if (confirmDiscard()) $('file-open').click();
});
$('file-open').addEventListener('change', (e) => {
  const file = e.target.files[0];
  e.target.value = '';
  if (file) openCsv(file);
});
$('log-recording').addEventListener('change', (e) => {
  if (e.target.checked && !session.recording) session.lastRowT = -Infinity; // log the next frame right away
  session.recording = e.target.checked;
  render();
});
$('log-interval').addEventListener('change', (e) => {
  state.interval = Number(e.target.value);
  prefs.set('interval', e.target.value);
});
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

// ---------------------------------------------------------------------------
// Start
const savedInterval = prefs.get('interval', '0.2');
if ([...$('log-interval').options].some((o) => o.value === savedInterval)) $('log-interval').value = savedInterval;
state.interval = Number($('log-interval').value);

for (const { code, name } of I18n.list()) {
  const btn = document.createElement('button');
  btn.dataset.value = code;
  btn.lang = code;
  btn.textContent = name;
  document.querySelector('[data-setting=lang]').append(btn);
}
I18n.apply();
applySettings();
if (!Ryken.Meter.supported) {
  showBanner({ key: window.isSecureContext ? 'banner.noWebHid' : 'banner.insecure' });
}
updateButtons();
checkStoredSession();
setInterval(render, UI_MS);
setInterval(flushLog, FLUSH_MS);
render();
