#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Smoke test: install the APK the pipeline just signed and prove the app starts.
#
# Runs inside reactivecircus/android-emulator-runner, so an emulator is already
# booted and adb is pointing at it. Written for bash (the runner's own shell is
# dash, which is why the workflow pipes this script's output through a file).
# ---------------------------------------------------------------------------
set -eux

apk="$(ls apk/*.apk | head -n 1)"
echo "Artifact: $apk"

echo "---- native code in the APK ----"
unzip -l "$apk" 'lib/*' || true

echo "---- device ----"
adb shell getprop ro.product.cpu.abi
adb shell getprop ro.build.version.release

echo "---- install ----"
adb install -r -g "$apk"

echo "---- launch ----"
adb shell am start -W -n dev.citali.needle/.MainActivity

sleep 20

echo "---- process ----"
pid="$(adb shell pidof dev.citali.needle | tr -d '\r')"
if [ -z "$pid" ]; then
  echo "::error::Needle is not running after launch"
  adb logcat -d -v brief | tail -n 200
  exit 1
fi
echo "Needle is running as pid $pid"

echo "---- crash buffer ----"
if adb logcat -d -b crash | grep -q 'dev.citali.needle'; then
  echo "::error::The app wrote to the crash buffer"
  adb logcat -d -b crash | tail -n 200
  exit 1
fi
echo "(crash buffer is clean)"

echo "---- top activity ----"
adb shell dumpsys activity activities | grep -m1 'topResumedActivity' || true
adb shell dumpsys activity activities | grep -m1 'topResumedActivity' | grep -q 'dev.citali.needle' ||
  {
    echo "::error::MainActivity is not the resumed activity"
    adb shell dumpsys activity activities | tail -n 40
    exit 1
  }

echo "---- errors the app logged ----"
adb logcat -d -v brief '*:E' | grep -i 'dev.citali.needle' || echo "(none)"

echo "---- screenshot ----"
adb exec-out screencap -p > screenshot.png
ls -la screenshot.png
