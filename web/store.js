// Crash-safe copy of the current session's CSV rows in IndexedDB.
// Every call fails soft: without IndexedDB (private mode, blocked storage) the app
// simply runs without persistence.
'use strict';

const Store = (() => {
  let dbPromise = null;

  function open() {
    if (!dbPromise) {
      dbPromise = new Promise((resolve) => {
        try {
          const req = indexedDB.open('ryken', 1);
          req.onupgradeneeded = () => {
            req.result.createObjectStore('meta');
            req.result.createObjectStore('chunks', { autoIncrement: true });
          };
          req.onsuccess = () => resolve(req.result);
          req.onerror = () => resolve(null);
          req.onblocked = () => resolve(null);
        } catch (_) {
          resolve(null);
        }
      });
    }
    return dbPromise;
  }

  // Runs fn(transaction) and resolves with its return value once committed (null on failure).
  // Read-write transactions on the same stores commit in the order they are created.
  async function run(mode, fn) {
    const db = await open();
    if (!db) return null;
    return new Promise((resolve) => {
      let out = null;
      try {
        const tx = db.transaction(['meta', 'chunks'], mode);
        tx.oncomplete = () => resolve(out);
        tx.onerror = tx.onabort = () => resolve(null);
        out = fn(tx);
      } catch (_) {
        resolve(null);
      }
    });
  }

  const req = (r) => new Promise((resolve) => {
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => resolve(null);
  });

  return {
    // Start a new stored session, dropping the previous one.
    begin(meta) {
      return run('readwrite', (tx) => {
        tx.objectStore('chunks').clear();
        tx.objectStore('meta').put(meta, 'current');
      });
    },

    append(rows, meta) {
      return run('readwrite', (tx) => {
        tx.objectStore('chunks').add(rows);
        tx.objectStore('meta').put(meta, 'current');
      });
    },

    setMeta(meta) {
      return run('readwrite', (tx) => tx.objectStore('meta').put(meta, 'current'));
    },

    clear() {
      return run('readwrite', (tx) => {
        tx.objectStore('chunks').clear();
        tx.objectStore('meta').clear();
      });
    },

    // -> {meta, rows} or null
    async load() {
      const db = await open();
      if (!db) return null;
      try {
        const tx = db.transaction(['meta', 'chunks'], 'readonly');
        const [meta, chunks] = await Promise.all([
          req(tx.objectStore('meta').get('current')),
          req(tx.objectStore('chunks').getAll()),
        ]);
        return meta ? { meta, rows: (chunks || []).flat() } : null;
      } catch (_) {
        return null;
      }
    },
  };
})();
