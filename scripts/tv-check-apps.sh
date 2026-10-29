#!/bin/sh
# What the TV's apps give a home screen, read only (changes nothing on the TV):
#   TV_IP=<tv address> sh scripts/tv-check-apps.sh [package ...]
# For each app (MAXtv and EON unless you name others): the rows it puts on Android TV's home screen,
# its Watch Next entries, whether it is a live TV source (a TvInputService), and the links it opens.
# Then whether HomeTV itself can read the rows. Uses tv-home/tools/tvadb.py.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PY="$ROOT/.venv/bin/python"
[ -n "$TV_IP" ] || { echo "Set TV_IP to the TV's address."; exit 1; }
export TV_IP
tv() { "$PY" "$ROOT/tv-home/tools/tvadb.py" shell "$1"; }
# The ADB shell itself may not read other apps' rows on this TV; say so in one line instead of a stack trace.
query() {
  out=$(tv "content query --uri $1 --projection $2 --where \"package_name='$3'\" 2>&1")
  case "$out" in
    *SecurityException*) echo "     the ADB shell is not allowed to read these (HomeTV can, once granted)";;
    *"No result found"*|"") echo "     none";;
    *) echo "$out" | sed 's/^/     /';;
  esac
}
[ $# -gt 0 ] || set -- hr.telekom.android.maxtvp uc.flex.hr.smarttv

"$PY" "$ROOT/tv-home/tools/tvadb.py" ping >/dev/null
for P in "$@"; do
  echo "== $P"
  if ! tv "pm path $P" | grep -q package:; then echo "   not installed"; continue; fi
  echo "   version: $(tv "dumpsys package $P | grep versionName" | head -1 | tr -d ' ')"
  echo "   home-screen rows (preview channels):"
  query "content://android.media.tv/channel" "_id:display_name:type:browsable" "$P"
  echo "   Watch Next entries:"
  query "content://android.media.tv/watch_next_program" "title:watch_next_type" "$P"
  echo "   live TV source:"
  src=$(tv "pm query-services --components -a android.media.tv.TvInputService" | grep "$P" || true)
  [ -n "$src" ] && echo "$src" | sed 's/^/     /' || echo "     none"
  echo "   links it opens (schemes and hosts):"
  tv "dumpsys package $P" | grep -E "Scheme: |Authority: " | sort -u | head -20 | sed 's/^/     /' || true
done

echo "== HomeTV"
tv "dumpsys package dev.couchtv.ontv" | grep -q "READ_TV_LISTINGS: granted=true" && echo "   TV listings: granted" || echo "   TV listings: NOT granted (run scripts/tv-update.sh)"
echo "   Settings, App Rows on the TV lists every row HomeTV can read."
