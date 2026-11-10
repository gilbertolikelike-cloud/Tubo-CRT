#!/usr/bin/env bash
# Cria um APK minimo (sem codigo) que so carrega a camada, para usar com gpu_debug_layer_app.
set -euo pipefail
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
PLAT="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)"
SO=build/libVkLayer_dsr_dynrender.so

rm -rf apkwork out
mkdir -p apkwork/lib/arm64-v8a out
cp "$SO" apkwork/lib/arm64-v8a/

cat > apkwork/AndroidManifest.xml <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.dsr.vklayer">
    <application android:label="DSR Vulkan layer" android:hasCode="false" android:extractNativeLibs="true" />
</manifest>
XML

"$BT/aapt2" link -o apkwork/base.apk --manifest apkwork/AndroidManifest.xml \
    -I "$PLAT/android.jar" --min-sdk-version 26 --target-sdk-version 29
(cd apkwork && zip -qr base.apk lib)
"$BT/zipalign" -f -p 4 apkwork/base.apk apkwork/aligned.apk

keytool -genkeypair -keystore apkwork/ks.jks -alias dsr -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=dsr"
"$BT/apksigner" sign --ks apkwork/ks.jks --ks-pass pass:android --key-pass pass:android \
    --out out/dsr-vklayer.apk apkwork/aligned.apk

cp "$SO" out/
cp scripts/enable_layer.sh scripts/disable_layer.sh out/
ls -la out
