'use strict';

const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');
const { launchCommand, universalSearchCommand, wakeCommand, packagesFor } = require('./deeplinks');

/**
 * Talks to the Fire TV. Two transports, tried in that order:
 *
 *  http - the StreamHub Fire TV app, if it is installed and running. Clean,
 *         needs no developer mode, and it launches apps with normal Android
 *         intents.
 *  adb  - Android Debug Bridge over the network. Works with nothing installed
 *         on the TV, but developer options and ADB debugging must be on.
 *
 * `adb` itself is not bundled - it ships with Android Studio and with Google's
 * platform-tools, so we look in the usual places and let the user point at it.
 */

const ADB_PORT = 5555;
const TV_APP_PORT = 8723; // the StreamHub TV app's control server
const EXEC_TIMEOUT = 15000;

function adbCandidates() {
  const home = process.env.USERPROFILE || process.env.HOME || '';
  const local = process.env.LOCALAPPDATA || path.join(home, 'AppData', 'Local');
  const exe = process.platform === 'win32' ? 'adb.exe' : 'adb';

  const list = [
    // Android Studio's default SDK location
    path.join(local, 'Android', 'Sdk', 'platform-tools', exe),
    path.join(home, 'Android', 'Sdk', 'platform-tools', exe),
    path.join(home, 'Library', 'Android', 'sdk', 'platform-tools', exe),
    // A standalone platform-tools unzip
    path.join(home, 'platform-tools', exe),
    path.join(home, 'Downloads', 'platform-tools', exe),
    // Package-manager installs
    '/usr/bin/adb',
    '/usr/local/bin/adb',
    '/opt/homebrew/bin/adb',
  ];

  if (process.env.ANDROID_HOME) list.unshift(path.join(process.env.ANDROID_HOME, 'platform-tools', exe));
  if (process.env.ANDROID_SDK_ROOT) list.unshift(path.join(process.env.ANDROID_SDK_ROOT, 'platform-tools', exe));
  return list;
}

function findAdb(explicit) {
  if (explicit) {
    try {
      if (fs.existsSync(explicit)) return explicit;
    } catch {
      /* ignore */
    }
  }
  for (const c of adbCandidates()) {
    try {
      if (c && fs.existsSync(c)) return c;
    } catch {
      /* ignore */
    }
  }
  return null; // may still be on PATH - run() falls back to the bare name
}

function run(bin, args, timeout = EXEC_TIMEOUT) {
  return new Promise((resolve) => {
    execFile(bin, args, { timeout, windowsHide: true }, (err, stdout, stderr) => {
      resolve({
        ok: !err,
        code: err ? (err.code === 'ENOENT' ? 'ENOENT' : err.code || 1) : 0,
        stdout: String(stdout || '').trim(),
        stderr: String(stderr || err?.message || '').trim(),
      });
    });
  });
}

// How long a status result stays fresh. The phone polls, and every uncached
// status call costs two subprocess spawns and two network round trips - which
// on a laptop is a measurable amount of battery for information that changes
// about once a month.
const STATUS_TTL_MS = 15000;
const PACKAGES_TTL_MS = 10 * 60 * 1000;

class FireTv {
  constructor(getSettings) {
    this.getSettings = getSettings;
    this._installed = null; // cached package list from the TV
    this._installedAt = 0;
    this._status = null;
    this._statusAt = 0;
  }

  /** Drop the caches - call after connect/disconnect or a settings change. */
  invalidate() {
    this._installed = null;
    this._installedAt = 0;
    this._status = null;
    this._statusAt = 0;
  }

  get host() {
    return (this.getSettings().tvHost || '').trim();
  }

  get adbPath() {
    return findAdb(this.getSettings().adbPath) || 'adb';
  }

  get serial() {
    return `${this.host}:${ADB_PORT}`;
  }

  // ---- transport: the StreamHub TV app ---------------------------------

  async httpAlive() {
    if (!this.host) return false;
    try {
      const res = await fetch(`http://${this.host}:${TV_APP_PORT}/ping`, {
        signal: AbortSignal.timeout(2500),
      });
      return res.ok;
    } catch {
      return false;
    }
  }

  async httpSend(action, payload) {
    const res = await fetch(`http://${this.host}:${TV_APP_PORT}/command`, {
      method: 'POST',
      headers: {
        'content-type': 'application/json',
        'x-streamhub-token': this.getSettings().tvToken || '',
      },
      body: JSON.stringify({ action, ...payload }),
      signal: AbortSignal.timeout(8000),
    });
    if (res.status === 401) {
      const e = new Error('The TV rejected the pairing code. Check it against the code on the TV\'s Setup screen.');
      e.code = 'BAD_TV_TOKEN';
      throw e;
    }
    if (!res.ok) throw new Error(`The TV app rejected the command (HTTP ${res.status}).`);
    return res.json().catch(() => ({}));
  }

  /**
   * Hand the TV our API keys and region over the control link, so nobody has to
   * type a 32-character key with a d-pad. The README promised this from the
   * start; it was never actually wired up.
   */
  async pushSetup() {
    if (!this.host) throw new Error('No Fire TV address set.');
    if (!(this.getSettings().tvToken || '').trim()) {
      const e = new Error('No pairing code yet. Open StreamHub on the TV, go to Setup, and copy the code it shows.');
      e.code = 'NO_TV_TOKEN';
      throw e;
    }
    if (!(await this.httpAlive())) {
      const e = new Error('The StreamHub TV app is not answering. Open it on the TV and try again.');
      e.code = 'NO_TV_APP';
      throw e;
    }
    const s = this.getSettings();
    return this.httpSend('setup', { tmdbKey: s.tmdbKey, omdbKey: s.omdbKey, region: s.region });
  }

  // ---- transport: adb ---------------------------------------------------

  async adbAvailable() {
    const r = await run(this.adbPath, ['version'], 6000);
    return r.ok;
  }

  async connect() {
    if (!this.host) throw new Error('No Fire TV address set. Add your TV\'s IP in Settings.');

    const version = await run(this.adbPath, ['version'], 6000);
    if (!version.ok) {
      const e = new Error(
        'Could not run adb. It ships with Android Studio (and with Google\'s platform-tools). ' +
          'Install either one, or point StreamHub at adb.exe in Settings.'
      );
      e.code = 'NO_ADB';
      throw e;
    }

    const r = await run(this.adbPath, ['connect', this.serial], 20000);
    const out = `${r.stdout} ${r.stderr}`.toLowerCase();

    if (out.includes('connected to')) {
      // "already connected" also lands here, which is fine.
      this.invalidate();
      return { connected: true, message: r.stdout || 'Connected.' };
    }
    if (out.includes('failed to authenticate') || out.includes('unauthorized')) {
      const e = new Error('The TV is asking you to allow this computer. Look at the TV screen and accept the debugging prompt, then try again.');
      e.code = 'UNAUTHORIZED';
      throw e;
    }
    const e = new Error(
      `Could not reach the Fire TV at ${this.host}. Check the IP, and that Settings → My Fire TV → Developer Options → ADB debugging is on.`
    );
    e.code = 'NO_ROUTE';
    throw e;
  }

  async disconnect() {
    if (!this.host) return { disconnected: true };
    await run(this.adbPath, ['disconnect', this.serial], 8000);
    this.invalidate();
    return { disconnected: true };
  }

  async shell(command) {
    const r = await run(this.adbPath, ['-s', this.serial, 'shell', command], EXEC_TIMEOUT);
    if (!r.ok) {
      const text = `${r.stdout} ${r.stderr}`.toLowerCase();
      if (text.includes('device') && (text.includes('not found') || text.includes('offline'))) {
        const e = new Error('Lost the connection to the TV. Hit Connect again.');
        e.code = 'DISCONNECTED';
        throw e;
      }
      throw new Error(r.stderr || `Command failed on the TV: ${command}`);
    }
    return r.stdout;
  }

  /** Which of the four services are actually installed on the TV. */
  async installedPackages(force = false) {
    const fresh = this._installed && Date.now() - this._installedAt < PACKAGES_TTL_MS;
    if (fresh && !force) return this._installed;
    const out = await this.shell('pm list packages');
    const set = new Set(
      out
        .split('\n')
        .map((l) => l.trim().replace(/^package:/, ''))
        .filter(Boolean)
    );
    this._installed = set;
    this._installedAt = Date.now();
    return set;
  }

  /** First package of this service that exists on the TV, or null. */
  async resolvePackage(serviceId) {
    const installed = await this.installedPackages();
    for (const pkg of packagesFor(serviceId)) {
      if (installed.has(pkg)) return pkg;
    }
    return null;
  }

  // ---- the actual API ---------------------------------------------------

  async status() {
    // Served from a short cache so a polling phone does not spawn adb twice
    // every 20 seconds for the rest of the evening.
    if (this._status && Date.now() - this._statusAt < STATUS_TTL_MS) return this._status;

    const adbPath = findAdb(this.getSettings().adbPath);
    const result = {
      host: this.host || null,
      adbFound: !!adbPath,
      adbPath: adbPath || null,
      tvApp: false,
      adbConnected: false,
      hasToken: !!(this.getSettings().tvToken || '').trim(),
      services: {},
    };
    if (!this.host) return result;

    result.tvApp = await this.httpAlive();

    if (!result.tvApp && adbPath) {
      const devices = await run(this.adbPath, ['devices'], 8000);
      result.adbConnected = devices.ok && devices.stdout.includes(this.serial) && !devices.stdout.includes(`${this.serial}\toffline`);
      if (result.adbConnected) {
        try {
          const installed = await this.installedPackages();
          for (const id of ['netflix', 'disneyplus', 'hbomax', 'primevideo']) {
            result.services[id] = packagesFor(id).some((p) => installed.has(p));
          }
        } catch {
          /* non-fatal */
        }
      }
    }

    this._status = result;
    this._statusAt = Date.now();
    return result;
  }

  /**
   * Open a service on the TV, optionally aimed at a title.
   *
   * @param {string} serviceId
   * @param {{title?:string, contentId?:string, useUniversalSearch?:boolean}} opts
   */
  async play(serviceId, opts = {}) {
    if (!this.host) throw new Error('No Fire TV address set. Add your TV\'s IP in Settings.');

    // Preferred path: the TV app is running, so let it use real intents. A bad
    // pairing code is surfaced rather than silently masked, but any other
    // failure falls through to adb instead of dead-ending.
    if (await this.httpAlive()) {
      try {
        const res = await this.httpSend('play', { serviceId, ...opts });
        return { via: 'tv-app', ...res };
      } catch (err) {
        if (err.code === 'BAD_TV_TOKEN') throw err;
        console.warn('[firetv] TV app failed, falling back to adb:', err.message);
      }
    }

    // Fallback: drive it over adb.
    await this.shell(wakeCommand()).catch(() => {});

    const pkg = await this.resolvePackage(serviceId);
    if (!pkg) {
      const e = new Error(`That service does not look installed on the TV. Install it from the Fire TV appstore first.`);
      e.code = 'NOT_INSTALLED';
      throw e;
    }

    // With a real content id we can jump straight in; otherwise Fire TV's own
    // universal search is the most reliable way to land on the exact title.
    if (opts.contentId) {
      const { cmd } = launchCommand(serviceId, { mode: 'title', contentId: opts.contentId });
      await this.shell(cmd);
      return { via: 'adb', kind: 'deeplink', package: pkg };
    }

    if (opts.title && opts.useUniversalSearch !== false) {
      const { cmd } = universalSearchCommand(opts.title);
      await this.shell(cmd);
      return { via: 'adb', kind: 'universal-search', package: pkg, note: 'Opened Fire TV search - pick the service on screen.' };
    }

    const { cmd } = launchCommand(serviceId, { mode: 'home' });
    await this.shell(cmd);
    return { via: 'adb', kind: 'home', package: pkg };
  }

  /** Straight to Fire TV's universal search for a title, no service chosen. */
  async search(query) {
    if (await this.httpAlive()) {
      try {
        const res = await this.httpSend('search', { query });
        return { via: 'tv-app', ...res };
      } catch (err) {
        if (err.code === 'BAD_TV_TOKEN') throw err;
      }
    }
    await this.shell(wakeCommand()).catch(() => {});
    const { cmd } = universalSearchCommand(query);
    await this.shell(cmd);
    return { via: 'adb', kind: 'universal-search' };
  }

  /** Remote-control keys, handy from the phone. */
  async key(name) {
    const MAP = {
      up: 'KEYCODE_DPAD_UP',
      down: 'KEYCODE_DPAD_DOWN',
      left: 'KEYCODE_DPAD_LEFT',
      right: 'KEYCODE_DPAD_RIGHT',
      select: 'KEYCODE_DPAD_CENTER',
      back: 'KEYCODE_BACK',
      home: 'KEYCODE_HOME',
      menu: 'KEYCODE_MENU',
      playpause: 'KEYCODE_MEDIA_PLAY_PAUSE',
      rewind: 'KEYCODE_MEDIA_REWIND',
      forward: 'KEYCODE_MEDIA_FAST_FORWARD',
      wake: 'KEYCODE_WAKEUP',
      sleep: 'KEYCODE_SLEEP',
    };
    const code = MAP[name];
    if (!code) throw new Error(`Unknown key: ${name}`);

    if (await this.httpAlive()) {
      try {
        const res = await this.httpSend('key', { key: name });
        return { via: 'tv-app', ...res };
      } catch (err) {
        if (err.code === 'BAD_TV_TOKEN') throw err;
      }
    }
    await this.shell(`input keyevent ${code}`);
    return { via: 'adb', key: name };
  }
}

module.exports = { FireTv, findAdb, ADB_PORT, TV_APP_PORT };
