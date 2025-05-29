#!/bin/bash
set -e

# configure the environment
KEYSTORE_PATH="$HOME/.android/my-release-key.keystore"
KEY_ALIAS="my-key-alias"
KEY_PASSWORD="YourKeyPassword"
KEYSTORE_PASSWORD="$KEY_PASSWORD"
APK_DIR="$MLC_LLM_SOURCE_DIR/android/MLCChat/app/build/outputs/apk/release"
UNSIGNED_APK="${APK_DIR}/app-release-unsigned.apk"
SIGNED_APK="${APK_DIR}/app-release-signed.apk"

# build the APK
cd $MLC_LLM_SOURCE_DIR/android/MLCChat
mlc_llm package

./gradlew assembleRelease

# generate the key if it does not exist
if [ ! -f "$KEYSTORE_PATH" ]; then
  echo "Keystore not found. Generating a new one..."
  keytool -genkey -v \
    -keystore "$KEYSTORE_PATH" \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass "$KEYSTORE_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -dname "CN=YourName, OU=., O=., L=., S=., C=."
fi


# sign the APK
echo "Signing APK..."
apksigner sign \
  --ks "$KEYSTORE_PATH" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass pass:"$KEYSTORE_PASSWORD" \
  --key-pass pass:"$KEY_PASSWORD" \
  --out "$SIGNED_APK" \
  "$UNSIGNED_APK"

# verify the signed APK
apksigner verify "$SIGNED_APK" && echo "APK signed successfully: $SIGNED_APK"