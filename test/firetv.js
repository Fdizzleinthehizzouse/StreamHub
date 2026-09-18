/* Tests the Fire TV bridge against a fake `adb` that records every invocation,
 * so we can prove the exact commands sent to the TV are the right ones without
 * owning a Fire TV. */

const fs = require('fs');
const os = require('os');
const path = require('path');
const assert = require('assert');

const { FireTv } = require('../src/firetv');
const { launchCommand, universalSearchCommand } = require('../src/deeplinks');

const checks = [];
function check(name, fn) {
  try {
    fn();
    checks.push(true);
    console.log(`  ok   ${name}`);
  } catch (err) {
    checks.push(false);
    console.log(` FAIL  ${name} — ${err.message}`);
  }
}
async function checkAsync(name, fn) {
  try {
    await fn();
    checks.push(true);
    console.log(`  ok   ${name}`);
  } catch (err) {
    checks.push(false);
    console.log(` FAIL  ${name} — ${err.message}`);
  }
}

// ---- a fake adb ------------------------------------------------------------

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'fake-adb-'));
const LOG = path.join(dir, 'calls.log');
const FAKE = path.join(dir, 'adb');

fs.writeFileSync(
  FAKE,
  `#!/usr/bin/env node
const fs = require('fs');
const args = process.argv.slice(2);
fs.appendFileSync(${JSON.stringify(LOG)}, JSON.stringify(args) + '\\n');
if (args[0] === 'version') { console.log('Android Debug Bridge version 35.0.0'); process.exit(0); }
if (args[0] === 'connect') { console.log('connected to ' + args[1]); process.exit(0); }
if (args[0] === 'disconnect') { console.log('disconnected'); process.exit(0); }
if (args[0] === 'devices') { console.log('List of devices attached\\n192.168.1.50:5555\\tdevice'); process.exit(0); }
const shellIdx = args.indexOf('shell');
if (shellIdx >= 0) {
  const cmd = args.slice(shellIdx + 1).join(' ');
  if (cmd.startsWith('pm list packages')) {
    console.log('package:com.netflix.ninja');
    console.log('package:com.disney.disneyplus');
    console.log('package:com.amazon.avod');
    console.log('package:com.android.settings');
    process.exit(0);
  }
  process.exit(0);
}
process.exit(0);
`,
  { mode: 0o755 }
);

const calls = () =>
  fs
    .readFileSync(LOG, 'utf8')
    .trim()
    .split('\n')
    .filter(Boolean)
    .map((l) => JSON.parse(l));
const reset = () => fs.writeFileSync(LOG, '');
const shellCmds = () => calls().filter((c) => c.includes('shell')).map((c) => c.slice(c.indexOf('shell') + 1).join(' '));

const settings = { tvHost: '192.168.1.50', adbPath: FAKE, tvToken: '' };
const tv = new FireTv(() => settings);

(async () => {
  console.log('deep links');

  check('netflix uses the Fire TV package, not the phone one', () => {
    const { cmd } = launchCommand('netflix', { mode: 'home' });
    assert.ok(cmd.includes('com.netflix.ninja'), cmd);
  });

  check('a title deep link carries the content id', () => {
    const { cmd, kind } = launchCommand('netflix', { mode: 'title', contentId: '80025384' });
    assert.strictEqual(kind, 'deeplink');
    assert.ok(cmd.includes('https://www.netflix.com/title/80025384'), cmd);
    assert.ok(cmd.includes('android.intent.action.VIEW'), cmd);
  });

  check('in-app search url-encodes the query', () => {
    const { cmd } = launchCommand('disneyplus', { mode: 'search', title: 'The Bear' });
    assert.ok(cmd.includes('The%20Bear'), cmd);
  });

  // The command string is handed to the *device's* shell by `adb shell`, so a
  // title containing quotes or semicolons must not be able to run anything.
  // Verified by feeding the quoted form to a real shell and checking that what
  // comes back out is byte-identical to what went in.
  check('shell metacharacters in a title cannot break out of the quoting', () => {
    const nasty = [
      `x'; rm -rf /; echo '`,
      `Rock & Roll`,
      `$(whoami)`,
      '`id`',
      `back\\slash`,
      `a"b"c`,
      `new\nline`,
      `Amélie`,
      `100% pure | grep x > /tmp/x`,
    ];
    for (const evil of nasty) {
      const { cmd } = universalSearchCommand(evil);
      const arg = cmd.slice(cmd.indexOf('--es query ') + '--es query '.length);
      const out = require('child_process').execFileSync('sh', ['-c', `printf '%s' ${arg}`], { encoding: 'utf8' });
      assert.strictEqual(out, evil, `quoting leaked for: ${JSON.stringify(evil)}`);
    }
  });

  check('hbo max lists the renamed package first but keeps the old ones', () => {
    const { packages } = launchCommand('hbomax', { mode: 'home' });
    assert.strictEqual(packages[0], 'com.wbd.stream');
    assert.ok(packages.includes('com.hbo.hbonow'));
  });

  check('an unknown service throws rather than building a bad command', () => {
    assert.throws(() => launchCommand('hulu', {}), /Unknown service/);
  });

  console.log('\nadb bridge');

  reset();
  await checkAsync('connect issues adb connect with the right host:port', async () => {
    const r = await tv.connect();
    assert.ok(r.connected);
    assert.deepStrictEqual(calls().at(-1), ['connect', '192.168.1.50:5555']);
  });

  reset();
  await checkAsync('status reports which of the four services are installed', async () => {
    const s = await tv.status();
    assert.strictEqual(s.adbFound, true);
    assert.strictEqual(s.adbConnected, true);
    assert.strictEqual(s.services.netflix, true);
    assert.strictEqual(s.services.disneyplus, true);
    assert.strictEqual(s.services.primevideo, true);
    assert.strictEqual(s.services.hbomax, false, 'hbo max was not in the fake package list');
  });

  reset();
  await checkAsync('play with a content id sends a real deep link', async () => {
    const r = await tv.play('netflix', { contentId: '80025384' });
    assert.strictEqual(r.kind, 'deeplink');
    const cmds = shellCmds();
    assert.ok(cmds.some((c) => c.includes('80025384')), cmds.join(' | '));
  });

  reset();
  await checkAsync('play with only a title falls back to Fire TV universal search', async () => {
    const r = await tv.play('netflix', { title: 'Breaking Bad' });
    assert.strictEqual(r.kind, 'universal-search');
    const cmds = shellCmds();
    assert.ok(cmds.some((c) => c.includes('android.search.action.GLOBAL_SEARCH')), cmds.join(' | '));
    assert.ok(cmds.some((c) => c.includes('Breaking Bad')), cmds.join(' | '));
  });

  reset();
  await checkAsync('play wakes the TV before launching', async () => {
    await tv.play('netflix', { title: 'Dune' });
    assert.ok(shellCmds().some((c) => c.includes('KEYCODE_WAKEUP')));
  });

  reset();
  await checkAsync('launching a service the TV does not have gives a clear error', async () => {
    await assert.rejects(() => tv.play('hbomax', { title: 'The Last of Us' }), /does not look installed/);
  });

  reset();
  await checkAsync('remote keys map to the right keycodes', async () => {
    await tv.key('playpause');
    await tv.key('back');
    const cmds = shellCmds();
    assert.ok(cmds.some((c) => c.includes('KEYCODE_MEDIA_PLAY_PAUSE')), cmds.join(' | '));
    assert.ok(cmds.some((c) => c.includes('KEYCODE_BACK')), cmds.join(' | '));
  });

  await checkAsync('an unknown key is rejected', async () => {
    await assert.rejects(() => tv.key('eject'), /Unknown key/);
  });

  reset();
  await checkAsync('no TV address configured produces a helpful message', async () => {
    const bare = new FireTv(() => ({ tvHost: '', adbPath: FAKE }));
    await assert.rejects(() => bare.play('netflix', {}), /No Fire TV address/);
  });

  await checkAsync('a missing adb is reported as a missing adb', async () => {
    const noAdb = new FireTv(() => ({ tvHost: '192.168.1.50', adbPath: '/nope/adb' }));
    // findAdb falls through to the bare name 'adb', which is not on PATH here.
    await assert.rejects(() => noAdb.connect(), /adb/i);
  });

  const failed = checks.filter((c) => !c).length;
  console.log(`\n${checks.length - failed}/${checks.length} checks passed`);
  fs.rmSync(dir, { recursive: true, force: true });
  process.exit(failed ? 1 : 0);
})();
