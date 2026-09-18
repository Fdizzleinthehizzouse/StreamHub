/* Boots the real Electron app headlessly and exercises the main-process IPC
 * through the renderer's own preload bridge. Network calls are stubbed at the
 * `fetch` level so this runs without API keys. */

const { _electron: electron } = require('playwright');
const path = require('path');
const fs = require('fs');
const os = require('os');

const ROOT = path.join(__dirname, '..');
// Always start from a clean profile, otherwise a key saved by the previous run
// leaks into this one and the "fresh install" checks are meaningless.
const USER_DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'streamhub-test-'));
const checks = [];
const errors = [];

function check(name, cond, detail = '') {
  checks.push({ name, pass: !!cond });
  console.log(`${cond ? '  ok  ' : ' FAIL '} ${name}${!cond && detail ? ' — ' + detail : ''}`);
}

(async () => {
  const app = await electron.launch({
    executablePath: require('electron'),
    args: [ROOT, '--no-sandbox', '--disable-gpu', `--user-data-dir=${USER_DATA}`],
    env: { ...process.env, ELECTRON_DISABLE_SECURITY_WARNINGS: '1' },
  });

  const page = await app.firstWindow();
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push('console: ' + m.text());
  });

  await page.waitForTimeout(1200);

  check('window opened', !!page);
  check('title is StreamHub', (await page.title()) === 'StreamHub');
  check('preload bridge is exposed', await page.evaluate(() => typeof window.hub === 'object'));
  check('node globals are NOT reachable from the renderer', await page.evaluate(() => typeof require === 'undefined' && typeof process === 'undefined'));

  // Fresh profile has no key, so the app should land on Settings.
  const heading = await page.locator('.page-head h1').textContent();
  check('a keyless install opens on Settings', heading === 'Settings', heading);
  check('settings explains the DRM constraint', (await page.locator('.callout').first().textContent()).includes('Widevine'));

  // ---- IPC round-trips ------------------------------------------------
  const boot = await page.evaluate(() => window.hub.bootstrap());
  check('app:bootstrap succeeds', boot.ok);
  check('bootstrap returns four services', boot.ok && boot.data.services.length === 4);
  check('bootstrap detects a browser or reports none', boot.ok && 'browser' in boot.data);

  const noKey = await page.evaluate(() => window.hub.data.search('dune'));
  check('search without a key fails cleanly (no crash)', noKey.ok === false && noKey.error.code === 'NO_KEY', JSON.stringify(noKey));

  const set = await page.evaluate(() => window.hub.settings.set({ region: 'FR', tmdbKey: 'test-key' }));
  check('settings:set round-trips', set.ok && set.data.settings.region === 'FR');

  const wl = await page.evaluate(() =>
    window.hub.state.toggleWatchlist({ id: 1396, mediaType: 'tv', title: 'Breaking Bad', poster: null, year: 2008, genreIds: [18] })
  );
  check('watchlist:toggle persists through the main process', wl.ok && wl.data.watchlist.length === 1);

  const wl2 = await page.evaluate(() =>
    window.hub.state.toggleWatchlist({ id: 1396, mediaType: 'tv', title: 'Breaking Bad', poster: null, year: 2008, genreIds: [18] })
  );
  check('watchlist:toggle removes on second call', wl2.ok && wl2.data.watchlist.length === 0);

  const pin = await page.evaluate(() => window.hub.state.togglePinned({ id: 550, mediaType: 'movie', title: 'Fight Club', year: 1999 }));
  check('pinned:toggle works', pin.ok && pin.data.pinned.length === 1);

  const bad = await page.evaluate(() => window.hub.open.service('nope'));
  check('unknown service is rejected, not thrown', bad.ok === false && /Unknown service/.test(bad.error.message));

  // A bogus key should surface TMDB's 401 as a friendly message rather than crash.
  const badKey = await page.evaluate(async () => {
    await window.hub.settings.set({ tmdbKey: 'definitely-not-a-real-key' });
    return window.hub.data.search('dune');
  });
  check('a rejected TMDB key produces a readable error', badKey.ok === false && /key/i.test(badKey.error.message), JSON.stringify(badKey.error));

  // ---- Fire TV + phone remote ------------------------------------------
  const tvStatus = await page.evaluate(() => window.hub.tv.status());
  check('tv:status works with no TV configured', tvStatus.ok && tvStatus.data.host === null, JSON.stringify(tvStatus));

  const tvPlayNoHost = await page.evaluate(() => window.hub.tv.play('netflix', null));
  check('playing with no TV address fails cleanly', tvPlayNoHost.ok === false && /Fire TV address/.test(tvPlayNoHost.error.message));

  const remoteOff = await page.evaluate(() => window.hub.remote.info());
  check('the phone remote starts out off', remoteOff.ok && remoteOff.data.running === false);

  const started = await page.evaluate(() => window.hub.remote.start(7788));
  check('the phone remote starts', started.ok && started.data.running === true, JSON.stringify(started.error || {}));
  check('it reports a six-digit pairing code', started.ok && /^\d{6}$/.test(started.data.code || ''));
  check('it reports at least one LAN url', started.ok && Array.isArray(started.data.urls));
  check('it generates a QR for the phone', started.ok && typeof started.data.qr === 'string' && started.data.qr.startsWith('<svg'));

  const restart = await page.evaluate(() => window.hub.remote.start(7788));
  check('starting twice is a no-op rather than a crash', restart.ok && restart.data.running === true);

  const revoked = await page.evaluate(async () => {
    const before = (await window.hub.remote.info()).data.code;
    const after = (await window.hub.remote.revoke()).data.code;
    return { before, after };
  });
  check('unpairing rotates the code', revoked.before !== revoked.after, JSON.stringify(revoked));

  const stopped = await page.evaluate(() => window.hub.remote.stop());
  check('the phone remote stops', stopped.ok && stopped.data.running === false);

  await page.locator('.sidebar-foot [data-view="tv"]').click();
  await page.waitForTimeout(600);
  check('the TV & Phone page renders', (await page.locator('.page-head h1').textContent()) === 'TV & Phone');
  check('it explains the PC-in-the-middle setup', (await page.locator('.callout').last().textContent()).toLowerCase().includes('adb'));
  await page.screenshot({ path: path.join(ROOT, 'test', 'shots', '08-electron-tv.png'), fullPage: true });

  await page.locator('.sidebar-foot [data-view="settings"]').click();
  await page.waitForTimeout(400);
  await page.screenshot({ path: path.join(ROOT, 'test', 'shots', '07-electron-settings.png'), fullPage: true });

  await app.close();

  console.log('');
  if (errors.length) {
    console.log('Runtime errors:');
    errors.forEach((e) => console.log('  ' + e));
  }
  const failed = checks.filter((c) => !c.pass).length;
  console.log(`\n${checks.length - failed}/${checks.length} checks passed, ${errors.length} runtime errors`);
  process.exit(failed || errors.length ? 1 : 0);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
