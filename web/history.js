// Chart history: 100 ms averages of V/I/P. When full, neighbouring points are
// merged (halving resolution) so an entire session always fits.
'use strict';

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

  // push the partially filled bucket (used after bulk-loading a saved log)
  flush() {
    if (this.acc) this._flush();
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
