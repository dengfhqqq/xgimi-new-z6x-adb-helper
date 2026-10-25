#!/usr/bin/env bash
# Dependency-free Android build using the Android SDK and JDK 17 or 21.
# Signing secrets are read from environment variables, never command arguments.
set -euo pipefail
umask 077

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
TASK_VERSION="1.6.0"
TASK_BUILD_DIR="$PROJECT_DIR/build/v$TASK_VERSION"
TASK_OUTPUT="$PROJECT_DIR/dist/Z6X-ADB-Helper-v$TASK_VERSION.apk"

fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }

if [ -n "${JAVA_HOME:-}" ]; then
    [ -x "$JAVA_HOME/bin/javac" ] || fail 'JAVA_HOME must point to a JDK 17 or 21.'
elif [ "$(uname -s)" = Darwin ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17 2>/dev/null || true)"
fi
if [ -n "${JAVA_HOME:-}" ]; then
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
fi
for TASK_TOOL in java javac jar keytool zip unzip; do
    command -v "$TASK_TOOL" >/dev/null 2>&1 || fail "Missing required tool: $TASK_TOOL"
done
TASK_JAVA_MAJOR="$(javac -version 2>&1 | sed -E 's/^javac ([0-9]+).*/\1/')"
case "$TASK_JAVA_MAJOR" in 17|21) ;; *) fail 'Use JDK 17 or 21 (set JAVA_HOME).';; esac

# Explicit SDK variables take precedence. A partial explicit SDK is an error.
TASK_SDK="${TASK_ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}}"
if [ -z "$TASK_SDK" ]; then
    for TASK_CANDIDATE in \
        "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" \
        "$HOME/.local/android-sdk" "$HOME/android-sdk" \
        /usr/local/lib/android/sdk /opt/android-sdk /opt/android-sdk-linux; do
        if [ -f "$TASK_CANDIDATE/platforms/android-34/android.jar" ] && \
           [ -x "$TASK_CANDIDATE/build-tools/34.0.0/aapt2" ]; then
            TASK_SDK="$TASK_CANDIDATE"
            break
        fi
    done
fi
[ -n "$TASK_SDK" ] || fail 'Set TASK_ANDROID_SDK, ANDROID_SDK_ROOT or ANDROID_HOME to an Android SDK with platform 34 and build-tools 34.0.0.'
TASK_BUILD_TOOLS="$TASK_SDK/build-tools/34.0.0"
TASK_ANDROID_JAR="$TASK_SDK/platforms/android-34/android.jar"
[ -f "$TASK_ANDROID_JAR" ] || fail 'Missing platforms/android-34/android.jar. Run sdkmanager "platforms;android-34" "build-tools;34.0.0".'
for TASK_TOOL in aapt aapt2 d8 zipalign apksigner; do
    [ -x "$TASK_BUILD_TOOLS/$TASK_TOOL" ] || fail "Missing build-tools 34.0.0 tool: $TASK_TOOL"
done

mkdir -p "$TASK_BUILD_DIR" "$PROJECT_DIR/dist"
# Remove only reproducible intermediate directories, so deleted classes cannot leak into the APK.
rm -rf "$TASK_BUILD_DIR/classes" "$TASK_BUILD_DIR/dex" "$TASK_BUILD_DIR/generated"
mkdir -p "$TASK_BUILD_DIR/classes" "$TASK_BUILD_DIR/dex" "$TASK_BUILD_DIR/generated"
"$TASK_BUILD_TOOLS/aapt2" compile --dir "$PROJECT_DIR/res" -o "$TASK_BUILD_DIR/resources.zip"
"$TASK_BUILD_TOOLS/aapt2" link -o "$TASK_BUILD_DIR/resources.apk" \
    --manifest "$PROJECT_DIR/AndroidManifest.xml" -I "$TASK_ANDROID_JAR" \
    --java "$TASK_BUILD_DIR/generated" "$TASK_BUILD_DIR/resources.zip"

# Bash 3 compatible, including filenames containing spaces; no mapfile or Gradle required.
TASK_SOURCES=()
while IFS= read -r -d '' TASK_SOURCE; do
    TASK_SOURCES[${#TASK_SOURCES[@]}]="$TASK_SOURCE"
done < <(find "$PROJECT_DIR/src" "$TASK_BUILD_DIR/generated" -type f -name '*.java' -print0)
[ "${#TASK_SOURCES[@]}" -gt 0 ] || fail 'No Java sources found.'
javac -encoding UTF-8 -g -parameters -source 8 -target 8 \
    -bootclasspath "$TASK_ANDROID_JAR" -d "$TASK_BUILD_DIR/classes" "${TASK_SOURCES[@]}"
jar cf "$TASK_BUILD_DIR/classes.jar" -C "$TASK_BUILD_DIR/classes" .
"$TASK_BUILD_TOOLS/d8" --min-api 21 --lib "$TASK_ANDROID_JAR" \
    --output "$TASK_BUILD_DIR/dex" "$TASK_BUILD_DIR/classes.jar"
cp "$TASK_BUILD_DIR/resources.apk" "$TASK_BUILD_DIR/unsigned.apk"
(cd "$TASK_BUILD_DIR/dex" && zip -q "$TASK_BUILD_DIR/unsigned.apk" ./*.dex)
"$TASK_BUILD_TOOLS/zipalign" -f 4 "$TASK_BUILD_DIR/unsigned.apk" "$TASK_BUILD_DIR/aligned.apk"

TASK_KEY_ALIAS="${TASK_KEY_ALIAS:-z6x-service-probe}"
if [ -n "${TASK_KEYSTORE:-}" ]; then
    [ -f "$TASK_KEYSTORE" ] || fail 'TASK_KEYSTORE does not exist.'
    [ -n "${TASK_STORE_PASSWORD:-}" ] || fail 'Set TASK_STORE_PASSWORD when using TASK_KEYSTORE.'
else
    mkdir -p "$PROJECT_DIR/signing"
    TASK_KEYSTORE="$PROJECT_DIR/signing/local-debug.jks"
    TASK_PASSWORD_FILE="$PROJECT_DIR/signing/local-debug.password"
    if [ -z "${TASK_STORE_PASSWORD:-}" ]; then
        if [ -f "$TASK_PASSWORD_FILE" ]; then
            TASK_STORE_PASSWORD="$(cat "$TASK_PASSWORD_FILE")"
        elif [ -f "$TASK_KEYSTORE" ]; then
            fail 'Existing local keystore has no password file. Set TASK_STORE_PASSWORD; do not replace the key if you need upgrade compatibility.'
        else
            TASK_STORE_PASSWORD="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
            printf '%s\n' "$TASK_STORE_PASSWORD" > "$TASK_PASSWORD_FILE"
            chmod 600 "$TASK_PASSWORD_FILE"
        fi
    fi
    [ -n "$TASK_STORE_PASSWORD" ] || fail 'The local signing password is empty.'
    export TASK_STORE_PASSWORD
    TASK_KEY_PASSWORD="${TASK_KEY_PASSWORD:-$TASK_STORE_PASSWORD}"
    export TASK_KEY_PASSWORD
    if [ ! -f "$TASK_KEYSTORE" ]; then
        keytool -genkeypair -keystore "$TASK_KEYSTORE" -storetype JKS \
            -storepass:env TASK_STORE_PASSWORD -keypass:env TASK_KEY_PASSWORD \
            -alias "$TASK_KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
            -dname 'CN=Z6X ADB Helper Local Build,O=Local Build'
    fi
fi
TASK_KEY_PASSWORD="${TASK_KEY_PASSWORD:-$TASK_STORE_PASSWORD}"
export TASK_STORE_PASSWORD TASK_KEY_PASSWORD
"$TASK_BUILD_TOOLS/apksigner" sign --ks "$TASK_KEYSTORE" --ks-key-alias "$TASK_KEY_ALIAS" \
    --ks-pass env:TASK_STORE_PASSWORD --key-pass env:TASK_KEY_PASSWORD \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --v4-signing-enabled false --out "$TASK_OUTPUT" "$TASK_BUILD_DIR/aligned.apk"

TASK_VALIDATION="$TASK_BUILD_DIR/validation.txt"
{
    printf 'Z6X ADB Helper %s\n\n' "$TASK_VERSION"
    "$TASK_BUILD_TOOLS/apksigner" verify --verbose --print-certs "$TASK_OUTPUT"
    "$TASK_BUILD_TOOLS/zipalign" -c -v 4 "$TASK_OUTPUT"
    "$TASK_BUILD_TOOLS/aapt" dump badging "$TASK_OUTPUT"
    "$TASK_BUILD_TOOLS/aapt" dump permissions "$TASK_OUTPUT"
    unzip -t "$TASK_OUTPUT"
} > "$TASK_VALIDATION" 2>&1
if command -v shasum >/dev/null 2>&1; then
    (cd "$PROJECT_DIR/dist" && shasum -a 256 "$(basename "$TASK_OUTPUT")") > "$PROJECT_DIR/dist/SHA256SUMS"
elif command -v sha256sum >/dev/null 2>&1; then
    (cd "$PROJECT_DIR/dist" && sha256sum "$(basename "$TASK_OUTPUT")") > "$PROJECT_DIR/dist/SHA256SUMS"
else
    fail 'Install shasum or sha256sum to produce a checksum.'
fi
cat "$PROJECT_DIR/dist/SHA256SUMS" >> "$TASK_VALIDATION"
printf 'Built: %s\nValidation: %s\n' "$TASK_OUTPUT" "$TASK_VALIDATION"
cat "$PROJECT_DIR/dist/SHA256SUMS"
