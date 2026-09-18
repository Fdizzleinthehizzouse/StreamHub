/* Headless smoke test for the StreamHub renderer.
 *
 * Boots src/renderer/index.html in Chromium with a mocked `window.hub`,
 * walks every view, opens the detail sheet, toggles the watchlist, and fails
 * on any console error, page error, or missing element.
 */

const path = require('path');
const fs = require('fs');
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

const ROOT = path.join(__dirname, '..');
const SHOTS = path.join(ROOT, 'test', 'shots');

const errors = [];
const checks = [];

function check(name, condition, detail = '') {
  checks.push({ name, pass: !!condition, detail });
  console.log(`${condition ? '  ok  ' : ' FAIL '} ${name}${detail && !condition ? ' — ' + detail : ''}`);
}

const MOCK = `
(() => {
  const F = window.__FIXTURES__;
  const clone = (x) => JSON.parse(JSON.stringify(x));
  let state = clone(F.state);
  window.__CALLS__ = [];
  const log = (n, a) => window.__CALLS__.push({ n, a });
  const ok = (data) => Promise.resolve({ ok: true, data });

  const detail = (mediaType, id) => ({
    id, mediaType,
    title: 'Breaking Bad',
    year: 2008, date: '2008-01-20',
    poster: null, backdrop: null,
    overview: 'A chemistry teacher turns to manufacturing.',
    score: 92, votes: 14000,
    runtime: 49, seasons: 5, episodes: 62,
    genres: ['Drama', 'Crime'], genreIds: [18, 80],
    directors: ['Vince Gilligan'],
    cast: [
      { name: 'Bryan Cranston', character: 'Walter White', profile: null },
      { name: 'Aaron Paul', character: 'Jesse Pinkman', profile: null }
    ],
    imdbId: 'tt0903747',
    providers: { flatrate: [{ id: 8, name: 'Netflix', logo: null }], rent: [], buy: [], link: 'https://www.themoviedb.org/tv/1396/watch' },
    recommendations: F.items.slice(1, 4),
    availableOn: [{ serviceId: 'netflix', kind: 'included' }, { serviceId: 'primevideo', kind: 'rent' }],
    ratings: { rottenTomatoes: 96, imdb: 9.5, metacritic: 87, rated: 'TV-MA', awards: 'Won 16 Primetime Emmys.', boxOffice: null }
  });

  window.hub = {
    bootstrap: () => ok({ services: F.SERVICES, state: clone(state), browser: { name: 'chrome', path: 'C:\\\\chrome.exe' }, version: '1.0.0' }),
    settings: {
      get: () => ok(state.settings),
      set: (patch) => { log('settings.set', patch); Object.assign(state.settings, patch); return ok({ settings: state.settings, browser: { name: 'edge', path: 'msedge.exe' } }); }
    },
    state: {
      get: () => ok(clone(state)),
      toggleWatchlist: (item) => {
        log('toggleWatchlist', item);
        const key = item.mediaType + ':' + item.id;
        const i = state.watchlist.findIndex(w => w.key === key);
        if (i >= 0) state.watchlist.splice(i, 1); else state.watchlist.unshift({ ...item, key });
        return ok(clone(state));
      },
      togglePinned: (item) => {
        log('togglePinned', item);
        const key = item.mediaType + ':' + item.id;
        const i = state.pinned.findIndex(w => w.key === key);
        if (i >= 0) state.pinned.splice(i, 1); else state.pinned.unshift({ ...item, key });
        return ok(clone(state));
      },
      clearHistory: () => { log('clearHistory'); state.history = []; return ok(clone(state)); }
    },
    open: {
      service: (id) => { log('open.service', id); return ok({ browser: 'chrome', url: 'x', state: clone(state) }); },
      title: (id, item) => { log('open.title', { id, item }); return ok({ browser: 'chrome', url: 'x', state: clone(state) }); },
      external: (url) => { log('open.external', url); return ok(true); }
    },
    data: {
      search: (q) => { log('data.search', q); return ok({ page: 1, totalPages: 1, results: F.items, providerIds: { netflix: [8] } }); },
      details: (mediaType, id) => { log('data.details', { mediaType, id }); return ok(detail(mediaType, id)); },
      home: () => ok({
        rows: [
          { id: 'because', title: 'Because you opened Breaking Bad', subtitle: 'More in the same vein', items: F.items },
          { id: 'foryou', title: 'Picks for you', subtitle: 'Built from what you have been opening', items: F.items },
          { id: 'popular-netflix', title: 'Popular on Netflix', service: 'netflix', items: F.items }
        ],
        providerIds: { netflix: [8], disneyplus: [337], hbomax: [1899], primevideo: [119] }
      }),
      browseService: (id) => { log('data.browseService', id); return ok({ rows: [{ id: 'pop', title: 'Popular films', items: F.items }] }); }
    },
    img: (p, size) => (p ? 'https://image.tmdb.org/t/p/' + size + p : null)
  };
})();
`;

(async () => {
  fs.mkdirSync(SHOTS, { recursive: true });
  const browser = await chromium.launch({
    // Use whatever Playwright installed (`npx playwright install chromium`).
    // CHROME_BIN overrides it; the sandbox path is only a last resort so this
    // keeps working on Windows and macOS.
    ...(chromeBin() ? { executablePath: chromeBin() } : {}),
    args: ['--no-sandbox'],
  });
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } });

  page.on('console', (m) => {
    if (m.type() === 'error') errors.push('console: ' + m.text());
  });
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));

  // Block real TMDB images so the test is offline-safe.
  await page.route('https://image.tmdb.org/**', (r) => r.abort());

  await page.addInitScript({ content: fs.readFileSync(path.join(__dirname, 'fixtures.js'), 'utf8') });
  await page.addInitScript({ content: MOCK });

  await page.goto('file://' + path.join(ROOT, 'src', 'renderer', 'index.html'));
  await page.waitForTimeout(400);

  // ---- home -----------------------------------------------------------
  check('sidebar lists all four services', (await page.locator('#nav-services .nav-item').count()) === 4);
  check('home renders four service tiles', (await page.locator('.svc-tile').count()) === 4);
  check('continue-watching row is present', await page.locator('.row-head h2', { hasText: 'Continue watching' }).isVisible());
  check('recommendation rows rendered', (await page.locator('.row').count()) >= 4);
  check('cards rendered', (await page.locator('.card').count()) > 10);
  check('watchlist badge shows a count', (await page.locator('#wl-count').textContent()) === '2');
  check('browser line names the browser', (await page.locator('#browser-line').textContent()).includes('Chrome'));
  check('region chip shows BE', (await page.locator('#region-chip').textContent()).includes('BE'));
  await page.screenshot({ path: path.join(SHOTS, '01-home.png'), fullPage: false });

  // ---- launching a service --------------------------------------------
  await page.locator('.svc-tile').first().click();
  await page.waitForTimeout(200);
  let calls = await page.evaluate(() => window.__CALLS__);
  check('clicking a service tile launches it', calls.some((c) => c.n === 'open.service' && c.a === 'netflix'));
  check('a toast confirms the launch', await page.locator('#toast').isVisible());

  // ---- search ----------------------------------------------------------
  await page.fill('#q', 'breaking bad');
  await page.press('#q', 'Enter');
  await page.waitForTimeout(300);
  check('search view renders a result grid', (await page.locator('.grid .card').count()) === 5);
  check('search heading echoes the query', (await page.locator('.page-head h1').textContent()).includes('breaking bad'));
  await page.screenshot({ path: path.join(SHOTS, '02-search.png') });

  // ---- detail sheet -----------------------------------------------------
  await page.locator('.grid .card').first().click();
  await page.waitForTimeout(300);
  check('detail sheet opens', await page.locator('#sheet .sheet-body').isVisible());
  const facts = await page.locator('.sheet-facts').textContent();
  check('facts include the year', facts.includes('2008'), facts);
  check('facts include seasons', facts.includes('5 seasons'), facts);
  check('facts include the director', facts.includes('Vince Gilligan'), facts);
  const ratingText = await page.locator('.ratings').textContent();
  check('Rotten Tomatoes score shown', ratingText.includes('96%'), ratingText);
  check('IMDb score shown', ratingText.includes('9.5/10'), ratingText);
  check('Metacritic score shown', ratingText.includes('87'), ratingText);
  check('TMDB score shown', ratingText.includes('92%'), ratingText);
  check('cast rendered', (await page.locator('.cast-member').count()) === 2);
  check('genres rendered', (await page.locator('.sheet-section .chip').count()) >= 2);
  check('"if you like this" row rendered', (await page.locator('#sheet .strip .card').count()) === 3);

  const watchBtns = page.locator('.watch-btn');
  check('availability buttons rendered', (await watchBtns.count()) === 2);
  check('rent variant is labelled', (await watchBtns.nth(1).textContent()).includes('rent'));
  await page.screenshot({ path: path.join(SHOTS, '03-detail.png') });

  // ---- watchlist toggle -------------------------------------------------
  const wlBtn = page.locator('.sheet-actions .btn').first();
  const before = await wlBtn.textContent();
  await wlBtn.click();
  await page.waitForTimeout(200);
  const after = await wlBtn.textContent();
  check('watchlist button toggles its label', before !== after, `${before} -> ${after}`);
  calls = await page.evaluate(() => window.__CALLS__);
  check('watchlist toggle reached the main process', calls.some((c) => c.n === 'toggleWatchlist'));

  // ---- open on service from the sheet ------------------------------------
  await watchBtns.first().click();
  await page.waitForTimeout(200);
  calls = await page.evaluate(() => window.__CALLS__);
  const openTitle = calls.find((c) => c.n === 'open.title');
  check('"Open on Netflix" launches with the title', openTitle && openTitle.a.id === 'netflix' && openTitle.a.item.title === 'Breaking Bad');

  await page.keyboard.press('Escape');
  await page.waitForTimeout(150);
  check('Escape closes the sheet', await page.locator('#sheet-backdrop').isHidden());

  // ---- watchlist view ----------------------------------------------------
  await page.locator('#nav-main [data-view="watchlist"]').click();
  await page.waitForTimeout(200);
  check('watchlist view lists saved titles', (await page.locator('.grid .card').count()) >= 2);
  await page.screenshot({ path: path.join(SHOTS, '04-watchlist.png') });

  // ---- a single service --------------------------------------------------
  await page.locator('#nav-services .nav-item').nth(2).click();
  await page.waitForTimeout(300);
  check('service view heading is the service name', (await page.locator('.page-head h1').textContent()) === 'HBO Max');
  check('service view renders rows', (await page.locator('.row').count()) >= 1);
  await page.screenshot({ path: path.join(SHOTS, '05-service.png') });

  // ---- settings ----------------------------------------------------------
  await page.locator('.sidebar-foot [data-view="settings"]').click();
  await page.waitForTimeout(200);
  check('settings shows the key fields', (await page.locator('.field input').count()) >= 3);
  check('settings explains the DRM situation', (await page.locator('.callout').first().textContent()).includes('Widevine'));
  await page.fill('.field input[type="text"]', 'FR');
  await page.locator('.btn.primary', { hasText: 'Save' }).click();
  await page.waitForTimeout(300);
  calls = await page.evaluate(() => window.__CALLS__);
  const saved = calls.find((c) => c.n === 'settings.set');
  check('saving settings sends the new region', saved && saved.a.region === 'FR', JSON.stringify(saved && saved.a));
  check('saving returns you to home', (await page.locator('.page-head h1').textContent()) === 'Home');
  await page.locator('.sidebar-foot [data-view="settings"]').click();
  await page.waitForTimeout(200);
  await page.screenshot({ path: path.join(SHOTS, '06-settings.png'), fullPage: true });

  // ---- keyboard shortcut --------------------------------------------------
  await page.keyboard.press('Control+k');
  check('Ctrl+K focuses the search box', await page.evaluate(() => document.activeElement.id === 'q'));

  await browser.close();

  // ---- report --------------------------------------------------------------
  console.log('');
  if (errors.length) {
    console.log('Console / page errors:');
    for (const e of errors) console.log('  ' + e);
  }
  const failed = checks.filter((c) => !c.pass);
  console.log(`\n${checks.length - failed.length}/${checks.length} checks passed, ${errors.length} runtime errors`);
  process.exit(failed.length || errors.length ? 1 : 0);
})();
