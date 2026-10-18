/*
 * IPTV core shared by the LG webOS app (browser) and its background service (Node.js on the TV):
 * M3U and Xtream parsing, stream / catch-up URLs, and a streaming XMLTV (guide) parser.
 * Plain ES2017 with no dependencies, so it runs on older webOS versions too.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.IptvCore = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var ATTR = /([A-Za-z0-9_-]+)="([^"]*)"/g;

  function attributes(line) {
    var out = {};
    var m;
    ATTR.lastIndex = 0;
    while ((m = ATTR.exec(line))) out[m[1].toLowerCase()] = m[2];
    return out;
  }

  /** The display name is the text after the first comma that is not inside quotes. */
  function displayName(info) {
    var q = false;
    for (var i = 0; i < info.length; i++) {
      var c = info[i];
      if (c === '"') q = !q;
      else if (c === ',' && !q) return info.substring(i + 1).trim();
    }
    return '';
  }

  /** Parses an extended M3U playlist. Returns { epgUrl, channels: [...] }. */
  function parseM3U(text) {
    var lines = String(text).split(/\r?\n/);
    var channels = [];
    var epgUrl = '';
    var info = null;
    var extGroup = null;
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].trim().replace(/^﻿/, '');
      if (!line) continue;
      if (/^#EXTM3U/i.test(line)) {
        var h = attributes(line);
        var e = h['url-tvg'] || h['x-tvg-url'] || h['tvg-url'];
        if (e) epgUrl = e.split(',')[0].trim();
      } else if (/^#EXTINF/i.test(line)) {
        info = line;
        extGroup = null;
      } else if (/^#EXTGRP:/i.test(line)) {
        extGroup = line.substring(line.indexOf(':') + 1).trim();
      } else if (line[0] === '#') {
        // #EXTVLCOPT and other directives
      } else if (info) {
        var a = attributes(info);
        var n = channels.length + 1;
        var name = displayName(info) || a['tvg-name'] || ('Channel ' + n);
        channels.push({
          key: name,
          num: parseInt(a['tvg-chno'], 10) || n,
          name: name,
          group: (a['group-title'] || extGroup || '').trim() || 'Uncategorized',
          logo: a['tvg-logo'] || '',
          url: line,
          epgId: (a['tvg-id'] || '').trim(),
          catchup: parseInt(a['catchup-days'] || a['tvg-rec'], 10) || 0,
        });
        info = null;
      }
    }
    return { epgUrl: epgUrl, channels: channels };
  }

  /** Many M3U links are really Xtream panels (…/get.php?username=U&password=P). Returns the login or null. */
  function xtreamFromUrl(url) {
    var m = /^(https?):\/\/([^\/?#]+)((?:\/[^?#]*)?)\?([^#]*)/i.exec(String(url).trim());
    if (!m) return null;
    var path = m[3] || '';
    if (!/(get|player_api)\.php$/i.test(path)) return null;
    var params = {};
    m[4].split('&').forEach(function (part) {
      var i = part.indexOf('=');
      if (i > 0) params[part.substring(0, i)] = decodeURIComponent(part.substring(i + 1).replace(/\+/g, ' '));
    });
    if (!params.username || !params.password) return null;
    var prefix = path.substring(0, path.lastIndexOf('/'));
    return { server: m[1].toLowerCase() + '://' + m[2] + prefix, username: params.username, password: params.password };
  }

  /** Cleans up a playlist before saving (trims, adds http://, converts hidden Xtream logins). */
  function normalize(p) {
    var q = Object.assign({}, p);
    if (q.type === 'm3u') {
      var x = xtreamFromUrl(q.url);
      if (x) {
        q.type = 'xtream';
        q.url = x.server;
        q.username = x.username;
        q.password = x.password;
        return q;
      }
      q.url = String(q.url).trim();
      return q;
    }
    var s = String(q.url).trim().replace(/\/+$/, '');
    if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
    s = s.replace(/\/player_api\.php$/i, '').replace(/\/get\.php$/i, '');
    q.url = s;
    q.username = String(q.username || '').trim();
    q.password = String(q.password || '').trim();
    return q;
  }

  function enc(s) {
    return encodeURIComponent(s);
  }

  var xtream = {
    api: function (p, action) {
      var u = p.url + '/player_api.php?username=' + enc(p.username) + '&password=' + enc(p.password);
      return action ? u + '&action=' + action : u;
    },
    stream: function (p, id) {
      return p.url + '/live/' + enc(p.username) + '/' + enc(p.password) + '/' + id + '.' + (p.format === 'ts' ? 'ts' : 'm3u8');
    },
    xmltv: function (p) {
      return p.url + '/xmltv.php?username=' + enc(p.username) + '&password=' + enc(p.password);
    },
    /** Catch-up (timeshift) URL for a past program. */
    timeshift: function (p, ch, prog) {
      var d = new Date(prog.start);
      function two(n) { return (n < 10 ? '0' : '') + n; }
      var start = d.getFullYear() + '-' + two(d.getMonth() + 1) + '-' + two(d.getDate()) + ':' + two(d.getHours()) + '-' + two(d.getMinutes());
      var minutes = Math.max(1, Math.round((prog.stop - prog.start) / 60000));
      return p.url + '/timeshift/' + enc(p.username) + '/' + enc(p.password) + '/' + minutes + '/' + start + '/' + ch.key + '.' + (p.format === 'ts' ? 'ts' : 'm3u8');
    },
    /** Turns the categories + streams API answers into channels, keeping the provider's group order. */
    channels: function (p, categories, streams) {
      var names = {};
      var order = {};
      (categories || []).forEach(function (c, i) {
        var n = String(c.category_name || '').trim();
        names[String(c.category_id)] = n;
        if (!(n in order)) order[n] = i;
      });
      var out = [];
      (streams || []).forEach(function (s) {
        var id = String(s.stream_id);
        if (!id || id === 'undefined') return;
        var group = names[String(s.category_id)] || 'Uncategorized';
        if (!(group in order)) order[group] = 10000 + Object.keys(order).length;
        out.push({
          key: id,
          num: parseInt(s.num, 10) > 0 ? parseInt(s.num, 10) : out.length + 1,
          name: String(s.name || ('Channel ' + id)).trim(),
          group: group,
          logo: s.stream_icon || '',
          url: xtream.stream(p, id),
          epgId: String(s.epg_channel_id || '').trim(),
          catchup: parseInt(s.tv_archive, 10) === 1 ? (parseInt(s.tv_archive_duration, 10) || 0) : 0,
        });
      });
      return { channels: out, order: order };
    },
  };

  /** Groups in provider order, from a channel list. */
  function groupsOf(channels, order) {
    var seen = {};
    var list = [];
    channels.forEach(function (c, i) {
      if (!(c.group in seen)) {
        seen[c.group] = { name: c.group, count: 0, sort: order && c.group in order ? order[c.group] : i };
        list.push(seen[c.group]);
      }
      seen[c.group].count++;
    });
    list.sort(function (a, b) { return a.sort - b.sort; });
    return list.map(function (g) { return { name: g.name, count: g.count }; });
  }

  // ---------- XMLTV ----------

  var ENT = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" };
  function decode(s) {
    return String(s)
      .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
      .replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos);/gi, function (_, e) {
        if (e[0] === '#') return String.fromCharCode(e[1] === 'x' || e[1] === 'X' ? parseInt(e.substring(2), 16) : parseInt(e.substring(1), 10));
        return ENT[e.toLowerCase()];
      })
      .trim();
  }

  /** "20240101120000 +0100" -> epoch ms. Returns NaN if unreadable. */
  function parseTime(s) {
    var m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-]\d{2}):?(\d{2})?/.exec(String(s).trim());
    if (!m) {
      var m2 = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?$/.exec(String(s).trim());
      if (!m2) return NaN;
      return Date.UTC(+m2[1], +m2[2] - 1, +m2[3], +m2[4], +m2[5], +(m2[6] || 0));
    }
    var t = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0));
    var sign = m[7][0] === '-' ? -1 : 1;
    var off = (Math.abs(parseInt(m[7], 10)) * 60 + parseInt(m[8] || '0', 10)) * 60000;
    return t - sign * off;
  }

  function attr(tag, name) {
    var m = new RegExp('\\s' + name + '="([^"]*)"').exec(tag) || new RegExp("\\s" + name + "='([^']*)'").exec(tag);
    return m ? decode(m[1]) : '';
  }

  function inner(xml, name) {
    var m = new RegExp('<' + name + '(?:\\s[^>]*)?>([\\s\\S]*?)</' + name + '>').exec(xml);
    return m ? decode(m[1]) : '';
  }

  /**
   * Streaming XMLTV parser: feed() it text as it downloads; it never holds the whole file.
   * opts.onChannel(id, [names]) for each <channel>, opts.onProgram({epgId, start, stop, title, desc})
   * for each <programme> that opts.accept(epgId, start, stop) allows.
   */
  function XmltvParser(opts) {
    this.opts = opts || {};
    this.buf = '';
    this.count = 0;
  }
  XmltvParser.prototype.feed = function (chunk) {
    this.buf += chunk;
    var re = /<(channel|programme)\b([^>]*)>([\s\S]*?)<\/\1>|<(channel|programme)\b([^>]*)\/>/g;
    var last = 0;
    var m;
    while ((m = re.exec(this.buf))) {
      last = re.lastIndex;
      var kind = m[1] || m[4];
      var head = ' ' + (m[2] || m[5] || '');
      var body = m[3] || '';
      if (kind === 'channel') {
        if (this.opts.onChannel) {
          var names = [];
          var dn = /<display-name(?:\s[^>]*)?>([\s\S]*?)<\/display-name>/g;
          var d;
          while ((d = dn.exec(body))) names.push(decode(d[1]));
          this.opts.onChannel(attr(head, 'id'), names);
        }
      } else {
        var id = attr(head, 'channel');
        var start = parseTime(attr(head, 'start'));
        var stop = parseTime(attr(head, 'stop'));
        if (!id || isNaN(start) || isNaN(stop)) continue;
        if (this.opts.accept && !this.opts.accept(id, start, stop)) continue;
        this.count++;
        if (this.opts.onProgram) this.opts.onProgram({ epgId: id, start: start, stop: stop, title: inner(body, 'title'), desc: inner(body, 'desc') });
      }
    }
    // Keep only the unfinished tail. If nothing matched, keep from the last element start.
    if (last > 0) this.buf = this.buf.substring(last);
    else {
      var i = Math.max(this.buf.lastIndexOf('<channel'), this.buf.lastIndexOf('<programme'));
      if (i > 0) this.buf = this.buf.substring(i);
      else if (i < 0 && this.buf.length > 1024) this.buf = this.buf.substring(this.buf.length - 1024);
    }
  };
  XmltvParser.prototype.end = function () {
    this.feed('');
    this.buf = '';
    return this.count;
  };

  /**
   * Collects a guide for a channel list: programs in [from, to) for the channels' guide ids,
   * plus guide ids for channels without one, matched by display name.
   * Returns a builder with feed(text) / finish() -> { programs: { epgId: [[start, stop, title, desc], ...] }, matched: { channelKey: epgId } }.
   */
  function guideBuilder(channels, from, to) {
    var wanted = {};
    var byName = {};
    channels.forEach(function (c) {
      if (c.epgId) wanted[c.epgId] = true;
      else byName[c.name.toLowerCase().trim()] = c.key;
    });
    var matched = {};
    var programs = {};
    var parser = new XmltvParser({
      onChannel: function (id, names) {
        names.forEach(function (n) {
          var key = byName[n.toLowerCase().trim()];
          if (key && !matched[key]) {
            matched[key] = id;
            wanted[id] = true;
          }
        });
      },
      accept: function (id, start, stop) { return wanted[id] && stop > from && start < to; },
      onProgram: function (p) {
        (programs[p.epgId] || (programs[p.epgId] = [])).push([p.start, p.stop, p.title, p.desc]);
      },
    });
    return {
      feed: function (t) { parser.feed(t); },
      finish: function () {
        parser.end();
        Object.keys(programs).forEach(function (k) { programs[k].sort(function (a, b) { return a[0] - b[0]; }); });
        return { programs: programs, matched: matched };
      },
    };
  }

  /** The program airing at `now` from a guide list, or null. */
  function nowOn(list, now) {
    if (!list) return null;
    for (var i = 0; i < list.length; i++) if (list[i][0] <= now && list[i][1] > now) return list[i];
    return null;
  }

  return {
    attributes: attributes,
    displayName: displayName,
    parseM3U: parseM3U,
    xtreamFromUrl: xtreamFromUrl,
    normalize: normalize,
    xtream: xtream,
    groupsOf: groupsOf,
    parseTime: parseTime,
    decode: decode,
    XmltvParser: XmltvParser,
    guideBuilder: guideBuilder,
    nowOn: nowOn,
  };
});
