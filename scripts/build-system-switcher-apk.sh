#!/usr/bin/env bash
set -euo pipefail

repo=$(cd -- "$(dirname -- "$0")/.." && pwd)
source_dir="$repo/tools/system-switcher/android"
destination=${1:-"$repo/artifacts/system-switcher/cloudbook-system-switcher.apk"}
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:?set ANDROID_HOME to the Android SDK directory}}
tools="$sdk/build-tools/35.0.0"
android_jar="$sdk/platforms/android-35/android.jar"
key_store=${ANDROID_DEBUG_KEYSTORE:-"$HOME/.android/debug.keystore"}
stage=$(mktemp -d)
trap 'rm -rf -- "$stage"' EXIT

if [[ ! -f "$android_jar" || ! -x "$tools/aapt2" ]]; then
    echo 'Install Android SDK Platform 35 and Build Tools 35.0.0.' >&2
    exit 1
fi
mkdir -p "$stage/classes" "$stage/generated" "$stage/assets" "$(dirname -- "$destination")"
curl --fail --silent --show-error --location --max-time 60 \
    https://repo.maven.apache.org/maven2/com/tananaev/adblib/1.3/adblib-1.3.jar \
    -o "$stage/adblib.jar"
cp "$repo/tools/system-switcher/ADBLIB-LICENSE.txt" "$stage/assets/"
cp "$repo/tools/system-switcher/FEATHER-LICENSE" "$stage/assets/"
"$tools/aapt2" compile --dir "$source_dir/res" -o "$stage/resources.zip"
"$tools/aapt2" link -I "$android_jar" --manifest "$source_dir/AndroidManifest.xml" \
    --java "$stage/generated" -A "$stage/assets" -o "$stage/unsigned.apk" "$stage/resources.zip"
javac -encoding UTF-8 --release 17 -classpath "$android_jar:$stage/adblib.jar" \
    -d "$stage/classes" "$stage/generated/io/github/kuaner/cloudbook/switcher/R.java" \
    "$source_dir"/src/io/github/kuaner/cloudbook/switcher/*.java
jar cf "$stage/classes.jar" -C "$stage/classes" .
"$tools/d8" --release --min-api 30 --lib "$android_jar" --output "$stage" \
    "$stage/classes.jar" "$stage/adblib.jar"
(cd "$stage" && zip -q unsigned.apk classes.dex)
"$tools/zipalign" -f 4 "$stage/unsigned.apk" "$stage/aligned.apk"

if [[ ! -e "$key_store" ]]; then
    mkdir -p "$(dirname -- "$key_store")"
    keytool -genkeypair -keystore "$key_store" -alias androiddebugkey \
        -storepass android -keypass android -keyalg RSA -keysize 2048 \
        -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'
fi
"$tools/apksigner" sign --ks "$key_store" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android --v4-signing-enabled false \
    --out "$stage/cloudbook-system-switcher.apk" "$stage/aligned.apk"
"$tools/apksigner" verify "$stage/cloudbook-system-switcher.apk"
mv "$stage/cloudbook-system-switcher.apk" "$destination"
printf 'Built %s\n' "$destination"
