// Where the home screen's data comes from. On the TV (window.TVOS, the app's bridge): the TV's own
// apps, and rows and aerials from the TV app's server. In the preview: the files next to this one.
(function () {
  "use strict";
  // Dock order: these first when installed, then every other app by name.
  var FIRST = ["com.netflix.ninja", "com.stremio.one", "com.google.android.youtube.tv", "com.apple.atve.androidtv.appletv",
    "hr.hrt.hrti", "hr.telekom.android.maxtvp", "uc.flex.hr.smarttv", "com.spotify.tv.android", "org.droidtv.playtv",
    "org.videolan.vlc", "com.google.android.youtube.tvmusic", "com.android.vending"];
  var NAMES = { "com.android.vending": "Play Store", "org.droidtv.playtv": "Watch TV", "uc.flex.hr.smarttv": "EON",
    "hr.telekom.android.maxtvp": "MAXtv", "com.google.android.youtube.tv": "YouTube", "com.apple.atve.androidtv.appletv": "Apple TV" };

  function rank(pkg) { var i = FIRST.indexOf(pkg); return i < 0 ? 999 : i; }

  if (!window.TVOS) { window.HOME_READY = Promise.resolve(); return; }

  var apps = JSON.parse(window.TVOS.apps()).map(function (a) {
    return { package: a.package, name: NAMES[a.package] || a.label, banner: "http://tvos.invalid/banner/" + a.package };
  }).sort(function (a, b) { return rank(a.package) - rank(b.package) || a.name.localeCompare(b.name); });

  function load(tries) {
    return fetch("/api/home-data").then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
      .catch(function (e) {
        if (tries > 0) return new Promise(function (res) { setTimeout(res, 1500); }).then(function () { return load(tries - 1); });
        return { rows: [], aerials: [] };   // the dock still works with no shows
      });
  }
  function shape(d) {
    return { apps: apps, rows: (d.rows || []).filter(function (r) { return r.items && r.items.length; }),
      address: d.address, port: d.port, version: d.version, sport: d.sport || [], tvnow: d.tvnow || [] };
  }
  // The home screen as it was last time, kept in the page's own storage: drawn at once, then the fresh
  // data (a second or more away on a cold start) replaces it through window.HOME_FRESH.
  var last = null;
  try { last = JSON.parse(localStorage.getItem("couch:home") || "null"); } catch (e) {}
  var fresh = load(4).then(function (d) {
    if (d.rows && d.rows.length) try { localStorage.setItem("couch:home", JSON.stringify(d)); } catch (e) {}
    return d;
  });
  if (last && last.rows) {
    window.HOME_DATA = shape(last); window.AERIALS = last.aerials || [];
    window.HOME_READY = Promise.resolve();
    fresh.then(function (d) { if (d.rows && d.rows.length && window.HOME_FRESH) window.HOME_FRESH(shape(d), d.aerials || []); });
  } else {
    window.HOME_READY = fresh.then(function (d) { window.HOME_DATA = shape(d); window.AERIALS = d.aerials || []; });
  }
})();
