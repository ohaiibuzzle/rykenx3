// Session log export, opening a saved CSV, and restoring a stored session.
// Uses the globals from app.js (`session`, `state`, helpers) at call time.
'use strict';

function downloadCsv(rows, startedAt) {
  const blob = new Blob([CSV_HEADER + '\n' + rows.join('\n') + '\n'], { type: 'text/csv' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `ryken_${stamp(startedAt)}.csv`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
}

function exportSession() {
  if (!session.rows.length) return;
  downloadCsv(session.rows, session.startedAt);
  session.exported = true;
  // an opened file is not in the Store; live and restored sessions are
  if (!session.source?.name) Store.setMeta(session.meta());
}

// Replace the session with rows of a saved log (CSV lines without the header).
function loadSession(lines, { source, startedAt = null, device = '', exported = true }) {
  session.reset();
  let prevT = null;
  for (const line of lines) {
    const c = line.split(',');
    const t = Number(c[1]) * 1000;
    const s = {
      voltage: Number(c[2]), current: Number(c[3]), power: Number(c[4]),
      dplus: Number(c[5]), dminus: Number(c[6]), cc1: Number(c[7]), cc2: Number(c[8]),
      temperature: c[9] === '' ? null : Number(c[9]), phonePower: Number(c[10]),
    };
    if (c.length !== 13 || ![t, s.voltage, s.current, s.power].every(Number.isFinite)) continue;
    if (prevT !== null && t < prevT) continue;
    // rows are sparse, so treat the whole span as active (average power = energy / duration)
    if (prevT !== null) session.activeMs += t - prevT;
    prevT = t;
    for (const k of ['voltage', 'current', 'power']) {
      if (s[k] < session.min[k]) session.min[k] = s[k];
      if (s[k] > session.max[k]) session.max[k] = s[k];
    }
    hist.add(t, s);
    session.last = s;
    session.charge = Number(c[11]) || 0;
    session.energy = Number(c[12]) || 0;
    session.rows.push(line);
    session.bytes += line.length + 1;
  }
  hist.flush();
  const first = new Date(lines[0]?.split(',')[0]);
  Object.assign(session, {
    start: 0,
    lastT: prevT,
    frames: session.rows.length,
    startedAt: startedAt || (isNaN(first) ? new Date() : first),
    device,
    source,
    exported,
  });
  return session.rows.length;
}

async function openCsv(file) {
  const lines = (await file.text()).split(/\r?\n/).filter((l) => l.trim());
  if (lines[0]?.trim() !== CSV_HEADER) {
    showBanner({ key: 'banner.badCsv', params: { name: file.name } });
    return;
  }
  if (lines.length < 2) {
    showBanner({ key: 'banner.emptyCsv', params: { name: file.name } });
    return;
  }
  if (state.meter) {
    state.reconnectDevice = null;
    await disconnect();
  }
  state.reconnectDevice = null;
  hideBanner();
  loadSession(lines.slice(1), { source: { name: file.name } });
  Store.clear(); // the stored session was confirmed as discarded
}

async function checkStoredSession() {
  const saved = await Store.load();
  if (!saved || saved.meta.exported || !saved.rows.length || state.meter || session.rows.length) return;
  state.restore = saved;
  const startedAt = new Date(saved.meta.startedAt);
  showBanner({
    key: 'banner.restore',
    params: () => ({ date: startedAt.toLocaleString(I18n.lang), n: fmtInt(saved.rows.length) }),
    kind: 'info',
    actions: [
      {
        label: 'btn.restore',
        run: () => {
          state.restore = null;
          hideBanner();
          loadSession(saved.rows, {
            source: { key: 'restored' }, startedAt, device: saved.meta.device, exported: false,
          });
        },
      },
      {
        label: 'btn.export',
        run: () => {
          downloadCsv(saved.rows, startedAt);
          Store.setMeta({ ...saved.meta, exported: true });
          state.restore = null;
          hideBanner();
        },
      },
      {
        label: 'btn.discard',
        run: () => {
          Store.clear();
          state.restore = null;
          hideBanner();
        },
      },
    ],
  });
}
