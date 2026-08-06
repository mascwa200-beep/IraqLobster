#!/usr/bin/env bash
# Build the tricorder APK.
#
# No Gradle, no Android Gradle Plugin, no maven dependencies, no library graph —
# the same spirit as the app itself. This drives the SDK's own tools directly:
#
#   aapt2 compile -> aapt2 link -> javac -> d8 -> zipalign -> apksigner
#
# The web app is not duplicated: tricorder.html, sw.js and manifest.webmanifest
# are copied out of the repo root into the APK's assets at build time, so the
# single source of truth stays the single source of truth.
#
# Usage:
#   ANDROID_SDK=/path/to/sdk ./android/build.sh
#
# Optional:
#   BUILD_TOOLS=34.0.0   PLATFORM=android-34   OUT=<dir>   KEYSTORE=<file>
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

: "${ANDROID_SDK:?set ANDROID_SDK to your Android SDK root}"
# 35.0.0, not 34.0.0: the R8/d8 shipped in build-tools 34 crashes with an
# internal NullPointerException when it dexes anonymous inner classes under
# JDK 21. The 35 toolchain handles them.
BUILD_TOOLS="${BUILD_TOOLS:-35.0.0}"
PLATFORM="${PLATFORM:-android-34}"
BT="$ANDROID_SDK/build-tools/$BUILD_TOOLS"
ANDROID_JAR="$ANDROID_SDK/platforms/$PLATFORM/android.jar"
OUT="${OUT:-$HERE/build}"
KEYSTORE="${KEYSTORE:-$OUT/tricorder-sideload.keystore}"
STOREPASS="${STOREPASS:-tricorder}"
APK="$OUT/tricorder.apk"

[ -x "$BT/aapt2" ]      || { echo "missing $BT/aapt2"; exit 1; }
[ -f "$ANDROID_JAR" ]   || { echo "missing $ANDROID_JAR"; exit 1; }

echo "==> clean"
rm -rf "$OUT/assets" "$OUT/gen" "$OUT/classes" "$OUT/dex" "$OUT/res.zip" \
       "$OUT/base.apk" "$OUT/aligned.apk" "$APK"
mkdir -p "$OUT/assets" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> stage the web app into assets"
cp "$ROOT/tricorder.html" "$ROOT/sw.js" "$ROOT/manifest.webmanifest" "$OUT/assets/"

echo "==> compile resources"
"$BT/aapt2" compile --dir "$HERE/res" -o "$OUT/res.zip"

echo "==> link resources + manifest"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$HERE/AndroidManifest.xml" \
  -R "$OUT/res.zip" \
  -A "$OUT/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code 3 \
  --version-name 1.3 \
  --auto-add-overlay

echo "==> javac"
find "$HERE/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 11 -nowarn -classpath "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --lib "$ANDROID_JAR" --min-api 24 --output "$OUT/dex" @"$OUT/classes.txt"

echo "==> package dex into the apk"
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" <<'PY'
import sys, zipfile, shutil, os
apk, dex = sys.argv[1], sys.argv[2]
tmp = apk + ".tmp"
with zipfile.ZipFile(apk) as src, zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as dst:
    for item in src.infolist():
        if item.filename == "classes.dex":
            continue
        dst.writestr(item, src.read(item.filename))
    dst.write(dex, "classes.dex")
os.replace(tmp, apk)
PY

echo "==> zipalign"
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
  echo "==> generate a self-signed sideload key (not for distribution)"
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" -storepass "$STOREPASS" -keypass "$STOREPASS" \
    -alias tricorder -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Tricorder, OU=Sideload, O=IraqLobster, C=US" >/dev/null
fi

echo "==> sign"
"$BT/apksigner" sign \
  --ks "$KEYSTORE" --ks-pass "pass:$STOREPASS" --key-pass "pass:$STOREPASS" \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK" "$OUT/aligned.apk"

echo "==> verify"
"$BT/apksigner" verify --print-certs "$APK" | head -4
"$BT/aapt2" dump badging "$APK" | grep -E "^(package|application-label|uses-permission|sdkVersion|targetSdkVersion)" || true

echo
echo "built: $APK  ($(du -h "$APK" | cut -f1))"
