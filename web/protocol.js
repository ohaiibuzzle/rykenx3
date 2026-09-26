// RYKEN RK-X3 protocol over WebHID. See ../PROTOCOL.md for the wire format.
'use strict';

const Ryken = (() => {
  const VID = 0x2e3c;
  const PID = 0xaf03;
  const REPORT_SIZE = 64;
  const HEARTBEAT_MS = 1000;
  const TEMP_ABSENT = 10000;
  const CMD = {
    HANDSHAKE: 0x00, SAMPLING: 0x02, PROTOCOL: 0x03, DISCONNECT: 0x05,
    HEARTBEAT: 0x06, CLAIM: 0xff, MEASURE: 0x20, PROTOCOL_DATA: 0x30,
  };

  function crc16(bytes) {
    let r = 0x0011;
    for (const b of bytes) {
      r ^= b << 8;
      for (let k = 0; k < 8; k++) {
        r = r & 0x8000 ? (r << 1) ^ 0x2507 : r << 1;
        r &= 0xffff;
      }
    }
    return (r + 1) & 0xffff;
  }

  function buildFrame(cmd, payload = []) {
    const f = new Uint8Array(payload.length + 5);
    f[0] = 0xaa;
    f[1] = f.length;
    f[2] = cmd;
    f.set(payload, 3);
    const c = crc16(f.subarray(0, f.length - 2));
    f[f.length - 2] = c >> 8;
    f[f.length - 1] = c & 0xff;
    return f;
  }

  // Device frames start with 0x55; returns {cmd, frame} or null if invalid.
  function splitFrame(bytes) {
    if (bytes.length < 5 || bytes[0] !== 0x55) return null;
    const n = bytes[1];
    if (n < 5 || n > bytes.length) return null;
    const frame = bytes.subarray(0, n);
    if (crc16(frame.subarray(0, n - 2)) !== ((frame[n - 2] << 8) | frame[n - 1])) return null;
    return { cmd: frame[2], frame };
  }

  function parseMeasurement(f) {
    const dv = new DataView(f.buffer, f.byteOffset, f.byteLength);
    const voltage = dv.getFloat32(3);
    const current = dv.getFloat32(7);
    const temp = dv.getUint16(19);
    return {
      voltage, current, power: voltage * current,
      dplus: dv.getUint16(11) / 1000,
      dminus: dv.getUint16(13) / 1000,
      cc1: dv.getUint16(15) / 1000,
      cc2: dv.getUint16(17) / 1000,
      temperature: temp === TEMP_ABSENT ? null : temp,
      phonePower: dv.getUint16(21) / 10,
    };
  }

  const hex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');

  // Events: 'measurement' {detail: {t, sample}}, 'close'
  class Meter extends EventTarget {
    constructor(device) {
      super();
      this.device = device;
      this.claimed = false;
      this.info = null;
      this.badFrames = 0;
      this._waiters = [];
      this._heartbeat = null;
      this._onReport = this._onReport.bind(this);
    }

    static get supported() {
      return 'hid' in navigator;
    }

    static async request() {
      const [device] = await navigator.hid.requestDevice({ filters: [{ vendorId: VID, productId: PID }] });
      if (!device) throw new Error('No meter selected.');
      return new Meter(device);
    }

    static async known() {
      const devices = await navigator.hid.getDevices();
      return devices.filter((d) => d.vendorId === VID && d.productId === PID).map((d) => new Meter(d));
    }

    async open() {
      if (!this.device.opened) await this.device.open();
      this.device.addEventListener('inputreport', this._onReport);
    }

    async send(cmd, payload = []) {
      const report = new Uint8Array(REPORT_SIZE);
      report.set(buildFrame(cmd, payload));
      await this.device.sendReport(0, report);
    }

    _waitFor(cmd, ms) {
      return new Promise((resolve, reject) => {
        const w = { cmd, resolve };
        w.timer = setTimeout(() => {
          this._waiters.splice(this._waiters.indexOf(w), 1);
          reject(new Error('The meter did not answer the handshake.'));
        }, ms);
        this._waiters.push(w);
      });
    }

    _onReport(e) {
      const got = splitFrame(new Uint8Array(e.data.buffer, e.data.byteOffset, e.data.byteLength));
      if (!got) {
        this.badFrames++;
        return;
      }
      for (const w of this._waiters.filter((x) => x.cmd === got.cmd)) {
        clearTimeout(w.timer);
        this._waiters.splice(this._waiters.indexOf(w), 1);
        w.resolve(got.frame.slice());
      }
      if (got.cmd === CMD.MEASURE && got.frame.length >= 25) {
        this.dispatchEvent(new CustomEvent('measurement', {
          detail: { t: performance.now(), sample: parseMeasurement(got.frame) },
        }));
      }
    }

    // Handshake, verify the signature, then claim (which starts the ~167 Hz stream).
    // A claim left behind by a crashed host keeps the meter busy for ~5 s (heartbeat
    // timeout); `force` sends a disconnect first so it can be taken over immediately.
    async connect({ force = false } = {}) {
      await this.open();
      if (force) {
        await this.send(CMD.DISCONNECT, [1]);
        await new Promise((r) => setTimeout(r, 100));
      }
      const d = new Date();
      const ts = [d.getFullYear() % 100, d.getMonth() + 1, d.getDate(), d.getHours(), d.getMinutes()];
      const reply = this._waitFor(CMD.HANDSHAKE, 2000);
      await this.send(CMD.HANDSHAKE, ts);
      let f;
      try {
        f = await reply;
      } catch (e) {
        e.busy = true; // a still-claimed meter may ignore handshakes
        throw e;
      }
      const status = f[3];
      const mcu = f.slice(4, 16);
      const sn = f.slice(16, 20);
      const sig = (f[22] << 8) | f[23];
      this.info = {
        serial: hex(sn),
        mcuId: hex(mcu),
        hardware: `V${Math.floor(f[20] / 10)}.${f[20] % 10}`,
        firmware: `V${Math.floor(f[21] / 100)}.${String(f[21] % 100).padStart(2, '0')}`,
      };
      if (status === 1) {
        const e = new Error('The meter is in use by another program (the RYKEN app, a script or another tab), '
          + 'or a previous session did not disconnect cleanly.');
        e.busy = true;
        throw e;
      }
      if (sig !== crc16([status, ...mcu, ...sn, ...ts])) {
        throw new Error('Handshake signature mismatch.');
      }
      await this.send(CMD.CLAIM, [...new TextEncoder().encode('ready'), sig >> 8, sig & 0xff]);
      this.claimed = true;
      this._heartbeat = setInterval(() => {
        this.send(CMD.HEARTBEAT, [1]).catch(() => {});
      }, HEARTBEAT_MS);
      return this.info;
    }

    async close() {
      clearInterval(this._heartbeat);
      this._heartbeat = null;
      this.device.removeEventListener('inputreport', this._onReport);
      try {
        if (this.claimed && this.device.opened) await this.send(CMD.DISCONNECT, [1]);
      } catch (_) { /* device may already be gone */ }
      this.claimed = false;
      try {
        if (this.device.opened) await this.device.close();
      } catch (_) { /* ignore */ }
      this.dispatchEvent(new Event('close'));
    }
  }

  return { VID, PID, CMD, crc16, buildFrame, splitFrame, parseMeasurement, Meter };
})();
