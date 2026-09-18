'use strict';

const fs = require('fs');
const path = require('path');

const DEFAULTS = {
  settings: {
    tmdbKey: '',
    omdbKey: '',
    region: 'BE',
    language: 'en-US',
    browser: 'auto', // 'auto' | 'chrome' | 'edge' | absolute path to an .exe
    profileMode: 'shared', // 'shared' = your normal browser profile, 'isolated' = one login per service
    windowSize: '1600,900',
    // --- Fire TV ---
    tvHost: '', // the TV's IP on your network
    tvToken: '', // shared secret with the StreamHub TV app, once it is installed
    adbPath: '', // blank = look in the usual places
    // --- phone remote ---
    remotePort: 7654,
    remoteAutoStart: false,
  },
  watchlist: [], // { key, id, mediaType, title, poster, year }
  pinned: [], // same shape - "continue watching"
  history: [], // { key, id, mediaType, title, poster, year, service, genreIds, openedAt }
  serviceOpens: {}, // { netflix: 12, ... }
};

class Store {
  constructor(file) {
    this.file = file;
    this.data = this._load();
  }

  _load() {
    const fresh = () => JSON.parse(JSON.stringify(DEFAULTS));

    let raw;
    try {
      raw = fs.readFileSync(this.file, 'utf8');
    } catch {
      return fresh(); // genuinely absent - a first run
    }

    let parsed;
    try {
      parsed = JSON.parse(raw);
    } catch (err) {
      // The file exists but will not parse. Starting from defaults is the only
      // way forward, but the next save would overwrite the bytes - which may
      // still hold the API keys and watchlist - so keep a copy first.
      const backup = `${this.file}.corrupt-${Date.now()}`;
      try {
        fs.renameSync(this.file, backup);
        console.error(`[store] unreadable, preserved at ${backup}: ${err.message}`);
      } catch (renameErr) {
        console.error('[store] unreadable and could not be preserved:', renameErr.message);
      }
      return fresh();
    }

    if (!parsed || typeof parsed !== 'object') return fresh();

    // Spreading DEFAULTS copies array *references*, so a file missing a key
    // would alias - and then mutate - the shared module-level default.
    const base = fresh();
    const arr = (v, fallback) => (Array.isArray(v) ? v : fallback);
    return {
      settings: { ...base.settings, ...(parsed.settings && typeof parsed.settings === 'object' ? parsed.settings : {}) },
      watchlist: arr(parsed.watchlist, base.watchlist),
      pinned: arr(parsed.pinned, base.pinned),
      history: arr(parsed.history, base.history),
      serviceOpens: parsed.serviceOpens && typeof parsed.serviceOpens === 'object' ? parsed.serviceOpens : base.serviceOpens,
    };
  }

  save() {
    // Write-then-rename. A plain writeFileSync that is interrupted (power cut,
    // kill) leaves a truncated file that will not parse on next launch, which
    // used to present as "all your settings vanished".
    const tmp = `${this.file}.tmp`;
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2), 'utf8');
      fs.renameSync(tmp, this.file);
    } catch (err) {
      console.error('[store] could not save:', err.message);
      try {
        fs.unlinkSync(tmp);
      } catch {
        /* nothing to clean up */
      }
    }
    return this.data;
  }

  get settings() {
    return this.data.settings;
  }

  updateSettings(patch) {
    this.data.settings = { ...this.data.settings, ...patch };
    this.save();
    return this.data.settings;
  }

  // ---- watchlist ------------------------------------------------------

  toggleWatchlist(item) {
    const key = itemKey(item);
    const i = this.data.watchlist.findIndex((x) => x.key === key);
    if (i >= 0) {
      this.data.watchlist.splice(i, 1);
    } else {
      this.data.watchlist.unshift({ ...slim(item), key, addedAt: Date.now() });
      // Capped like `pinned`. Without this a paired phone could grow the file
      // without bound, and every save rewrites it synchronously.
      this.data.watchlist = this.data.watchlist.slice(0, 500);
    }
    this.save();
    return this.data.watchlist;
  }

  // ---- continue watching (pins) ---------------------------------------

  togglePinned(item) {
    const key = itemKey(item);
    const i = this.data.pinned.findIndex((x) => x.key === key);
    if (i >= 0) {
      this.data.pinned.splice(i, 1);
    } else {
      this.data.pinned.unshift({ ...slim(item), key, pinnedAt: Date.now() });
      this.data.pinned = this.data.pinned.slice(0, 24);
    }
    this.save();
    return this.data.pinned;
  }

  /** Bump a pinned entry to the front and remember which service was used. */
  touchPinned(key, service, { defer = false } = {}) {
    const entry = this.data.pinned.find((x) => x.key === key);
    if (!entry) return this.data.pinned;
    entry.lastOpened = Date.now();
    if (service) entry.service = service;
    this.data.pinned.sort((a, b) => (b.lastOpened || b.pinnedAt || 0) - (a.lastOpened || a.pinnedAt || 0));
    // `defer` lets recordOpen do a single write instead of two.
    if (!defer) this.save();
    return this.data.pinned;
  }

  // ---- history --------------------------------------------------------

  recordOpen(item, service) {
    if (service) {
      this.data.serviceOpens[service] = (this.data.serviceOpens[service] || 0) + 1;
    }
    if (item && (item.id || item.title)) {
      const key = itemKey(item);
      this.data.history = this.data.history.filter((h) => h.key !== key);
      this.data.history.unshift({
        ...slim(item), // already bounds genreIds
        key,
        service: service || null,
        openedAt: Date.now(),
      });
      this.data.history = this.data.history.slice(0, 200);
      this.touchPinned(key, service, { defer: true });
    }
    this.save();
    return this.data;
  }

  clearHistory() {
    this.data.history = [];
    this.data.serviceOpens = {};
    this.save();
    return this.data;
  }

  /** Full state, including secrets. Only ever crosses the Electron IPC boundary. */
  snapshot() {
    return {
      settings: this.data.settings,
      watchlist: this.data.watchlist,
      pinned: this.data.pinned,
      history: this.data.history.slice(0, 60),
      serviceOpens: this.data.serviceOpens,
    };
  }
}

function itemKey(item) {
  if (item.key) return item.key;
  if (item.id && item.mediaType) return `${item.mediaType}:${item.id}`;
  return `title:${String(item.title || '').toLowerCase()}`;
}

/** Keep only the fields the UI needs, and bound each one - this is fed by the phone. */
function slim(item) {
  const str = (v, max) => (typeof v === 'string' ? v.slice(0, max) : null);
  const genreIds = Array.isArray(item.genreIds || item.genre_ids) ? item.genreIds || item.genre_ids : [];
  return {
    id: Number.isFinite(item.id) ? item.id : null,
    mediaType: item.mediaType === 'tv' || item.media_type === 'tv' ? 'tv' : 'movie',
    title: str(item.title || item.name, 300) || '',
    poster: str(item.poster || item.poster_path, 200),
    year: Number.isFinite(item.year) ? item.year : null,
    genreIds: genreIds.filter((g) => Number.isFinite(g)).slice(0, 12),
  };
}

/**
 * What the phone is allowed to see. `snapshot()` carries the TMDB key, the OMDb
 * key and the Fire TV token; the phone client needs none of them, and it
 * travels over plain HTTP on the LAN.
 */
Store.prototype.publicSnapshot = function publicSnapshot() {
  const s = this.data.settings;
  return {
    settings: {
      region: s.region,
      language: s.language,
      profileMode: s.profileMode,
      remotePort: s.remotePort,
      tvConfigured: !!s.tvHost,
    },
    watchlist: this.data.watchlist,
    pinned: this.data.pinned,
    history: this.data.history.slice(0, 60),
  };
};

module.exports = { Store, itemKey };
