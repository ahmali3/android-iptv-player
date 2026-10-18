// A fake IPTV provider (Xtream API, XMLTV guide, M3U, streams) for the automated tests.
// Like most real providers it sends no CORS headers.
const http = require('node:http');
const zlib = require('node:zlib');

function fmt(ms) {
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}${p(d.getUTCMonth() + 1)}${p(d.getUTCDate())}${p(d.getUTCHours())}${p(d.getUTCMinutes())}00 +0000`;
}

function start() {
  const H = 3600000;
  const now = Date.now();
  const t0 = now - (now % (30 * 60000)) - H;
  const prog = (ch, a, b, title) => `<programme start="${fmt(a)}" stop="${fmt(b)}" channel="${ch}"><title>${title}</title><desc>About ${title}.</desc></programme>`;
  const xml = `<?xml version="1.0" encoding="UTF-8"?><tv>
<channel id="pitch1"><display-name>Pitch One</display-name></channel>
<channel id="pitch2"><display-name>Pitch Two</display-name></channel>
<channel id="cartoon"><display-name>Cartoon Hub</display-name></channel>
<channel id="akhbar"><display-name>Al Akhbar</display-name></channel>
${prog('pitch1', t0, t0 + 2 * H, 'Derby Day Live')}
${prog('pitch1', t0 + 2 * H, t0 + 4 * H, 'Post-match Analysis')}
${prog('pitch2', t0, t0 + 3 * H, 'Classic Matches')}
${prog('cartoon', t0, t0 + 3 * H, 'Saturday Cartoons')}
${prog('akhbar', t0, t0 + 1.5 * H, 'Evening Bulletin')}
${prog('akhbar', t0 + 1.5 * H, t0 + 3 * H, 'World Report')}
</tv>`;
  const requests = [];
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://x');
    requests.push(req.url);
    const json = (o) => { res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(o)); };
    const action = url.searchParams.get('action');
    if (url.pathname === '/player_api.php') {
      if (url.searchParams.get('password') !== 'secret') return json({ user_info: { auth: 0 } });
      if (!action) return json({ user_info: { username: 'demo', auth: 1, status: 'Active', exp_date: '1893456000' }, server_info: {} });
      if (action === 'get_live_categories') return json([{ category_id: '1', category_name: 'SOCCER' }, { category_id: '2', category_name: 'KIDS' }, { category_id: '3', category_name: 'AR | NEWS' }]);
      if (action === 'get_live_streams') return json([
        { num: 1, name: 'Pitch One', stream_id: 101, stream_icon: '', epg_channel_id: 'pitch1', category_id: '1', tv_archive: 1, tv_archive_duration: 3 },
        { num: 2, name: 'Pitch Two', stream_id: 102, stream_icon: '', epg_channel_id: 'pitch2', category_id: '1', tv_archive: 0 },
        { num: 3, name: 'Cartoon Hub', stream_id: 103, stream_icon: '', epg_channel_id: '', category_id: '2', tv_archive: 0 },
        { num: 4, name: 'Al Akhbar', stream_id: 104, stream_icon: '', epg_channel_id: 'akhbar', category_id: '3', tv_archive: 0 },
      ]);
    }
    if (url.pathname === '/xmltv.php') { res.end(zlib.gzipSync(xml)); return; } // gzipped, as many providers do
    if (url.pathname === '/list.m3u') {
      const base = `http://${req.headers.host}`;
      res.end(`#EXTM3U url-tvg="${base}/xmltv.php"\n#EXTINF:-1 tvg-id="pitch1" group-title="MOVIES",Film Four\n${base}/live/m/m/201.ts\n#EXTINF:-1 group-title="MUSIC",Hits Radio\n${base}/live/m/m/202.ts\n`);
      return;
    }
    if (url.pathname.startsWith('/live/') || url.pathname.startsWith('/timeshift/')) {
      res.writeHead(200, { 'Content-Type': url.pathname.endsWith('.m3u8') ? 'application/vnd.apple.mpegurl' : 'video/mp2t' });
      res.end(url.pathname.endsWith('.m3u8') ? '#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg.ts\n' : Buffer.alloc(188 * 10, 0x47));
      return;
    }
    res.writeHead(404);
    res.end('not found');
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({ server, requests, base: `http://127.0.0.1:${server.address().port}` })));
}

module.exports = { start };
