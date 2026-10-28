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
  window.HOME_READY = load(4).then(function (d) {
    window.HOME_DATA = { apps: apps, rows: (d.rows || []).filter(function (r) { return r.items && r.items.length; }),
      address: d.address, port: d.port, version: d.version, sport: d.sport || [], tvnow: d.tvnow || [] };
    window.AERIALS = d.aerials || [];
  });
})();
