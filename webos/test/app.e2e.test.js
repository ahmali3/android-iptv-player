// End-to-end test of the webOS app in a real browser engine (Chromium), driven with remote-control key codes.
// The app runs as on the TV: requests go through a stand-in for the webOS service bus to the real
// service.js, and the fake provider is on another origin with no CORS headers (like real providers).
// Run: NODE_PATH=<dir with playwright> node --test webos/test/app.e2e.test.js
const test = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
let pw;
try { pw = require('playwright'); } catch (e) { pw = require('playwright-core'); }
const { chromium } = pw;
const provider = require('./fake-provider.js');

fs.copyFileSync(path.join(__dirname, '../app/iptv-core.js'), path.join(__dirname, '../service/iptv-core.js'));
const svc = require('../service/service.js');
const handlers = {};
svc.register({ register: (name, fn) => (handlers[name] = fn) });
const callService = (method, payload) => new Promise((resolve) => handlers[method]({ payload, respond: resolve }));

const APP_DIR = path.join(__dirname, '../app');
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.png': 'image/png' };
function appServer() {
  const s = http.createServer((req, res) => {
    const file = path.join(APP_DIR, decodeURIComponent(new URL(req.url, 'http://x').pathname));
    if (!file.startsWith(APP_DIR) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise((r) => s.listen(0, '127.0.0.1', () => r({ s, base: `http://127.0.0.1:${s.address().port}` })));
}

const K = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, OK: 13, BACK: 461, CH_UP: 33, CH_DOWN: 34, RED: 403 };

let browser, page, prov, app, consoleErrors = [];
const shot = (n) => process.env.SHOTS ? page.screenshot({ path: path.join(process.env.SHOTS, n + '.png') }) : null;
const step = (s) => { if (process.env.DEBUG) process.stderr.write('STEP ' + s + '\n'); };

async function key(code, times = 1) {
  for (let i = 0; i < times; i++) {
    await page.evaluate((c) => {
      const e = new KeyboardEvent('keydown', { bubbles: true, cancelable: true });
      Object.defineProperty(e, 'keyCode', { get: () => c });
      (document.activeElement || document).dispatchEvent(e);
    }, code);
    await page.waitForTimeout(40);
  }
}
const focusedKey = () => page.evaluate(() => { const f = window.IptvApp.focus(); return f ? f.getAttribute('data-key') : null; });
async function waitFocus(k, timeout = 5000) {
  await page.waitForFunction((x) => { const f = window.IptvApp.focus(); return f && f.getAttribute('data-key') === x; }, k, { timeout });
}
/** Presses a direction until the highlight lands on `target` (fails after `max` presses). */
async function navTo(dir, target, max = 15) {
  for (let i = 0; i < max; i++) {
    if ((await focusedKey()) === target) return;
    await key(dir);
  }
  assert.equal(await focusedKey(), target, `could not reach ${target} by pressing ${dir}`);
}
const visible = (sel) => page.evaluate((s) => { const e = document.querySelector(s); return !!e && !e.closest('.hidden') && e.offsetParent !== null; }, sel);
const videoSrc = () => page.evaluate(() => document.getElementById('video').getAttribute('src') || '');

test.before(async () => {
  prov = await provider.start();
  app = await appServer();
  browser = await chromium.launch(process.env.CHROME ? { executablePath: process.env.CHROME } : {});
  page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
  page.on('pageerror', (e) => consoleErrors.push(e.message));
  await page.exposeFunction('__luna', async (uri, params) => callService(uri.split('/').pop(), JSON.parse(params)));
  await page.addInitScript(() => {
    // Stand-in for the webOS service bus (luna://) and the platform Back key.
    window.PalmServiceBridge = function () {
      const self = this;
      this.call = (uri, params) => window.__luna(uri, params).then((r) => self.onservicecallback(JSON.stringify(r)));
    };
    window.webOS = { platformBack: () => { window.__platformBack = true; } };
  });
});
test.after(async () => {
  if (browser) await browser.close();
  if (prov) { prov.server.closeAllConnections(); prov.server.close(); }
  if (app) { app.s.closeAllConnections(); app.s.close(); }
});

test('webOS app: full remote-control flow against a provider without CORS', { timeout: 120000 }, async () => {
  await page.goto(app.base + '/index.html');

  // ---- first launch: welcome form, add an Xtream login with the remote ----
  step('first launch: welcome form, add an Xtream login with the remote');
  await page.waitForSelector('text=Welcome');
  await shot('welcome');
  assert.equal(await focusedKey(), 'seg-x');
  await key(K.DOWN); assert.equal(await focusedKey(), 'field-name');
  await page.keyboard.type('Home');
  await key(K.DOWN); assert.equal(await focusedKey(), 'field-server');
  await page.keyboard.type(prov.base);
  await key(K.DOWN); assert.equal(await focusedKey(), 'field-user');
  await page.keyboard.type('demo');
  await key(K.RIGHT); assert.equal(await focusedKey(), 'field-pass');
  await page.keyboard.type('wrong');
  await navTo(K.DOWN, 'save');
  await key(K.OK);

  // wrong password: a clear error, then fix it in Playlists > Edit
  await page.waitForFunction(() => /Login failed/.test(document.getElementById('status').textContent));
  await page.evaluate(() => { const S = window.IptvApp.state; S.form = S.playlists[0]; S.view = 'form'; S.formState = null; window.IptvApp.render(); });
  await page.fill('#field-pass', 'secret');
  await page.evaluate(() => document.querySelector('[data-key="save"]').click());

  // ---- groups come from the provider; channels show what's on now from the (gzipped) guide ----
  step("groups come from the provider; channels show what's on now from the (gzipped) guide");
  await page.waitForSelector('[data-key="g:SOCCER"]');
  await page.waitForSelector('text=Derby Day Live', { timeout: 15000 });
  const groups = await page.$$eval('[data-zone="groups"] .grow', (els) => els.map((e) => e.firstChild.textContent));
  assert.deepEqual(groups.slice(3), ['SOCCER', 'KIDS', 'AR | NEWS']);
  await waitFocus('c:101');
  await page.waitForFunction(() => document.querySelector('[data-key="nav-refresh"]').textContent === 'Refresh');
  await shot('home');

  // ---- play with OK; up/down and CH+/CH- change channel ----
  step('play with OK; up/down and CH+/CH- change channel');
  await key(K.OK);
  assert.ok(await visible('#video'));
  assert.ok((await videoSrc()).endsWith('/live/demo/secret/101.m3u8'), await videoSrc());
  assert.match(await page.textContent('#bannerName'), /Pitch One/);
  await page.waitForFunction(() => true);
  await key(K.UP);
  assert.ok((await videoSrc()).endsWith('/102.m3u8'));
  assert.match(await page.textContent('#bannerName'), /Pitch Two/);
  await key(K.DOWN);
  assert.ok((await videoSrc()).endsWith('/101.m3u8'));
  await key(K.CH_UP);
  assert.ok((await videoSrc()).endsWith('/102.m3u8'));
  await key(K.CH_DOWN);
  assert.ok((await videoSrc()).endsWith('/101.m3u8'));
  await page.waitForTimeout(300);
  assert.ok(prov.requests.some((r) => r.includes('/live/demo/secret/101.m3u8')), 'the stream was requested');

  // OK opens the controls: add to favorites
  await key(K.OK);
  assert.ok(await visible('#overlay'));
  assert.equal(await focusedKey(), 'fav');
  await shot('player');
  await key(K.OK);
  await page.waitForFunction(() => /Added Pitch One to favorites/.test(document.getElementById('status').textContent));
  // left opens the channel list on the current channel
  await key(K.BACK);
  assert.ok(!(await visible('#overlay')));
  await key(K.LEFT);
  assert.ok(await visible('#chlist'));
  assert.equal(await focusedKey(), 'pc:101');
  await key(K.DOWN); await key(K.OK);
  assert.ok((await videoSrc()).endsWith('/102.m3u8'));
  // Back closes the player and returns to the channel you started from
  await key(K.BACK);
  assert.ok(!(await visible('#video')));
  await waitFocus('c:101');
  assert.ok(await page.$('[data-key="c:101"] .star'), 'favorite star shown');

  // ---- search: results stay after playing one and coming back ----
  step('search: results stay after playing one and coming back');
  await navTo(K.UP, 'search');
  await page.keyboard.type('pitch');
  await page.waitForSelector('text=Results for');
  await key(K.DOWN);
  assert.equal(await focusedKey(), 'c:101');
  await key(K.DOWN);
  assert.equal(await focusedKey(), 'c:102');
  await key(K.OK);
  assert.ok((await videoSrc()).endsWith('/102.m3u8'));
  await key(K.BACK);
  await waitFocus('c:102');
  assert.equal(await page.inputValue('[data-key="search"]'), 'pitch');
  assert.ok(await page.$('[data-key="c:101"]'), 'results are still there');
  assert.match(await page.textContent('[data-zone="channels"]'), /Pitch Two/);
  await key(K.BACK); // clears the search
  assert.equal(await page.inputValue('[data-key="search"]'), '');

  // ---- groups: KIDS channel gets its guide by name matching (no tvg-id) ----
  step('groups: KIDS channel gets its guide by name matching (no tvg-id)');
  await page.evaluate(() => window.IptvApp.render());
  await key(K.LEFT);
  await navTo(K.DOWN, 'g:KIDS');
  await key(K.OK);
  await waitFocus('c:103');
  assert.match(await page.textContent('[data-key="c:103"]'), /Saturday Cartoons/);
  await key(K.BACK);
  assert.equal(await focusedKey(), 'g:KIDS');

  // ---- guide: opens on Favorites; shows are drawn at their real length; catch-up ----
  step('guide: opens on Favorites; shows are drawn at their real length; catch-up');
  await navTo(K.UP, 'nav-guide');
  await key(K.OK);
  await page.waitForSelector('.prog');
  const row = await page.$$eval('.gch span', (els) => els.map((e) => e.textContent));
  assert.deepEqual(row, ['Pitch One']);
  const width = await page.$eval('.prog.live', (e) => e.getBoundingClientRect().width);
  const rem = await page.evaluate(() => parseFloat(getComputedStyle(document.documentElement).fontSize));
  assert.ok(width > 100 * 6 * rem / 16, `2-hour show drawn ${width}px wide`);
  assert.match(await page.textContent('.prog.live'), /Derby Day Live/);
  await shot('guide');
  await page.evaluate(() => document.querySelector('.prog.live').click());
  await page.waitForSelector('#programCard');
  assert.equal(await focusedKey(), 'watch');
  await navTo(K.RIGHT, 'catchup');
  await key(K.OK);
  assert.match(await videoSrc(), /\/timeshift\/demo\/secret\/120\/\d{4}-\d{2}-\d{2}:\d{2}-\d{2}\/101\.m3u8$/);
  assert.match(await page.textContent('#bannerName'), /Catch-up/);
  await key(K.BACK);
  await key(K.BACK); // guide -> live

  // ---- second playlist: an M3U link that is really an Xtream login gets converted ----
  step('second playlist: an M3U link that is really an Xtream login gets converted');
  await page.evaluate(() => { window.IptvApp.state.view = 'playlists'; window.IptvApp.render(); });
  await waitFocus('add');
  await key(K.OK);
  await page.waitForSelector('text=Add playlist');
  await navTo(K.RIGHT, 'seg-m');
  await key(K.OK);
  await page.fill('#field-name', 'Backup');
  await page.fill('#field-m3u', prov.base + '/list.m3u');
  await page.evaluate(() => document.querySelector('[data-key="save"]').click());
  await page.waitForSelector('[data-key="g:MOVIES"]');
  await page.waitForSelector('[data-key="g:MUSIC"]');
  await page.waitForFunction(() => window.IptvApp.state.guide && window.IptvApp.state.guide.programs && window.IptvApp.state.guide.programs.pitch1);
  await page.evaluate(() => { const S = window.IptvApp.state; S.form = null; S.formState = null; S.view = 'form'; window.IptvApp.render(); });
  await page.evaluate(() => document.querySelector('[data-key="seg-m"]').click());
  await page.fill('#field-m3u', prov.base + '/get.php?username=demo&password=secret&type=m3u_plus');
  await page.evaluate(() => document.querySelector('[data-key="save"]').click());
  await page.waitForFunction(() => /added as Xtream/.test(document.getElementById('status').textContent));
  await page.waitForSelector('[data-key="g:SOCCER"]');
  const pls = await page.evaluate(() => window.IptvApp.state.playlists.map((p) => p.type));
  assert.deepEqual(pls, ['xtream', 'm3u', 'xtream']);

  // ---- everything is remembered after the TV restarts the app (no re-download) ----
  step('everything is remembered after the TV restarts the app (no re-download)');
  await page.evaluate(() => { const S = window.IptvApp.state; S.activeId = S.playlists[0].id; localStorage.setItem('iptv.active', JSON.stringify(S.activeId)); });
  const before = prov.requests.filter((r) => r.includes('get_live_streams')).length;
  await page.reload();
  await page.waitForSelector('[data-key="c:101"] .star');
  await page.waitForSelector('text=Derby Day Live');
  assert.equal(prov.requests.filter((r) => r.includes('get_live_streams')).length, before, 'used the cached channel list');

  // ---- Back at the top level hands control back to the TV ----
  step('Back at the top level hands control back to the TV');
  await page.evaluate(() => { window.IptvApp.state.list = null; window.IptvApp.render(); });
  await key(K.BACK); // channels -> groups
  await key(K.BACK); // groups -> exit
  assert.equal(await page.evaluate(() => window.__platformBack === true), true);

  assert.deepEqual(consoleErrors, [], 'no script errors');
});

test('webOS app in a plain browser (no TV service) loads a gzipped guide by itself', { timeout: 60000 }, async () => {
  // Same-origin setup so the browser may fetch directly.
  const p2 = await browser.newPage();
  const errors = [];
  p2.on('pageerror', (e) => errors.push(e.message));
  const srv = http.createServer((req, res) => {
    const u = new URL(req.url, 'http://x');
    if (u.pathname.startsWith('/app/')) {
      const f = path.join(APP_DIR, u.pathname.substring(5));
      res.writeHead(200, { 'Content-Type': TYPES[path.extname(f)] || 'text/plain' });
      fs.createReadStream(f).pipe(res);
      return;
    }
    // proxy everything else to the fake provider
    http.get(prov.base + req.url, { headers: { host: req.headers.host } }, (r) => { res.writeHead(r.statusCode, r.headers); r.pipe(res); });
  });
  await new Promise((r) => srv.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${srv.address().port}`;
  await p2.goto(base + '/app/index.html');
  await p2.evaluate((b) => {
    localStorage.setItem('iptv.playlists', JSON.stringify([{ id: 1, name: 'Direct', type: 'm3u', url: b + '/list.m3u', epgUrl: '', format: 'm3u8' }]));
    localStorage.setItem('iptv.active', '1');
  }, base);
  await p2.reload();
  await p2.waitForSelector('[data-key="g:MOVIES"]');
  await p2.waitForFunction(() => window.IptvApp.state.guide.programs.pitch1 && window.IptvApp.state.guide.programs.pitch1.length === 2);
  assert.deepEqual(errors, []);
  await p2.close();
  srv.closeAllConnections();
  srv.close();
});
