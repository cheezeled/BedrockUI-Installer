#!/bin/sh
# Builds bedrock-ui-installer-<version>.apk with the plain SDK tools (no Gradle).
# The mod is NOT bundled: users import the jar they downloaded from the mod's page (credits + license stay with it).
# Needs: Android SDK (build-tools 36, platform 36), JDK 21, Python. Signs with keystore.jks (created on first run;
# keep it: updates must be signed with the same key).
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/36.0.0"
JAR="$SDK/platforms/android-36/android.jar"
export JAVA_HOME="${JAVA_HOME_21:-${JAVA_HOME:?set JAVA_HOME (JDK 21)}}"
PATH="$JAVA_HOME/bin:$PATH"
KS_PASS="${BEDROCKUI_KS_PASS:-bedrockui}" # set BEDROCKUI_KS_PASS to your own keystore password
VERSION=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
rm -rf build && mkdir -p build/res build/gen build/classes build/dex

python strings.py


"$BT/aapt2.exe" compile --dir res -o build/res/res.zip
"$BT/aapt2.exe" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml \
  --min-sdk-version 24 --target-sdk-version 36 --java build/gen build/res/res.zip
javac --release 8 -cp "$JAR" -Xlint:-options -encoding UTF-8 -d build/classes \
  $(find src build/gen -name '*.java')
"$BT/d8.bat" --release --min-api 24 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
# add dex with Python (aapt2 -A on Windows would write backslash paths if assets are ever added)
python - <<'PY'
import zipfile, pathlib
with zipfile.ZipFile("build/unsigned.apk", "a") as z:
    z.write("build/dex/classes.dex", "classes.dex", zipfile.ZIP_DEFLATED)
    for f in sorted(pathlib.Path("build/assets").rglob("*")):
        if f.is_file():
            z.write(f, "assets/" + f.relative_to("build/assets").as_posix(), zipfile.ZIP_STORED)
PY
"$BT/zipalign.exe" -f -p 4 build/unsigned.apk build/aligned.apk
if [ ! -f keystore.jks ]; then
  keytool -genkeypair -keystore keystore.jks -storepass "$KS_PASS" -keypass "$KS_PASS" -alias exporter \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Bedrock UI Exporter"
fi
OUT="bedrock-ui-installer-$VERSION.apk"
"$BT/apksigner.bat" sign --v2-signing-enabled true --v3-signing-enabled true --ks keystore.jks --ks-pass "pass:$KS_PASS" --ks-key-alias exporter --out "$OUT" build/aligned.apk
"$BT/apksigner.bat" verify "$OUT"
ls -la "$OUT"
