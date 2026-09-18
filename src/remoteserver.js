'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');
const crypto = require('crypto');

/**
 * Serves the phone remote on the local network.
 *
 * Your phone opens http://<this-pc>:7654, pairs once with a code shown on the
 * PC, and from then on it is a full StreamHub client: browse, search, manage
 * the watchlist, and send a title to the Fire TV.
 *
 * Deliberately LAN-only and deliberately simple: no accounts, no cloud, no
 * inbound ports to open on your router. The pairing code exists so that a
 * housemate on the same wifi cannot drive your TV by guessing the URL.
 */

const STATIC = {
  '/': ['remote/index.html', 'text/html; charset=utf-8'],
  '/index.html': ['remote/index.html', 'text/html; charset=utf-8'],
  '/app.js': ['remote/app.js', 'text/javascript; charset=utf-8'],
  '/styles.css': ['remote/styles.css', 'text/css; charset=utf-8'],
  '/manifest.webmanifest': ['remote/manifest.webmanifest', 'application/manifest+json'],
  '/sw.js': ['remote/sw.js', 'text/javascript; charset=utf-8'],
  '/icon.svg': ['remote/icon.svg', 'image/svg+xml'],
};

const MAX_PAIR_ATTEMPTS = 10;
const PAIR_LOCKOUT_MS = 5 * 60 * 1000;

/** A scannable QR of the remote's URL, so the phone never types an IP. */
function qrSvg(text) {
  try {
    const qrcode = require('qrcode-generator');
    const q = qrcode(0, 'M');
    q.addData(text);
    q.make();
    return q.createSvgTag({ cellSize: 4, margin: 2, scalable: true });
  } catch {
    return null;
  }
}

class RemoteServer {
  /**
   * @param {object} deps
   * @param {() => object} deps.getSettings
   * @param {object} deps.store
   * @param {object} deps.tmdb
   * @param {object} deps.omdb
   * @param {object} deps.firetv
   * @param {Array}  deps.services
   * @param {Function} deps.buildHome
   * @param {Function} deps.detailsFor  shared with the desktop UI
   */
  constructor(deps) {
    this.deps = deps;
    this.server = null;
    this.port = null;
    this.code = null;
    this.tokens = new Set();
    this.failedPairs = 0;
    this.lockedUntil = 0;
    // Read once, then served from memory - these were being re-read
    // synchronously off disk on the Electron main thread for every request.
    this._static = new Map();
    this.onChange = null; // called when something changes, so the PC UI can refresh
  }

  get running() {
    return !!this.server;
  }

  newCode() {
    // 6 digits is fine because guessing is rate-limited and locked out; the
    // lockout is deliberately NOT cleared here, so rotating the code cannot be
    // used to escape it.
    this.code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0');
    this.failedPairs = 0;
    return this.code;
  }

  /** Every LAN address this machine has, so the PC can show a reachable URL. */
  addresses() {
    const out = [];
    for (const [name, addrs] of Object.entries(os.networkInterfaces())) {
      for (const a of addrs || []) {
        if (a.family !== 'IPv4' || a.internal) continue;
        out.push({ name, address: a.address });
      }
    }
    return out;
  }

  async start(port = 7654) {
    if (this.server) return this.info();
    this.newCode();
    this.tokens.clear();

    this.server = http.createServer((req, res) => {
      this._handle(req, res).catch((err) => {
        this._json(res, 500, { error: err.message || 'Internal error' });
      });
    });

    await new Promise((resolve, reject) => {
      this.server.once('error', (err) => {
        this.server = null;
        reject(
          err.code === 'EADDRINUSE'
            ? new Error(`Port ${port} is already in use. Pick a different one in Settings.`)
            : err
        );
      });
      this.server.listen(port, '0.0.0.0', () => resolve());
    });

    this.port = port;
    return this.info();
  }

  async stop() {
    if (!this.server) return { running: false };
    await new Promise((resolve) => this.server.close(resolve));
    this.server = null;
    this.port = null;
    this.tokens.clear();
    return { running: false };
  }

  info() {
    const addrs = this.addresses();
    const urls = addrs.map((a) => `http://${a.address}:${this.port}`);
    return {
      running: this.running,
      port: this.port,
      code: this.code,
      urls,
      addresses: addrs,
      paired: this.tokens.size,
      qr: this.running && urls[0] ? qrSvg(urls[0]) : null,
    };
  }

  /** Drop every paired phone. */
  revokeAll() {
    this.tokens.clear();
    this.lockedUntil = 0; // a human pressed the button, so clear the lockout
    this.newCode();
    return this.info();
  }

  // ---- plumbing ---------------------------------------------------------

  _json(res, status, body) {
    const payload = JSON.stringify(body);
    res.writeHead(status, {
      'content-type': 'application/json; charset=utf-8',
      'content-length': Buffer.byteLength(payload),
      'cache-control': 'no-store',
    });
    res.end(payload);
  }

  _authed(req) {
    const token = req.headers['x-streamhub-token'];
    return typeof token === 'string' && this.tokens.has(token);
  }

  async _body(req) {
    const chunks = [];
    let size = 0;
    for await (const c of req) {
      size += c.length;
      if (size > 256 * 1024) throw new Error('Request too large.');
      chunks.push(c);
    }
    if (!chunks.length) return {};
    try {
      return JSON.parse(Buffer.concat(chunks).toString('utf8'));
    } catch {
      throw new Error('Malformed JSON.');
    }
  }

  async _handle(req, res) {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const route = url.pathname;

    // --- static files ---------------------------------------------------
    if (req.method === 'GET' && STATIC[route]) {
      const [rel, type] = STATIC[route];
      let body = this._static.get(route);
      if (!body) {
        try {
          body = fs.readFileSync(path.join(__dirname, rel));
          this._static.set(route, body);
        } catch {
          return this._json(res, 404, { error: 'Not found' });
        }
      }
      res.writeHead(200, {
        'content-type': type,
        'content-length': body.length,
        'cache-control': 'no-cache',
        'x-content-type-options': 'nosniff',
        'content-security-policy':
          "default-src 'self'; img-src 'self' https://image.tmdb.org data:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'",
      });
      return res.end(body);
    }

    // --- pairing ---------------------------------------------------------
    if (route === '/api/pair' && req.method === 'POST') {
      const { code } = await this._body(req);

      // Rotating the code on the 11th wrong guess used to also zero the
      // counter, so an attacker got unlimited guesses at full speed - about a
      // 6-digit code's worth of entropy in a few minutes on a LAN. Now the
      // lockout actually locks, and each wrong guess costs real time.
      if (this.lockedUntil && Date.now() < this.lockedUntil) {
        const secs = Math.ceil((this.lockedUntil - Date.now()) / 1000);
        return this._json(res, 429, {
          error: `Too many wrong codes. Pairing is locked for ${secs}s — or press "Unpair all phones" on the PC to reset it now.`,
        });
      }
      if (this.lockedUntil && Date.now() >= this.lockedUntil) {
        this.lockedUntil = 0;
        this.failedPairs = 0;
      }
      const given = String(code || '');
      const expected = String(this.code || '');
      // Without this guard an empty code would match an unset one.
      if (expected.length !== 6) return this._json(res, 503, { error: 'Pairing is not ready yet.' });
      const match =
        given.length === expected.length &&
        crypto.timingSafeEqual(Buffer.from(given), Buffer.from(expected));
      if (!match) {
        this.failedPairs += 1;
        // A deliberate cost per attempt, on top of the lockout.
        await new Promise((r) => setTimeout(r, 400));
        if (this.failedPairs >= MAX_PAIR_ATTEMPTS) {
          this.lockedUntil = Date.now() + PAIR_LOCKOUT_MS;
          this.newCode();
          this._notify();
          return this._json(res, 429, {
            error: 'Too many wrong codes. Pairing is locked for 5 minutes and a new code was generated.',
          });
        }
        return this._json(res, 401, { error: 'Wrong code.' });
      }
      const token = crypto.randomBytes(32).toString('hex');
      this.tokens.add(token);
      this.failedPairs = 0;
      this.lockedUntil = 0;
      this._notify();
      return this._json(res, 200, { token });
    }

    // Lets the phone show "pair me" instead of a dead page.
    if (route === '/api/hello' && req.method === 'GET') {
      return this._json(res, 200, { app: 'StreamHub', needsPairing: !this._authed(req) });
    }

    // --- everything below needs a paired phone ---------------------------
    if (!route.startsWith('/api/')) return this._json(res, 404, { error: 'Not found' });
    if (!this._authed(req)) return this._json(res, 401, { error: 'Not paired.' });

    const { store, tmdb, firetv, services, buildHome, detailsFor } = this.deps;

    if (route === '/api/state' && req.method === 'GET') {
      let tv = null;
      try {
        tv = await firetv.status();
      } catch {
        /* the TV being unreachable is not an error for the phone */
      }
      return this._json(res, 200, {
        services,
        state: store.publicSnapshot(),
        tv,
      });
    }

    if (route === '/api/home' && req.method === 'GET') {
      const rows = await buildHome(tmdb, store);
      return this._json(res, 200, { rows });
    }

    if (route === '/api/search' && req.method === 'GET') {
      const q = url.searchParams.get('q') || '';
      if (!q.trim()) return this._json(res, 400, { error: 'Empty query.' });
      return this._json(res, 200, await tmdb.searchMulti(q, Number(url.searchParams.get('page')) || 1));
    }

    if (route === '/api/details' && req.method === 'GET') {
      const mediaType = url.searchParams.get('mediaType');
      const id = url.searchParams.get('id');
      // Both land in a TMDB URL path, so they are constrained here rather than
      // trusted - `..` segments would otherwise reach other TMDB endpoints
      // with the user's key attached.
      if (mediaType !== 'movie' && mediaType !== 'tv') return this._json(res, 400, { error: 'Unknown media type.' });
      if (!/^\d+$/.test(String(id))) return this._json(res, 400, { error: 'Invalid title id.' });
      return this._json(res, 200, await detailsFor(mediaType, id));
    }

    if (route === '/api/watchlist' && req.method === 'POST') {
      const { item } = await this._body(req);
      if (!item) return this._json(res, 400, { error: 'No item.' });
      store.toggleWatchlist(item);
      this._notify();
      return this._json(res, 200, store.publicSnapshot());
    }

    if (route === '/api/pinned' && req.method === 'POST') {
      const { item } = await this._body(req);
      if (!item) return this._json(res, 400, { error: 'No item.' });
      store.togglePinned(item);
      this._notify();
      return this._json(res, 200, store.publicSnapshot());
    }

    // --- the TV ----------------------------------------------------------
    if (route === '/api/tv/play' && req.method === 'POST') {
      const { serviceId, item, contentId } = await this._body(req);
      const result = await firetv.play(serviceId, { title: item && item.title, contentId });
      if (item) store.recordOpen(item, serviceId);
      this._notify();
      return this._json(res, 200, { ...result, state: store.publicSnapshot() });
    }

    if (route === '/api/tv/search' && req.method === 'POST') {
      const { query } = await this._body(req);
      if (!query) return this._json(res, 400, { error: 'No query.' });
      return this._json(res, 200, await firetv.search(query));
    }

    if (route === '/api/tv/key' && req.method === 'POST') {
      const { key } = await this._body(req);
      return this._json(res, 200, await firetv.key(key));
    }

    return this._json(res, 404, { error: 'Not found' });
  }

  _notify() {
    if (typeof this.onChange === 'function') {
      try {
        this.onChange();
      } catch {
        /* never let a UI refresh break a request */
      }
    }
  }
}

module.exports = { RemoteServer };
