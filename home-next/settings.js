window.HOME_READY.then(function () {
  "use strict";
  var H = window.Home, P = H.prefs, D = window.HOME_DATA, TV = window.TVOS;
  var address = (D.address || location.hostname) + ":" + (D.port || 5400);
  function post(path, body) {
    return fetch(path, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body || {}) }).then(function (r) { return r.json(); });
  }

  // Channels the Live TV slider can show (TvProfil's free daily guides), in the guide's order.
  var LIVE = ["HRT 1", "HRT 2", "HRT 3", "HRT 4", "RTL", "RTL 2", "Nova TV", "Doma TV", "HBO", "CineStar TV", "SciFi", "CMC",
    "Eurosport", "Sport Klub", "Arena Sport 1"];
  function liveChannels() { return P.tvnowChannels || ["RTL", "Nova TV"]; }

  // The rows the TV's apps publish, asked for when the page opens.
  var appRows = null, appRowsAllowed = true;
  var SAMPLE_ROWS = [{ key: "hr.telekom.android.maxtvp|Sljedeće", title: "MAXtv · Sljedeće", count: 12, shown: true },
    { key: "uc.flex.hr.smarttv|Preporučeno", title: "EON · Preporučeno", count: 10, shown: false },
    { key: "com.netflix.ninja|Netflix", title: "Netflix", count: 20, shown: false }];
  function loadAppRows() {
    var got = TV ? fetch("/api/app-rows").then(function (r) { return r.json(); }) : Promise.resolve({ ok: true, rows: SAMPLE_ROWS, allowed: true });
    got.then(function (d) { appRows = d.rows || []; appRowsAllowed = d.allowed !== false; if (openFlag) draw(); },
      function () { appRows = []; if (openFlag) draw(); });
  }

  // The TV's sources, asked for each time the page opens (a console can be switched on in between).
  var inputs = null;
  var SAMPLE_INPUTS = [
    { id: "h1", name: "HDMI 1", kind: "hdmi", state: "on", connected: true, device: "PlayStation 5", colour: "#0070d1" },
    { id: "h2", name: "HDMI 2", kind: "hdmi", state: "standby", connected: true, device: "Apple TV", colour: "#aeaeb2" },
    { id: "h3", name: "HDMI 3", kind: "hdmi", state: "empty", connected: false },
    { id: "h4", name: "HDMI 4", kind: "hdmi", state: "empty", connected: false },
    { id: "live", name: "Live TV", kind: "tuner", state: "on", connected: true }];
  function loadInputs() {
    var got = TV ? fetch("/api/inputs").then(function (r) { return r.json(); }) : Promise.resolve({ ok: true, inputs: SAMPLE_INPUTS });
    got.then(function (d) { inputs = d.inputs || []; if (openFlag) draw(); }, function () { inputs = []; if (openFlag) draw(); });
  }
  function connectedCount() {
    var n = inputs.filter(function (i) { return i.connected && i.kind !== "tuner"; }).length;
    return n ? n + " connected" : "";
  }
  var el = document.getElementById("settings"), list = document.getElementById("set-list");
  var $ = function (id) { return document.getElementById(id); };

  var CHEV = '<svg class="chev" viewBox="0 0 14 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><path d="M2.5 2.5 11.5 12l-9 9.5"/></svg>';
  var TICK = '<svg class="tick" viewBox="0 0 28 28" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 14.5 11.5 21 23 7.5"/></svg>';
  // Leaves HomeTV for another screen.
  var EXT = '<svg class="chev ext" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M6 14 14 6M7.5 6H14v6.5"/></svg>';
  var CATS = ["Landscapes", "Cities", "Space", "Underwater"];
  var CAT_COUNT = {};
  (window.AERIALS || []).forEach(function (a) { CAT_COUNT[a.cat] = (CAT_COUNT[a.cat] || 0) + 1; });
  var AFTER = [5, 10, 15, 30];
  var appName = function (pkg) { for (var i = 0; i < H.apps.length; i++) if (H.apps[i].package === pkg) return H.apps[i].name; return pkg; };
  var side = null;   // a page can put its own words in the side panel (the pairing code)

  // ---------------------------------------------------------------- pages

  var PAGES = {
    root: { title: "Settings", rows: function () {
      var wx = H.weather();
      return [
        { label: "Inputs", menu: "inputs", value: inputs ? connectedCount() : "",
          help: "Switch to HDMI or Live TV. Consoles and players show by the name they give themselves." },
        { label: "Background", value: P.background === "aerial" ? "Aerials" : "Shows",
          help: "What plays behind the home screen: artwork from popular shows, or Apple’s aerial films.",
          act: function () { H.setBackground(P.background === "aerial" ? "shows" : "aerial"); } },
        { label: "Aerial Places", menu: "places",
          value: P.aerialCats.length === CATS.length ? "All" : P.aerialCats.length + " of " + CATS.length,
          help: "Which aerials play, on the home screen and as the screensaver." },
        { label: "Screensaver", value: P.screensaver ? "On" : "Off",
          help: "Aerials with the time, when the TV has been idle for a while.",
          act: function () { P.screensaver = !P.screensaver; H.save(); } },
        { label: "Start After", value: P.screensaverAfter + " minutes", off: !P.screensaver,
          help: "How long the TV waits before the screensaver starts.",
          act: function () { P.screensaverAfter = AFTER[(AFTER.indexOf(P.screensaverAfter) + 1) % AFTER.length]; H.save(); } },
        { label: "Clock", value: P.clock === "12h" ? "12-hour" : "24-hour",
          help: "How the time reads in the corner of the home screen.",
          act: function () { P.clock = P.clock === "12h" ? "24h" : "12h"; H.save(); } },
        { label: "Weather", value: !P.weather ? "Off" : wx ? wx.place : TV ? "No place set" : "On",
          help: P.weather && TV && !wx ? "Choose your town on the setup page, " + address + "/setup, and the weather shows beside the clock."
            : "The temperature beside the clock. Move onto it for the next hours. The place is set on " + address + "/setup.",
          act: function () { P.weather = !P.weather; H.save(); } },
        { label: "Live Sport", value: P.sport ? "On" : "Off",
          help: "F1 and NK Osijek broadcasts at the top of the home screen. MAXSport and Arena Sport open MAXtv, other channels open EON.",
          act: function () { P.sport = !P.sport; H.save(); H.rebuildRows(); } },
        { label: "Live TV", menu: "live", value: P.tvnow ? liveChannels().length + " channels" : "Off",
          help: "What\u2019s on now on the channels you pick, in the slider at the top. From TvProfil\u2019s free guide; opens EON." },
        { label: "App Rows", menu: "approws", value: appRows ? appRows.filter(function (r) { return r.shown; }).length + " shown" : "",
          help: "The rows your apps put on Android TV\u2019s home screen, like MAXtv\u2019s Sljede\u0107e, with their own pictures." },
        { label: "Hidden Genres", menu: "genres",
          value: P.hiddenGenres.length ? P.hiddenGenres.join(", ") : "None",
          help: "Shows in these genres stay out of the suggestions. Continue Watching keeps everything." },
        { label: "Apps", menu: "apps", value: (H.apps.length - P.hidden.length) + " on Home",
          help: "Which apps sit in the dock. Hidden apps stay installed." },
        { label: "Remote Buttons", menu: "buttons",
          help: "What the Philips and Rakuten TV buttons on your remote open." },
        { label: "Phone Remote", menu: "phone", help: "Use your phone as the remote, with a keyboard for typing." },
        { label: "TV Settings", ext: true, help: "The TV’s own settings: picture, sound, network, inputs.",
          act: function () { if (TV) TV.openSettings(); else H.toast("Opens the TV’s settings"); } },
        { label: "About", menu: "about", help: "This TV and where the home screen gets its shows." }
      ];
    } },

    inputs: { title: "Inputs", enter: loadInputs, rows: function () {
      if (!inputs) return [{ label: "Looking for sources", help: "Asking the TV what is plugged in." }];
      return inputs.map(function (i) {
        var state = i.kind === "tuner" ? "" : i.state === "on" ? "On" : i.state === "standby" ? "Standby" : "Nothing plugged in";
        return { label: i.device || i.name, value: i.device ? i.name + " · " + state : state, off: !i.connected, dot: i.connected && i.kind !== "tuner" ? i.colour || "#30d158" : "transparent",   // every row keeps the slot, so names line up
          help: i.kind === "tuner" ? "The TV’s own channels." : i.device ? i.device + " on " + i.name + ". Select to switch to it."
            : i.connected ? "Select to switch to " + i.name + "." : "Nothing is plugged into " + i.name + ", or it is switched off at the wall.",
          act: function () {
            H.toast("Switching to " + (i.device || i.name));
            if (!TV) return;
            post("/api/inputs/open", { id: i.id }).then(function (d) { if (!d.ok) H.toast(d.error || "The TV would not switch"); },
              function () { H.toast("Could not reach the TV app"); });
          } };
      });
    } },

    live: { title: "Live TV", rows: function () {
      var on = liveChannels();
      return [{ label: "In the Slider", value: P.tvnow ? "On" : "Off",
        help: "Show what\u2019s on now at the top of the home screen, between the shows.",
        act: function () { P.tvnow = !P.tvnow; H.save(); H.rebuildRows(); } }].concat(LIVE.map(function (c) {
        return { label: c, tick: on.indexOf(c) >= 0, off: !P.tvnow,
          help: on.indexOf(c) >= 0 ? "Select to take " + c + " out of the slider." : "Select to show what\u2019s on " + c + " now.",
          act: function () {
            var l = liveChannels().slice(), i = l.indexOf(c);
            if (i >= 0) l.splice(i, 1); else l.push(c);
            P.tvnowChannels = LIVE.filter(function (x) { return l.indexOf(x) >= 0; });   // keep the guide's order
            H.save(); H.toast(i >= 0 ? c + " is out of the slider" : c + " joins the slider within a minute");
          } };
      }));
    } },

    approws: { title: "App Rows", enter: loadAppRows, rows: function () {
      if (!appRows) return [{ label: "Looking for rows", help: "Asking the TV which rows its apps publish." }];
      if (!appRows.length) return [{ label: "No rows yet", help: appRowsAllowed
        ? "None of your apps has put a row on Android TV\u2019s home screen. Open MAXtv or EON once; they add theirs when they run."
        : "HomeTV needs the TV listings permission to see them. Connect debugging once in setup, or allow it when the TV asks." }];
      return appRows.map(function (r) {
        return { label: r.title, tick: r.shown, value: r.count + (r.count === 1 ? " item" : " items"),
          help: r.shown ? "Shown under Continue Watching. Select to hide it." : "Select to show it under Continue Watching.",
          act: function () {
            r.shown = !r.shown;
            P.appRows = appRows.filter(function (x) { return x.shown; }).map(function (x) { return x.key; });
            H.save(); H.refreshRows();
          } };
      });
    } },

    places: { title: "Aerial Places", rows: function () {
      return CATS.map(function (c) {
        return { label: c, tick: P.aerialCats.indexOf(c) >= 0, value: (CAT_COUNT[c] || 0) + " films",
          help: "Tap to include or leave out " + c.toLowerCase() + ". At least one place stays on.",
          act: function () {
            var i = P.aerialCats.indexOf(c);
            if (i >= 0 && P.aerialCats.length === 1) { H.toast("Keep at least one place"); return; }
            if (i >= 0) P.aerialCats.splice(i, 1); else P.aerialCats.push(c);
            H.save(); H.newAerial();
          } };
      });
    } },

    genres: { title: "Hidden Genres", rows: function () {
      return H.genres().map(function (g) {
        var hidden = P.hiddenGenres.indexOf(g) >= 0;
        return { label: g, value: hidden ? "Hidden" : "Shown", off: hidden,
          help: hidden ? g + " stays out of the suggestions. Select to show it." : "Select to keep " + g.toLowerCase() + " out of the suggestions.",
          act: function () {
            var i = P.hiddenGenres.indexOf(g);
            if (i >= 0) P.hiddenGenres.splice(i, 1); else P.hiddenGenres.push(g);
            H.save(); H.rebuildRows();
          } };
      });
    } },

    apps: { title: "Apps", rows: function () {
      return H.apps.map(function (a) {
        var hidden = P.hidden.indexOf(a.package) >= 0;
        return { label: a.name, value: hidden ? "Hidden" : "On Home", off: hidden,
          help: hidden ? a.name + " is hidden. Select to put it back in the dock." : a.name + " is in the dock. Select to hide it.",
          act: function () {
            var i = P.hidden.indexOf(a.package);
            if (i >= 0) P.hidden.splice(i, 1); else P.hidden.push(a.package);
            H.save(); H.rebuildDock();
          } };
      });
    } },

    buttons: { title: "Remote Buttons", rows: function () {
      return [
        { label: "Philips Button", menu: "pick-philips", value: appName(P.philipsButton), help: "The button with the Philips logo." },
        { label: "Rakuten TV Button", menu: "pick-rakuten", value: appName(P.rakutenButton), help: "The button labelled Rakuten TV." }
      ];
    } },

    "pick-philips": picker("Philips Button", "philipsButton"),
    "pick-rakuten": picker("Rakuten TV Button", "rakutenButton"),

    phone: { title: "Phone Remote", rows: function () {
      return [
        { label: "Pair a Phone", help: "Shows a code for a new phone.",
          act: function () {
            var show = function (code) {
              side = { name: code.slice(0, 3) + " " + code.slice(3),
                help: "On your phone, open " + address + " and type this code. It works once, for 3 minutes." };
              draw();
            };
            if (TV) post("/api/pair/new").then(function (d) { if (d.pin) show(d.pin); }, function () { H.toast("Could not reach the TV app"); });
            else show(String(100000 + Math.floor(Math.random() * 900000)));
          } },
        { label: "Forget All Phones", help: "Signs every phone out. Each one pairs again with a new code.",
          act: function () {
            if (!TV) { H.toast("All phones signed out"); return; }
            post("/api/phones/forget").then(function () { H.toast("All phones signed out"); }, function () { H.toast("Could not reach the TV app"); });
          } }
      ];
    } },

    about: { title: "About", rows: function () {
      return [
        { label: "TV", value: "Philips 70PUS7304", help: "Android TV 12." },
        { label: "Phone Remote", value: address, help: "Open this address on a phone at home to use it as the remote." },
        { label: "Shows", value: "TMDB, Stremio", help: "Popular on Netflix comes from TMDB for Croatia. Trending comes from Stremio." },
        { label: "Live Sport", value: "TvProfil", help: "Live sport times from TvProfil’s free guide for home use (tvprofil.net/xmltv)." },
        { label: "Photos", value: "Yours, Apple",
          help: "The F1 and Opus Arena pictures are the ones you chose, for this TV only. Aerials are Apple’s." },
        { label: "Version", value: D.version || "2.0 preview", help: "HomeTV." }
      ];
    } }
  };

  function picker(title, key) {
    return { title: title, rows: function () {
      return H.apps.map(function (a) {
        return { label: a.name, tick: P[key] === a.package, help: "Press the button to open " + a.name + ".",
          act: function () { P[key] = a.package; H.save(); back(); } };
      });
    } };
  }

  // ---------------------------------------------------------------- state + drawing

  var stack = [], rows = [];
  var ROW = 94, VISIBLE = 9;

  function page() { return stack[stack.length - 1]; }

  function draw(slide) {
    var pg = page(), def = PAGES[pg.id];
    rows = def.rows();
    pg.focus = Math.max(0, Math.min(pg.focus, rows.length - 1));
    list.innerHTML = "";
    rows.forEach(function (r, i) {
      var li = document.createElement("li");
      li.className = "set-row" + (r.off ? " off" : "") + (i === pg.focus ? " focus" : "");
      li.innerHTML = (r.dot ? '<span class="dot"></span>' : "") + '<span class="label"></span>' + (r.value ? '<span class="value"></span>' : "") +
        // A row that can be ticked keeps the tick's space when it is not, so the values line up.
        ("tick" in r ? (r.tick ? TICK : TICK.replace('class="tick"', 'class="tick" style="visibility:hidden"')) : "") +
        (r.menu ? CHEV : "") + (r.ext ? EXT : "");
      li.querySelector(".label").textContent = r.label;
      if (r.dot) li.querySelector(".dot").style.background = r.dot;
      if (r.value) li.querySelector(".value").textContent = r.value;
      li.addEventListener("click", function () { if (pg.focus === i) select(); else { pg.focus = i; side = null; draw(); } });
      list.appendChild(li);
    });
    var top = rows.length > VISIBLE ? Math.max(0, Math.min(pg.focus - 3, rows.length - VISIBLE)) : 0;
    list.style.transform = "translateY(" + -top * ROW + "px)";
    if (slide) { list.classList.remove("slide"); void list.offsetWidth; list.classList.add("slide"); }

    $("set-crumb").textContent = stack.map(function (p) { return PAGES[p.id].title; }).join("  ›  ");
    var r = rows[pg.focus];
    $("set-name").textContent = side ? side.name : r.label;
    $("set-help").textContent = side ? side.help : r.help || "";
  }

  function select() {
    var r = rows[page().focus];
    if (r.menu) { stack.push({ id: r.menu, focus: 0 }); side = null; if (PAGES[r.menu].enter) PAGES[r.menu].enter(); draw(true); return; }
    if (r.act) { r.act(); draw(); }
  }

  function back() {
    side = null;
    if (stack.length > 1) { stack.pop(); draw(true); } else close();
  }

  var openFlag = false;
  function open() {
    stack = [{ id: "root", focus: 0 }]; side = null;
    loadInputs(); loadAppRows();
    el.hidden = false; void el.offsetWidth; el.classList.add("open"); openFlag = true;
    draw();
  }
  function close() {
    el.classList.remove("open"); openFlag = false;
    setTimeout(function () { if (!openFlag) el.hidden = true; }, 420);
    H.closed();
  }

  window.Settings = {
    open: open,
    isOpen: function () { return openFlag; },
    key: function (k) {
      var pg = page();
      if (k === "ArrowUp") { if (pg.focus > 0) { pg.focus--; side = null; draw(); } }
      else if (k === "ArrowDown") { if (pg.focus < rows.length - 1) { pg.focus++; side = null; draw(); } }
      else if (k === "Enter" || k === " ") select();
      else if (k === "ArrowRight") { if (rows[pg.focus].menu) select(); }
      else if (k === "ArrowLeft" || k === "Escape" || k === "Backspace" || k === "GoBack" || k === "BrowserBack") back();
      else return false;
      return true;
    }
  };
});
