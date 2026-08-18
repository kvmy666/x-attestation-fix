#!/usr/bin/env bash
# Reproducible build for the X Attestation Fix LSPosed module.
# Produces a signed, installable APK from source with no prebuilt base.apk.
#
# Requirements:
#   - JDK 17+ (javac, keytool on PATH)
#   - Android SDK with: platforms/android-35/android.jar and build-tools/35.0.0
#   - python3 (used only to splice classes.dex into the linked APK)
#
# Override the SDK location with ANDROID_SDK_ROOT (or ANDROID_HOME) if needed.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
cd "$here"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}}"
BT_VER="${BUILD_TOOLS_VERSION:-35.0.0}"
PLATFORM="${ANDROID_PLATFORM:-android-35}"

BT="$SDK/build-tools/$BT_VER"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"

AAPT2="$BT/aapt2"; D8="$BT/d8"; ZIPALIGN="$BT/zipalign"; APKSIGNER="$BT/apksigner"
# Windows (Git-bash) uses .exe/.bat wrappers.
[ -x "$AAPT2" ]     || AAPT2="$BT/aapt2.exe"
[ -f "$D8" ]        || D8="$BT/d8.bat"
[ -x "$ZIPALIGN" ]  || ZIPALIGN="$BT/zipalign.exe"
[ -f "$APKSIGNER" ] || APKSIGNER="$BT/apksigner.bat"

for f in "$ANDROID_JAR" "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER"; do
    [ -e "$f" ] || { echo "ERROR: missing $f (check ANDROID_SDK_ROOT / build-tools)"; exit 1; }
done
DEXDUMP="$BT/dexdump"; [ -x "$DEXDUMP" ] || DEXDUMP="$BT/dexdump.exe"

PY="${PYTHON:-python3}"; command -v "$PY" >/dev/null 2>&1 || PY=python

OUT="build"
rm -rf "$OUT"; mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/dex"

echo "==> aapt2 compile resources"
"$AAPT2" compile --dir res -o "$OUT/compiled/res.zip"

echo "==> aapt2 link (manifest + resources + assets)"
"$AAPT2" link \
    -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml \
    -A assets \
    --min-sdk-version 28 \
    --target-sdk-version 35 \
    "$OUT/compiled/res.zip"

echo "==> javac (module + compile-only Xposed stubs)"
# Stubs are compiled for reference resolution only and are deliberately NOT dexed;
# bundling the Xposed API into the APK shadows LSPosed's runtime API and breaks loading.
find src stubs -name '*.java' > "$OUT/sources.txt"
javac -source 17 -target 17 -encoding UTF-8 \
    -classpath "$ANDROID_JAR" \
    -d "$OUT/classes" \
    @"$OUT/sources.txt"

echo "==> d8 (dex the module classes ONLY, not the stubs)"
MODULE_CLASSES=$(find "$OUT/classes/io/github/mara/xbypass" -name '*.class')
"$D8" --min-api 28 --lib "$ANDROID_JAR" --output "$OUT/dex" $MODULE_CLASSES

echo "==> splice classes.dex into the APK"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
"$PY" - "$OUT/unsigned.apk" "$OUT/dex/classes.dex" <<'PYEOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    names = z.namelist()
    assert "classes.dex" not in names, "classes.dex already present"
    z.write(dex, "classes.dex")
print("added classes.dex")
PYEOF

echo "==> invariant: no Xposed API stubs DEFINED in classes.dex (references are fine; definitions would shadow LSPosed)"
if [ -x "$DEXDUMP" ] || [ -f "$DEXDUMP" ]; then
    # Only inspect defined classes ("Class descriptor" lines), not referenced types.
    if "$DEXDUMP" "$OUT/dex/classes.dex" 2>/dev/null | grep "Class descriptor" | grep -q "Lde/robv/"; then
        echo "ERROR: Xposed stub classes were dexed into the APK — aborting"; exit 1
    fi
    echo "    OK: only io.github.mara.xbypass classes are defined"
fi

echo "==> zipalign"
"$ZIPALIGN" -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> ensure a debug keystore"
# Kept outside build/ (which is wiped each run) so rebuilds reuse the same signer and can update in place.
KS="$here/debug.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -v -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=X Attestation Fix Debug,O=xbypass,C=US"
fi

echo "==> apksigner sign"
"$APKSIGNER" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT/xbypass-fix.apk" "$OUT/aligned.apk"
"$APKSIGNER" verify --print-certs "$OUT/xbypass-fix.apk" >/dev/null

echo
echo "BUILD OK -> $here/$OUT/xbypass-fix.apk"
