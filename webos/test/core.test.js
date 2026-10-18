// Unit tests for iptv-core.js (run: node --test webos/test)
const test = require('node:test');
const assert = require('node:assert');
const zlib = require('node:zlib');
const core = require('../app/iptv-core.js');

test('M3U: attributes, names with commas, groups, header guide link', () => {
  const m3u = [
    '#EXTM3U url-tvg="http://g/guide.xml.gz,http://g/second.xml"',
    '#EXTINF:-1 tvg-id="bbc1" tvg-name="BBC One" tvg-logo="http://l/bbc.png" group-title="UK, News",BBC One, HD',
    'http://s/1.ts',
    '#EXTINF:-1,No Group Channel',
    '#EXTVLCOPT:http-user-agent=VLC',
    'http://s/2.ts',
    '#EXTINF:-1 tvg-chno="42" catchup-days="3",With Number',
    '#EXTGRP:Sports',
    'http://s/3.ts',
    '#EXTINF:-1 tvg-id="x",Missing URL at end',
  ].join('\n');
  const r = core.parseM3U(m3u);
  assert.equal(r.epgUrl, 'http://g/guide.xml.gz');
  assert.equal(r.channels.length, 3);
  assert.deepEqual(
    { name: r.channels[0].name, group: r.channels[0].group, epgId: r.channels[0].epgId, logo: r.channels[0].logo },
    { name: 'BBC One, HD', group: 'UK, News', epgId: 'bbc1', logo: 'http://l/bbc.png' });
  assert.equal(r.channels[1].group, 'Uncategorized');
  assert.equal(r.channels[1].url, 'http://s/2.ts');
  assert.equal(r.channels[2].num, 42);
  assert.equal(r.channels[2].group, 'Sports');
  assert.equal(r.channels[2].catchup, 3);
});

test('Xtream login hidden in an M3U link is detected and normalized', () => {
  assert.deepEqual(core.xtreamFromUrl('http://host:8080/get.php?username=a%20b&password=p%26w&type=m3u_plus&output=ts'),
    { server: 'http://host:8080', username: 'a b', password: 'p&w' });
  assert.equal(core.xtreamFromUrl('http://host/playlist.m3u'), null);
  const n = core.normalize({ type: 'm3u', url: 'http://h:1/get.php?username=u&password=p' });
  assert.equal(n.type, 'xtream');
  assert.equal(n.url, 'http://h:1');
  assert.equal(core.normalize({ type: 'xtream', url: ' provider.tv:8080/player_api.php ', username: ' u ', password: 'p' }).url, 'http://provider.tv:8080');
});

test('Xtream channels keep provider group order and build stream URLs', () => {
  const p = { url: 'http://h', username: 'u', password: 'p w', format: 'm3u8' };
  const r = core.xtream.channels(p,
    [{ category_id: '2', category_name: 'KIDS' }, { category_id: '1', category_name: 'SOCCER' }],
    [{ num: 1, name: 'Pitch', stream_id: 101, category_id: '1', epg_channel_id: 'p1', tv_archive: 1, tv_archive_duration: 3 },
     { num: 0, name: 'Toons', stream_id: 102, category_id: '2' },
     { name: 'Orphan', stream_id: 103, category_id: '99' }]);
  assert.equal(r.channels[0].url, 'http://h/live/u/p%20w/101.m3u8');
  assert.equal(r.channels[0].catchup, 3);
  assert.equal(r.channels[1].num, 2);
  assert.deepEqual(core.groupsOf(r.channels, r.order).map(g => g.name), ['KIDS', 'SOCCER', 'Uncategorized']);
  assert.equal(core.xtream.stream({ ...p, format: 'ts' }, 7), 'http://h/live/u/p%20w/7.ts');
  const ts = core.xtream.timeshift(p, { key: '101' }, { start: new Date(2026, 9, 17, 20, 5).getTime(), stop: new Date(2026, 9, 17, 22, 5).getTime() });
  assert.equal(ts, 'http://h/timeshift/u/p%20w/120/2026-10-17:20-05/101.m3u8');
});

test('XMLTV times with offsets', () => {
  assert.equal(core.parseTime('20240101120000 +0000'), Date.UTC(2024, 0, 1, 12));
  assert.equal(core.parseTime('20240101120000 +0130'), Date.UTC(2024, 0, 1, 10, 30));
  assert.equal(core.parseTime('20240101120000 -0500'), Date.UTC(2024, 0, 1, 17));
  assert.equal(core.parseTime('202401011200'), Date.UTC(2024, 0, 1, 12));
  assert.ok(Number.isNaN(core.parseTime('garbage')));
});

const XML = `<?xml version="1.0"?><tv>
<channel id="pitch1"><display-name>Pitch One</display-name></channel>
<channel id="cartoon"><display-name lang="en">Cartoon Hub</display-name><display-name>CH</display-name></channel>
<programme start="20260101100000 +0000" stop="20260101120000 +0000" channel="pitch1"><title lang="en">Derby &amp; Day</title><desc>Big &lt;match&gt;</desc></programme>
<programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="pitch1"><title><![CDATA[After]]></title></programme>
<programme start="20260101100000 +0000" stop="20260101110000 +0000" channel="cartoon"><title>Toons</title></programme>
<programme start="20260101100000 +0000" stop="20260101110000 +0000" channel="unwanted"><title>Skip</title></programme>
<programme start="20250101100000 +0000" stop="20250101110000 +0000" channel="pitch1"><title>Too old</title></programme>
</tv>`;

test('Guide builder: streaming in tiny chunks, filtering, name matching, entities', () => {
  const chans = [{ key: '101', name: 'Pitch One', epgId: 'pitch1' }, { key: '103', name: 'Cartoon Hub', epgId: '' }];
  const from = Date.UTC(2026, 0, 1, 0), to = Date.UTC(2026, 0, 2, 0);
  const b = core.guideBuilder(chans, from, to);
  for (let i = 0; i < XML.length; i += 7) b.feed(XML.substring(i, i + 7)); // worst case: split everywhere
  const g = b.finish();
  assert.deepEqual(Object.keys(g.programs).sort(), ['cartoon', 'pitch1']);
  assert.equal(g.programs.pitch1.length, 2);
  assert.equal(g.programs.pitch1[0][2], 'Derby & Day');
  assert.equal(g.programs.pitch1[0][3], 'Big <match>');
  assert.equal(g.programs.pitch1[1][2], 'After');
  assert.equal(g.matched['103'], 'cartoon');
  assert.equal(core.nowOn(g.programs.pitch1, Date.UTC(2026, 0, 1, 11))[2], 'Derby & Day');
  assert.equal(core.nowOn(g.programs.pitch1, Date.UTC(2026, 0, 1, 14)), null);
});

test('Guide parser handles a large file without keeping it in memory', () => {
  const parts = ['<tv>'];
  for (let i = 0; i < 20000; i++) {
    parts.push(`<programme start="20260101${String(i % 24).padStart(2, '0')}0000 +0000" stop="20260101${String(i % 24).padStart(2, '0')}3000 +0000" channel="c${i % 500}"><title>Show ${i}</title></programme>`);
  }
  parts.push('</tv>');
  const text = parts.join('\n');
  let max = 0, count = 0;
  const p = new core.XmltvParser({ onProgram: () => count++ });
  for (let i = 0; i < text.length; i += 65536) { p.feed(text.substring(i, i + 65536)); max = Math.max(max, p.buf.length); }
  p.end();
  assert.equal(count, 20000);
  assert.ok(max < 70000, 'buffer stays small: ' + max);
  assert.ok(zlib.gzipSync(text).length < text.length); // sanity for the gzip test data used elsewhere
});
