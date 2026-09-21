#!/bin/sh
# Build and sign the APK without Android Studio or the Google-hosted SDK,
# using only packages available from the Ubuntu archive plus a JDK.
#
#   sudo apt-get install aapt apksigner zipalign dalvik-exchange \
#                        android-framework-res android-sdk-platform-23
#   tools/build_apk.sh <keystore.p12> <store-pass> <key-alias> [output.apk]
#
# Reuse the same keystore every time: Android only installs an update over an
# existing app when the signing certificate matches.
set -eu

KEYSTORE=${1:?usage: build_apk.sh <keystore.p12> <store-pass> <key-alias> [out.apk]}
STOREPASS=${2:?missing store password}
ALIAS=${3:?missing key alias}
OUT=${4:-KhmerUnicodeLayout.apk}

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

ANDROID_JAR=/usr/lib/android-sdk/platforms/android-23/android.jar
FRAMEWORK_RES=/usr/share/android-framework-res/framework-res.apk

PACKAGE=net.socheat.apps.khmerunicodelayoutforexternalkeyboard
VERSION_CODE=18
VERSION_NAME=0.9.0
MIN_SDK=21
TARGET_SDK=34

echo "==> validating the keyboard layout"
python3 "$ROOT/tools/validate_kcm.py" \
        "$ROOT/app/src/main/res/raw/keyboard_layout_khmer.kcm"

echo "==> staging resources"
cp -r "$ROOT/app/src/main/res" "$WORK/res"
cp -r "$ROOT/app/src/main/assets" "$WORK/assets"
# aapt reads package and version from the manifest; the Gradle build supplies
# them from app/build.gradle instead, so inject them for this path only.
sed "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PACKAGE\" android:versionCode=\"$VERSION_CODE\" android:versionName=\"$VERSION_NAME\">\n    <uses-sdk android:minSdkVersion=\"$MIN_SDK\" android:targetSdkVersion=\"$TARGET_SDK\" />|" \
    "$ROOT/app/src/main/AndroidManifest.xml" > "$WORK/AndroidManifest.xml"

echo "==> generating R.java"
mkdir -p "$WORK/gen"
( cd "$WORK" && aapt package -f -m -J gen -M AndroidManifest.xml -S res \
      -A assets -I "$FRAMEWORK_RES" )

echo "==> compiling java (source/target 8, dx cannot read newer bytecode)"
mkdir -p "$WORK/classes"
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -classpath "$ANDROID_JAR" \
      -nowarn -Xlint:-options -d "$WORK/classes" \
      "$ROOT"/app/src/main/java/net/socheat/apps/khmerunicodelayoutforexternalkeyboard/*.java \
      "$WORK"/gen/net/socheat/apps/khmerunicodelayoutforexternalkeyboard/R.java

echo "==> dexing"
dalvik-exchange --dex --output="$WORK/classes.dex" "$WORK/classes"

echo "==> packaging resources"
( cd "$WORK" && aapt package -f -M AndroidManifest.xml -S res \
      -A assets -I "$FRAMEWORK_RES" -F app.apk )
( cd "$WORK" && aapt add -f app.apk classes.dex >/dev/null )

echo "==> aligning and signing"
zipalign -p -f 4 "$WORK/app.apk" "$WORK/aligned.apk"
zipalign -c 4 "$WORK/aligned.apk"
apksigner sign --ks "$KEYSTORE" --ks-type PKCS12 --ks-pass "pass:$STOREPASS" \
    --ks-key-alias "$ALIAS" --min-sdk-version "$MIN_SDK" \
    --max-sdk-version "$TARGET_SDK" --out "$OUT" "$WORK/aligned.apk"

echo "==> verifying"
apksigner verify --min-sdk-version "$MIN_SDK" --verbose "$OUT" | head -5
echo "built $OUT"
