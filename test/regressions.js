/* One test per defect found in the deep debug. These are the bugs that shipped
 * once; each check here is what would have caught it. */

const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

const { Store } = require('../src/store');
const { buildHome, tasteProfile } = require('../src/recommend');
const { findBrowser, openAppWindow } = require('../src/launcher');
const { RemoteServer } = require('../src/remoteserver');
const { SERVICES } = require('../src/services');

const checks = [];
async function check(name, fn) {
  try {
    await fn();
    checks.push(true);
    console.log(`  ok   ${name}`);
  } catch (e) {
    checks.push(false);
    console.log(` FAIL  ${name} — ${e.message}`);
  }
}

const tmpStore = () => new Store(path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'sh-reg-')), 's.json'));
const title = (id, type, genres) => ({ key: `${type}:${id}`, id, mediaType: type, title: `T${id}`, genreIds: genres });

(async () => {
  console.log('recommendation engine');

  await check('movie and TV genre profiles are kept apart', () => {
    const s = tmpStore();
    s.data.history = [title(1, 'movie', [878, 28]), title(2, 'tv', [10765, 10759])];
    const { topGenres } = tasteProfile(s);
    assert.deepStrictEqual(topGenres.movie.sort(), [28, 878], 'movie profile leaked TV ids');
    assert.deepStrictEqual(topGenres.tv.sort(), [10759, 10765], 'tv profile leaked movie ids');
  });

  await check('genres are OR-joined, so "Picks for you" is not an empty intersection', async () => {
    const s = tmpStore();
    s.data.history = [title(1, 'movie', [18, 80]), title(2, 'movie', [878, 12])];
    const seenParams = [];
    const fakeTmdb = {
      region: 'BE',
      providerIds: async () => ({ netflix: [8], disneyplus: [337], hbomax: [1899], primevideo: [119] }),
      details: async () => { throw new Error('skip'); },
      trending: async () => [],
      discover: async (type, params) => {
        seenParams.push(params);
        return [title(90 + seenParams.length, type, [18])];
      },
    };
    await buildHome(fakeTmdb, s);
    const withGenres = seenParams.filter((p) => p.with_genres);
    assert.ok(withGenres.length, 'no genre-filtered discover call was made at all');
    for (const p of withGenres) {
      assert.ok(!String(p.with_genres).includes(','), `with_genres uses AND: ${p.with_genres}`);
      assert.ok(String(p.with_genres).includes('|') || /^\d+$/.test(String(p.with_genres)), p.with_genres);
    }
  });

  await check('discovery is subscription-only, so rentals stay out of the rows', async () => {
    const s = tmpStore();
    const seenParams = [];
    const fakeTmdb = {
      region: 'BE',
      providerIds: async () => ({ netflix: [8], disneyplus: [337], hbomax: [1899], primevideo: [9, 10] }),
      details: async () => { throw new Error('skip'); },
      trending: async () => [],
      discover: async (type, params) => { seenParams.push(params); return []; },
    };
    await buildHome(fakeTmdb, s);
    const providerCalls = seenParams.filter((p) => p.with_watch_providers);
    assert.ok(providerCalls.length, 'no provider-filtered discover call');
    for (const p of providerCalls) {
      assert.strictEqual(p.with_watch_monetization_types, 'flatrate,free,ads', JSON.stringify(p));
    }
  });

  await check('a title cannot appear in two rows that share a seen set', async () => {
    const s = tmpStore();
    s.data.history = [title(1, 'movie', [18])];
    const dup = title(555, 'movie', [18]);
    const fakeTmdb = {
      region: 'BE',
      providerIds: async () => ({ netflix: [8] }),
      details: async () => ({ title: 'Anchor', recommendations: [dup, title(556, 'movie', [18])] }),
      trending: async () => [],
      discover: async () => [dup, title(557, 'movie', [18])],
    };
    const rows = await buildHome(fakeTmdb, s);
    const because = rows.find((r) => r.id === 'because');
    const foryou = rows.find((r) => r.id === 'foryou');
    if (because && foryou) {
      const a = because.items.map((i) => `${i.mediaType}:${i.id}`);
      const b = foryou.items.map((i) => `${i.mediaType}:${i.id}`);
      const overlap = a.filter((k) => b.includes(k));
      assert.strictEqual(overlap.length, 0, `duplicated across rows: ${overlap.join(', ')}`);
    }
  });

  await check('pinning alone counts as taste signal', () => {
    const s = tmpStore();
    s.data.pinned = [title(1, 'movie', [18])];
    assert.strictEqual(tasteProfile(s).hasSignal, true);
  });

  console.log('\nstore durability');

  await check('an interrupted write cannot destroy the settings', () => {
    const s = tmpStore();
    s.updateSettings({ tmdbKey: 'REAL-KEY-32-CHARS' });
    s.toggleWatchlist(title(1, 'movie', [18]));
    // Simulate a crash mid-write: a stray temp file must not be picked up.
    fs.writeFileSync(`${s.file}.tmp`, '{"settings":{"tmdbKey":"trunc');
    const reloaded = new Store(s.file);
    assert.strictEqual(reloaded.settings.tmdbKey, 'REAL-KEY-32-CHARS');
    assert.strictEqual(reloaded.data.watchlist.length, 1);
  });

  await check('a corrupt store is preserved, not silently overwritten', () => {
    const s = tmpStore();
    s.updateSettings({ tmdbKey: 'RECOVER-ME' });
    fs.writeFileSync(s.file, '{"settings":{"tmdbKey":"RECOVER-ME"'); // truncated
    const reloaded = new Store(s.file);
    assert.strictEqual(reloaded.settings.tmdbKey, '', 'should start fresh');
    const dir = path.dirname(s.file);
    const kept = fs.readdirSync(dir).find((f) => f.includes('.corrupt-'));
    assert.ok(kept, 'the unreadable file was thrown away instead of preserved');
    assert.ok(fs.readFileSync(path.join(dir, kept), 'utf8').includes('RECOVER-ME'));
  });

  await check('a file missing a key does not alias the shared defaults', () => {
    const a = tmpStore();
    fs.writeFileSync(a.file, JSON.stringify({ settings: { tmdbKey: 'x' } })); // no pinned/watchlist
    const s1 = new Store(a.file);
    s1.togglePinned(title(1, 'movie', [18]));
    const b = tmpStore();
    assert.strictEqual(b.data.pinned.length, 0, 'a pin leaked into an unrelated store via DEFAULTS');
  });

  await check('malformed collections are coerced rather than crashing', () => {
    const s = tmpStore();
    fs.writeFileSync(s.file, JSON.stringify({ watchlist: null, history: 'nope', pinned: 7 }));
    const reloaded = new Store(s.file);
    assert.doesNotThrow(() => reloaded.toggleWatchlist(title(1, 'movie', [18])));
    assert.doesNotThrow(() => reloaded.recordOpen(title(2, 'tv', [18]), 'netflix'));
  });

  await check('the watchlist is capped and oversized fields are truncated', () => {
    const s = tmpStore();
    s.toggleWatchlist({ id: 1, mediaType: 'movie', title: 'A'.repeat(50000), genreIds: Array(500).fill(7) });
    const entry = s.data.watchlist[0];
    assert.ok(entry.title.length <= 300, `title not truncated: ${entry.title.length}`);
    assert.ok(entry.genreIds.length <= 12, `genreIds not truncated: ${entry.genreIds.length}`);
  });

  await check('opening a title writes the store once, not twice', () => {
    const s = tmpStore();
    s.togglePinned(title(1, 'movie', [18]));
    let writes = 0;
    const real = s.save.bind(s);
    s.save = (...a) => { writes += 1; return real(...a); };
    s.recordOpen(title(1, 'movie', [18]), 'netflix');
    assert.strictEqual(writes, 1, `wrote ${writes} times`);
  });

  console.log('\nlauncher');

  await check('a browser path that does not exist is an error, not a silent swap', () => {
    assert.strictEqual(findBrowser('/definitely/not/here/chrome.exe'), null);
  });

  await check('a failed spawn rejects instead of crashing the process', async () => {
    const bad = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'sh-nx-')), 'chrome');
    fs.writeFileSync(bad, '#!/bin/sh\nexit 0\n', { mode: 0o644 }); // present but not executable
    await assert.rejects(() => openAppWindow('https://example.com', { browser: bad }), /Could not start|EACCES|not/i);
  });

  console.log('\nremote server');

  const server = new RemoteServer({
    getSettings: () => store.settings,
    store: (global.store = tmpStore()),
    tmdb: {}, omdb: {}, firetv: { status: async () => ({}) },
    services: SERVICES,
    buildHome: async () => [],
    detailsFor: async () => ({}),
  });
  const store = global.store;
  store.updateSettings({ tmdbKey: 'SECRET-TMDB', omdbKey: 'SECRET-OMDB', tvToken: 'SECRET-TV' });
  await server.start(0);
  const base = `http://127.0.0.1:${server.server.address().port}`;
  server.port = server.server.address().port;

  await check('brute-forcing the pairing code hits a lockout that actually locks', async () => {
    let got429 = false;
    for (let i = 0; i < 14; i++) {
      const res = await fetch(`${base}/api/pair`, {
        method: 'POST', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ code: '000001' }),
      });
      if (res.status === 429) got429 = true;
      else if (got429) assert.fail('kept accepting guesses after the lockout');
    }
    assert.ok(got429, 'never locked out');
    assert.ok(server.lockedUntil > Date.now(), 'lockout not held');
  });

  await check('the correct code is refused while locked out', async () => {
    const res = await fetch(`${base}/api/pair`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code: server.code }),
    });
    assert.strictEqual(res.status, 429);
  });

  await check('unpairing from the PC clears the lockout', async () => {
    server.revokeAll();
    assert.strictEqual(server.lockedUntil, 0);
    const res = await fetch(`${base}/api/pair`, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ code: server.code }),
    });
    assert.strictEqual(res.status, 200);
    global.token = (await res.json()).token;
  });

  await check('the phone is never sent the API keys or the TV token', async () => {
    const res = await fetch(`${base}/api/state`, { headers: { 'x-streamhub-token': global.token } });
    const body = await res.text();
    for (const secret of ['SECRET-TMDB', 'SECRET-OMDB', 'SECRET-TV']) {
      assert.ok(!body.includes(secret), `${secret} was served to the phone`);
    }
  });

  await check('a watchlist edit response also carries no secrets', async () => {
    const res = await fetch(`${base}/api/watchlist`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'x-streamhub-token': global.token },
      body: JSON.stringify({ item: { id: 5, mediaType: 'movie', title: 'X' } }),
    });
    const body = await res.text();
    assert.ok(!body.includes('SECRET-TMDB') && !body.includes('SECRET-TV'), body.slice(0, 200));
  });

  await check('details params cannot escape into other TMDB endpoints', async () => {
    for (const q of ['mediaType=..%2F..&id=authentication', 'mediaType=movie&id=abc', 'mediaType=person&id=1']) {
      const res = await fetch(`${base}/api/details?${q}`, { headers: { 'x-streamhub-token': global.token } });
      assert.strictEqual(res.status, 400, `${q} was not rejected`);
    }
  });

  await server.stop();

  console.log('\nefficiency');

  await check('a polling phone does not respawn adb on every tick', async () => {
    const { FireTv } = require('../src/firetv');
    const os2 = require('os');
    const dir = fs.mkdtempSync(path.join(os2.tmpdir(), 'fake-adb2-'));
    const log = path.join(dir, 'calls.log');
    const fake = path.join(dir, 'adb');
    fs.writeFileSync(fake, `#!/usr/bin/env node
const fs = require('fs');
const a = process.argv.slice(2);
fs.appendFileSync(${JSON.stringify(log)}, JSON.stringify(a) + '\\n');
if (a[0] === 'version') { console.log('v'); process.exit(0); }
if (a[0] === 'devices') { console.log('List of devices attached\\n1.2.3.4:5555\\tdevice'); process.exit(0); }
const i = a.indexOf('shell');
if (i >= 0 && a.slice(i + 1).join(' ').startsWith('pm list packages')) {
  console.log('package:com.netflix.ninja'); process.exit(0);
}
process.exit(0);
`, { mode: 0o755 });
    fs.writeFileSync(log, '');

    const tv = new FireTv(() => ({ tvHost: '1.2.3.4', adbPath: fake, tvToken: '' }));
    for (let i = 0; i < 5; i++) await tv.status();

    const calls = fs.readFileSync(log, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
    const pmCalls = calls.filter((c) => c.join(' ').includes('pm list packages')).length;
    const deviceCalls = calls.filter((c) => c[0] === 'devices').length;
    assert.ok(pmCalls <= 1, `queried the TV's package list ${pmCalls} times for 5 status calls`);
    assert.ok(deviceCalls <= 1, `spawned "adb devices" ${deviceCalls} times for 5 status calls`);
  });

  await check('connecting invalidates the cache so status is not stale', async () => {
    const { FireTv } = require('../src/firetv');
    const tv = new FireTv(() => ({ tvHost: '', adbPath: '/nope' }));
    tv._status = { host: 'stale' };
    tv._statusAt = Date.now();
    tv.invalidate();
    assert.strictEqual(tv._status, null);
  });

  console.log('\nfire tv app');

  // The Kotlin can't run in Node, so these hand off to JVM tests in
  // firetv/app/src/test. No JDK is a failure, not a skip: a check that quietly
  // passes proves nothing.
  const jvmTest = (testClass) => {
    const { spawnSync } = require('child_process');
    const firetv = path.join(__dirname, '..', 'firetv');
    // Not Android Studio's bundled Java: recent versions ship Java 25, which
    // Gradle 8.7 cannot run on.
    const javaHome = process.env.JAVA_HOME;
    assert.ok(javaHome && fs.existsSync(javaHome), 'needs JAVA_HOME pointing at a JDK 17-21');

    // Absolute and quoted: Windows may refuse to run a bare name from the
    // current folder (NoDefaultCurrentDirectoryInExePath), and the path has spaces.
    const win = process.platform === 'win32';
    const run = spawnSync(
      win ? `"${path.join(firetv, 'gradlew.bat')}"` : path.join(firetv, 'gradlew'),
      [':app:testDebugUnitTest', '--tests', `com.felix.streamhub.${testClass}`, '-q'],
      { cwd: firetv, shell: win, encoding: 'utf8', timeout: 600000 }
    );
    const out = `${run.stdout || ''}${run.stderr || ''}`;
    assert.strictEqual(run.status, 0, out.split('\n').filter((l) => /FAILED|error|expected/i.test(l)).slice(0, 6).join(' | ') || out.slice(-400));
  };

  // Starts the real ControlServer and pairs two phones against it over HTTP.
  await check('two paired phones each add a title, and neither sees the other’s', () => jvmTest('PerPhoneListsTest'));

  // Found on a real Fire TV: HBO Max and Prime Video are TV-launcher-only apps.
  await check('TV-only apps (HBO Max, Prime Video) are found and opened', () => jvmTest('AppLauncherTest'));

  // The desktop app's genre and rental fixes, which the TV app never had.
  await check('TV app: genres are OR-joined and rentals stay out of the rows', () => jvmTest('TmdbTest'));

  // The key helper runs with shell rights; only StreamHub may drive it.
  await check('the key helper serves only StreamHub, and only fixed keys', () => jvmTest('KeyServerTest'));

  // Against real picker dumps: never clicks "New", never guesses a name.
  await check('profile picking only clicks an exact, unique match on the real screens', () => jvmTest('ProfilePickersTest'));

  const failed = checks.filter((c) => !c).length;
  console.log(`\n${checks.length - failed}/${checks.length} checks passed`);
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
