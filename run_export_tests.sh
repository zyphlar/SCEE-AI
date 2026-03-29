#!/usr/bin/env bash
set -euo pipefail

JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.1.12-hotspot"
export JAVA_HOME
ADB="/c/Users/Will/AppData/Local/Android/Sdk/platform-tools/adb.exe"
EMULATOR="/c/Users/Will/AppData/Local/Android/Sdk/emulator/emulator.exe"
SDK_TOOLS="/c/Users/Will/AppData/Local/Android/Sdk"
TEST_CLASS="de.westnordost.streetcomplete.voicemapper.VoiceMapperExporterTest"
PACKAGE="com.zyphon.scee.ai.debug"
TEST_PACKAGE="${PACKAGE}.test"

# ── 1. Ensure emulator is running ─────────────────────────────────────────────
if "$ADB" devices | grep -q "emulator-5554.*device"; then
    echo "✓ Emulator already running"
else
    echo "→ Starting emulator..."
    AVD=$("$EMULATOR" -list-avds 2>/dev/null | head -1)
    if [ -z "$AVD" ]; then
        echo "✗ No AVDs found. Create one in Android Studio first." >&2
        exit 1
    fi
    echo "  Using AVD: $AVD"
    "$EMULATOR" -avd "$AVD" -no-snapshot-load -no-audio -no-window &
    EMULATOR_PID=$!

    echo "  Waiting for emulator to boot (up to 120s)..."
    for i in $(seq 1 60); do
        if "$ADB" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | grep -q "^1$"; then
            echo "✓ Emulator booted"
            break
        fi
        if [ "$i" -eq 60 ]; then
            echo "✗ Emulator did not boot in time" >&2
            exit 1
        fi
        sleep 2
    done
fi

# ── 2. Build debug APK + test APK ─────────────────────────────────────────────
echo "→ Building..."
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon -q
echo "✓ Build complete"

# ── 3. Install both APKs ──────────────────────────────────────────────────────
echo "→ Installing app APK..."
"$ADB" -s emulator-5554 uninstall "$PACKAGE" 2>/dev/null || true
"$ADB" -s emulator-5554 uninstall "$TEST_PACKAGE" 2>/dev/null || true
"$ADB" -s emulator-5554 install app/build/outputs/apk/debug/app-debug.apk

echo "→ Installing test APK..."
TEST_APK=$(find app/build/outputs/apk/androidTest/debug -name "*.apk" | head -1)
"$ADB" -s emulator-5554 install "$TEST_APK"
echo "✓ Installed"

# ── 4. Run tests ──────────────────────────────────────────────────────────────
echo "→ Running $TEST_CLASS..."
"$ADB" -s emulator-5554 shell am instrument -w -r \
    -e class "$TEST_CLASS" \
    "${TEST_PACKAGE}/androidx.test.runner.AndroidJUnitRunner" \
    | tee /tmp/test_output.txt

# ── 5. Parse result ───────────────────────────────────────────────────────────
if grep -q "OK (" /tmp/test_output.txt; then
    PASSED=$(grep "OK (" /tmp/test_output.txt | grep -o '[0-9]*')
    echo ""
    echo "✓ All $PASSED tests passed"
    exit 0
else
    echo ""
    echo "✗ Tests failed:"
    grep -E "FAIL|ERROR|Exception" /tmp/test_output.txt || true
    exit 1
fi
