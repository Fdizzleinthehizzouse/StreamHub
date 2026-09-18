'use strict';

const { app, BrowserWindow, ipcMain, shell, Menu } = require('electron');
const path = require('path');

const { Store } = require('./store');
const { Tmdb } = require('./tmdb');
const { Omdb } = require('./omdb');
const { SERVICES, byId, titleUrl } = require('./services');
const { findBrowser, openAppWindow } = require('./launcher');
const { buildHome } = require('./recommend');
const { FireTv } = require('./firetv');
const { RemoteServer } = require('./remoteserver');

let win = null;
let store = null;
let tmdb = null;
let omdb = null;
let firetv = null;
let remote = null;

/**
 * Full detail for a title: TMDB metadata, which of the four services carry it
 * in this region, and the OMDb ratings. Shared by the desktop UI and the phone.
 */
async function detailsFor(mediaType, id) {
  const detail = await tmdb.details(mediaType, id);

  // If the provider lookup fails we do NOT know what is available. Reporting an
  // empty list would make the UI state confidently that a title is on none of
  // the four services, which is a different claim entirely.
  let providerIds = null;
  try {
    providerIds = await tmdb.providerIds(SERVICES);
  } catch {
    providerIds = null;
  }
  if (!providerIds) {
    let ratingsOnly = null;
    if (detail.imdbId) ratingsOnly = await omdb.ratings(detail.imdbId).catch(() => null);
    return { ...detail, availableOn: null, availabilityUnknown: true, ratings: ratingsOnly };
  }

  const availableOn = [];
  const flatrateIds = new Set((detail.providers.flatrate || []).map((p) => p.id));
  const rentBuyIds = new Set([...(detail.providers.rent || []), ...(detail.providers.buy || [])].map((p) => p.id));
  for (const svc of SERVICES) {
    const ids = providerIds[svc.id] || [];
    if (ids.some((pid) => flatrateIds.has(pid))) availableOn.push({ serviceId: svc.id, kind: 'included' });
    else if (ids.some((pid) => rentBuyIds.has(pid))) availableOn.push({ serviceId: svc.id, kind: 'rent' });
  }

  let ratings = null;
  let ratingsError = null;
  if (detail.imdbId) {
    try {
      ratings = await omdb.ratings(detail.imdbId);
    } catch (err) {
      ratingsError = err.code === 'BAD_OMDB_KEY' ? err.message : null;
    }
  }

  return { ...detail, availableOn, availabilityUnknown: false, ratings, ratingsError };
}

function createWindow() {
  win = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 1000,
    minHeight: 640,
    backgroundColor: '#0b0d12',
    title: 'StreamHub',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));

  // Anything that wants a new window goes to the real browser, never here.
  win.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });

  win.on('closed', () => {
    win = null;
  });
}

app.whenReady().then(() => {
  store = new Store(path.join(app.getPath('userData'), 'streamhub.json'));
  const getSettings = () => store.settings;
  tmdb = new Tmdb(getSettings);
  omdb = new Omdb(getSettings);
  firetv = new FireTv(getSettings);
  remote = new RemoteServer({
    getSettings,
    store,
    tmdb,
    omdb,
    firetv,
    services: SERVICES,
    buildHome,
    detailsFor,
  });
  // The phone can change the watchlist, so keep the desktop view honest.
  remote.onChange = () => {
    if (win && !win.isDestroyed()) win.webContents.send('state:changed', store.snapshot());
  };

  Menu.setApplicationMenu(null);
  createWindow();

  if (store.settings.remoteAutoStart) {
    remote.start(store.settings.remotePort).catch((err) => console.error('[remote] autostart failed:', err.message));
  }

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow();
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

app.on('before-quit', () => {
  if (remote && remote.running) remote.stop().catch(() => {});
});

// ---------------------------------------------------------------------------
// IPC
// ---------------------------------------------------------------------------

/** Wrap a handler so the renderer always gets {ok, data} or {ok:false, error}. */
function handle(channel, fn) {
  ipcMain.handle(channel, async (_evt, ...args) => {
    try {
      return { ok: true, data: await fn(...args) };
    } catch (err) {
      return { ok: false, error: { message: err.message || String(err), code: err.code || null } };
    }
  });
}

handle('app:bootstrap', async () => {
  const browser = findBrowser(store.settings.browser);
  return {
    services: SERVICES,
    state: store.snapshot(),
    browser: browser ? { name: browser.name, path: browser.path } : null,
    version: app.getVersion(),
  };
});

handle('settings:get', async () => store.settings);
handle('settings:set', async (patch) => {
  const next = store.updateSettings(patch || {});
  const browser = findBrowser(next.browser);
  return { settings: next, browser: browser ? { name: browser.name, path: browser.path } : null };
});

handle('state:get', async () => store.snapshot());

// ---- launching -------------------------------------------------------------

handle('open:service', async (serviceId) => {
  const svc = byId(serviceId);
  if (!svc) throw new Error(`Unknown service: ${serviceId}`);
  const result = await openAppWindow(svc.home, {
    browser: store.settings.browser,
    profileMode: store.settings.profileMode,
    profileDir: path.join(app.getPath('userData'), 'profiles'),
    serviceId: svc.id,
    windowSize: store.settings.windowSize,
  });
  store.recordOpen(null, svc.id);
  return { ...result, state: store.snapshot() };
});

handle('open:title', async ({ serviceId, item }) => {
  const svc = byId(serviceId);
  if (!svc) throw new Error(`Unknown service: ${serviceId}`);
  const url = titleUrl(serviceId, item && item.title);
  const result = await openAppWindow(url, {
    browser: store.settings.browser,
    profileMode: store.settings.profileMode,
    profileDir: path.join(app.getPath('userData'), 'profiles'),
    serviceId: svc.id,
    windowSize: store.settings.windowSize,
  });
  store.recordOpen(item, svc.id);
  return { ...result, state: store.snapshot() };
});

handle('open:external', async (url) => {
  await shell.openExternal(url);
  return true;
});

// ---- library ---------------------------------------------------------------

handle('watchlist:toggle', async (item) => {
  store.toggleWatchlist(item);
  return store.snapshot();
});

handle('pinned:toggle', async (item) => {
  store.togglePinned(item);
  return store.snapshot();
});

handle('history:clear', async () => {
  store.clearHistory();
  return store.snapshot();
});

// ---- data ------------------------------------------------------------------

handle('tmdb:search', async ({ query, page }) => {
  const result = await tmdb.searchMulti(query, page || 1);
  const providerIds = await tmdb.providerIds(SERVICES).catch(() => ({}));
  return { ...result, providerIds };
});

handle('tmdb:details', async ({ mediaType, id }) => {
  if (mediaType !== 'movie' && mediaType !== 'tv') throw new Error('Unknown media type.');
  if (!/^\d+$/.test(String(id))) throw new Error('Invalid title id.');
  return detailsFor(mediaType, id);
});

// ---- Fire TV ---------------------------------------------------------------

handle('tv:status', async () => firetv.status());
handle('tv:connect', async () => {
  const r = await firetv.connect();
  return { ...r, status: await firetv.status() };
});
handle('tv:disconnect', async () => {
  await firetv.disconnect();
  return firetv.status();
});
handle('tv:play', async ({ serviceId, item, contentId }) => {
  const result = await firetv.play(serviceId, { title: item && item.title, contentId });
  if (item) store.recordOpen(item, serviceId);
  return { ...result, state: store.snapshot() };
});
handle('tv:pushSetup', async () => firetv.pushSetup());
handle('tv:search', async (query) => firetv.search(query));
handle('tv:key', async (key) => firetv.key(key));

// ---- phone remote ----------------------------------------------------------

handle('remote:info', async () => remote.info());
handle('remote:start', async (port) => remote.start(port || store.settings.remotePort));
handle('remote:stop', async () => remote.stop());
handle('remote:revoke', async () => remote.revokeAll());

handle('tmdb:home', async () => {
  const rows = await buildHome(tmdb, store);
  const providerIds = await tmdb.providerIds(SERVICES).catch(() => ({}));
  return { rows, providerIds };
});

handle('tmdb:browseService', async (serviceId) => {
  const svc = byId(serviceId);
  if (!svc) throw new Error(`Unknown service: ${serviceId}`);
  const providerIds = await tmdb.providerIds(SERVICES);
  const ids = providerIds[serviceId] || [];
  if (!ids.length) return { rows: [], note: `TMDB does not list ${svc.name} for region ${tmdb.region}.` };

  const p = ids.join('|');
  // Subscription-only, so a service page does not fill up with rentals.
  const MONETIZATION = 'flatrate,free,ads';
  const since = new Date(Date.now() - 1000 * 60 * 60 * 24 * 120).toISOString().slice(0, 10);
  const today = new Date().toISOString().slice(0, 10);

  const [popMovies, popShows, newMovies, topMovies, topShows] = await Promise.all([
    tmdb.discover('movie', { with_watch_providers: p, watch_region: tmdb.region, sort_by: 'popularity.desc' }),
    tmdb.discover('tv', { with_watch_providers: p, watch_region: tmdb.region, sort_by: 'popularity.desc' }),
    tmdb.discover('movie', {
      with_watch_providers: p,
      with_watch_monetization_types: MONETIZATION,
      watch_region: tmdb.region,
      'primary_release_date.gte': since,
      'primary_release_date.lte': today,
      sort_by: 'primary_release_date.desc',
    }),
    tmdb.discover('movie', {
      with_watch_providers: p,
      with_watch_monetization_types: MONETIZATION,
      watch_region: tmdb.region,
      sort_by: 'vote_average.desc',
      'vote_count.gte': 400,
    }),
    tmdb.discover('tv', {
      with_watch_providers: p,
      with_watch_monetization_types: MONETIZATION,
      watch_region: tmdb.region,
      sort_by: 'vote_average.desc',
      'vote_count.gte': 300,
    }),
  ]);

  return {
    rows: [
      { id: 'pop-movies', title: 'Popular films', items: popMovies.slice(0, 20) },
      { id: 'pop-shows', title: 'Popular series', items: popShows.slice(0, 20) },
      { id: 'new', title: 'Recently released', items: newMovies.slice(0, 20) },
      { id: 'top-movies', title: 'Highest rated films', items: topMovies.slice(0, 20) },
      { id: 'top-shows', title: 'Highest rated series', items: topShows.slice(0, 20) },
    ].filter((r) => r.items.length),
  };
});
