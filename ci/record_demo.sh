#!/usr/bin/env bash
# Automotive 에뮬레이터에서 앱을 실행하고 시나리오를 재생하며 화면을 녹화합니다. (GitHub Actions용)
set -uo pipefail
IMG="system-images;android-33;android-automotive;x86_64"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME}}"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$SDK/emulator:$PATH"
export ANDROID_AVD_HOME="$HOME/.android/avd"
mkdir -p demo "$ANDROID_AVD_HOME"

echo "== sdk components =="
yes 2>/dev/null | sdkmanager --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
sdkmanager --sdk_root="$SDK" --install "platform-tools" "emulator" "$IMG" 2>&1 | tr '\r' '\n' | grep -vE "^\s*$|Loading|Fetch|%" | tail -5
ls "$SDK/system-images/android-33/android-automotive/x86_64/" || { echo "SYSTEM IMAGE MISSING"; exit 1; }
emulator -version 2>&1 | head -2

echo "== avd =="
avdmanager list device 2>/dev/null | grep -i automotive | head -5
echo "no" | avdmanager --verbose create avd -n car -k "$IMG" --device "automotive_1024p_landscape" --force 2>&1 | tail -5
CFG=$(find "$HOME" -path '*car.avd/config.ini' 2>/dev/null | head -1)
if [ -z "$CFG" ]; then
  echo "retry without device profile"
  echo "no" | avdmanager --verbose create avd -n car -k "$IMG" --force 2>&1 | tail -5
  CFG=$(find "$HOME" -path '*car.avd/config.ini' 2>/dev/null | head -1)
fi
[ -n "$CFG" ] || { echo "AVD CREATE FAILED"; avdmanager list avd; exit 1; }
echo "config: $CFG"
cat >> "$CFG" <<EOF
hw.lcd.width=1920
hw.lcd.height=1080
hw.lcd.density=160
hw.keyboard=yes
hw.ramSize=2560
disk.dataPartition.size=2048M
EOF

echo "== boot =="
ls -la /dev/kvm || echo "no kvm"
nohup emulator -avd car -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot -no-metrics \
  > demo/emulator.log 2>&1 &
EMU=$!
sleep 10
if ! kill -0 $EMU 2>/dev/null; then echo "EMULATOR EXITED"; tail -40 demo/emulator.log; exit 1; fi
timeout 300 adb wait-for-device || { echo "ADB WAIT TIMEOUT"; tail -40 demo/emulator.log; exit 1; }
BOOTED=0
for i in $(seq 1 100); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then echo "booted after ${i}x5s"; BOOTED=1; break; fi
  kill -0 $EMU 2>/dev/null || { echo "EMULATOR DIED"; tail -40 demo/emulator.log; exit 1; }
  sleep 5
done
[ "$BOOTED" = "1" ] || { echo "BOOT TIMEOUT"; tail -40 demo/emulator.log; exit 1; }
adb shell getprop ro.build.fingerprint
# 시스템 서비스·저장소가 실제로 준비될 때까지 추가 대기
for i in $(seq 1 40); do
  if adb shell pm path com.android.settings >/dev/null 2>&1 && adb shell "echo ok > /data/local/tmp/.ready" >/dev/null 2>&1 \
     && [ "$(adb shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r')" = "stopped" ]; then echo "system ready after ${i}x5s"; break; fi
  sleep 5
done
sleep 20
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put system screen_off_timeout 1800000
adb shell input keyevent 82 || true
sleep 5

echo "== install =="
adb install -r app/build/outputs/apk/debug/app-debug.apk
for i in $(seq 1 12); do adb shell pm path com.hiddenframe.mosim >/dev/null 2>&1 && break; sleep 5; done
adb shell pm path com.hiddenframe.mosim
adb shell pm grant com.hiddenframe.mosim android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell am start -n com.hiddenframe.mosim/.ui.MainActivity
sleep 10
adb shell dumpsys activity activities | grep -m1 "mResumedActivity" || true
adb exec-out screencap -p > demo/screenshot_home.png
adb shell wm size

echo "== record =="
adb shell "screenrecord --time-limit 110 --bit-rate 8000000 /data/local/tmp/mosim_demo.mp4" &
REC=$!
sleep 4
adb shell am broadcast -a com.hiddenframe.mosim.SIM -n com.hiddenframe.mosim/.vehicle.SimulationReceiver --es scenario rainy_night
sleep 22
adb exec-out screencap -p > demo/screenshot_rainy_night.png
adb shell am broadcast -a com.hiddenframe.mosim.SIM -n com.hiddenframe.mosim/.vehicle.SimulationReceiver --es scenario tire_low
sleep 12
adb shell am broadcast -a com.hiddenframe.mosim.SIM -n com.hiddenframe.mosim/.vehicle.SimulationReceiver --es scenario long_stop
sleep 12
adb shell am broadcast -a com.hiddenframe.mosim.SIM -n com.hiddenframe.mosim/.vehicle.SimulationReceiver --es scenario daytime
sleep 12
adb shell input tap 1500 250
sleep 8
adb exec-out screencap -p > demo/screenshot_final.png
wait $REC || true
sleep 3
adb pull /data/local/tmp/mosim_demo.mp4 demo/mosim_emulator_raw.mp4 || echo "pull failed"
adb logcat -d -s AlertSender:* CarApiSignalSource:* Voice:* AndroidRuntime:E ActivityManager:W > demo/logcat.txt || true
ls -la demo
