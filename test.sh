#!/usr/bin/env bash
# Pure policy tests only: no Android SDK, connected device, or system writes.
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
TASK_TEST_DIR="$PROJECT_DIR/build/policy-tests"
if [ -n "${JAVA_HOME:-}" ]; then
    [ -x "$JAVA_HOME/bin/javac" ] || { printf 'Invalid JAVA_HOME\n' >&2; exit 1; }
elif [ "$(uname -s)" = Darwin ] && [ -x /usr/libexec/java_home ]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || /usr/libexec/java_home -v 17 2>/dev/null || true)"
fi
if [ -n "${JAVA_HOME:-}" ]; then
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
fi
TASK_JAVA_MAJOR="$(javac -version 2>&1 | sed -E 's/^javac ([0-9]+).*/\1/')"
case "$TASK_JAVA_MAJOR" in 17|21) ;; *) printf 'Use JDK 17 or 21 (set JAVA_HOME).\n' >&2; exit 1;; esac
rm -rf "$TASK_TEST_DIR"
mkdir -p "$TASK_TEST_DIR"
javac -encoding UTF-8 --release 8 -d "$TASK_TEST_DIR" \
    "$PROJECT_DIR/src/com/local/z6xserviceprobe/UsbAdbGuard.java" \
    "$PROJECT_DIR/tests/UsbAdbGuardTest.java"
java -cp "$TASK_TEST_DIR" com.local.z6xserviceprobe.UsbAdbGuardTest \
    | tee "$TASK_TEST_DIR/result.txt"
