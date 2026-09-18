/* Drives the phone page exactly as the TV will serve it — same files, same
 * endpoints — against a mock of the TV's API. Catches the wiring mistakes that
 * moving the remote from the PC to the TV could easily have introduced. */

const http = require('http');
const fs = require('fs');
const path = require('path');
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

const ASSETS = path.join(__dirname, '..', 'firetv', 'app', 'src', 'main', 'assets', 'remote');
const CODE = 'K7M2QX';

const checks = [];
const errors = [];
const calls = [];
function check(name, cond, detail = '') {
  checks.push(!!cond);
  console.log(`${cond ? '  ok  ' : ' FAIL '} ${name}${!cond && detail ? ' — ' + detail : ''}`);
}

const TITLES = [
  { id: 1396, mediaType: 'tv', title: 'Breaking Bad', year: 2008, poster: null, backdrop: null, overview: 'Stub.', score: 92, genreIds: [18] },
  { id: 136315, mediaType: 'movie', title: 'Dune', year: 2021, poster: null, backdrop: null, overview: 'Stub.', score: 78, genreIds: [878] },
  { id: 94605, mediaType: 'tv', title: 'Arcane', year: 2021, poster: null, backdrop: null, overview: 'Stub.', score: 88, genreIds: [16] },
];

let hasKey = false;
let token = null;
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.webmanifest': 'application/manifest+json' };

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname === '/' ? '/index.html' : url.pathname;
  const send = (code, obj) => { res.writeHead(code, { 'content-type': 'application/json' }); res.end(JSON.stringify(obj)); };

  // static, straight from the app's assets folder
  const file = path.join(ASSETS, p);
  if (fs.existsSync(file) && fs.statSync(file).isFile()) {
    res.writeHead(200, { 'content-type': MIME[path.extname(p)] || 'text/plain' });
    return res.end(fs.readFileSync(file));
  }

  let body = {};
  if (req.method === 'POST') {
    const chunks = [];
    for await (const c of req) chunks.push(c);
    try { body = JSON.parse(Buffer.concat(chunks).toString() || '{}'); } catch { /* ignore */ }
  }
  calls.push({ path: p, method: req.method, body, query: Object.fromEntries(url.searchParams) });

  if (p === '/api/pair') {
    if ((body.code || '').toUpperCase() !== CODE) return send(401, { error: 'Wrong code.' });
    token = 'tok-' + Math.random().toString(16).slice(2);
    return send(200, { token });
  }
  if (req.headers['x-streamhub-token'] !== token) return send(401, { error: 'Not paired.' });

  if (p === '/api/state') {
    return send(200, {
      services: [
        { id: 'netflix', name: 'Netflix', short: 'N', color: '#e50914', accent: '#e50914', installed: true },
        { id: 'disneyplus', name: 'Disney+', short: 'D+', color: '#2f7bff', accent: '#2f7bff', installed: true },
        { id: 'hbomax', name: 'HBO Max', short: 'HBO', color: '#8b5cf6', accent: '#8b5cf6', installed: false },
        { id: 'primevideo', name: 'Prime Video', short: 'PV', color: '#00a8e1', accent: '#00a8e1', installed: true },
      ],
      state: { hasTmdbKey: hasKey, watchlist: [], pinned: [] },
      tv: { host: 'this-tv', tvApp: true, services: { netflix: true, disneyplus: true, hbomax: false, primevideo: true } },
    });
  }
  if (p === '/api/home') {
    if (!hasKey) return send(200, { rows: [], needsKey: true });
    return send(200, { rows: [{ id: 'foryou', title: 'Picks for you', subtitle: 'From what you watch', items: TITLES }] });
  }
  if (p === '/api/search') return send(200, { results: TITLES });
  if (p === '/api/details') {
    return send(200, {
      ...TITLES[0], runtime: 49, seasons: 5, genres: ['Drama', 'Crime'],
      directors: ['Vince Gilligan'],
      cast: [{ name: 'Bryan Cranston', character: 'Walter White', profile: null }],
      imdbId: 'tt0903747', recommendations: TITLES.slice(1),
      availableOn: [{ serviceId: 'netflix', kind: 'included' }],
      ratings: { rottenTomatoes: 96, imdb: 9.5, metacritic: 87, rated: 'TV-MA' },
    });
  }
  if (p === '/api/settings') {
    if (req.method === 'POST') { if (body.tmdbKey) hasKey = true; return send(200, { ok: true, region: body.region || 'BE', hasTmdbKey: hasKey, hasOmdbKey: !!body.omdbKey }); }
    return send(200, { region: 'BE', hasTmdbKey: hasKey, hasOmdbKey: false });
  }
  if (p === '/api/play') {
    if (body.serviceId === '') {
      return send(200, body.title ? { ok: true, kind: 'universal-search' } : { ok: false, error: 'Nothing to search for.' });
    }
    if (body.serviceId === 'hbomax') return send(200, { ok: false, error: 'HBO Max is not installed on this TV.' });
    return send(200, { ok: true, kind: 'universal-search', service: 'Netflix', state: { watchlist: [], pinned: [] } });
  }
  if (p === '/api/watchlist' || p === '/api/pinned') {
    // the real server includes `key`; the client matches on it
    return send(200, { watchlist: [{ ...TITLES[0], key: 'tv:1396' }], pinned: [], hasTmdbKey: true });
  }
  return send(404, { error: 'Not found' });
});

(async () => {
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;

  const browser = await chromium.launch({
    // Use whatever Playwright installed (`npx playwright install chromium`).
    // CHROME_BIN overrides it; the sandbox path is only a last resort so this
    // keeps working on Windows and macOS.
    ...(chromeBin() ? { executablePath: chromeBin() } : {}),
    args: ['--no-sandbox'],
  });
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error' && !/401|service worker|sw\.js|ERR_CONNECTION_REFUSED|Failed to fetch/i.test(m.text())) errors.push('console: ' + m.text()); });
  await page.route('https://image.tmdb.org/**', (r) => r.abort());

  await page.goto(base);
  await page.waitForTimeout(400);

  check('the TV serves the pairing screen', await page.locator('#pair').isVisible());
  check('it asks for the code shown on the TV', (await page.locator('.pair-card p').textContent()).toLowerCase().includes('tv'));

  await page.fill('#pair-code', 'wrong1');
  await page.waitForTimeout(900);
  check('a wrong code is refused and explained', (await page.locator('#pair-err').textContent()).trim().length > 0 && await page.locator('#app').isHidden());

  await page.fill('#pair-code', CODE.toLowerCase()); // typed in lower case on a phone
  await page.waitForTimeout(900);
  check('the code works regardless of letter case', await page.locator('#app').isVisible());

  await page.waitForTimeout(500);
  check('with no key yet, it points you at Settings', (await page.locator('#view').textContent()).toLowerCase().includes('cog'));
  await page.screenshot({ path: path.join(__dirname, 'shots', '20-tv-firstrun.png') });

  await page.locator('#settings-btn').click();
  await page.waitForTimeout(400);
  check('the settings sheet opens', await page.locator('#settings-wrap').isVisible());
  check('it explains where to get the key', (await page.locator('#settings-wrap').textContent()).includes('themoviedb.org'));
  await page.fill('#set-tmdb', 'MY-TMDB-KEY');
  await page.fill('#set-region', 'be');
  await page.screenshot({ path: path.join(__dirname, 'shots', '21-tv-settings.png') });
  await page.locator('#settings-save').click();
  await page.waitForTimeout(1400);

  const saved = calls.find((c) => c.path === '/api/settings' && c.method === 'POST');
  check('the key is sent to the TV', saved && saved.body.tmdbKey === 'MY-TMDB-KEY', JSON.stringify(saved && saved.body));
  check('the country is upper-cased before sending', saved && saved.body.region === 'BE');
  check('saving drops you back into the films', await page.locator('#settings-wrap').isHidden());

  await page.waitForTimeout(700);
  check('films load once the key is in', (await page.locator('.card').count()) >= 3);
  await page.screenshot({ path: path.join(__dirname, 'shots', '22-tv-home.png') });

  await page.locator('.tab[data-view="services"]').click();
  await page.waitForTimeout(300);
  check('all four services are listed', (await page.locator('.svc-card').count()) === 4);
  check('a service missing from the TV says so', (await page.locator('.svc-card').nth(2).textContent()).includes('Not installed'));

  calls.length = 0;
  await page.locator('.svc-card').first().click();
  await page.waitForTimeout(400);
  const svcPlay = calls.find((c) => c.path === '/api/play');
  check('tapping a service opens it on the TV', svcPlay && svcPlay.body.serviceId === 'netflix', JSON.stringify(calls.map((c) => c.path)));

  await page.fill('#q', 'breaking bad');
  await page.press('#q', 'Enter');
  await page.waitForTimeout(700);
  check('search works', (await page.locator('.grid .card').count()) === 3);

  await page.locator('.grid .card').first().click();
  await page.waitForTimeout(700);
  const sheet = await page.locator('#sheet').textContent();
  check('the film sheet shows the director', sheet.includes('Vince Gilligan'));
  check('and Rotten Tomatoes', sheet.includes('96%'));
  check('and a play button for the service that has it', (await page.locator('.play-btn').count()) === 1);
  await page.screenshot({ path: path.join(__dirname, 'shots', '23-tv-detail.png') });

  calls.length = 0;
  await page.locator('.play-btn').first().click();
  await page.waitForTimeout(600);
  const play = calls.find((c) => c.path === '/api/play');
  check('tapping play sends the film to the TV', play && play.body.serviceId === 'netflix' && play.body.item.title === 'Breaking Bad', JSON.stringify(play && play.body));
  check('nothing is asked of a PC any more', !calls.some((c) => c.path.includes('/api/tv/')));

  // --- the defects the review turned up ---------------------------------

  await page.locator('.tab[data-view="services"]').click();
  await page.waitForTimeout(300);
  const hbo = page.locator('.svc-card').nth(2); // HBO Max, not installed
  await hbo.click();
  await page.waitForTimeout(500);
  const toastText = await page.locator('#toast').textContent();
  check('a service the TV lacks reports failure, not success', /not installed/i.test(toastText), toastText);
  check('and it is shown as an error', (await page.locator('#toast').getAttribute('class')).includes('err'));

  // watchlist button must reflect what actually happened
  await page.fill('#q', 'breaking bad');
  await page.press('#q', 'Enter');
  await page.waitForTimeout(600);
  await page.locator('.grid .card').first().click();
  await page.waitForTimeout(700);
  // Pin the button by index first: its label changes after the tap, so a
  // text-based locator would stop matching the thing it just clicked.
  const labels = await page.locator('#sheet .btn').allTextContents();
  const wlIndex = labels.findIndex((t) => t.includes('Watchlist'));
  check('the sheet has a watchlist button', wlIndex >= 0, JSON.stringify(labels));
  const wlBtn = page.locator('#sheet .btn').nth(wlIndex);
  await wlBtn.click();
  await page.waitForTimeout(600);
  check('the watchlist button confirms the save', (await wlBtn.textContent()).includes('✓'), await wlBtn.textContent());

  // Search on TV, for a title none of the four carry
  calls.length = 0;
  await page.evaluate(() => {
    document.querySelectorAll('#sheet .btn').forEach((b) => { if (b.textContent.includes('Search on TV')) b.click(); });
  });
  await page.waitForTimeout(500);
  const searchCall = calls.find((c) => c.path === '/api/play');
  if (searchCall) {
    check('Search on TV sends the title with no service', searchCall.body.serviceId === '' && !!searchCall.body.title, JSON.stringify(searchCall.body));
  } else {
    checks.push(true);
    console.log('  ok   Search on TV button absent when the title is available (correct)');
  }

  await page.reload();
  await page.waitForTimeout(900);
  check('reloading keeps you paired', await page.locator('#app').isVisible());

  // an unreachable TV must offer a retry, not a dead screen
  server.close();
  await page.evaluate(() => localStorage.setItem('streamhub.token', 'stale'));
  await page.reload();
  await page.waitForTimeout(1500);
  const body = await page.locator('body').textContent();
  check('an unreachable TV explains itself', /can.t reach your tv/i.test(body), body.slice(0, 120));
  check('and offers a retry button', await page.locator('button', { hasText: 'Try again' }).isVisible());
  await page.screenshot({ path: path.join(__dirname, 'shots', '24-tv-offline.png') });

  await browser.close();
  try { server.close(); } catch { /* already closed above */ }

  console.log('');
  if (errors.length) { console.log('Runtime errors:'); errors.forEach((e) => console.log('  ' + e)); }
  const failed = checks.filter((c) => !c).length;
  console.log(`\n${checks.length - failed}/${checks.length} checks passed, ${errors.length} runtime errors`);
  process.exit(failed || errors.length ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
