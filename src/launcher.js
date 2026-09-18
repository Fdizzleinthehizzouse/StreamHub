'use strict';

const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn, execFileSync } = require('child_process');

/**
 * StreamHub never plays video itself - Netflix, Disney+, HBO Max and Prime Video
 * all require a licensed Widevine CDM that Electron does not ship. Instead we
 * open the *official* web player in a chromeless Chromium "app window", which
 * has Widevine and is a normal, supported way to watch.
 */

const WIN_CANDIDATES = {
  chrome: [
    path.join(process.env['PROGRAMFILES'] || 'C:\\Program Files', 'Google\\Chrome\\Application\\chrome.exe'),
    path.join(process.env['PROGRAMFILES(X86)'] || 'C:\\Program Files (x86)', 'Google\\Chrome\\Application\\chrome.exe'),
    path.join(process.env['LOCALAPPDATA'] || '', 'Google\\Chrome\\Application\\chrome.exe'),
  ],
  edge: [
    path.join(process.env['PROGRAMFILES(X86)'] || 'C:\\Program Files (x86)', 'Microsoft\\Edge\\Application\\msedge.exe'),
    path.join(process.env['PROGRAMFILES'] || 'C:\\Program Files', 'Microsoft\\Edge\\Application\\msedge.exe'),
    path.join(process.env['LOCALAPPDATA'] || '', 'Microsoft\\Edge\\Application\\msedge.exe'),
  ],
  brave: [
    path.join(process.env['PROGRAMFILES'] || 'C:\\Program Files', 'BraveSoftware\\Brave-Browser\\Application\\brave.exe'),
    path.join(process.env['PROGRAMFILES(X86)'] || 'C:\\Program Files (x86)', 'BraveSoftware\\Brave-Browser\\Application\\brave.exe'),
  ],
};

const NIX_CANDIDATES = {
  chrome: ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium', '/usr/bin/chromium-browser', '/opt/google/chrome/chrome'],
  edge: ['/usr/bin/microsoft-edge', '/usr/bin/microsoft-edge-stable'],
  brave: ['/usr/bin/brave-browser', '/usr/bin/brave'],
};

const MAC_CANDIDATES = {
  chrome: ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'],
  edge: ['/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge'],
  brave: ['/Applications/Brave Browser.app/Contents/MacOS/Brave Browser'],
};

function candidates() {
  if (process.platform === 'win32') return WIN_CANDIDATES;
  if (process.platform === 'darwin') return MAC_CANDIDATES;
  return NIX_CANDIDATES;
}

function firstExisting(list) {
  for (const p of list || []) {
    if (!p) continue;
    try {
      if (fs.existsSync(p)) return p;
    } catch {
      /* ignore */
    }
  }
  return null;
}

/** Last-resort Windows lookup through the registry. */
function registryChrome() {
  if (process.platform !== 'win32') return null;
  const keys = [
    'HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\chrome.exe',
    'HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\App Paths\\chrome.exe',
    'HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\chrome.exe',
  ];
  for (const key of keys) {
    try {
      const out = execFileSync('reg', ['query', key, '/ve'], { encoding: 'utf8', timeout: 4000 });
      const m = out.match(/REG_SZ\s+(.+\.exe)/i);
      if (m && fs.existsSync(m[1].trim())) return m[1].trim();
    } catch {
      /* ignore */
    }
  }
  return null;
}

/**
 * @param {string} pref 'auto' | 'chrome' | 'edge' | 'brave' | absolute path
 * @returns {{path:string, name:string}|null}
 */
function findBrowser(pref = 'auto') {
  const c = candidates();

  if (pref && pref !== 'auto' && !c[pref]) {
    // Treated as an explicit path to an executable. If the user named a path
    // and it is wrong, say so - silently launching a different browser than
    // the one they chose is worse than failing.
    return fs.existsSync(pref) ? { path: pref, name: path.basename(pref) } : null;
  }

  const order = pref && c[pref] ? [pref, 'chrome', 'edge', 'brave'] : ['chrome', 'edge', 'brave'];
  for (const name of order) {
    const found = firstExisting(c[name]);
    if (found) return { path: found, name };
  }

  const reg = registryChrome();
  if (reg) return { path: reg, name: 'chrome' };

  return null;
}

/**
 * Open a URL in a chromeless app window.
 *
 * profileMode 'shared'   - your normal browser profile, so existing logins just work
 * profileMode 'isolated' - a dedicated profile per service (first launch needs a login,
 *                          after that each service keeps its own session)
 */
function openAppWindow(url, opts = {}) {
  const { browser = 'auto', profileMode = 'shared', profileDir = null, serviceId = 'default', windowSize = '1600,900' } = opts;

  const found = findBrowser(browser);
  if (!found) {
    const err = new Error(
      'No Chromium-based browser found. StreamHub needs Chrome, Edge or Brave installed to play DRM-protected video.'
    );
    err.code = 'NO_BROWSER';
    throw err;
  }

  const args = [`--app=${url}`];

  if (windowSize) args.push(`--window-size=${windowSize}`);

  if (profileMode === 'isolated') {
    const dir = path.join(profileDir || path.join(os.tmpdir(), 'streamhub-profiles'), serviceId);
    fs.mkdirSync(dir, { recursive: true });
    args.push(`--user-data-dir=${dir}`);
    // A fresh profile has no first-run state; skip the setup noise.
    args.push('--no-first-run', '--no-default-browser-check');
  }

  return new Promise((resolve, reject) => {
    const child = spawn(found.path, args, {
      detached: true,
      stdio: 'ignore',
      windowsHide: false,
    });

    // Without this handler a failed spawn (binary present but not executable,
    // blocked by EDR, a stale path after an update) raises an unhandled
    // 'error' event, which takes down the whole Electron main process.
    child.once('error', (err) => {
      const e = new Error(`Could not start ${found.name}: ${err.message}`);
      e.code = 'SPAWN_FAILED';
      reject(e);
    });

    child.once('spawn', () => {
      child.unref();
      resolve({ browser: found.name, browserPath: found.path, url });
    });
  });
}

module.exports = { findBrowser, openAppWindow };
