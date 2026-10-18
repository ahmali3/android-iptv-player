/*
 * Background service that ships inside the webOS app and runs on the TV (Node.js).
 * Installed web apps can't call most providers' servers directly (no CORS headers),
 * so the app asks this service to download playlists and the guide instead.
 *
 *   luna://dev.ahmed.iptv.service/fetch  { url }                    -> { status, body }
 *   luna://dev.ahmed.iptv.service/guide  { url, channels, from, to } -> { programs, matched, count }
 *
 * The guide is parsed here while it downloads, so a 100 MB XMLTV file never has to be held in
 * memory or sent to the app; only the programs for the user's channels and time window come back.
 */
'use strict';

var http = require('http');
var https = require('https');
var zlib = require('zlib');
var core = require('./iptv-core.js');

var USER_AGENT = 'VLC/3.0.21 LibVLC/3.0.21';
var MAX_BODY = 40 * 1024 * 1024;

/** GET with redirects; calls back with (err, response stream already gunzipped if needed, status). */
function open(url, cb, redirects) {
  redirects = redirects || 0;
  var lib = /^https:/i.test(url) ? https : http;
  var req;
  try {
    req = lib.get(url, { headers: { 'User-Agent': USER_AGENT, 'Accept-Encoding': 'gzip' }, timeout: 60000 }, function (res) {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects < 5) {
        res.resume();
        var next = new (require('url').URL)(res.headers.location, url).toString();
        return open(next, cb, redirects + 1);
      }
      // Detect gzip by its first two bytes (guides are often .xml.gz without a header saying so).
      var first = null;
      var out = new (require('stream').PassThrough)();
      var piped = false;
      res.on('data', function onData(chunk) {
        if (piped) return;
        first = first ? Buffer.concat([first, chunk]) : chunk;
        if (first.length < 2) return;
        piped = true;
        res.removeListener('data', onData);
        var gz = first[0] === 0x1f && first[1] === 0x8b;
        var dest = gz ? zlib.createGunzip() : out;
        if (gz) dest.pipe(out);
        dest.on('error', function (e) { out.emit('error', e); });
        dest.write(first);
        res.pipe(dest);
      });
      res.on('end', function () {
        if (!piped) {
          if (first) out.write(first);
          out.end();
        }
      });
      res.on('error', function (e) { out.emit('error', e); });
      cb(null, out, res.statusCode);
    });
  } catch (e) {
    return cb(e);
  }
  req.on('timeout', function () { req.destroy(new Error('Timed out')); });
  req.on('error', function (e) { cb(e); });
}

function fetchText(url, cb) {
  var done = false;
  function finish(err, status, body) {
    if (done) return;
    done = true;
    cb(err, status, body);
  }
  open(url, function (err, stream, status) {
    if (err) return finish(err);
    var parts = [];
    var size = 0;
    stream.setEncoding('utf8');
    stream.on('data', function (s) {
      size += s.length;
      if (size > MAX_BODY) {
        stream.destroy();
        return finish(new Error('Response too large'));
      }
      parts.push(s);
    });
    stream.on('end', function () { finish(null, status, parts.join('')); });
    stream.on('error', function (e) { finish(e); });
  });
}

function guide(params, cb) {
  var done = false;
  function finish(err, result) {
    if (done) return;
    done = true;
    cb(err, result);
  }
  var builder = core.guideBuilder(params.channels || [], params.from || 0, params.to || Date.now() + 86400000);
  open(params.url, function (err, stream, status) {
    if (err) return finish(err);
    if (status >= 400) {
      stream.resume();
      return finish(new Error('Server answered HTTP ' + status));
    }
    stream.setEncoding('utf8');
    stream.on('data', function (s) {
      try { builder.feed(s); } catch (e) { stream.destroy(); finish(e); }
    });
    stream.on('end', function () {
      var g = builder.finish();
      var count = 0;
      Object.keys(g.programs).forEach(function (k) { count += g.programs[k].length; });
      finish(null, { programs: g.programs, matched: g.matched, count: count });
    });
    stream.on('error', function (e) { finish(e); });
  });
}

/** Wires the methods to the webOS service bus. `Service` is webos-service (provided by the TV). */
function register(service) {
  service.register('fetch', function (msg) {
    fetchText(msg.payload.url, function (err, status, body) {
      if (err) msg.respond({ returnValue: false, errorText: err.message || String(err) });
      else msg.respond({ returnValue: true, status: status, body: body });
    });
  });
  service.register('guide', function (msg) {
    guide(msg.payload, function (err, result) {
      if (err) msg.respond({ returnValue: false, errorText: err.message || String(err) });
      else msg.respond(Object.assign({ returnValue: true }, result));
    });
  });
}

module.exports = { fetchText: fetchText, guide: guide, register: register };

// On the TV, webos-service is available and the service registers itself when loaded.
var Service = null;
try {
  Service = require('webos-service');
} catch (e) {
  // Not on a TV (e.g. tests): nothing to register.
}
if (Service) register(new Service(require('./package.json').name));
