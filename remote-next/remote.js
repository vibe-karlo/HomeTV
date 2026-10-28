(function () {
  "use strict";
  var $ = function (id) { return document.getElementById(id); };

  // ---------------------------------------------------------------- icons (one family: 2px round strokes, 28 grid)

  var S = function (d, extra) {
    return '<svg viewBox="0 0 28 28" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"' +
      (extra || "") + ">" + d + "</svg>";
  };
  var ICON = {
    back: S('<path d="M17.5 7 10.5 14l7 7"/>'),
    home: S('<rect x="4" y="6" width="20" height="13" rx="2.5"/><path d="M10.5 23h7"/>'),
    play_pause: S('<path d="M5.5 8.2v11.6a.6.6 0 0 0 .9.5l8.6-5.8a.6.6 0 0 0 0-1L6.4 7.7a.6.6 0 0 0-.9.5z" fill="currentColor" stroke-width="1.2"/><path d="M19 8.5v11M23 8.5v11"/>'),
    mute: S('<path d="M5 11h3.5L13 7v14l-4.5-4H5z"/><path d="m18 11 5 6m0-6-5 6"/>'),
    down: S('<path d="M8 14h12"/>'),
    up: S('<path d="M8 14h12M14 8v12"/>'),
    power: S('<path d="M14 5v8"/><path d="M8.6 8.6a8 8 0 1 0 10.8 0"/>'),
    keyboard: S('<rect x="3.5" y="7.5" width="21" height="13" rx="3"/><path d="M8 11.5h.01M12 11.5h.01M16 11.5h.01M20 11.5h.01M9.5 16.5h9"/>'),
    apps: S('<rect x="4.5" y="4.5" width="8" height="8" rx="2"/><rect x="15.5" y="4.5" width="8" height="8" rx="2"/><rect x="4.5" y="15.5" width="8" height="8" rx="2"/><rect x="15.5" y="15.5" width="8" height="8" rx="2"/>')
  };
  document.querySelectorAll("#keys button").forEach(function (b) { b.innerHTML = ICON[b.dataset.key || b.dataset.vol]; });
  $("power").innerHTML = ICON.power;
  $("kbd-open").innerHTML = ICON.keyboard;
  $("apps-open").innerHTML = ICON.apps;

  // ---------------------------------------------------------------- haptics (iPhone: the hidden switch trick)

  var hLabel;
  function tick() {
    if (navigator.vibrate) { navigator.vibrate(8); return; }
    if (!hLabel) {
      var i = document.createElement("input"); i.type = "checkbox"; i.setAttribute("switch", ""); i.id = "hap";
      hLabel = document.createElement("label"); hLabel.htmlFor = "hap";
      [i, hLabel].forEach(function (e) { e.style.cssText = "position:fixed;left:-9999px;opacity:0;pointer-events:none"; e.setAttribute("aria-hidden", "true"); });
      document.body.append(i, hLabel);
    }
    hLabel.click();
  }

  // ---------------------------------------------------------------- API (the server inside the TV app)

  var MOCK = /[?&]mock/.test(location.search) || location.port === "4631";
  var token = null;
  try { token = localStorage.getItem("couch:token"); } catch (e) {}

  function call(method, path, body) {
    if (MOCK) return mock(method, path, body);
    var ctl = new AbortController(), t = setTimeout(function () { ctl.abort(); }, 10000);
    var headers = { "content-type": "application/json" };
    if (token) headers.authorization = "Bearer " + token;
    return fetch(path, { method: method, headers: headers, body: body ? JSON.stringify(body) : undefined, signal: ctl.signal })
      .then(function (r) {
        clearTimeout(t);
        return r.json().catch(function () { return {}; }).then(function (d) {
          if (r.status === 401 && path !== "/api/pair") { showPair(); throw new Error("pair"); }
          if (!r.ok || d.ok === false) throw new Error(d.error || "Something went wrong");
          return d;
        });
      });
  }
  // Keys and typing go out one at a time, in order, so fast swipes never arrive shuffled.
  var queue = Promise.resolve();
  function send(method, path, body) {
    queue = queue.then(function () { return call(method, path, body); }).catch(function (e) {
      if (e.message !== "pair") toast(e.message);
    });
    return queue;
  }
  // Keys, volume and typing go over the TV app's WebSocket: one open connection, in order, no per-press
  // HTTP. If the TV has not answered two presses yet, more arrow presses are dropped instead of piling
  // up and arriving in a burst (a burst into an app that is still opening is what froze Stremio).
  var ws = null, wsOpen = false, inFlight = 0, inFlightAt = 0;
  var MOVES = { up: 1, down: 1, left: 1, right: 1 };
  function wsSend(msg) {
    if (!wsOpen) return false;
    if (Date.now() - inFlightAt > 2500) inFlight = 0;          // never stay jammed on a lost answer
    if (msg.key && MOVES[msg.key] && inFlight >= 2) return true;
    inFlight++; inFlightAt = Date.now();
    ws.send(JSON.stringify(msg));
    return true;
  }
  var key = function (k, hold) {
    var m = hold ? { key: k, hold: true } : { key: k };
    if (!MOCK && wsSend(m)) return;
    send("POST", "/api/key", m);
  };
  var vol = function (a) { if (!MOCK && wsSend({ volume: a })) return; send("POST", "/api/volume", { action: a }); };
  var text = function (s) { if (!MOCK && wsSend({ text: s })) return; send("POST", "/api/text", { text: s }); };

  // ---------------------------------------------------------------- live state

  var state = { power: "on", keyboard: false };
  function onNow(n) {
    state.power = n.power;
    $("now-app").textContent = n.power === "off" ? "Philips TV" : (n.app && n.app.id !== "home" ? n.app.name : "Home");
    var p = n.playing, what = n.power === "off" ? "Off" : p && p.title ? p.title + (p.state === "paused" ? " · Paused" : "") : "Philips TV";
    $("now-what").textContent = what;
    $("power").classList.toggle("off", n.power === "off");
    if (n.keyboard && !state.keyboard) openKbd(true);
    if (!n.keyboard && state.keyboard && kbd.auto) closeKbd();
    state.keyboard = !!n.keyboard;
  }
  function listen() {
    if (MOCK) { onNow(mockNow); return; }
    ws = new WebSocket((location.protocol === "https:" ? "wss://" : "ws://") + location.host + "/api/ws?token=" + encodeURIComponent(token || ""));
    ws.onopen = function () { wsOpen = true; inFlight = 0; };
    ws.onmessage = function (e) {
      var m; try { m = JSON.parse(e.data); } catch (x) { return; }
      if (m.event === "now") { onNow(m); return; }
      inFlight = Math.max(0, inFlight - 1);
      if (m.ok === false && m.error) toast(m.error);
    };
    ws.onclose = function () {
      wsOpen = false; $("now-what").textContent = "Reconnecting";
      setTimeout(listen, 1500);
    };
  }
  // Back from the background (phone locked, app switched): reconnect at once.
  document.addEventListener("visibilitychange", function () {
    if (!document.hidden && !MOCK && token && (!ws || ws.readyState > 1)) listen();
  });

  // ---------------------------------------------------------------- touch pad

  var pad = $("pad"), dot = $("pad-touch");
  var g = null, FIRST = 34, NEXT = 46, HOLD = 560;
  try { if (localStorage.getItem("couch:padUsed")) pad.classList.add("used"); } catch (e) {}

  function place(e) {
    var r = pad.getBoundingClientRect();
    dot.style.left = e.clientX - r.left + "px"; dot.style.top = e.clientY - r.top + "px";
  }
  pad.addEventListener("pointerdown", function (e) {
    pad.setPointerCapture(e.pointerId);
    g = { x: e.clientX, y: e.clientY, ax: e.clientX, ay: e.clientY, t: e.timeStamp, moved: 0, held: false };
    place(e); pad.classList.add("touching");
    g.timer = setTimeout(function () { if (g && !g.moved) { g.held = true; pad.classList.add("pressed"); tick(); key("ok", true); } }, HOLD);
  });
  pad.addEventListener("pointermove", function (e) {
    if (!g) return;
    place(e);
    var dx = e.clientX - g.ax, dy = e.clientY - g.ay, step = g.moved ? NEXT : FIRST;
    if (Math.max(Math.abs(dx), Math.abs(dy)) < step) return;
    var dir = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? "right" : "left") : (dy > 0 ? "down" : "up");
    clearTimeout(g.timer);
    g.moved++; g.ax = e.clientX; g.ay = e.clientY;
    tick(); key(dir);
  });
  function end(e) {
    if (!g) return;
    clearTimeout(g.timer);
    var tap = !g.moved && !g.held && e.type === "pointerup" && e.timeStamp - g.t < 400;
    if (tap) { pad.classList.add("pressed"); tick(); key("ok"); }
    if (g.moved || tap) { pad.classList.add("used"); try { localStorage.setItem("couch:padUsed", "1"); } catch (x) {} }
    g = null;
    setTimeout(function () { pad.classList.remove("touching", "pressed"); }, tap ? 140 : 0);
  }
  pad.addEventListener("pointerup", end);
  pad.addEventListener("pointercancel", end);

  // ---------------------------------------------------------------- buttons (volume repeats while held)

  document.querySelectorAll("#keys button").forEach(function (b) {
    var rep = null;
    var fire = function () {
      tick();
      if (b.dataset.key) key(b.dataset.key);
      else vol(b.dataset.vol);
    };
    b.addEventListener("pointerdown", function () {
      fire();
      if (b.dataset.vol === "up" || b.dataset.vol === "down") {
        rep = setTimeout(function loop() { fire(); rep = setTimeout(loop, 160); }, 420);
      }
    });
    ["pointerup", "pointercancel", "pointerleave"].forEach(function (ev) { b.addEventListener(ev, function () { clearTimeout(rep); }); });
  });

  // Deep standby: the TV hears nothing but a Wake-on-LAN packet, which a web page cannot send. The
  // phone's "Turn TV On" shortcut can (Wake Me Up, then /api/wake), so the power button hands over to it.
  var SHORTCUT = "shortcuts://run-shortcut?name=" + encodeURIComponent("Turn TV On");
  function wakeByShortcut() { toast("Waking the TV"); location.href = SHORTCUT; }

  $("power").addEventListener("click", function () {
    tick();
    if (!MOCK && !wsOpen) { wakeByShortcut(); return; }
    if (state.power === "off") {
      // Switched off a short while ago the TV still answers, and HomeTV turns it back on.
      var gave = false, t = setTimeout(function () { gave = true; wakeByShortcut(); }, 3000);
      call("POST", "/api/power", { state: "on" }).then(function () { if (!gave) { clearTimeout(t); toast("TV on"); } },
        function () { if (!gave) { clearTimeout(t); wakeByShortcut(); } });
      return;
    }
    send("POST", "/api/power", { state: "off" }).then(function () { toast("TV off"); });
  });

  // ---------------------------------------------------------------- keyboard: what you type here appears on the TV

  var kbd = { el: $("kbd"), input: $("kbd-input"), sent: "", auto: false };
  function openKbd(auto) {
    kbd.auto = !!auto; kbd.sent = ""; kbd.input.value = "";
    kbd.el.classList.add("open"); kbd.el.setAttribute("aria-hidden", "false");
    $("kbd-title").textContent = auto ? "The TV is asking for text" : "Typing on the TV";
    // Android brings the keyboard up straight away; iPhone needs one tap on the field.
    kbd.input.focus({ preventScroll: true });
  }
  function closeKbd() {
    kbd.el.classList.remove("open"); kbd.el.setAttribute("aria-hidden", "true");
    kbd.input.blur(); kbd.auto = false;
  }
  kbd.input.addEventListener("input", function () {
    var now = kbd.input.value, old = kbd.sent, i = 0;
    while (i < now.length && i < old.length && now[i] === old[i]) i++;
    for (var d = old.length - i; d > 0; d--) key("DEL");
    if (now.length > i) text(now.slice(i));
    kbd.sent = now;
  });
  kbd.input.addEventListener("keydown", function (e) {
    if (e.key === "Enter") { e.preventDefault(); key("enter"); closeKbd(); }
  });
  $("kbd-done").addEventListener("click", function () { tick(); closeKbd(); });
  $("kbd-open").addEventListener("click", function () { tick(); openKbd(false); });
  kbd.el.addEventListener("click", function (e) { if (e.target === kbd.el) closeKbd(); });

  // ---------------------------------------------------------------- apps: the home screen's apps, in its order

  var appsEl = $("apps");
  function openApps() {
    appsEl.classList.add("open"); appsEl.setAttribute("aria-hidden", "false");
    var grid = $("apps-grid");
    var got = MOCK ? Promise.resolve({ apps: [{ package: "com.netflix.ninja", name: "Netflix" }, { package: "com.stremio.one", name: "Stremio" },
      { package: "com.google.android.youtube.tv", name: "YouTube" }, { package: "com.spotify.tv.android", name: "Spotify" }] }) : call("GET", "/api/tv-apps");
    got.then(function (d) {
      grid.innerHTML = "";
      d.apps.forEach(function (a) {
        var b = document.createElement("button"); b.className = "app";
        b.innerHTML = "<i></i><span></span>";
        if (a.banner) b.querySelector("i").style.backgroundImage = "url(" + a.banner + ")";
        b.querySelector("span").textContent = a.name;
        b.addEventListener("click", function () {
          tick(); closeApps();
          send("POST", "/api/launch", { app: a.package });
        });
        grid.appendChild(b);
      });
    }, function () { toast("Could not load the apps"); });
  }
  function closeApps() { appsEl.classList.remove("open"); appsEl.setAttribute("aria-hidden", "true"); }
  $("apps-open").addEventListener("click", function () { tick(); openApps(); });
  $("apps-done").addEventListener("click", function () { tick(); closeApps(); });
  appsEl.addEventListener("click", function (e) { if (e.target === appsEl) closeApps(); });

  // iPhone: with the keyboard up, Safari can scroll the page under fixed elements. Keep the typing
  // sheet on the part of the screen that is actually visible.
  if (window.visualViewport) {
    var pin = function () {
      kbd.el.style.top = visualViewport.offsetTop + "px";
      kbd.el.style.height = visualViewport.height + "px";
    };
    visualViewport.addEventListener("resize", pin);
    visualViewport.addEventListener("scroll", pin);
  }

  // ---------------------------------------------------------------- pairing

  var pairInput = $("pair-code"), boxes = document.querySelectorAll("#pair-boxes i");
  function showPair() {
    if (!$("pair").hidden) return;
    $("pair").hidden = false;
    send("POST", "/api/pair/start");
    setTimeout(function () { pairInput.focus(); }, 50);
    drawBoxes();
  }
  function drawBoxes() {
    var v = pairInput.value;
    boxes.forEach(function (b, i) { b.textContent = v[i] || ""; b.classList.toggle("cur", i === Math.min(v.length, 5)); });
  }
  $("pair-boxes").addEventListener("click", function () { pairInput.focus(); });
  pairInput.addEventListener("input", function () {
    pairInput.value = pairInput.value.replace(/\D/g, "").slice(0, 6); drawBoxes();
    if (pairInput.value.length < 6) { $("pair-msg").textContent = ""; return; }
    $("pair-msg").textContent = "Checking";
    call("POST", "/api/pair", { pin: pairInput.value, name: navigator.platform || "Phone" }).then(function (d) {
      token = d.token; try { localStorage.setItem("couch:token", token); } catch (e) {}
      $("pair").hidden = true; tick(); listen();
    }, function (e) { $("pair-msg").textContent = e.message; pairInput.value = ""; drawBoxes(); });
  });

  // ---------------------------------------------------------------- toast

  var toastT = null;
  function toast(msg) {
    var t = $("toast"); t.textContent = msg; t.classList.add("on");
    clearTimeout(toastT); toastT = setTimeout(function () { t.classList.remove("on"); }, 1800);
  }

  // ---------------------------------------------------------------- preview stand-in for the TV

  var mockNow = { power: "on", app: { id: "netflix", name: "Netflix" }, playing: { title: "The Gentlemen", state: "playing" }, keyboard: false };
  var mockLog = [];
  function mock(method, path, body) {
    mockLog.push(path + " " + JSON.stringify(body || {}));
    window.__sent = mockLog;
    if (path === "/api/power") { mockNow.power = "off"; setTimeout(function () { onNow(mockNow); }, 300); }
    return new Promise(function (res) { setTimeout(function () { res({ ok: true }); }, 30); });
  }
  if (MOCK) {
    // In the preview, K stands in for "a text field opened on the TV" (the TV app sends this for real).
    addEventListener("keyup", function (e) {   // keyup, so the K itself never lands in the field
      if (e.target === kbd.input) return;
      if (e.key === "k" || e.key === "K") { mockNow.keyboard = !mockNow.keyboard; onNow(mockNow); }
    });
  }

  if (!MOCK && !token) showPair(); else listen();
})();
