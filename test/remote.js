/* End-to-end test of the phone remote: boots the real HTTP server with stubbed
 * TMDB/Fire TV, checks the auth model, then drives the actual phone UI in a
 * headless browser against it. */

const path = require('path');
const fs = require('fs');
const os = require('os');
const assert = require('assert');
const { chromium } = require('playwright');

/** Prefer an explicit CHROME_BIN, then a sandbox path if it happens to exist. */
function chromeBin() {
  if (process.env.CHROME_BIN) return process.env.CHROME_BIN;
  const sandbox = '/opt/pw-browsers/chromium-1194/chrome-linux/chrome';
  try {
    return require('fs').existsSync(sandbox) ? sandbox : null;
  } catch {
    return null;
  }
}

const { RemoteServer } = require('../src/remoteserver');
const { Store } = require('../src/store');
const { SERVICES } = require('../src/services');

const checks = [];
const errors = [];
function check(name, cond, detail = '') {
  checks.push(!!cond);
  console.log(`${cond ? '  ok  ' : ' FAIL '} ${name}${!cond && detail ? ' — ' + detail : ''}`);
}
async function checkAsync(name, fn) {
  try {
    await fn();
    checks.push(true);
    console.log(`  ok   ${name}`);
  } catch (e) {
    checks.push(false);
    console.log(` FAIL  ${name} — ${e.message}`);
  }
}

// ---- stubs -----------------------------------------------------------------

const item = (id, title, type, year, score) => ({
  id, mediaType: type, title, year, poster: null, backdrop: null,
  overview: 'Stub synopsis.', score, genreIds: [18], date: `${year}-01-01`,
});

const ITEMS = [
  item(1396, 'Breaking Bad', 'tv', 2008, 92),
  item(136315, 'Dune', 'movie', 2021, 78),
  item(94605, 'Arcane', 'tv', 2021, 88),
];

const tvCalls = [];
const fakeFiretv = {
  status: async () => ({ host: '192.168.1.50', adbFound: true, adbConnected: true, tvApp: false, services: { netflix: true, disneyplus: true, hbomax: false, primevideo: true } }),
  play: async (serviceId, opts) => { tvCalls.push({ action: 'play', serviceId, opts }); return { via: 'adb', kind: 'universal-search' }; },
  search: async (query) => { tvCalls.push({ action: 'search', query }); return { via: 'adb' }; },
  key: async (key) => { tvCalls.push({ action: 'key', key }); return { via: 'adb', key }; },
};

const fakeTmdb = {
  searchMulti: async (q) => ({ page: 1, totalPages: 1, results: ITEMS }),
};

const detailsFor = async (mediaType, id) => ({
  ...ITEMS[0], id: Number(id), mediaType,
  runtime: 49, seasons: 5, genres: ['Drama', 'Crime'], genreIds: [18, 80],
  directors: ['Vince Gilligan'],
  cast: [{ name: 'Bryan Cranston', character: 'Walter White', profile: null }],
  imdbId: 'tt0903747',
  providers: { flatrate: [], rent: [], buy: [], link: null },
  recommendations: ITEMS.slice(1),
  availableOn: [{ serviceId: 'netflix', kind: 'included' }, { serviceId: 'primevideo', kind: 'rent' }],
  ratings: { rottenTomatoes: 96, imdb: 9.5, metacritic: 87, rated: 'TV-MA', awards: null },
});

const buildHome = async () => [
  { id: 'foryou', title: 'Picks for you', subtitle: 'From what you open', items: ITEMS },
  { id: 'trending', title: 'Trending this week', subtitle: null, items: ITEMS },
];

// ---- go --------------------------------------------------------------------

(async () => {
  const storeFile = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'sh-remote-')), 'store.json');
  const store = new Store(storeFile);
  store.updateSettings({ tmdbKey: 'x', region: 'BE' });

  const server = new RemoteServer({
    getSettings: () => store.settings,
    store,
    tmdb: fakeTmdb,
    omdb: {},
    firetv: fakeFiretv,
    services: SERVICES,
    buildHome,
    detailsFor,
  });

  const info = await server.start(0);
  const port = server.server.address().port;
  server.port = port;
  const base = `http://127.0.0.1:${port}`;
  const CODE = server.code;

  console.log('auth');

  check('a six-digit pairing code is generated', /^\d{6}$/.test(CODE), CODE);

  await checkAsync('the API is closed before pairing', async () => {
    const res = await fetch(`${base}/api/state`);
    assert.strictEqual(res.status, 401);
  });

  await checkAsync('a wrong code is rejected', async () => {
    const res = await fetch(`${base}/api/pair`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code: '000000' === CODE ? '111111' : '000000' }),
    });
    assert.strictEqual(res.status, 401);
  });

  await checkAsync('an empty code cannot pair', async () => {
    const res = await fetch(`${base}/api/pair`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code: '' }),
    });
    assert.ok(res.status === 401, `got ${res.status}`);
  });

  let token;
  await checkAsync('the right code returns a token', async () => {
    const res = await fetch(`${base}/api/pair`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code: CODE }),
    });
    assert.strictEqual(res.status, 200);
    ({ token } = await res.json());
    assert.ok(token && token.length >= 32, 'token too short');
  });

  await checkAsync('a forged token is rejected', async () => {
    const res = await fetch(`${base}/api/state`, { headers: { 'x-streamhub-token': 'a'.repeat(64) } });
    assert.strictEqual(res.status, 401);
  });

  await checkAsync('brute-forcing the code rotates it', async () => {
    const before = server.code;
    for (let i = 0; i < 12; i++) {
      await fetch(`${base}/api/pair`, {
        method: 'POST', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ code: '999999' }),
      });
    }
    assert.notStrictEqual(server.code, before, 'code should have been regenerated');
  });

  await checkAsync('the already-issued token still works after rotation', async () => {
    const res = await fetch(`${base}/api/state`, { headers: { 'x-streamhub-token': token } });
    assert.strictEqual(res.status, 200);
  });

  await checkAsync('unpair-all revokes existing tokens', async () => {
    server.revokeAll();
    const res = await fetch(`${base}/api/state`, { headers: { 'x-streamhub-token': token } });
    assert.strictEqual(res.status, 401);
  });

  // re-pair for the UI test
  const code2 = server.code;
  const paired = await (await fetch(`${base}/api/pair`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ code: code2 }),
  })).json();
  token = paired.token;

  await checkAsync('static files are served with a strict CSP', async () => {
    const res = await fetch(`${base}/`);
    assert.strictEqual(res.status, 200);
    const csp = res.headers.get('content-security-policy') || '';
    assert.ok(csp.includes("script-src 'self'"), csp);
  });

  await checkAsync('the web manifest is served so the phone can install it', async () => {
    const res = await fetch(`${base}/manifest.webmanifest`);
    assert.strictEqual(res.status, 200);
    const m = await res.json();
    assert.strictEqual(m.display, 'standalone');
    assert.ok(m.icons.length);
  });

  await checkAsync('unknown paths 404 rather than leaking files', async () => {
    for (const p of ['/../package.json', '/main.js', '/secret']) {
      const res = await fetch(base + p);
      assert.ok(res.status === 404 || res.status === 400, `${p} gave ${res.status}`);
    }
  });

  console.log('\nphone UI');

  const browser = await chromium.launch({
    // Use whatever Playwright installed (`npx playwright install chromium`).
    // CHROME_BIN overrides it; the sandbox path is only a last resort so this
    // keeps working on Windows and macOS.
    ...(chromeBin() ? { executablePath: chromeBin() } : {}),
    args: ['--no-sandbox'],
  });
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error' && !/service worker|sw\.js|401/i.test(m.text())) errors.push('console: ' + m.text()); });
  await page.route('https://image.tmdb.org/**', (r) => r.abort());

  await page.goto(base);
  await page.waitForTimeout(400);

  check('an unpaired phone sees the pairing screen', await page.locator('#pair').isVisible());
  check('the app is hidden until paired', await page.locator('#app').isHidden());

  await page.fill('#pair-code', '123456');
  await page.waitForTimeout(1200);
  const pairErr = await page.locator('#pair-err').textContent();
  const stillGated = await page.locator('#app').isHidden();
  check('a wrong code shows an error and does not let you in', pairErr.trim().length > 0 && stillGated, `err=${JSON.stringify(pairErr)} gated=${stillGated}`);

  await page.fill('#pair-code', server.code);
  await page.waitForTimeout(900);
  check('the right code unlocks the app', await page.locator('#app').isVisible());
  check('the token is remembered for next time', !!(await page.evaluate(() => localStorage.getItem('streamhub.token'))));

  await page.waitForTimeout(600);
  check('home rows render', (await page.locator('.section').count()) >= 2);
  check('cards render', (await page.locator('.card').count()) >= 3);
  check('TV status is shown', (await page.locator('#tv-status').textContent()).toLowerCase().includes('connected'));
  await page.screenshot({ path: path.join(__dirname, 'shots', '10-phone-home.png') });

  // services tab
  await page.locator('.tab[data-view="services"]').click();
  await page.waitForTimeout(300);
  check('the services tab lists all four', (await page.locator('.svc-card').count()) === 4);
  check('a service missing from the TV is flagged', (await page.locator('.svc-card').nth(2).textContent()).includes('Not installed'));

  tvCalls.length = 0;
  await page.locator('.svc-card').first().click();
  await page.waitForTimeout(400);
  check('tapping a service launches it on the TV', tvCalls.some((c) => c.action === 'play' && c.serviceId === 'netflix'), JSON.stringify(tvCalls));
  await page.screenshot({ path: path.join(__dirname, 'shots', '11-phone-services.png') });

  // search + detail sheet
  await page.fill('#q', 'breaking bad');
  await page.press('#q', 'Enter');
  await page.waitForTimeout(700);
  check('search renders results', (await page.locator('.grid .card').count()) === 3);

  await page.locator('.grid .card').first().click();
  await page.waitForTimeout(700);
  check('the title sheet opens', await page.locator('#sheet .sheet-body').isVisible());
  const sheetText = await page.locator('#sheet').textContent();
  check('the sheet shows the director', sheetText.includes('Vince Gilligan'));
  check('the sheet shows Rotten Tomatoes', sheetText.includes('96%'));
  check('the sheet shows IMDb', sheetText.includes('9.5/10'));
  check('play buttons appear for the services that have it', (await page.locator('.play-btn').count()) === 2);
  await page.screenshot({ path: path.join(__dirname, 'shots', '12-phone-detail.png') });

  tvCalls.length = 0;
  await page.locator('.play-btn').first().click();
  await page.waitForTimeout(500);
  const played = tvCalls.find((c) => c.action === 'play');
  check('tapping play sends the title to the TV', played && played.serviceId === 'netflix' && played.opts.title === 'Breaking Bad', JSON.stringify(tvCalls));

  // watchlist round-trip
  await page.locator('.grid .card').first().click();
  await page.waitForTimeout(600);
  await page.locator('.btn-row .btn', { hasText: 'Watchlist' }).click();
  await page.waitForTimeout(400);
  check('the watchlist edit reached the shared store', store.data.watchlist.length === 1, JSON.stringify(store.data.watchlist));
  await page.waitForTimeout(400);
  const badge = await page.locator('#wl-count').textContent();
  const clientWl = await page.evaluate(() => (window.__S && window.__S.state.watchlist || []).length);
  check('the tab badge updates', badge === '1', `badge=${JSON.stringify(badge)} clientWatchlist=${clientWl}`);

  // A title with no backdrop still needs a way out of the sheet.
  check('the sheet always offers a close button', await page.locator('#sheet .close').isVisible());
  await page.locator('#sheet .close').click();
  await page.waitForTimeout(300);
  check('the close button dismisses the sheet', await page.locator('#sheet-wrap').isHidden());

  // d-pad remote
  await page.locator('#remote-btn').click();
  await page.waitForTimeout(300);
  check('the d-pad remote opens', await page.locator('.dpad').isVisible());
  tvCalls.length = 0;
  await page.locator('.dk.ok').click();
  await page.locator('.rk[data-key="back"]').click();
  await page.waitForTimeout(400);
  check('d-pad presses reach the TV', tvCalls.filter((c) => c.action === 'key').length === 2, JSON.stringify(tvCalls));
  await page.screenshot({ path: path.join(__dirname, 'shots', '13-phone-remote.png') });

  // reload keeps you paired
  await page.reload();
  await page.waitForTimeout(900);
  check('a reload does not ask you to pair again', await page.locator('#app').isVisible());

  await browser.close();
  await server.stop();

  console.log('');
  if (errors.length) { console.log('Runtime errors:'); errors.forEach((e) => console.log('  ' + e)); }
  const failed = checks.filter((c) => !c).length;
  console.log(`\n${checks.length - failed}/${checks.length} checks passed, ${errors.length} runtime errors`);
  process.exit(failed || errors.length ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
