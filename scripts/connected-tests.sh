#!/usr/bin/env bash
# Run the emulator suite the way it must be run before a push, and say *why* it failed when it does.
#
# Two failures on a headless AVD look identical and neither is the app:
#   1. Gradle installs the app and the test APK when the run starts. If the package is still bound (a
#      previous run's notification listener, an activity left behind, a half-finished install), the
#      installer kills the process mid-test. The XML then holds one testcase with an empty <failure/>
#      and <system-err>Process crashed</system-err>, and no Java stack anywhere - that is the package
#      manager, not our code (seen 2026-11-22: "Killing ... due to installPackageLI", "due to start
#      instr", while an isolated run of the same test passed on a cleaned device).
#   2. The AVD dies between long runs; Gradle then reports "No compatible devices connected".
# So: clean the package, the old reports and the log first; decide with check-connected-results.py,
# which never passes on a Gradle failure or on stale XML (its own failure paths are `--selftest`ed).
set -uo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-$HOME/toolchain/jdk17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/toolchain/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
ADB="$ANDROID_HOME/platform-tools/adb"
APP=com.daand.drivemusic
REPORTS=app/build/outputs/androidTest-results/connected
LOG=/tmp/connected-tests.log

if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "No device. Start the AVD first:" >&2
    echo "  $ANDROID_HOME/emulator/emulator -avd ci35 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot &" >&2
    exit 2
fi

for p in "$APP.test" "$APP"; do "$ADB" shell pm uninstall "$p" >/dev/null 2>&1; done
"$ADB" shell am force-stop "$APP" >/dev/null 2>&1
"$ADB" logcat -c 2>/dev/null
# Old reports must not be able to answer for this run: delete them before Gradle starts.
rm -rf "$REPORTS"

./gradlew connectedDebugAndroidTest --no-daemon "$@" > "$LOG" 2>&1
status=$?

"$ADB" logcat -d > /tmp/connected-logcat.txt 2>/dev/null
python3 scripts/check-connected-results.py \
    --dir "$REPORTS" --log /tmp/connected-logcat.txt --gradle-status "$status"
result=$?
tail -3 "$LOG"
exit "$result"
