// Formatting helpers.
'use strict';

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
  if (v < 0.2) return I18n.t('cc.open');
  if (v <= 0.66) return I18n.t('cc.default');
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

function fmtInt(n) {
  return n.toLocaleString(I18n.lang);
}
