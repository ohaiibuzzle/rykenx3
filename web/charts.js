// Canvas charts for V/I/P (reads the globals `hist`, `session` and `state` from app.js).
'use strict';

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
// Canvas needs concrete colors; tokens may use light-dark(), so resolve them through
// a probe element's computed color instead of reading the raw custom property.
let palette = {};
const probe = document.createElement('span');
probe.hidden = true;
document.body.append(probe);
function readPalette() {
  palette = {};
  for (const k of ['--volt', '--amp', '--watt', '--muted', '--grid']) {
    probe.style.color = `var(${k})`;
    palette[k] = getComputedStyle(probe).color;
  }
  palette['--mono'] = getComputedStyle(document.documentElement).getPropertyValue('--mono').trim();
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
