/* Verifies the exact folder layout the Windows installer produces actually
 * boots StreamHub - i.e. that dropping the app into resources/app next to the
 * renamed Electron binary is enough, with no packaging step. */

const { _electron: electron } = require('playwright');
const path = require('path');
const fs = require('fs');
const os = require('os');

const LAYOUT = process.argv[2] || '/tmp/layout-test';
const BIN = path.join(LAYOUT, process.platform === 'win32' ? 'StreamHub.exe' : 'StreamHub');
const USER_DATA = fs.mkdtempSync(path.join(os.tmpdir(), 'streamhub-layout-'));

const checks = [];
const errors = [];
const check = (name, cond, detail = '') => {
  checks.push(!!cond);
  console.log(`${cond ? '  ok  ' : ' FAIL '} ${name}${!cond && detail ? ' — ' + detail : ''}`);
};

(async () => {
  check('renamed binary exists', fs.existsSync(BIN), BIN);
  check('resources/app/package.json is in place', fs.existsSync(path.join(LAYOUT, 'resources', 'app', 'package.json')));

  const app = await electron.launch({
    executablePath: BIN,
    args: ['--no-sandbox', '--disable-gpu', `--user-data-dir=${USER_DATA}`],
  });

  const page = await app.firstWindow();
  page.on('pageerror', (e) => errors.push(e.message));
  await page.waitForTimeout(1200);

  const title = await page.title();
  check('boots StreamHub, not the Electron default app', title === 'StreamHub', `title was "${title}"`);
  check('the UI actually rendered', (await page.locator('#nav-services .nav-item').count()) === 4);
  check('preload bridge loaded from resources/app', await page.evaluate(() => typeof window.hub === 'object'));

  const boot = await page.evaluate(() => window.hub.bootstrap());
  check('IPC works in this layout', boot.ok && boot.data.services.length === 4);

  await app.close();

  const failed = checks.filter((c) => !c).length;
  if (errors.length) console.log('\nRuntime errors:\n  ' + errors.join('\n  '));
  console.log(`\n${checks.length - failed}/${checks.length} checks passed, ${errors.length} runtime errors`);
  process.exit(failed || errors.length ? 1 : 0);
})().catch((e) => {
  console.error(e.message);
  process.exit(1);
});
