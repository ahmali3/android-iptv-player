// Tests for the TV-side service: real HTTP against a local fake provider, gzip, redirects, and the luna wiring.
const test = require('node:test');
const assert = require('node:assert');
const http = require('node:http');
const zlib = require('node:zlib');
const fs = require('node:fs');
const path = require('node:path');

// The packaged service gets its own copy of iptv-core.js; do the same for the test.
fs.copyFileSync(path.join(__dirname, '../app/iptv-core.js'), path.join(__dirname, '../service/iptv-core.js'));
const svc = require('../service/service.js');

const day = Date.UTC(2026, 0, 1);
function prog(ch, h1, h2, t) {
  const fmt = (h) => `20260101${String(h).padStart(2, '0')}0000 +0000`;
  return `<programme start="${fmt(h1)}" stop="${fmt(h2)}" channel="${ch}"><title>${t}</title></programme>`;
}
const XML = `<tv><channel id="a"><display-name>Alpha</display-name></channel>${prog('a', 10, 11, 'A1')}${prog('a', 11, 12, 'A2')}${prog('b', 10, 11, 'B1')}</tv>`;

let server, base, seenUA = '';
test.before(async () => {
  server = http.createServer((req, res) => {
    seenUA = req.headers['user-agent'];
    if (req.url === '/plain.m3u') { res.end('#EXTM3U\n#EXTINF:-1,One\nhttp://x/1.ts\n'); return; }
    if (req.url === '/redirect') { res.writeHead(302, { Location: '/plain.m3u' }); res.end(); return; }
    if (req.url === '/guide.xml.gz') { res.end(zlib.gzipSync(XML)); return; } // gzip without a header saying so
    if (req.url === '/guide-header') { res.writeHead(200, { 'Content-Encoding': 'gzip' }); res.end(zlib.gzipSync(XML)); return; }
    res.writeHead(404); res.end('nope');
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  base = `http://127.0.0.1:${server.address().port}`;
});
test.after(() => server.close());

const fetchText = (url) => new Promise((res, rej) => svc.fetchText(url, (e, s, b) => (e ? rej(e) : res({ s, b }))));
const guide = (p) => new Promise((res, rej) => svc.guide(p, (e, r) => (e ? rej(e) : res(r))));

test('fetch follows redirects and sends a VLC user agent', async () => {
  const r = await fetchText(base + '/redirect');
  assert.equal(r.s, 200);
  assert.match(r.b, /#EXTINF:-1,One/);
  assert.match(seenUA, /VLC/);
});

test('fetch reports HTTP errors and connection failures', async () => {
  assert.equal((await fetchText(base + '/missing')).s, 404);
  await assert.rejects(fetchText('http://127.0.0.1:1/x'));
});

test('guide: gzip detected by content, only wanted channels, name matching', async () => {
  const channels = [{ key: '1', name: 'Alpha (HD)', epgId: '' }, { key: '2', name: 'Alpha', epgId: '' }, { key: '3', name: 'Bee', epgId: 'b' }];
  for (const u of ['/guide.xml.gz', '/guide-header']) {
    const r = await guide({ url: base + u, channels, from: day, to: day + 86400000 });
    assert.equal(r.matched['2'], 'a');
    assert.equal(r.matched['1'], undefined);
    assert.deepEqual(r.programs.a.map((p) => p[2]), ['A1', 'A2']);
    assert.deepEqual(r.programs.b.map((p) => p[2]), ['B1']);
    assert.equal(r.count, 3);
  }
});

test('guide: errors are reported, not thrown', async () => {
  await assert.rejects(guide({ url: base + '/missing', channels: [] }), /HTTP 404/);
});

test('luna wiring responds with returnValue', async () => {
  const handlers = {};
  svc.register({ register: (name, fn) => (handlers[name] = fn) });
  const call = (name, payload) => new Promise((res) => handlers[name]({ payload, respond: res }));
  const ok = await call('fetch', { url: base + '/plain.m3u' });
  assert.equal(ok.returnValue, true);
  assert.equal(ok.status, 200);
  const bad = await call('fetch', { url: 'http://127.0.0.1:1/x' });
  assert.equal(bad.returnValue, false);
  assert.ok(bad.errorText);
  const g = await call('guide', { url: base + '/guide.xml.gz', channels: [{ key: '3', name: 'Bee', epgId: 'b' }], from: day, to: day + 86400000 });
  assert.equal(g.returnValue, true);
  assert.equal(g.programs.b.length, 1);
});
