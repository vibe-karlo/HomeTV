#!/bin/sh
# Puts the latest HomeTV build on the TV and checks it has what it needs.
#   TV_IP=<tv address> sh scripts/tv-update.sh            install dist/couch-tv-next.apk
#   TV_IP=<tv address> sh scripts/tv-update.sh --debloat  then switch off the telemetry list too
# Build first with scripts/build-next.sh. Uses the pure Python ADB in tv-home/tools/tvadb.py, so a Mac
# needs no Local Network permission for Google's adb. The TV must be on, with USB debugging on.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PY="$ROOT/.venv/bin/python"
ADB="$ROOT/tv-home/tools/tvadb.py"
APK="$ROOT/dist/couch-tv-next.apk"
PKG=dev.couchtv.ontv
[ -n "$TV_IP" ] || { echo "Set TV_IP to the TV's address, for example: TV_IP=<tv address> sh scripts/tv-update.sh"; exit 1; }
[ -f "$APK" ] || { echo "No $APK yet: run sh scripts/build-next.sh first."; exit 1; }
if [ ! -x "$PY" ]; then
  python3 -m venv "$ROOT/.venv" && "$PY" -m pip install -q -r "$ROOT/tv-home/tools/requirements.txt"
fi
export TV_IP
tv() { "$PY" "$ADB" shell "$1"; }

echo "1. Reaching the TV at $TV_IP"
"$PY" "$ADB" ping

# The TV falls asleep in the middle of a long install if left alone; keep it awake until the end.
tv "svc power stayon true" >/dev/null
trap '"$PY" "$ADB" shell "svc power stayon false" >/dev/null 2>&1 || true' EXIT

echo "2. Installing $(basename "$APK") ($(du -h "$APK" | cut -f1 | tr -d ' '))"
# Big pushes stall on this kind of TV: copy it as base64 text, checked with md5 before installing.
FORCE_B64=1 "$PY" "$ADB" install "$APK"
echo "   on the TV: $(tv "dumpsys package $PKG | grep versionName" | head -1 | tr -d ' ')"

echo "3. Permissions for Continue Watching and now playing"
tv "pm grant $PKG android.permission.READ_TV_LISTINGS" >/dev/null 2>&1 || true
tv "cmd notification allow_listener $PKG/$PKG.MediaListener" >/dev/null 2>&1 || true
tv "dumpsys package $PKG" | grep -q "READ_TV_LISTINGS: granted=true" && echo "   TV listings: granted" || echo "   TV listings: NOT granted (Continue Watching shows only shows opened from HomeTV)"
tv "settings get secure enabled_notification_listeners" | grep -q "$PKG" && echo "   notification access: on" || echo "   notification access: OFF (the phone remote falls back to the slower reading)"

echo "4. Back to the home screen"
tv "input keyevent KEYCODE_HOME" >/dev/null

if [ "$1" = "--debloat" ]; then
  echo "5. Telemetry and promotions"
  "$PY" "$ROOT/scripts/debloat.py" plan
  "$PY" "$ROOT/scripts/debloat.py" apply
fi
echo "Done."
