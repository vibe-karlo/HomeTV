#!/bin/sh
# Builds the redesigned on-TV app (new home screen, settings, phone remote, aerials) into
# dist/couch-tv-next.apk. Uses the JDK and Android SDK in build/ (see the README's build notes),
# and your TMDB key from .env.local.
set -e
ROOT=$(cd "$(dirname "$0")/.." && pwd)
B="$ROOT/build"
export JAVA_HOME="${JAVA_HOME:-$B/jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$B/sdk}"

# Switzer (Fontshare, free licence that does not allow passing the files on), fetched once.
if [ ! -f "$ROOT/home-next/fonts/switzer-700.woff2" ] || [ ! -f "$ROOT/on-tv/android/app/src/main/res/font/switzer_semibold.ttf" ]; then
  T=$(mktemp -d)
  curl -fsSL -o "$T/switzer.zip" "https://api.fontshare.com/v2/fonts/download/switzer"
  unzip -q "$T/switzer.zip" -d "$T"
  W="$T/Switzer_Complete/Fonts/WEB/fonts"
  mkdir -p "$ROOT/home-next/fonts" "$ROOT/remote-next/fonts" "$ROOT/on-tv/android/app/src/main/res/font"
  for n in 400:Regular 500:Medium 600:Semibold 700:Bold; do cp "$W/Switzer-${n#*:}.woff2" "$ROOT/home-next/fonts/switzer-${n%%:*}.woff2"; done
  cp "$W/Switzer-Medium.woff2" "$ROOT/remote-next/fonts/switzer-500.woff2"
  cp "$W/Switzer-Semibold.woff2" "$ROOT/remote-next/fonts/switzer-600.woff2"
  cp "$W/Switzer-Medium.ttf" "$ROOT/on-tv/android/app/src/main/res/font/switzer_medium.ttf"
  cp "$W/Switzer-Semibold.ttf" "$ROOT/on-tv/android/app/src/main/res/font/switzer_semibold.ttf"
  rm -rf "$T"
fi

# The web pages, without the preview-only files.
rm -rf "$B/web" && mkdir -p "$B/web/tv" "$B/web/remote"
rsync -a --exclude 'preview-data.js' --exclude 'aerials.js' --exclude 'aerial-cache*' --exclude 'banners' \
  --exclude 'serve.py' "$ROOT/home-next/" "$B/web/tv/"
rsync -a "$ROOT/remote-next/" "$B/web/remote/"

# Apple's aerial catalog, for the TV app to download from.
python3 -I -c "
import json, sys
s = open(sys.argv[1]).read()
json.dump(json.loads(s[s.index('['):s.rindex(']') + 1]), open(sys.argv[2], 'w'), ensure_ascii=False)
" "$ROOT/home-next/aerials.js" "$ROOT/on-tv/android/app/src/main/assets/aerials.json"

[ -f "$ROOT/on-tv/android/local.properties" ] || echo "sdk.dir=$ANDROID_HOME" > "$ROOT/on-tv/android/local.properties"
"$ROOT/tv-home/android/gradlew" -p "$ROOT/on-tv/android" --no-daemon -q assembleRelease \
  "-PremoteDist=$B/web/remote" "-PtvHomeWeb=$B/web/tv"
mkdir -p "$ROOT/dist"
cp "$ROOT/on-tv/android/app/build/outputs/apk/release/app-release.apk" "$ROOT/dist/couch-tv-next.apk"
echo "Built: $ROOT/dist/couch-tv-next.apk"
