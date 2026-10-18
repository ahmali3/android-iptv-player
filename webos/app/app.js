/*
 * IPTV Player for LG webOS TVs.
 * Screens: Live (groups + channels), Guide, Playlists, playlist form, and the full-screen player.
 * Everything is driven by the remote: arrows move the amber highlight, OK selects, Back goes back.
 */
(function () {
  'use strict';

  var C = window.IptvCore;
  var SERVICE = 'luna://dev.ahmed.iptv.service/';
  var HOUR = 3600000;
  var PAGE = 120; // channel rows rendered at a time
  var KEY = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, OK: 13, BACK: 461, ESC: 27, CH_UP: 33, CH_DOWN: 34, PLAY: 415, PAUSE: 19, PLAYPAUSE: 10252, STOP: 413, RED: 403, GREEN: 404, YELLOW: 405, BLUE: 406 };
  var FAV = '\u0000fav', RECENT = '\u0000recent', ALL = '\u0000all';

  // ---------------------------------------------------------------- helpers

  function h(tag, attrs, kids) {
    var el = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (k) {
        var v = attrs[k];
        if (v === null || v === undefined || v === false) return;
        if (k === 'class') el.className = v;
        else if (k === 'text') el.textContent = v;
        else if (k === 'style') el.setAttribute('style', v);
        else if (k.indexOf('on') === 0) el[k] = v;
        else el.setAttribute(k, v === true ? '' : v);
      });
    }
    (kids || []).forEach(function (c) {
      if (c === null || c === undefined || c === false) return;
      el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
    });
    return el;
  }
  function $(id) { return document.getElementById(id); }
  function show(el, on) { el.classList.toggle('hidden', !on); }
  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); return el; }
  function label(list) { return list === FAV ? 'Favorites' : list === RECENT ? 'Recent' : list === ALL ? 'All channels' : list; }
  function clock(ms) {
    var d = new Date(ms);
    return (d.getHours() < 10 ? '0' : '') + d.getHours() + ':' + (d.getMinutes() < 10 ? '0' : '') + d.getMinutes();
  }
  function initials(name) {
    var w = String(name).replace(/[^\p{L}\p{N} ]/gu, ' ').split(' ').filter(Boolean);
    if (!w.length) return 'TV';
    return (w.length === 1 ? w[0].substring(0, 2) : w[0][0] + w[1][0]).toUpperCase();
  }
  var TINTS = ['#2A3A55', '#2E4A3C', '#4A3326', '#45283A', '#33364A', '#2C4440', '#4A4026', '#3A2C4A'];
  function hash(s) { var x = 0; for (var i = 0; i < s.length; i++) x = (x * 31 + s.charCodeAt(i)) | 0; return Math.abs(x); }
  function logo(c) {
    var box = h('div', { class: 'logo', style: 'background:' + TINTS[hash(c.name) % TINTS.length] }, [initials(c.name)]);
    if (c.logo) {
      var img = h('img', { src: c.logo, alt: '', loading: 'lazy' });
      img.onerror = function () { img.remove(); };
      box.appendChild(img);
    }
    return box;
  }

  // ---------------------------------------------------------------- storage

  var store = {
    get: function (k, d) { try { var v = localStorage.getItem('iptv.' + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem('iptv.' + k, JSON.stringify(v)); } catch (e) { /* full */ } },
  };

  // Channel lists and guides can be large, so they go in IndexedDB.
  var idb = {
    db: null,
    open: function () {
      if (idb.db) return Promise.resolve(idb.db);
      return new Promise(function (res) {
        try {
          var r = indexedDB.open('iptv', 1);
          r.onupgradeneeded = function () { r.result.createObjectStore('kv'); };
          r.onsuccess = function () { idb.db = r.result; res(idb.db); };
          r.onerror = function () { res(null); };
        } catch (e) { res(null); }
      });
    },
    get: function (k) {
      return idb.open().then(function (db) {
        if (!db) return null;
        return new Promise(function (res) {
          var r = db.transaction('kv').objectStore('kv').get(k);
          r.onsuccess = function () { res(r.result || null); };
          r.onerror = function () { res(null); };
        });
      });
    },
    set: function (k, v) {
      return idb.open().then(function (db) {
        if (!db) return;
        return new Promise(function (res) {
          var t = db.transaction('kv', 'readwrite');
          t.objectStore('kv').put(v, k);
          t.oncomplete = res;
          t.onerror = res;
        });
      });
    },
    del: function (k) {
      return idb.open().then(function (db) {
        if (!db) return;
        return new Promise(function (res) {
          var t = db.transaction('kv', 'readwrite');
          t.objectStore('kv').delete(k);
          t.oncomplete = res;
          t.onerror = res;
        });
      });
    },
  };

  // ---------------------------------------------------------------- network

  // On the TV, requests go through the app's own background service (no CORS limits there).
  var hasBridge = typeof window.PalmServiceBridge !== 'undefined';
  var bridges = [];
  function luna(method, params) {
    return new Promise(function (resolve, reject) {
      var b = new window.PalmServiceBridge();
      bridges.push(b); // keep a reference until the answer arrives
      b.onservicecallback = function (msg) {
        bridges.splice(bridges.indexOf(b), 1);
        var r;
        try { r = JSON.parse(msg); } catch (e) { return reject(new Error('Bad answer from the service')); }
        if (r.returnValue) resolve(r);
        else reject(new Error(r.errorText || 'Service error'));
      };
      b.call(SERVICE + method, JSON.stringify(params));
    });
  }

  function getText(url) {
    if (hasBridge) {
      return luna('fetch', { url: url }).then(function (r) {
        if (r.status >= 400) throw new Error('Server answered HTTP ' + r.status);
        return r.body;
      });
    }
    return fetch(url).then(function (r) {
      if (!r.ok) throw new Error('Server answered HTTP ' + r.status);
      return r.text();
    });
  }
  function getJson(url) {
    return getText(url).then(function (t) {
      try { return JSON.parse(t); } catch (e) { throw new Error('Unexpected reply from the server'); }
    });
  }

  /** Downloads and filters the XMLTV guide for these channels. */
  function loadGuide(url, channels, from, to) {
    var slim = channels.map(function (c) { return { key: c.key, name: c.name, epgId: c.epgId }; });
    if (hasBridge) return luna('guide', { url: url, channels: slim, from: from, to: to });
    // In a normal browser: stream it here (used by the automated tests).
    var b = C.guideBuilder(slim, from, to);
    return fetch(url).then(function (r) {
      if (!r.ok) throw new Error('Server answered HTTP ' + r.status);
      return r.arrayBuffer();
    }).then(function (buf) {
      var bytes = new Uint8Array(buf);
      var gz = bytes[0] === 0x1f && bytes[1] === 0x8b;
      var stream = new Blob([bytes]).stream();
      if (gz) stream = stream.pipeThrough(new DecompressionStream('gzip'));
      var reader = stream.pipeThrough(new TextDecoderStream()).getReader();
      function pump() {
        return reader.read().then(function (x) {
          if (x.done) return b.finish();
          b.feed(x.value);
          return pump();
        });
      }
      return pump();
    });
  }

  // ---------------------------------------------------------------- state

  var S = {
    playlists: store.get('playlists', []),
    activeId: store.get('active', 0),
    channels: [],
    order: {},
    groups: [],
    guide: { programs: {}, matched: {} },
    fav: {},
    recent: [],
    hidden: {},
    view: 'home',
    list: null,
    search: '',
    refreshing: false,
  };

  function active() {
    return S.playlists.filter(function (p) { return p.id === S.activeId; })[0] || S.playlists[0] || null;
  }
  function savePlaylists() { store.set('playlists', S.playlists); store.set('active', S.activeId); }
  function guideFor(c) {
    var id = c.epgId || S.guide.matched[c.key];
    return id ? S.guide.programs[id] : null;
  }
  function channelsIn(list) {
    if (list === ALL) return S.channels;
    if (list === FAV) return S.channels.filter(function (c) { return S.fav[c.key]; });
    if (list === RECENT) {
      var by = {};
      S.channels.forEach(function (c) { by[c.key] = c; });
      return S.recent.map(function (k) { return by[k]; }).filter(Boolean);
    }
    return S.channels.filter(function (c) { return c.group === list; });
  }
  function searchResults(q) {
    q = q.trim().toLowerCase();
    if (!q) return [];
    var out = [];
    for (var i = 0; i < S.channels.length && out.length < 300; i++) if (S.channels[i].name.toLowerCase().indexOf(q) >= 0) out.push(S.channels[i]);
    return out;
  }
  function loadUserLists(p) {
    var f = store.get('fav.' + p.id, []);
    S.fav = {};
    f.forEach(function (k) { S.fav[k] = true; });
    S.recent = store.get('recent.' + p.id, []);
    S.hidden = {};
    store.get('hidden.' + p.id, []).forEach(function (g) { S.hidden[g] = true; });
  }
  function toggleFav(c) {
    var p = active();
    if (!p) return;
    if (S.fav[c.key]) delete S.fav[c.key];
    else S.fav[c.key] = true;
    store.set('fav.' + p.id, Object.keys(S.fav));
    status(S.fav[c.key] ? 'Added ' + c.name + ' to favorites' : 'Removed ' + c.name + ' from favorites', 2500);
  }
  function addRecent(c) {
    var p = active();
    if (!p) return;
    S.recent = [c.key].concat(S.recent.filter(function (k) { return k !== c.key; })).slice(0, 40);
    store.set('recent.' + p.id, S.recent);
  }

  var statusTimer = null;
  function status(msg, ms, isError) {
    var el = $('status');
    clearTimeout(statusTimer);
    if (!msg) { show(el, false); return; }
    el.textContent = msg;
    el.classList.toggle('err', !!isError);
    show(el, true);
    if (ms) statusTimer = setTimeout(function () { show(el, false); }, ms);
  }

  // ---------------------------------------------------------------- loading

  function loadCached(p) {
    loadUserLists(p);
    return Promise.all([idb.get('channels.' + p.id), idb.get('guide.' + p.id)]).then(function (r) {
      var ch = r[0], g = r[1];
      S.channels = ch ? ch.channels : [];
      S.order = ch ? ch.order : {};
      S.groups = C.groupsOf(S.channels, S.order);
      S.guide = g || { programs: {}, matched: {} };
      return { channelsAt: ch ? ch.at : 0, guideAt: g ? g.at : 0 };
    });
  }

  /** Reloads channels from the provider, then the guide. */
  function refresh(p) {
    if (S.refreshing) return Promise.resolve();
    S.refreshing = true;
    status('Loading channels…');
    var job;
    if (p.type === 'xtream') {
      job = getJson(C.xtream.api(p)).then(function (acc) {
        var u = (acc && acc.user_info) || {};
        if (!(u.auth === 1 || u.auth === '1')) throw new Error(u.status ? 'Login refused (' + u.status + ')' : 'Login failed — check the server, username and password');
        p.expires = parseInt(u.exp_date, 10) > 0 ? parseInt(u.exp_date, 10) * 1000 : 0;
        return Promise.all([getJson(C.xtream.api(p, 'get_live_categories')), getJson(C.xtream.api(p, 'get_live_streams'))]);
      }).then(function (r) {
        var x = C.xtream.channels(p, r[0], r[1]);
        return { channels: x.channels, order: x.order, epg: p.epgUrl || C.xtream.xmltv(p) };
      });
    } else {
      job = getText(p.url).then(function (text) {
        var m = C.parseM3U(text);
        var order = {};
        m.channels.forEach(function (c) { if (!(c.group in order)) order[c.group] = Object.keys(order).length; });
        return { channels: m.channels, order: order, epg: p.epgUrl || m.epgUrl };
      });
    }
    return job.then(function (r) {
      if (!r.channels.length) throw new Error('No live channels found');
      var had = S.channels.length > 0;
      S.channels = r.channels;
      S.order = r.order;
      S.groups = C.groupsOf(S.channels, S.order);
      p.channelCount = r.channels.length;
      p.lastRefresh = Date.now();
      savePlaylists();
      render(had); // a background refresh keeps your place
      return idb.set('channels.' + p.id, { channels: r.channels, order: r.order, at: Date.now() }).then(function () {
        if (!r.epg) { status(S.note || 'No guide for this playlist', 5000); S.note = null; return; }
        status('Loading guide…');
        var now = Date.now();
        return loadGuide(r.epg, r.channels, now - 6 * HOUR, now + 36 * HOUR).then(function (g) {
          S.guide = { programs: g.programs || {}, matched: g.matched || {}, at: Date.now() };
          status(S.note || null, S.note ? 6000 : 0);
          S.note = null;
          render(true);
          return idb.set('guide.' + p.id, S.guide);
        }, function (e) {
          status('Channels updated, but the guide failed: ' + e.message, 6000, true);
        });
      });
    }).catch(function (e) {
      status(e.message || 'Refresh failed', 8000, true);
    }).then(function () {
      S.refreshing = false;
      var b = document.querySelector('[data-key="nav-refresh"]');
      if (b) b.textContent = 'Refresh';
    });
  }

  function start() {
    var p = active();
    if (!p) { S.view = 'form'; S.form = null; render(); return; }
    S.activeId = p.id;
    loadCached(p).then(function (at) {
      S.view = 'home';
      render();
      if (!S.channels.length || Date.now() - at.channelsAt > 12 * HOUR) refresh(p);
    });
  }

  // ---------------------------------------------------------------- focus + remote

  var focused = null;
  var zoneMemory = {};

  function focusables(root) {
    return Array.prototype.filter.call((root || document).querySelectorAll('.f'), function (el) {
      return el.offsetParent !== null || el === document.activeElement;
    });
  }
  function focus(el) {
    if (!el) return;
    if (focused && focused !== el) focused.classList.remove('focused');
    focused = el;
    el.classList.add('focused');
    var zone = el.closest('[data-zone]');
    if (zone) zoneMemory[zone.getAttribute('data-zone')] = el;
    if (el.tagName === 'INPUT') el.focus();
    else if (document.activeElement && document.activeElement.tagName === 'INPUT') document.activeElement.blur();
    el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    if (el.onfocusin2) el.onfocusin2();
  }
  function layerRoot() {
    if (!$('modal').classList.contains('hidden')) return $('modal');
    if (!$('player').classList.contains('hidden')) {
      if (!$('chlist').classList.contains('hidden')) return $('chlist');
      if (!$('overlay').classList.contains('hidden')) return $('overlay');
      return null;
    }
    return $('view');
  }
  function move(dir) {
    var root = layerRoot();
    if (!root) return;
    var all = focusables(root);
    if (!focused || all.indexOf(focused) < 0) { focus(all[0]); return; }
    var a = focused.getBoundingClientRect();
    var ax = a.left + a.width / 2, ay = a.top + a.height / 2;
    var best = null, bestScore = Infinity;
    all.forEach(function (el) {
      if (el === focused) return;
      var b = el.getBoundingClientRect();
      var bx = b.left + b.width / 2, by = b.top + b.height / 2;
      var primary, gap, center;
      // gap: 0 when the two overlap side by side (straight up/down or left/right), else the distance between them.
      if (dir === 'up' || dir === 'down') {
        if (dir === 'up') { if (b.bottom > a.top + 2 && by >= ay) return; primary = a.top - b.bottom; }
        else { if (b.top < a.bottom - 2 && by <= ay) return; primary = b.top - a.bottom; }
        gap = Math.max(0, Math.max(a.left, b.left) - Math.min(a.right, b.right));
        center = Math.abs(bx - ax);
      } else {
        if (dir === 'left') { if (b.right > a.left + 2 && bx >= ax) return; primary = a.left - b.right; }
        else { if (b.left < a.right - 2 && bx <= ax) return; primary = b.left - a.right; }
        gap = Math.max(0, Math.max(a.top, b.top) - Math.min(a.bottom, b.bottom));
        center = Math.abs(by - ay);
      }
      var score = Math.max(primary, 0) + gap * 3 + center * 0.05;
      if (score < bestScore) { bestScore = score; best = el; }
    });
    if (!best) return;
    // Jumping into another column: go back to where you were in it.
    var fz = focused.closest('[data-zone]'), bz = best.closest('[data-zone]');
    if (bz && fz !== bz) {
      var mem = zoneMemory[bz.getAttribute('data-zone')];
      if (mem && mem.isConnected && mem.offsetParent !== null) best = mem;
    }
    focus(best);
  }
  function focusFirst(sel) {
    var root = layerRoot();
    var el = sel ? (root || document).querySelector(sel) : null;
    focus(el || focusables(root)[0]);
  }

  document.addEventListener('keydown', function (e) {
    var k = e.keyCode;
    var inInput = document.activeElement && document.activeElement.tagName === 'INPUT';
    if (k === KEY.BACK || k === KEY.ESC || (k === 8 && !inInput)) { e.preventDefault(); back(); return; }
    if (!$('player').classList.contains('hidden') && playerKey(k)) { e.preventDefault(); return; }
    if (inInput && (k === KEY.LEFT || k === KEY.RIGHT)) {
      var inp = document.activeElement;
      var atStart = inp.selectionStart === 0 && inp.selectionEnd === 0;
      var atEnd = inp.selectionStart === inp.value.length;
      if ((k === KEY.LEFT && !atStart) || (k === KEY.RIGHT && !atEnd)) return; // move the text cursor
    }
    if (k === KEY.UP) { e.preventDefault(); move('up'); }
    else if (k === KEY.DOWN) { e.preventDefault(); move('down'); }
    else if (k === KEY.LEFT) { e.preventDefault(); move('left'); }
    else if (k === KEY.RIGHT) { e.preventDefault(); move('right'); }
    else if (k === KEY.OK && focused && !inInput) { e.preventDefault(); focused.click(); }
    else if (k === KEY.OK && inInput && focused && focused.onenter) { focused.onenter(); }
    else if (k === KEY.RED && focused && focused.channel) { toggleFav(focused.channel); render(true); }
  });
  // Magic Remote pointer: hovering moves the highlight.
  document.addEventListener('mouseover', function (e) {
    var el = e.target.closest && e.target.closest('.f');
    if (el && el !== focused) focus(el);
  });

  function back() {
    if (!$('modal').classList.contains('hidden')) { closeModal(); return; }
    if (!$('player').classList.contains('hidden')) { playerBack(); return; }
    if (S.view === 'form' && S.playlists.length) { S.view = 'playlists'; render(); return; }
    if (S.view === 'guide' || S.view === 'playlists') { S.view = 'home'; render(); return; }
    if (S.view === 'home' && S.list !== null && S.list !== undefined && !S.search) {
      // On the channel list: back moves to the groups.
      var g = document.querySelector('[data-zone="groups"] .sel') || document.querySelector('[data-zone="groups"] .f');
      if (focused && focused.closest('[data-zone="channels"]') && g) { focus(g); return; }
    }
    if (S.view === 'home' && S.search) { S.search = ''; render(); focusFirst('.search'); return; }
    // At the top level the TV's Back closes the app.
    if (window.webOS && window.webOS.platformBack) window.webOS.platformBack();
    else if (window.PalmSystem && window.PalmSystem.platformBack) window.PalmSystem.platformBack();
  }

  // ---------------------------------------------------------------- views

  function render(keepFocus) {
    var keepKey = keepFocus && focused ? focused.getAttribute('data-key') : null;
    var view = clear($('view'));
    if (S.view === 'home') renderHome(view);
    else if (S.view === 'guide') renderGuide(view);
    else if (S.view === 'playlists') renderPlaylists(view);
    else renderForm(view);
    // While the player or a dialog is up, the screen behind updates without taking the highlight.
    if (!$('player').classList.contains('hidden') || !$('modal').classList.contains('hidden')) return;
    if (keepKey) {
      var el = view.querySelector('[data-key="' + keepKey.replace(/"/g, '\\"') + '"]');
      if (el) { focus(el); return; }
    }
    {
      var first = view.querySelector('[data-zone="channels"] .crow') || view.querySelector('.f.sel') || focusables(view)[0];
      focus(first);
    }
  }

  function topBar(title, sub, buttons) {
    return h('div', { class: 'top' }, [h('div', { class: 'title' }, [sub ? h('span', { class: 'sub', text: sub }) : null, title])].concat(buttons));
  }
  function btn(text, onclick, extra) {
    var b = h('div', Object.assign({ class: 'btn f', role: 'button', tabindex: '-1', text: text, onclick: onclick }, extra || {}));
    return b;
  }

  // --- Live: groups on the left, channels on the right

  function renderHome(view) {
    var p = active();
    if (!p) return;
    if (S.list === null || S.list === undefined) S.list = Object.keys(S.fav).length ? FAV : (S.groups[0] ? S.groups[0].name : ALL);
    view.appendChild(topBar(p.name, 'PLAYLIST', [
      btn('Guide', function () { S.view = 'guide'; render(); }, { 'data-key': 'nav-guide' }),
      btn('Playlists', function () { S.view = 'playlists'; render(); }, { 'data-key': 'nav-playlists' }),
      btn(S.refreshing ? 'Refreshing…' : 'Refresh', function () { refresh(p); }, { 'data-key': 'nav-refresh' }),
    ]));
    if (!S.channels.length) {
      view.appendChild(h('div', { class: 'empty' }, [h('b', { text: S.refreshing ? 'Loading channels…' : 'No channels yet' }), S.refreshing ? 'This takes a few seconds the first time.' : 'Press Refresh to load your provider’s channels.']));
      return;
    }
    var groups = h('div', { class: 'scroll', 'data-zone': 'groups' });
    function groupRow(name, count, key) {
      var row = h('div', { class: 'grow f' + (S.list === key && !S.search ? ' sel' : ''), 'data-key': 'g:' + key, onclick: function () { S.search = ''; S.list = key; render(); focusFirst('[data-zone="channels"] .f'); } },
        [h('span', { text: name }), h('span', { class: 'n', text: String(count) })]);
      groups.appendChild(row);
    }
    groupRow('★ Favorites', Object.keys(S.fav).length, FAV);
    groupRow('Recent', S.recent.length, RECENT);
    groupRow('All channels', S.channels.length, ALL);
    groups.appendChild(h('div', { class: 'label', text: 'Groups' }));
    S.groups.forEach(function (g) { if (!S.hidden[g.name]) groupRow(g.name, g.count, g.name); });

    var right = h('div', { class: 'channels' });
    var search = h('input', { class: 'input f search', type: 'text', placeholder: 'Search channels', value: S.search, 'data-key': 'search', 'aria-label': 'Search channels' });
    search.oninput = function () { S.search = search.value; fillChannels(); };
    search.onenter = function () { focusFirst('[data-zone="channels"] .crow'); };
    right.appendChild(search);
    var title = h('div', { class: 'label' });
    right.appendChild(title);
    var list = h('div', { class: 'scroll', 'data-zone': 'channels' });
    right.appendChild(list);

    function fillChannels() {
      var rows = S.search ? searchResults(S.search) : channelsIn(S.list);
      title.textContent = S.search ? (rows.length ? 'Results for “' + S.search.trim() + '”' : 'No matching channels') : label(S.list) + ' · ' + rows.length;
      channelRows(list, rows);
      Array.prototype.forEach.call(groups.querySelectorAll('.grow'), function (r) { r.classList.toggle('sel', !S.search && r.getAttribute('data-key') === 'g:' + S.list); });
    }
    fillChannels();
    view.appendChild(h('div', { class: 'panes' }, [h('div', { class: 'groups' }, [groups]), right]));
  }

  function channelRows(container, rows) {
    clear(container);
    var now = Date.now();
    var playing = Player.channel;
    var shown = 0;
    function more() {
      var end = Math.min(rows.length, shown + PAGE);
      for (var i = shown; i < end; i++) container.appendChild(channelRow(rows, i, now, playing));
      shown = end;
    }
    container.more = more;
    more();
    if (!rows.length) container.appendChild(h('div', { class: 'empty', text: S.list === FAV && !S.search ? 'No favorites yet. Press the red button on a channel, or use ★ in the player.' : 'Nothing here.' }));
  }

  function channelRow(rows, i, now, playing) {
    var c = rows[i];
    var prog = C.nowOn(guideFor(c), now);
    var row = h('div', { class: 'crow f' + (playing && playing.key === c.key ? ' playing' : ''), 'data-key': 'c:' + c.key, 'data-name': c.name },
      [
        h('div', { class: 'num', text: String(c.num) }),
        logo(c),
        h('div', { class: 'meta' }, [
          h('div', { class: 'name' }, [c.name, S.fav[c.key] ? h('span', { class: 'star', text: '★' }) : null]),
          h('div', { class: 'now', text: prog ? clock(prog[0]) + '  ' + prog[2] : (guideFor(c) ? 'No program info right now' : 'No guide data') }),
          prog ? h('div', { class: 'progress' }, [h('i', { style: 'width:' + Math.round(100 * (now - prog[0]) / (prog[1] - prog[0])) + '%' })]) : null,
        ]),
      ]);
    row.channel = c;
    row.onclick = function () { Player.open(rows, i); };
    row.onfocusin2 = function () {
      var list = row.parentNode;
      if (list && list.more && list.lastElementChild === row) list.more();
      else if (list && list.more && row.nextElementSibling && !row.nextElementSibling.nextElementSibling) list.more();
    };
    return row;
  }

  // --- Guide

  var PX_PER_MIN = 6; // in rem/16 units at 1920 wide
  function renderGuide(view) {
    var p = active();
    var list = S.guideList || (Object.keys(S.fav).length ? FAV : (S.groups[0] ? S.groups[0].name : ALL));
    S.guideList = list;
    var rows = channelsIn(list).slice(0, 300);
    var now = Date.now();
    var from = Math.floor((now - 2 * HOUR) / (30 * 60000)) * 30 * 60000;
    var to = from + 26 * HOUR;
    var rem = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
    var pxMin = PX_PER_MIN * rem / 16;
    function x(t) { return (t - from) / 60000 * pxMin; }
    var offset = S.guideOffset !== undefined ? S.guideOffset : Math.max(0, x(now) - 8 * 60 * pxMin * 0.25);

    view.appendChild(topBar('Guide', p ? p.name.toUpperCase() : '', [
      btn(label(list) + ' ▾', function () {
        var items = [];
        if (Object.keys(S.fav).length) items.push([FAV, 'Favorites']);
        S.groups.forEach(function (g) { if (!S.hidden[g.name]) items.push([g.name, g.name]); });
        chooser('Choose a list', items, function (k) { S.guideList = k; S.guideOffset = undefined; render(); });
      }, { 'data-key': 'guide-list' }),
      btn('Now', function () { S.guideOffset = undefined; render(); }, { 'data-key': 'guide-now' }),
      btn('Live', function () { S.view = 'home'; render(); }, { 'data-key': 'nav-live' }),
    ]));

    var wrap = h('div', { class: 'gridwrap' });
    var axis = h('div', { class: 'timeaxis' });
    for (var t = from; t < to; t += 30 * 60000) axis.appendChild(h('span', { 'data-x': String(x(t)), text: clock(t) }));
    wrap.appendChild(axis);
    var body = h('div', { class: 'grows', 'data-zone': 'guide' });
    var tracks = [];
    rows.forEach(function (c) {
      var track = h('div', { class: 'track' });
      tracks.push(track);
      var progs = guideFor(c) || [];
      progs.forEach(function (g) {
        if (g[1] <= from || g[0] >= to) return;
        var cls = g[0] <= now && g[1] > now ? ' live' : g[1] <= now ? ' past' : '';
        var block = h('div', { class: 'prog f' + cls, 'data-x': String(x(g[0])), 'data-key': 'p:' + c.key + ':' + g[0], style: 'width:' + Math.max(8, x(g[1]) - x(g[0]) - 4) + 'px' },
          [h('div', { class: 'pt', text: g[2] }), h('div', { class: 'pm', text: clock(g[0]) + '–' + clock(g[1]) })]);
        block.onclick = function () { programDetails(p, c, g, rows); };
        block.onfocusin2 = function () {
          // Keep the focused show on screen as you move through time.
          var w = track.clientWidth;
          var start = x(g[0]), end = x(g[1]);
          if (end < offset + 40 || start > offset + w * 0.8) { offset = Math.max(0, start - w * 0.1); S.guideOffset = offset; layout(); }
        };
        track.appendChild(block);
      });
      if (!progs.length) track.appendChild(h('div', { class: 'nodata', text: 'No guide data' }));
      body.appendChild(h('div', { class: 'grow2' }, [h('div', { class: 'gch' }, [logo(c), h('span', { text: c.name })]), track]));
    });
    var line = h('div', { class: 'nowline' });
    body.appendChild(line);
    wrap.appendChild(body);
    view.appendChild(wrap);
    if (!rows.length) wrap.appendChild(h('div', { class: 'empty', text: 'This list has no channels.' }));

    function layout() {
      Array.prototype.forEach.call(axis.children, function (s) { s.style.left = (parseFloat(s.getAttribute('data-x')) - offset + 6) + 'px'; });
      tracks.forEach(function (tr) {
        Array.prototype.forEach.call(tr.querySelectorAll('.prog'), function (b) {
          var bx = parseFloat(b.getAttribute('data-x')) - offset;
          b.style.left = (bx + 2) + 'px';
          // A show that started earlier keeps its title visible at the left edge.
          var hidden = Math.max(0, Math.min(-bx, b.offsetWidth - 100));
          b.firstChild.style.marginLeft = b.lastChild.style.marginLeft = hidden + 'px';
        });
      });
      var gch = body.querySelector('.gch');
      line.style.left = ((gch ? gch.offsetWidth : 240) + x(now) - offset) + 'px';
    }
    requestAnimationFrame(layout);
    layout();
  }

  function programDetails(p, c, g, rows) {
    var now = Date.now();
    var canCatchup = p.type === 'xtream' && c.catchup > 0 && g[0] < now && g[0] > now - c.catchup * 24 * HOUR;
    var actions = [btn(g[0] <= now && g[1] > now ? 'Watch live' : 'Watch channel', function () { closeModal(); Player.open(rows, rows.indexOf(c)); }, { class: 'btn primary f', 'data-key': 'watch' })];
    if (canCatchup) actions.push(btn(g[1] <= now ? 'Watch from start' : 'Start over', function () { closeModal(); Player.open(rows, rows.indexOf(c), { url: C.xtream.timeshift(p, c, { start: g[0], stop: g[1] }), title: g[2] }); }, { 'data-key': 'catchup' }));
    actions.push(btn('Close', closeModal));
    modal(h('div', { class: 'dialog', id: 'programCard' }, [
      h('div', { class: 'when', text: c.name + ' · ' + new Date(g[0]).toDateString() + ' · ' + clock(g[0]) + '–' + clock(g[1]) }),
      h('h2', { text: g[2] }),
      h('div', { class: 'desc', text: g[3] || '' }),
      h('div', { class: 'actions' }, actions),
    ]));
  }

  // --- Playlists

  function renderPlaylists(view) {
    view.appendChild(topBar('Playlists', '', [
      btn('Add playlist', function () { S.form = null; S.view = 'form'; render(); }, { class: 'btn primary f', 'data-key': 'add' }),
      btn('Live', function () { S.view = 'home'; render(); }, { 'data-key': 'nav-live' }),
    ]));
    var cards = h('div', { class: 'cards scroll' });
    S.playlists.forEach(function (p) {
      var exp = p.expires ? ' · expires ' + new Date(p.expires).toLocaleDateString() : '';
      var info = h('div', { class: 'info f', 'data-key': 'pl:' + p.id, onclick: function () { switchTo(p); } }, [
        h('div', { class: 'pname', text: p.name + (p.id === S.activeId ? '  ✓' : '') }),
        h('div', { class: 'pmeta', text: (p.type === 'xtream' ? 'Xtream' : 'M3U') + ' · ' + (p.channelCount || 0) + ' channels' + exp }),
      ]);
      cards.appendChild(h('div', { class: 'card' + (p.id === S.activeId ? ' active' : '') }, [
        info,
        btn('Refresh', function () { switchTo(p, true); }),
        btn('Edit', function () { S.form = p; S.view = 'form'; render(); }),
        btn('Delete', function () { confirmDelete(p); }),
      ]));
    });
    view.appendChild(cards);
  }

  function switchTo(p, forceRefresh) {
    S.activeId = p.id;
    savePlaylists();
    S.list = null;
    S.search = '';
    S.guideList = null;
    loadCached(p).then(function (at) {
      S.view = 'home';
      render();
      if (forceRefresh || !S.channels.length || Date.now() - at.channelsAt > 12 * HOUR) refresh(p);
    });
  }

  function confirmDelete(p) {
    modal(h('div', { class: 'dialog' }, [
      h('h2', { text: 'Delete ' + p.name + '?' }),
      h('div', { class: 'desc', text: 'Its channels, favorites and guide data will be removed from this TV.' }),
      h('div', { class: 'actions' }, [
        btn('Delete', function () {
          S.playlists = S.playlists.filter(function (x) { return x.id !== p.id; });
          ['fav.', 'recent.', 'hidden.'].forEach(function (k) { try { localStorage.removeItem('iptv.' + k + p.id); } catch (e) { /* ignore */ } });
          idb.del('channels.' + p.id);
          idb.del('guide.' + p.id);
          if (S.activeId === p.id) S.activeId = S.playlists[0] ? S.playlists[0].id : 0;
          savePlaylists();
          closeModal();
          if (!S.playlists.length) { S.channels = []; S.view = 'form'; S.form = null; render(); }
          else if (S.activeId) switchTo(active());
        }, { class: 'btn primary f' }),
        btn('Cancel', closeModal),
      ]),
    ]));
  }

  // --- Add / edit playlist

  function renderForm(view) {
    var editing = S.form;
    var f = S.formState || (S.formState = {
      xtream: !editing || editing.type === 'xtream',
      name: editing ? editing.name : '',
      server: editing && editing.type === 'xtream' ? editing.url : '',
      user: editing ? editing.username || '' : '',
      pass: editing ? editing.password || '' : '',
      m3u: editing && editing.type === 'm3u' ? editing.url : '',
      epg: editing ? editing.epgUrl || '' : '',
      hls: editing ? editing.format !== 'ts' : true,
      error: '',
    });
    view.appendChild(topBar(editing ? 'Edit playlist' : S.playlists.length ? 'Add playlist' : 'Welcome', S.playlists.length ? '' : 'IPTV PLAYER', []));
    var form = h('div', { class: 'form' });
    if (!S.playlists.length) form.appendChild(h('div', { class: 'hint', text: 'Add your provider’s Xtream Codes login or an M3U playlist link to start watching.' }));
    form.appendChild(h('div', { class: 'seg' }, [
      btn('Xtream Codes', function () { f.xtream = true; f.error = ''; render(); focusFirst('[data-key="seg-x"]'); }, { class: 'btn f' + (f.xtream ? ' on' : ''), 'data-key': 'seg-x' }),
      btn('M3U', function () { f.xtream = false; f.error = ''; render(); focusFirst('[data-key="seg-m"]'); }, { class: 'btn f' + (!f.xtream ? ' on' : ''), 'data-key': 'seg-m' }),
    ]));
    function field(lbl, key, ph, type) {
      var inp = h('input', { class: 'input f', id: 'field-' + key, 'data-key': 'field-' + key, type: type || 'text', placeholder: ph || '', value: f[key], autocomplete: 'off', spellcheck: 'false' });
      inp.oninput = function () { f[key] = inp.value; };
      inp.onenter = function () { move('down'); };
      return h('div', { class: 'field', style: 'flex:1' }, [h('label', { text: lbl, for: 'field-' + key }), inp]);
    }
    form.appendChild(field('Name', 'name', 'e.g. Home'));
    if (f.xtream) {
      form.appendChild(field('Server URL', 'server', 'http://provider.example:8080', 'url'));
      form.appendChild(h('div', { class: 'row' }, [field('Username', 'user'), field('Password', 'pass', '', 'password')]));
      form.appendChild(btn('Stream format: ' + (f.hls ? 'HLS (recommended)' : 'MPEG-TS'), function () { f.hls = !f.hls; render(); focusFirst('[data-key="format"]'); }, { 'data-key': 'format' }));
      form.appendChild(h('div', { class: 'hint', text: 'The guide is loaded from your provider automatically.' }));
    } else {
      form.appendChild(field('Playlist URL', 'm3u', 'http://…/playlist.m3u', 'url'));
      form.appendChild(field('Guide (XMLTV) URL — optional', 'epg', 'http://…/guide.xml.gz', 'url'));
      form.appendChild(h('div', { class: 'hint', text: 'Links with a username and password switch to Xtream automatically.' }));
    }
    if (f.error) form.appendChild(h('div', { class: 'error', id: 'formError', text: f.error }));
    var actions = [btn(editing ? 'Save' : 'Add playlist', save, { class: 'btn primary f', 'data-key': 'save' })];
    if (S.playlists.length) actions.push(btn('Cancel', function () { S.formState = null; S.view = 'playlists'; render(); }, { 'data-key': 'cancel' }));
    form.appendChild(h('div', { class: 'row' }, actions));
    view.appendChild(form);

    function save() {
      var ok = f.xtream ? f.server.trim() && f.user.trim() && f.pass.trim() : f.m3u.trim();
      if (!ok) { f.error = f.xtream ? 'Enter the server URL, username and password.' : 'Enter a playlist URL.'; render(); focusFirst('[data-key="save"]'); return; }
      var raw = {
        id: editing ? editing.id : Date.now(),
        name: f.name.trim() || (f.xtream ? 'Xtream' : 'M3U'),
        type: f.xtream ? 'xtream' : 'm3u',
        url: f.xtream ? f.server : f.m3u,
        username: f.xtream ? f.user : '',
        password: f.xtream ? f.pass : '',
        epgUrl: f.epg.trim(),
        format: f.hls ? 'm3u8' : 'ts',
        channelCount: editing ? editing.channelCount : 0,
        expires: editing ? editing.expires : 0,
        lastRefresh: 0,
      };
      var p = C.normalize(raw);
      if (editing) S.playlists = S.playlists.map(function (x) { return x.id === p.id ? p : x; });
      else S.playlists.push(p);
      S.formState = null;
      S.form = null;
      if (p.type === 'xtream' && !f.xtream) S.note = 'That link is an Xtream login, so it was added as Xtream.';
      switchTo(p, true);
    }
  }

  // ---------------------------------------------------------------- modal + chooser

  var modalReturn = null;
  function modal(content) {
    modalReturn = focused;
    var m = clear($('modal'));
    m.appendChild(content);
    show(m, true);
    focusFirst('.primary');
  }
  function closeModal() {
    show($('modal'), false);
    clear($('modal'));
    var r = modalReturn;
    modalReturn = null;
    if (r && r.isConnected) focus(r);
    else if (!$('player').classList.contains('hidden')) Player.overlay(false);
    else render();
  }
  function chooser(title, items, onPick) {
    var list = h('div', { class: 'scroll' });
    items.forEach(function (it) {
      list.appendChild(h('div', { class: 'grow f', 'data-key': 'ch:' + it[0], onclick: function () { show($('modal'), false); clear($('modal')); modalReturn = null; onPick(it[0]); } }, [h('span', { text: it[1] })]));
    });
    modal(h('div', { class: 'dialog' }, [h('h2', { text: title }), list]));
    focusFirst('.grow');
  }

  // ---------------------------------------------------------------- player

  var Player = {
    list: [],
    index: 0,
    channel: null,
    catchup: null,
    retries: 0,
    bannerTimer: null,
    overlayTimer: null,
    returnFocus: null,

    open: function (list, index, catchup) {
      if (index < 0 || index >= list.length) return;
      if ($('player').classList.contains('hidden')) Player.returnFocus = focused ? focused.getAttribute('data-key') : null;
      Player.list = list;
      Player.index = index;
      Player.channel = list[index];
      Player.catchup = catchup || null;
      Player.retries = 0;
      show($('player'), true);
      if (focused) focused.classList.remove('focused');
      focused = null;
      Player.overlay(false);
      show($('chlist'), false);
      Player.load();
      Player.banner();
      if (!catchup) addRecent(Player.channel);
    },
    url: function () { return Player.catchup ? Player.catchup.url : Player.channel.url; },
    load: function () {
      var v = $('video');
      show($('perror'), false);
      show($('spinner'), true);
      v.src = Player.url();
      var pr = v.play();
      if (pr && pr.catch) pr.catch(function () { /* reported through the error event */ });
    },
    zap: function (step) {
      if (!Player.list.length) return;
      var n = Player.list.length;
      Player.open(Player.list, ((Player.index + step) % n + n) % n);
    },
    close: function () {
      var v = $('video');
      v.pause();
      v.removeAttribute('src');
      v.load();
      show($('player'), false);
      Player.overlay(false);
      show($('chlist'), false);
      clearTimeout(Player.bannerTimer);
      show($('banner'), false);
      var key = Player.returnFocus;
      Player.channel = null;
      render();
      if (key) {
        var el = $('view').querySelector('[data-key="' + key.replace(/"/g, '\\"') + '"]');
        if (el) focus(el);
      }
    },
    banner: function () {
      var c = Player.channel;
      var list = guideFor(c);
      var now = Date.now();
      var cur = C.nowOn(list, now);
      var next = list ? list.filter(function (g) { return g[0] >= (cur ? cur[1] : now); })[0] : null;
      var b = clear($('banner'));
      b.appendChild(h('div', { class: 'chnum', text: String(c.num) }));
      b.appendChild(logo(c));
      b.appendChild(h('div', { style: 'flex:1;min-width:0' }, [
        h('div', { class: 'big', id: 'bannerName', text: c.name + (Player.catchup ? '  ·  Catch-up' : '') }),
        h('div', { class: 't', text: Player.catchup ? Player.catchup.title : cur ? clock(cur[0]) + '–' + clock(cur[1]) + '  ' + cur[2] : 'No guide information' }),
        !Player.catchup && next ? h('div', { class: 'next', text: 'Next · ' + clock(next[0]) + '  ' + next[2] }) : null,
      ]));
      show(b, true);
      clearTimeout(Player.bannerTimer);
      Player.bannerTimer = setTimeout(function () { show(b, false); }, 4000);
    },
    overlay: function (on) {
      var o = $('overlay');
      clearTimeout(Player.overlayTimer);
      if (!on) { show(o, false); return; }
      var c = Player.channel;
      clear(o);
      o.appendChild(h('div', { class: 'chname', text: c.num + '  ' + c.name }));
      o.appendChild(btn(S.fav[c.key] ? '★ Favorite' : '☆ Add to favorites', function () { toggleFav(c); Player.overlay(true); focusFirst('[data-key="fav"]'); }, { 'data-key': 'fav' }));
      var tracks = $('video').audioTracks;
      if (tracks && tracks.length > 1) {
        o.appendChild(btn('Audio', function () {
          var cur = 0;
          for (var i = 0; i < tracks.length; i++) if (tracks[i].enabled) cur = i;
          for (var j = 0; j < tracks.length; j++) tracks[j].enabled = j === (cur + 1) % tracks.length;
          var t = tracks[(cur + 1) % tracks.length];
          status('Audio: ' + (t.label || t.language || 'track ' + ((cur + 1) % tracks.length + 1)), 2500);
        }, { 'data-key': 'audio' }));
      }
      o.appendChild(btn('Channels', function () { Player.overlay(false); Player.channels(); }, { 'data-key': 'channels' }));
      o.appendChild(btn('Close player', Player.close, { 'data-key': 'closeplayer' }));
      show(o, true);
      Player.banner();
      focusFirst('[data-key="fav"]');
      Player.poke();
    },
    poke: function () {
      clearTimeout(Player.overlayTimer);
      Player.overlayTimer = setTimeout(function () {
        if ($('modal').classList.contains('hidden')) { Player.overlay(false); focused = null; }
      }, 6000);
    },
    channels: function () {
      var box = clear($('chlist'));
      box.appendChild(h('div', { class: 'label', text: 'Channels' }));
      var list = h('div', { class: 'scroll', 'data-zone': 'plist' });
      box.appendChild(list);
      var now = Date.now();
      Player.list.forEach(function (c, i) {
        var prog = C.nowOn(guideFor(c), now);
        var row = h('div', { class: 'crow f' + (i === Player.index ? ' playing' : ''), 'data-key': 'pc:' + c.key },
          [h('div', { class: 'num', text: String(c.num) }), h('div', { class: 'meta' }, [h('div', { class: 'name', text: c.name }), h('div', { class: 'now', text: prog ? prog[2] : '' })])]);
        row.onclick = function () { show($('chlist'), false); Player.open(Player.list, i); };
        list.appendChild(row);
      });
      show(box, true);
      focusFirst('.crow.playing');
    },
  };

  /** Remote keys while the player is open. Returns true if handled. */
  function playerKey(k) {
    var overlayOn = !$('overlay').classList.contains('hidden');
    var listOn = !$('chlist').classList.contains('hidden');
    var modalOn = !$('modal').classList.contains('hidden');
    if (k === KEY.CH_UP) { Player.zap(+1); return true; }
    if (k === KEY.CH_DOWN) { Player.zap(-1); return true; }
    if (k === KEY.PLAY || k === KEY.PAUSE || k === KEY.PLAYPAUSE) {
      var v = $('video');
      if (k === KEY.PAUSE || (k === KEY.PLAYPAUSE && !v.paused)) v.pause(); else v.play();
      return true;
    }
    if (k === KEY.STOP) { Player.close(); return true; }
    if (k === KEY.RED) { toggleFav(Player.channel); if (overlayOn) Player.overlay(true); return true; }
    if (modalOn || listOn) return false;
    if (overlayOn) { Player.poke(); return false; }
    if (k === KEY.UP) { Player.zap(+1); return true; }
    if (k === KEY.DOWN) { Player.zap(-1); return true; }
    if (k === KEY.LEFT) { Player.channels(); return true; }
    if (k === KEY.OK || k === KEY.RIGHT) { Player.overlay(true); return true; }
    return false;
  }
  function playerBack() {
    if (!$('chlist').classList.contains('hidden')) { show($('chlist'), false); focused = null; return; }
    if (!$('overlay').classList.contains('hidden')) { Player.overlay(false); focused = null; return; }
    Player.close();
  }

  (function wireVideo() {
    var v = $('video');
    v.addEventListener('playing', function () { show($('spinner'), false); show($('perror'), false); Player.retries = 0; });
    v.addEventListener('waiting', function () { show($('spinner'), true); });
    v.addEventListener('error', function () {
      if (!Player.channel) return;
      show($('spinner'), false);
      if (Player.retries < 3) {
        Player.retries++;
        var e = $('perror');
        e.textContent = 'Reconnecting… (' + Player.retries + '/3)';
        show(e, true);
        setTimeout(function () { if (Player.channel) Player.load(); }, 1500 * Player.retries);
      } else {
        var el = $('perror');
        el.textContent = 'This channel won’t play right now. Your provider may be down, or your connection limit is reached. Press up or down for another channel.';
        show(el, true);
      }
    });
  })();

  // ---------------------------------------------------------------- go

  window.IptvApp = { state: S, player: Player, refresh: refresh, render: render, focus: function () { return focused; } };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
