# Notes for an AI agent working in this repo

HomeTV is an Android TV app: a home screen, a phone remote and an aerial screensaver, served by the
app itself on the TV. It started as couch-tv's on-TV mode; the computer bridge, the React remote and
the first home page were removed (they are in git history).

## Layout

- `on-tv/android/` the app (Kotlin). `Server.kt` is the local web server and its `/api/*`.
- `home-next/` the home screen page (and a browser preview: `preview-data.js`, `aerials.js`).
- `remote-next/` the phone remote page.
- `on-tv/web/setup.html` the setup page.
- `on-tv/debloat/` the list `scripts/debloat.py` switches off.
- `tv-home/tools/tvadb.py` ADB from a computer, in pure Python (needs `tv-home/tools/requirements.txt`).

## Build and install

```
sh scripts/build-next.sh                          # dist/couch-tv-next.apk (JDK and SDK in build/, see README)
TV_IP=<tv address> sh scripts/tv-update.sh         # install, grant permissions, back to home
TV_IP=<tv address> sh scripts/tv-check-apps.sh     # what MAXtv, EON or other apps give the home screen
TV_IP=<tv address> .venv/bin/python scripts/debloat.py plan|apply|undo|check
```

Check a change on the TV itself: screenshots (`screencap -p` through `tvadb.py shell`) and
`logcat -s couchtv`. The API needs a pairing token, so it cannot be called from a computer.

## Rules

- The TMDB key lives in `.env.local` (git-ignored) and goes into the APK. Never print or commit it.
- Keep private data out of the repo: the TV's address and MAC, names, personal photos. Scripts take
  the address from `TV_IP`. Run `scripts/privacy-scan.sh` before pushing to a public fork.
- Nothing is uninstalled from the TV: `debloat.py` only disables, and `undo` puts it back.
- If anything on the TV fails, stop and say what happened rather than switching more off.
