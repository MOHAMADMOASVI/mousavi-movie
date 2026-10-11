#!/bin/bash
# Builds build/mousavi-movie.apk straight from the SDK tools (no Gradle needed).
# Needs: a JDK, and an Android SDK with platforms;android-34 and build-tools;34.0.0.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
API=34
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
JAR="$SDK/platforms/android-$API/android.jar"
[ -f "$JAR" ] || { echo "Android platform $API not found in $SDK" >&2; exit 1; }

rm -rf build
mkdir -p build/gen build/classes build/dex

# The home screen for when the server can't be reached: server.py's own page, with its styles and scripts.
mkdir -p build/assets
cp -R ../static build/assets/static
python3 -c "import sys; sys.argv = ['server.py']; sys.path.insert(0, '..'); import server; print(server.offline_tv_page())" > build/assets/tv.html

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml --java build/gen -A build/assets \
  --min-sdk-version 21 --target-sdk-version $API --version-code 1 --version-name 1.0 build/res.zip

javac -nowarn --release 11 -cp "$JAR" -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --release --lib "$JAR" --min-api 21 --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q -j ../unsigned.apk classes.dex)

"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

# A local self-signed key, kept next to the project so updates install over the old version.
KS=signing.keystore
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias key \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Video TV"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out build/mousavi-movie.apk build/aligned.apk
"$BT/apksigner" verify build/mousavi-movie.apk

echo "Built $(pwd)/build/mousavi-movie.apk"
