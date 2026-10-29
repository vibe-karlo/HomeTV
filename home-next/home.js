window.HOME_READY.then(function () {
  "use strict";
  var DATA = window.HOME_DATA;
  var TV = window.TVOS;   // the TV app's bridge; absent in the preview
  if (TV) document.body.classList.add("tv");
  var $ = function (s) { return document.querySelector(s); };

  // Settings the home reads. On the TV the app keeps these; the preview keeps them in this browser.
  var prefs = { background: "shows", aerialCats: ["Landscapes", "Cities", "Space", "Underwater"], screensaver: true,
    screensaverAfter: 10, clock: "24h", hidden: [], philipsButton: "com.stremio.one", rakutenButton: "com.spotify.tv.android",
    hiddenGenres: ["Horror", "Sci-Fi"], sport: true, tvnow: true, weather: true };
  try { Object.assign(prefs, JSON.parse((TV ? TV.kept("couch:prefs") : localStorage.getItem("couch:prefs")) || "{}")); } catch (e) {}
  if (/[?&]bg=aerial/.test(location.search)) prefs.background = "aerial";
  function savePrefs() {
    var s = JSON.stringify(prefs);
    try { if (TV) TV.keep("couch:prefs", s); else localStorage.setItem("couch:prefs", s); } catch (e) {}
  }
  var stage = $("#stage");

  // ---------------------------------------------------------------- scale the 1920x1080 canvas to the window

  // The TV's WebView settles its size after the page starts, so measure again once it has.
  function fit() {
    var w = document.documentElement.clientWidth || innerWidth, h = document.documentElement.clientHeight || innerHeight;
    var s = Math.min(w / 1920, h / 1080);
    stage.style.transform = "translate(" + (w - 1920 * s) / 2 + "px," + (h - 1080 * s) / 2 + "px) scale(" + s + ")";
    console.log("couch fit " + JSON.stringify({ w: w, h: h, iw: innerWidth, ih: innerHeight, dpr: devicePixelRatio, s: s }));
  }
  addEventListener("resize", fit);
  addEventListener("load", fit);
  fit(); setTimeout(fit, 300); setTimeout(fit, 1500);

  // ---------------------------------------------------------------- grain for the glass (drawn once, our own pixels)

  (function grain() {
    var c = document.createElement("canvas"); c.width = c.height = 128;
    var g = c.getContext("2d"), img = g.createImageData(128, 128);
    for (var i = 0; i < img.data.length; i += 4) {
      var v = Math.random() * 255;
      img.data[i] = img.data[i + 1] = img.data[i + 2] = v; img.data[i + 3] = 14;
    }
    g.putImageData(img, 0, 0);
    document.documentElement.style.setProperty("--grain", "url(" + c.toDataURL() + ")");
  })();

  // ---------------------------------------------------------------- frost: a tiny copy of the hero, scaled up, clipped by each host

  var FW = 40, FH = 22;   // small enough that bilinear upscaling reads as a heavy blur
  var frostHosts = Array.prototype.slice.call(document.querySelectorAll(".frost-host")).map(function (host) {
    var clip = document.createElement("div"); clip.className = "frost-clip";
    host.insertBefore(clip, host.firstChild);
    var pair = [0, 1].map(function () {
      var c = document.createElement("canvas"); c.width = FW; c.height = FH; c.className = "frost";
      clip.appendChild(c);
      return c;
    });
    return { host: host, pair: pair, cur: 0 };
  });
  function placeFrost() {
    frostHosts.forEach(function (f) {
      var x = f.host.offsetLeft, y = f.host.offsetTop, p = f.host.offsetParent;
      while (p && p !== stage) { x += p.offsetLeft; y += p.offsetTop; p = p.offsetParent; }
      f.pair.forEach(function (c) { c.style.left = -x + "px"; c.style.top = -y + "px"; });
    });
  }
  var mid = document.createElement("canvas"); mid.width = 320; mid.height = 180;
  function paintFrost(img) {
    // Two-step downscale averages the picture instead of sampling it, so the glass has no speckle.
    var m = mid.getContext("2d"); m.imageSmoothingQuality = "high";
    m.drawImage(img, 0, 0, 320, 180);
    frostHosts.forEach(function (f) {
      var next = f.pair[1 - f.cur], g = next.getContext("2d");
      g.imageSmoothingQuality = "high";
      g.drawImage(mid, 0, 0, FW, FH);
      g.fillStyle = "rgba(13,12,11,0.18)"; g.fillRect(0, 0, FW, FH);
      next.classList.add("on"); f.pair[f.cur].classList.remove("on"); f.cur = 1 - f.cur;
    });
  }

  // ---------------------------------------------------------------- hero

  var heroLayers = Array.prototype.slice.call(document.querySelectorAll(".hero-img"));
  var heroCur = 0, heroItem = null, heroToken = 0;
  var SOURCE = { netflix: "Netflix", stremio: "Stremio" };
  var cache = {};
  // Pictures from the web go through the TV's shrinking cache; the TV's own (Watch Next artwork) are already small.
  function art(url, w) { return TV && url && url.charAt(0) !== "/" ? "/img?w=" + w + "&u=" + encodeURIComponent(url) : url; }
  // Where a show opens: Netflix or Stremio for the suggestions, the app itself for what an app reported.
  function srcName(it) { return it.kind === "watchnext" ? it.service : SOURCE[it.opens || it.source]; }
  // Remembers only the last few pictures: each decoded backdrop is megabytes on a 2 GB TV.
  var cacheOrder = [];
  function load(url) {
    if (!cache[url]) {
      cache[url] = new Promise(function (res, rej) {
        var i = new Image(); i.decoding = "async"; i.onload = function () { res(i); }; i.onerror = rej; i.src = url;
      });
      cacheOrder.push(url);
      while (cacheOrder.length > 6) delete cache[cacheOrder.shift()];
    }
    return cache[url];
  }

  // ---------------------------------------------------------------- live sport (F1, NK Osijek) at the top

  var APP_NAME = { "hr.telekom.android.maxtvp": "MAXtv", "uc.flex.hr.smarttv": "EON" };
  function when(ev) {
    var now = Date.now(), d = new Date(ev.start), hm = d.getHours() + ":" + String(d.getMinutes()).padStart(2, "0");
    var end = new Date(ev.stop), until = end.getHours() + ":" + String(end.getMinutes()).padStart(2, "0");
    if (ev.start <= now && now < ev.stop) return ev.tv ? "Now until " + until : "Live now";
    var day = new Date(); day.setHours(0, 0, 0, 0);
    var diff = Math.floor((d - day) / 86400e3);
    return (diff === 0 ? "Today " : diff === 1 ? "Tomorrow " : d.toLocaleDateString("en-GB", { weekday: "long" }) + " ") + hm;
  }
  function liveItems() {
    var sport = prefs.sport ? DATA.sport || [] : [], tv = prefs.tvnow ? DATA.tvnow || [] : [];
    return sport.concat(tv).filter(function (e) { return e.stop > Date.now(); }).map(function (e) {
      var t = e.title.toLowerCase();
      // Pictures shipped with the home screen (credits in Settings, About).
      var bg = e.kind === "tv" ? (e.image ? art(e.image, 1280) : null)
        : /(^|[^a-z0-9])f1([^a-z0-9]|$)|formula 1/.test(t) ? "sport/f1.jpg" : /osijek/.test(t) ? "sport/osijek.jpg" : null;
      return { kind: "live", tv: e.kind === "tv", title: e.title.replace(/,?\s*ep\.\s*(\d+)\.?$/, " \u00b7 Ep. $1"),
        channel: e.channel, start: e.start, stop: e.stop, opens: e.opens, bg: bg, desc: e.desc, id: "live:" + e.channel + e.start };
    });
  }
  function showLive(item) {
    stage.classList.add("swap");
    setTimeout(function () {
      $("#hero").classList.toggle("event", !item.bg);   // no picture: a dark title card
      $("#hero").classList.toggle("sport", !!item.bg);
      var t = $("#hero-title"); t.textContent = "";
      var s = document.createElement("div"); s.className = "text-title event-title"; s.textContent = item.title; t.appendChild(s);
      $("#hero-meta").innerHTML = '<span class="src">' + when(item) + '</span><span class="sep">·</span>' + item.channel.replace(/ HR$/, "");
      $("#hero-desc").textContent = item.desc || "";
      $("#hero-watch .label").textContent = "Watch on " + (APP_NAME[item.opens] || "TV");
      $("#hero-watch").classList.remove("none");
      stage.classList.remove("swap");
    }, 260);
  }
  if (TV) setInterval(function () {
    fetch("/api/sport").then(function (r) { return r.json(); }).then(function (d) { if (d.ok) { DATA.sport = d.sport; DATA.tvnow = d.tvnow || []; } });
  }, 10 * 60e3);

  function setHero(item) {
    if (!item || item === heroItem) return;
    if (item.kind === "live") {
      heroItem = item;
      var tok = ++heroToken;
      if (!item.bg) { showLive(item); return; }
      load(item.bg).then(function (img) {
        if (tok !== heroToken) return;
        var next = heroLayers[1 - heroCur];
        next.style.backgroundImage = "url(" + item.bg + ")";
        next.classList.add("on"); heroLayers[heroCur].classList.remove("on"); heroCur = 1 - heroCur;
        paintFrost(img);
        showLive(item);
      }, function () { if (tok === heroToken) showLive(item); });
      return;
    }
    heroItem = item;
    var token = ++heroToken;
    var bg = art(item.bg, 1280), logo = art(item.logo, 640);
    Promise.all([bg ? load(bg) : Promise.resolve(null), logo ? load(logo).catch(function () { return null; }) : null]).then(function (r) {
      if (token !== heroToken) return;
      var next = heroLayers[1 - heroCur];
      next.style.backgroundImage = r[0] ? "url(" + bg + ")" : "none";   // no picture: the dark title card
      next.classList.remove("drift"); void next.offsetWidth; next.classList.add("drift");
      next.classList.add("on"); heroLayers[heroCur].classList.remove("on"); heroCur = 1 - heroCur;
      if (r[0]) paintFrost(r[0]);
      stage.classList.add("swap");
      setTimeout(function () {
        if (token !== heroToken) return;
        var t = $("#hero-title"); t.textContent = "";
        if (r[1]) { var im = document.createElement("img"); im.src = logo; im.alt = item.title; t.appendChild(im); }
        else { var s = document.createElement("div"); s.className = "text-title"; s.textContent = item.title; t.appendChild(s); }
        var meta = ['<span class="src">' + srcName(item) + "</span>"];
        if (item.episode) meta.push(item.episode);
        else if (item.kind !== "watchnext") meta.push(item.type === "series" ? "Series" : "Film");
        if (item.episode_title) meta.push(item.episode_title);
        if (item.progress) meta.push(Math.max(1, Math.round((1 - item.progress) * 100)) + "% left");
        if (item.year) meta.push(item.year);
        if (item.rating) meta.push("IMDb " + item.rating);
        if (item.genres && item.genres.length) meta.push(item.genres.slice(0, 2).join(", "));
        $("#hero-meta").innerHTML = meta.map(function (m, i) { return i ? esc(m) : m; }).join('<span class="sep">·</span>');
        $("#hero-desc").textContent = item.description || "";
        $("#hero").classList.remove("event", "sport");
        $("#hero").classList.toggle("event", !r[0]);
        $("#hero-watch .label").textContent = (item.kind === "watchnext" ? "Resume in " : "Watch on ") + srcName(item);
        $("#hero-watch").classList.remove("none");
        stage.classList.remove("swap");
      }, 260);
    }, function () { if (token === heroToken) heroItem = null; });
  }

  function esc(t) { return String(t).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]; }); }

  // ---------------------------------------------------------------- dock

  var EXT = { "com.android.vending": "webp" };
  // Apps with only a square launcher icon. HRTi's icon is itself a full tile, so it fills the tile;
  // VLC's cone sits bare on a dark tile. YouTube Music ships an 80px icon, so it is drawn here.
  var ICON_ONLY = { "hr.hrt.hrti": ["#600000", "cover-icon"], "org.videolan.vlc": ["#24211e", "icon"] };
  var YTM = '<svg viewBox="0 0 72 72"><circle cx="36" cy="36" r="30" fill="#ff0033"/>' +
    '<circle cx="36" cy="36" r="16.5" fill="none" stroke="#fff" stroke-width="3"/><path d="M32 29.5v13l11-6.5z" fill="#fff"/></svg>';
  var GEAR = '<svg viewBox="0 0 72 72" fill="none" stroke="#f6f3ee" stroke-width="4" stroke-linecap="round" stroke-linejoin="round">' +
    '<path d="M31 9h10l1.6 7.2a21 21 0 0 1 5.3 3.1l7-2.3 5 8.6-5.4 5a21 21 0 0 1 0 6.1l5.4 5-5 8.6-7-2.3a21 21 0 0 1-5.3 3.1L41 63H31l-1.6-7.2a21 21 0 0 1-5.3-3.1l-7 2.3-5-8.6 5.4-5a21 21 0 0 1 0-6.1l-5.4-5 5-8.6 7 2.3a21 21 0 0 1 5.3-3.1z"/>' +
    '<circle cx="36" cy="36" r="8.5"/></svg>';

  var dockTrack = $("#dock-track"), apps = [], tiles = [];
  function buildDock() {
    var mine = prefs.appOrder || [];
    var rank = function (a) { var i = mine.indexOf(a.package); return i < 0 ? 1000 + DATA.apps.indexOf(a) : i; };
    apps = DATA.apps.filter(function (a) { return prefs.hidden.indexOf(a.package) < 0; })
      .sort(function (a, b) { return rank(a) - rank(b); })
      .concat([{ package: "settings", name: "Settings" }]);
    dockTrack.textContent = "";
    focused = null;
    tiles = apps.map(dockTile);
    st.dock = Math.min(st.dock, apps.length - 1);
    var order = apps.filter(function (a) { return a.package !== "settings"; }).map(function (a) { return a.package; });
    if (JSON.stringify(order) !== JSON.stringify(prefs.dockOrder)) { prefs.dockOrder = order; savePrefs(); }
  }
  function dockTile(a, i) {
    var el = document.createElement("div");
    el.className = "tile focusable";
    if (a.package === "settings") { el.innerHTML = GEAR; el.style.background = "#22201d"; }
    else if (a.banner) el.style.backgroundImage = "url(" + a.banner + ")";   // on the TV: the banner Android draws
    else if (a.package === "com.google.android.youtube.tvmusic") { el.innerHTML = YTM; el.style.background = "#121110"; }
    else if (ICON_ONLY[a.package]) {
      el.classList.add(ICON_ONLY[a.package][1]);
      el.style.backgroundColor = ICON_ONLY[a.package][0];
      el.style.backgroundImage = "url(banners/" + a.package + ".png)";
    } else el.style.backgroundImage = "url(banners/" + a.package + "." + (EXT[a.package] || "png") + ")";
    el.insertAdjacentHTML("beforeend", '<span class="dim"></span><span class="sheen"></span><span class="name"></span>');
    el.querySelector(".name").textContent = a.name;
    el.addEventListener("click", function () { pointer("dock", 0, i); });
    dockTrack.appendChild(el);
    return el;
  }

  // ---------------------------------------------------------------- rows

  var rowsEl = $("#rows"), rows = [];
  // Hidden genres leave every row but Continue Watching (that one is yours).
  function shown(r) {
    if (r.id === "continue") return r.items;
    return r.items.filter(function (it) {
      return !(it.genres || []).some(function (g) { return prefs.hiddenGenres.indexOf(g) >= 0; });
    }).slice(0, 16);
  }
  function buildRows() {
    rowsEl.textContent = "";
    rows = DATA.rows.map(function (r) { return { def: r, items: shown(r) }; }).filter(function (x) { return x.items.length; })
      .map(function (x, ri) {
        var row = document.createElement("section"); row.className = "row";
        row.innerHTML = "<h2></h2>"; row.querySelector("h2").textContent = x.def.title;
        var track = document.createElement("div"); track.className = "track"; row.appendChild(track);
        var cards = x.items.map(function (it, ci) {
          var c = document.createElement("div"); c.className = "card focusable";
          var p = it.progress ? '<span class="progress"><i style="width:' + Math.round(it.progress * 100) + '%"></i></span>' : "";
          if (p) c.classList.add("has-progress");
          c.innerHTML = '<span class="scrim"></span><span class="logo"></span>' + p +
            '<span class="dim"></span><span class="sheen"></span><span class="caption"></span>';
          c.querySelector(".caption").textContent = (it.episode ? it.title + " · " + it.episode : it.title + (it.year ? " · " + it.year : "")) +
            (it.rating ? " · IMDb " + it.rating : "");
          var logo = art(it.logo, 320);
          (logo ? load(logo) : Promise.reject()).then(function () { c.querySelector(".logo").style.backgroundImage = "url(" + logo + ")"; }, function () {
            var f = document.createElement("span"); f.className = "fallback"; f.textContent = it.title; c.insertBefore(f, c.querySelector(".dim"));
            // An app's card with no picture still says where it plays.
            if (it.kind === "watchnext" && !it.bg) { var a = document.createElement("small"); a.textContent = it.service; f.insertBefore(a, f.firstChild); }
          });
          c.addEventListener("click", function () { pointer("rows", ri, ci); });
          track.appendChild(c);
          return c;
        });
        rowsEl.appendChild(row);
        return { id: x.def.id, el: row, track: track, cards: cards, items: x.items, col: 0, painted: 0 };
      });
    st.row = Math.min(st.row, Math.max(0, rows.length - 1));
  }

  // Only rows near the focus get their artwork, so a small TV never holds every backdrop at once.
  function paintNear(ri) {
    for (var i = Math.max(0, ri - 1); i <= Math.min(rows.length - 1, ri + 1); i++) {
      var r = rows[i], upto = Math.min(r.cards.length, r.col + 7);
      for (var c = r.painted; c < upto; c++) if (r.items[c].bg) r.cards[c].style.backgroundImage = "url(" + art(r.items[c].bg, 480) + ")";
      r.painted = Math.max(r.painted, upto);
    }
  }

  // ---------------------------------------------------------------- focus model

  var st = { zone: "dock", dock: 0, row: 0, top: 0 };
  var focused = null, heroTimer = null, rotateTimer = null, rotateIdx = 0;
  var TILE = 260 + 32, CARD = 418 + 34;

  function render() {
    if (!rows.length && st.zone === "rows") st.zone = "dock";
    var el = st.zone === "top" ? (st.top === 1 ? $("#clock") : $("#top-gear")) : st.zone === "hero" ? $("#hero-watch") : st.zone === "dock" ? tiles[st.dock] : rows[st.row].cards[rows[st.row].col];
    if (focused && focused !== el) focused.classList.remove("focus");
    el.classList.add("focus"); focused = el;
    document.body.classList.toggle("browse", st.zone === "rows");
    document.body.classList.toggle("forecast", st.zone === "top" && st.top === 1);

    // Dock: hold focus near the left once it passes the fifth tile.
    var first = Math.max(0, Math.min(st.dock - 4, apps.length - 6));
    dockTrack.style.transform = "translateX(" + -first * TILE + "px)";
    tiles.forEach(function (t, i) { t.classList.toggle("out", i < first || i > first + 5); });
    rows.forEach(function (r, i) {
      var off = Math.max(0, r.col - 1);
      r.el.classList.toggle("active", st.zone === "rows" && i === st.row);
      r.el.classList.toggle("past", st.zone === "rows" && i < st.row);
      r.track.style.transform = "translateX(" + -off * CARD + "px)";
      r.cards.forEach(function (c, ci) { c.classList.toggle("out", ci < off); c.classList.toggle("peek", ci > off + 3); });
    });
    // In browse the focused row sits just under the hero info; on the dock only card tops peek.
    var y = st.zone === "rows" ? 560 - (990 + st.row * 392) : 0;
    $("#scroller").style.transform = "translateY(" + y + "px)";
    if (rows.length) rows[0].el.querySelector("h2").style.opacity = st.zone === "rows" ? "" : "0";
    paintNear(st.zone === "rows" ? st.row : 0);

    clearTimeout(heroTimer);
    if (st.zone === "hero" || st.zone === "top") { clearInterval(rotateTimer); return; }   // the featured show holds still while focused
    heroTimer = setTimeout(followFocus, st.zone === "rows" ? 180 : 320);
  }

  function heroPool() {
    var by = function (id) { for (var i = 0; i < rows.length; i++) if (rows[i].id === id) return rows[i].items; return []; };
    var pkg = apps[st.dock].package, live = liveItems();
    if (live.length) return live.concat(by("netflix").slice(0, 3), by("series").slice(0, 3));   // what you follow comes first
    if (pkg === "com.netflix.ninja") return by("netflix");
    if (pkg === "com.stremio.one") return by("series").concat(by("movies"));
    return by("netflix").slice(0, 4).concat(by("series").slice(0, 4), by("movies").slice(0, 4));
  }

  // ---------------------------------------------------------------- aerial background (Apple's videos, from Apple's servers)

  var aerial = { on: prefs.background === "aerial", vids: Array.prototype.slice.call(document.querySelectorAll("#aerial video")),
    cur: 0, clip: null, frostTimer: null, poi: -1 };
  // Apple's servers use a certificate browsers do not trust, so clips play from a local copy:
  // the TV app keeps a few downloaded, this preview reads aerial-cache/ (listed in aerial-cache.js).
  var AERIALS = window.AERIAL_CACHE ? (window.AERIALS || []).filter(function (a) {
    return window.AERIAL_CACHE.indexOf(a.url.split("/").pop()) >= 0;
  }).map(function (a) { return Object.assign({}, a, { url: "aerial-cache/" + a.url.split("/").pop() }); }) : (window.AERIALS || []);
  function pickAerial() {
    if (!AERIALS.length) return null;
    var pool = AERIALS.filter(function (a) { return prefs.aerialCats.indexOf(a.cat) >= 0; });
    if (!pool.length) pool = AERIALS;
    var c;
    do { c = pool[Math.floor(Math.random() * pool.length)]; } while (pool.length > 1 && c === aerial.clip);
    return c;
  }
  function playAerial(clip) {
    if (!clip) return;   // none on the TV yet: the first ones download in the background
    var v = aerial.vids[1 - aerial.cur];
    aerial.clip = clip; aerial.poi = -1;
    v.src = clip.url; v.currentTime = 0;
    v.play().then(function () {
      v.classList.add("on"); aerial.vids[aerial.cur].classList.remove("on");
      var old = aerial.vids[aerial.cur]; setTimeout(function () { if (!old.classList.contains("on")) { old.pause(); old.removeAttribute("src"); old.load(); } }, 1600);
      aerial.cur = 1 - aerial.cur;
      $("#aerial-place").textContent = clip.label;
      caption();
    }, function () { setTimeout(function () { playAerial(pickAerial()); }, 2000); });
  }
  function caption() {
    var v = aerial.vids[aerial.cur], t = v.currentTime, poi = aerial.clip.poi, i = -1;
    for (var k = 0; k < poi.length; k++) if (poi[k][0] <= t) i = k;
    if (i === aerial.poi) return;
    aerial.poi = i;
    var el = $("#aerial-caption"); el.classList.add("fade");
    setTimeout(function () { el.textContent = i >= 0 ? poi[i][1] : ""; el.classList.remove("fade"); }, 500);
  }
  aerial.vids.forEach(function (v) {
    v.addEventListener("timeupdate", function () { if (v === aerial.vids[aerial.cur]) caption(); });
    v.addEventListener("ended", function () { if (v === aerial.vids[aerial.cur]) playAerial(pickAerial()); });
  });
  function aerialActive(active) {
    clearInterval(aerial.frostTimer);
    var v = aerial.vids[aerial.cur];
    if (!active) { if (aerial.clip) v.pause(); return; }
    if (!aerial.clip) playAerial(pickAerial()); else v.play().catch(function () {});
    // The glass follows the video: a tiny frame every 1.5 s, crossfaded.
    aerial.frostTimer = setInterval(function () {
      var cv = aerial.vids[aerial.cur];
      if (cv.readyState >= 2) paintFrost(cv);
    }, 1500);
  }
  function setBackground(mode) {
    prefs.background = mode; savePrefs();
    aerial.on = mode === "aerial";
    document.body.classList.toggle("aerial", aerial.on);
    heroItem = null;
    render();
  }

  function followFocus() {
    clearInterval(rotateTimer);
    if (window.Settings && window.Settings.isOpen()) return;
    aerialActive(aerial.on && st.zone === "dock");
    if (st.zone === "rows") { var r = rows[st.row]; setHero(r.items[r.col]); return; }
    if (aerial.on) return;
    // Coming back down from the featured show (or moving along the dock): keep what is showing and
    // carry on from it, instead of jumping to the first show as the focus moves.
    var pool = heroPool(), here = -1;
    for (var i = 0; i < pool.length && heroItem; i++) if (pool[i] === heroItem || (pool[i].id && pool[i].id === heroItem.id)) { here = i; break; }
    if (here >= 0) { rotateIdx = here; heroItem = pool[here]; } else { rotateIdx = 0; setHero(pool[0]); }
    rotateTimer = setInterval(function () { rotateIdx = (rotateIdx + 1) % pool.length; setHero(pool[rotateIdx]); }, TV ? 15000 : 9000);
  }

  var moving = false;
  function shiftApp(dx) {
    var to = st.dock + dx;
    if (to < 0 || to >= apps.length - 1) return;      // Settings stays last
    var a = apps.splice(st.dock, 1)[0]; apps.splice(to, 0, a);
    st.dock = to;
    prefs.appOrder = apps.filter(function (x) { return x.package !== "settings"; }).map(function (x) { return x.package; });
    savePrefs(); buildDock(); tiles[st.dock].classList.add("moving"); render();
  }
  function endMove() { moving = false; tiles.forEach(function (t) { t.classList.remove("moving"); }); }

  function move(dx, dy) {
    if (moving) { if (dx) shiftApp(dx); return; }
    if (st.zone === "top") {
      if (dx && weather) st.top = dx > 0 ? 1 : 0;   // the gear, then the weather beside the clock
      if (dy > 0) st.zone = !aerial.on && heroItem ? "hero" : "dock";
      render(); return;
    }
    if (st.zone === "hero") {
      if (dy > 0) st.zone = "dock";
      if (dy < 0) st.zone = "top";
      render(); return;
    }
    if (st.zone === "dock") {
      if (dy < 0) { st.zone = !aerial.on && heroItem ? "hero" : "top"; render(); return; }
      if (dx) st.dock = Math.max(0, Math.min(apps.length - 1, st.dock + dx));
      if (dy > 0 && rows.length) { st.zone = "rows"; st.row = 0; }
    } else {
      var r = rows[st.row];
      if (dx) r.col = Math.max(0, Math.min(r.cards.length - 1, r.col + dx));
      if (dy < 0) { if (st.row === 0) st.zone = "dock"; else st.row--; }
      if (dy > 0 && st.row < rows.length - 1) st.row++;
    }
    render();
  }

  var busyUntil = 0;
  function activate() {
    if (Date.now() < busyUntil) return;
    busyUntil = Date.now() + 2500;
    if (st.zone === "hero") { openShow(heroItem); return; }
    if (st.zone === "top" && st.top === 1) { busyUntil = 0; return; }   // the forecast is already open
    if (st.zone === "top") { busyUntil = 0; clearInterval(rotateTimer); aerialActive(false); window.Settings.open(); return; }
    if (st.zone === "dock") {
      var a = apps[st.dock];
      if (a.package === "settings") { busyUntil = 0; clearInterval(rotateTimer); aerialActive(false); window.Settings.open(); }
      else if (TV) TV.launch(a.package);
      else toast("Opening " + a.name);
      return;
    }
    openShow(rows[st.row].items[rows[st.row].col]);
  }
  function openShow(it) {
    if (!it) return;
    if (it.kind === "live") {
      toast("Opening " + (APP_NAME[it.opens] || "the app") + " · " + it.channel.replace(/ HR$/, ""));
      if (TV) TV.launch(it.opens);
      return;
    }
    // Netflix when TMDB lists it there for Croatia, Stremio otherwise; an app's own card opens in that app.
    toast("Opening “" + it.title + "” in " + srcName(it));
    if (!TV) return;
    var wn = it.kind === "watchnext";
    fetch(wn ? "/api/watchnext/open" : "/api/open-show", { method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify(wn ? { id: it.id } : it) })
      .then(function (r) { return r.json(); })
      .then(function (d) { if (!d.ok) toast(d.error || "Could not open it"); }, function () { toast("Could not reach the TV app"); });
  }

  function back() { if (moving) { endMove(); return; } if (st.zone !== "dock") { st.zone = "dock"; render(); } }

  function pointer(zone, ri, ci) {
    var was = zone === st.zone && (zone === "dock" ? st.dock === ci : st.row === ri && rows[ri].col === ci);
    st.zone = zone;
    if (zone === "dock") st.dock = ci; else { st.row = ri; rows[ri].col = ci; }
    render();
    if (was) activate();
  }

  var hold = { timer: null, used: false, down: false };
  function holdMenu() {
    if (st.zone === "dock") {
      var a = apps[st.dock];
      if (a.package === "settings") return false;
      Menu.open(a.name, [
        { label: "Move", act: function () { moving = true; tiles[st.dock].classList.add("moving"); toast("Left and right to move, OK when done"); } },
        { label: "Hide from Home", act: function () { prefs.hidden.push(a.package); savePrefs(); buildDock(); render(); toast(a.name + " is hidden. Settings, Apps brings it back."); } }
      ]);
      return true;
    }
    var r = rows[st.row];
    if (st.zone !== "rows" || !r || r.id !== "continue") return false;
    var it = r.items[r.col];
    Menu.open(it.title, [{ label: "Remove from Continue Watching", act: function () { removeContinue(it); } }]);
    return true;
  }
  function removeContinue(it) {
    var row = DATA.rows.filter(function (r) { return r.id === "continue"; })[0];
    row.items = row.items.filter(function (x) { return x.id !== it.id; });
    if (TV) fetch("/api/continue/remove", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ id: it.id, at: it.at || 0 }) });
    heroItem = null; buildRows(); render();
    toast("Removed “" + it.title + "”");
  }
  addEventListener("keyup", function (e) {
    if (e.key !== "Enter" || !hold.down) return;
    if (moving && !hold.used) { hold.down = false; clearTimeout(hold.timer); endMove(); return; }
    hold.down = false; clearTimeout(hold.timer);
    if (!hold.used && !(window.Settings && window.Settings.isOpen()) && !Menu.isOpen()) activate();
  });
  addEventListener("keydown", function (e) {
    var k = e.key;
    if (Menu.isOpen()) { if (e.repeat || hold.down) { e.preventDefault(); return; } if (Menu.key(k)) e.preventDefault(); return; }
    if (window.Settings && window.Settings.isOpen()) { if (window.Settings.key(k)) e.preventDefault(); return; }
    if (k === "Enter") {
      e.preventDefault();
      if (e.repeat || hold.down) return;
      hold.down = true; hold.used = false;
      hold.timer = setTimeout(function () { if (hold.down) hold.used = holdMenu(); }, 650);
      return;
    }
    if (k === "ArrowLeft") move(-1, 0);
    else if (k === "ArrowRight") move(1, 0);
    else if (k === "ArrowUp") move(0, -1);
    else if (k === "ArrowDown") move(0, 1);
    else if (k === " ") activate();
    else if (k === "Escape" || k === "Backspace" || k === "GoBack" || k === "BrowserBack") back();
    else if (k === "b" || k === "B") setBackground(aerial.on ? "shows" : "aerial");   // preview only; Settings will hold this
    else return;
    e.preventDefault();
  });

  var wheelLock = 0;
  addEventListener("wheel", function (e) {
    if (Date.now() < wheelLock || Math.abs(e.deltaY) < 12) return;
    wheelLock = Date.now() + 380; move(0, e.deltaY > 0 ? 1 : -1);
  }, { passive: true });

  // ---------------------------------------------------------------- weather (Open-Meteo, from the TV app), beside the clock

  // Drawn for HomeTV in the gear's language: 72 grid, 4px round strokes. The cloud is one shape reused.
  var CLOUD = "M24 50H50A10 10 0 0 0 50 30A14 14 0 0 0 23.5 27A11.5 11.5 0 0 0 24 50Z";
  function rays(cx, cy, r1, r2, from, to) {
    var d = "";
    for (var a = from; a <= to; a += 45) {
      var t = a * Math.PI / 180;
      d += "M" + (cx + r1 * Math.cos(t)).toFixed(1) + " " + (cy + r1 * Math.sin(t)).toFixed(1) +
        "L" + (cx + r2 * Math.cos(t)).toFixed(1) + " " + (cy + r2 * Math.sin(t)).toFixed(1);
    }
    return '<path d="' + d + '"/>';
  }
  var MOON = '<path d="M47 45.5A17 17 0 0 1 30.5 19 18.5 18.5 0 1 0 53 41.5 17 17 0 0 1 47 45.5Z"/>';
  function glyph(code, day) {
    var svg = function (body) {
      return '<svg viewBox="0 0 72 72" fill="none" stroke="currentColor" stroke-width="4" stroke-linecap="round" stroke-linejoin="round">' + body + "</svg>";
    };
    var cloudUp = '<path d="' + CLOUD + '" transform="translate(0 -7)"/>';
    if (code <= 1) return svg(day ? '<circle cx="36" cy="36" r="10"/>' + rays(36, 36, 17, 23, 0, 315)
      : '<g transform="translate(-6 -2)">' + MOON + "</g>");
    if (code === 2) {
      // The sun or moon sits behind the cloud: a mask keeps its strokes out of the cloud.
      var behind = day ? '<circle cx="27" cy="27" r="8"/>' + rays(27, 27, 13.5, 18.5, 135, 315) : '<g transform="translate(-14 -12) scale(0.8)">' + MOON + "</g>";
      return svg('<mask id="wxm' + code + day + '"><rect width="72" height="72" fill="#fff"/><path d="' + CLOUD +
        '" transform="translate(5 6)" fill="#000" stroke="#000" stroke-width="10"/></mask><g mask="url(#wxm' + code + day + ')">' + behind +
        '</g><path d="' + CLOUD + '" transform="translate(5 6)"/>');
    }
    if (code === 3) return svg('<path d="' + CLOUD + '" transform="translate(0 -2)"/>');
    if (code === 45 || code === 48) return svg('<path d="M16 26h40M22 36h34M16 46h32M28 56h18"/>');
    if (code >= 95) return svg(cloudUp + '<path d="M38 47l-6 10h9l-6 10"/>');
    if ((code >= 71 && code <= 77) || code === 85 || code === 86)
      return svg(cloudUp + '<path d="M25 54h.01M36 59h.01M47 54h.01M30 64h.01M42 64h.01" stroke-width="5"/>');
    return svg(cloudUp + '<path d="M27 51l-3 9M38 51l-3 9M49 51l-3 9"/>');   // drizzle, rain, showers
  }

  var weather = null;
  // The preview has no TV app: a sample day, so the layout can be seen.
  var SAMPLE_WX = { set: true, place: "Sample", temp: 14, code: 2, text: "Partly cloudy", is_day: true, high: 17, low: 8,
    hours: [[ "15:00", 15, 2, 1 ], ["16:00", 15, 3, 1], ["17:00", 13, 61, 1], ["18:00", 12, 61, 0], ["19:00", 11, 3, 0], ["20:00", 10, 0, 0]]
      .map(function (h) { return { time: h[0], temp: h[1], code: h[2], text: "", is_day: !!h[3] }; }) };
  function showWeather(w) {
    weather = w && w.set && prefs.weather ? w : null;
    $("#wx").hidden = !weather;
    $("#clock").classList.toggle("focusable", !!weather);
    if (!weather) { if (st.zone === "top" && st.top === 1) { st.top = 0; render(); } return; }
    $("#wx-glyph").innerHTML = glyph(w.code, w.is_day);
    $("#wx-temp").textContent = w.temp + "°";
    $("#fc-place").textContent = w.place;
    $("#fc-now").textContent = [w.text, w.high != null ? "High " + w.high + "°" : "", w.low != null ? "Low " + w.low + "°" : ""]
      .filter(Boolean).join("  ·  ");
    var hours = $("#fc-hours"); hours.textContent = "";
    (w.hours || []).slice(0, 6).forEach(function (h) {
      var c = document.createElement("div"); c.className = "fc-hour";
      c.innerHTML = '<span class="t"></span>' + glyph(h.code, h.is_day) + '<span class="v"></span>';
      c.querySelector(".t").textContent = h.time.replace(/^0/, "");
      c.querySelector(".v").textContent = h.temp + "°";
      hours.appendChild(c);
    });
    placeFrost();
  }
  function refreshWeather() {
    if (!prefs.weather) { showWeather(null); return; }
    if (!TV) { showWeather(SAMPLE_WX); return; }
    fetch("/api/weather").then(function (r) { return r.json(); }).then(function (d) { showWeather(d.ok ? d : null); }, function () {});
  }
  setInterval(refreshWeather, 30 * 60e3);

  // ---------------------------------------------------------------- clock + toast

  function tick() {
    var d = new Date(), h = d.getHours(), m = String(d.getMinutes()).padStart(2, "0");
    $("#clock-time").textContent = prefs.clock === "12h" ? ((h % 12) || 12) + ":" + m + (h < 12 ? " AM" : " PM") : h + ":" + m;
  }
  tick(); setInterval(tick, 10000);

  var toastTimer = null;
  function toast(msg) {
    var t = $("#toast"); t.textContent = msg; t.classList.add("on");
    clearTimeout(toastTimer); toastTimer = setTimeout(function () { t.classList.remove("on"); }, 1900);
  }

  window.Home = {
    prefs: prefs, apps: DATA.apps, aerialCount: function (cat) { return AERIALS.filter(function (a) { return a.cat === cat; }).length; },
    save: function () { savePrefs(); tick(); refreshWeather(); },
    weather: function () { return weather; },
    setBackground: function (mode) { setBackground(mode); clearInterval(rotateTimer); aerialActive(false); },
    newAerial: function () { if (aerial.on && aerial.clip && prefs.aerialCats.indexOf(aerial.clip.cat) < 0) aerial.clip = null; },
    rebuildDock: function () { buildDock(); render(); clearInterval(rotateTimer); aerialActive(false); },
    rebuildRows: function () { heroItem = null; buildRows(); render(); clearInterval(rotateTimer); aerialActive(false); },
    genres: function () {
      var seen = {};
      DATA.rows.forEach(function (r) { r.items.forEach(function (it) { (it.genres || []).forEach(function (g) { seen[g] = (seen[g] || 0) + 1; }); }); });
      prefs.hiddenGenres.forEach(function (g) { seen[g] = seen[g] || 0; });
      return Object.keys(seen).sort();
    },
    closed: function () { followFocus(); },
    toast: toast
  };

  // What the TV app calls: the remote's Back, the Home button, and coming back to the home screen.
  var loadedAt = Date.now();
  window.tv = {
    back: function () { if (window.Settings && window.Settings.isOpen()) window.Settings.key("Escape"); else back(); },
    home: function () {
      while (window.Settings && window.Settings.isOpen()) window.Settings.key("Escape");
      st.zone = "dock"; st.dock = 0; render();
    },
    // Android says memory is low: forget remembered pictures; what is on screen stays.
    lowMemory: function () { cache = {}; cacheOrder = []; },
    resume: function () {
      if (Date.now() - loadedAt > 3 * 3600e3) { location.reload(); return; }   // fresh shows a few times a day
      followFocus();
      refreshContinue();
      refreshWeather();
    }
  };
  // Back from an app: what it says you are in the middle of has probably changed.
  function refreshContinue() {
    if (!TV) return;
    fetch("/api/continue-row").then(function (r) { return r.json(); }).then(function (d) {
      if (!d.ok || !d.row) return;
      var old = DATA.rows.filter(function (r) { return r.id === "continue"; })[0];
      if (old && JSON.stringify(old.items) === JSON.stringify(d.row.items)) return;
      DATA.rows = DATA.rows.filter(function (r) { return r.id !== "continue"; });
      if (d.row.items.length) DATA.rows.unshift(d.row);
      var keep = st.zone === "rows" && rows[st.row] ? rows[st.row].id : null;
      buildRows();
      if (keep && st.zone === "rows") { for (var i = 0; i < rows.length; i++) if (rows[i].id === keep) st.row = i; }
      if (st.zone === "rows" && !rows.length) st.zone = "dock";
      render();
    }, function () {});
  }

  addEventListener("visibilitychange", function () { if (document.hidden) { clearInterval(rotateTimer); aerialActive(false); } });

  $("#top-gear").insertAdjacentHTML("beforeend", GEAR.replace('stroke="#f6f3ee"', 'stroke="currentColor"'));
  $("#top-gear").addEventListener("click", function () { st.zone = "top"; render(); activate(); });
  buildDock();
  buildRows();
  placeFrost();
  refreshWeather();
  document.body.classList.toggle("aerial", aerial.on);
  render();
});
