#!/usr/bin/env bash
# Automotive 에뮬레이터에서 앱을 실행하고 시나리오를 재생하며 화면을 녹화합니다. (GitHub Actions용)
set -uo pipefail
IMG="system-images;android-33;android-automotive;x86_64"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME}}"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$SDK/emulator:$PATH"
mkdir -p demo

echo "== sdk components =="
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install "platform-tools" "emulator" "$IMG" 2>&1 | tail -5

echo "== avd =="
echo "no" | avdmanager create avd -n car -k "$IMG" --device "automotive_1024p_landscape" --force 2>&1 | tail -3 \
  || echo "no" | avdmanager create avd -n car -k "$IMG" --force 2>&1 | tail -3
cat >> "$HOME/.android/avd/car.avd/config.ini" <<EOF
hw.lcd.width=1920
hw.lcd.height=1080
hw.lcd.density=160
hw.keyboard=yes
hw.ramSize=3072
disk.dataPartition.size=4G
EOF

echo "== boot =="
nohup emulator -avd car -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot -no-metrics \
  > demo/emulator.log 2>&1 &
adb wait-for-device
for i in $(seq 1 90); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then echo "booted after ${i}x5s"; break; fi
  sleep 5
done
adb shell getprop ro.build.fingerprint
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put system screen_off_timeout 1800000
adb shell input keyevent 82 || true

echo "== install =="
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.hiddenframe.mosim android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell am start -n com.hiddenframe.mosim/.ui.MainActivity
sleep 6
adb exec-out screencap -p > demo/screenshot_home.png

echo "== record =="
adb shell "screenrecord --time-limit 110 --bit-rate 8000000 --size 1920x1080 /sdcard/mosim_demo.mp4" &
REC=$!
sleep 4
adb shell am broadcast -a com.hiddenframe.mosim.SIM --es scenario rainy_night
sleep 22
adb exec-out screencap -p > demo/screenshot_rainy_night.png
adb shell am broadcast -a com.hiddenframe.mosim.SIM --es scenario tire_low
sleep 12
adb shell am broadcast -a com.hiddenframe.mosim.SIM --es scenario long_stop
sleep 12
adb shell am broadcast -a com.hiddenframe.mosim.SIM --es scenario daytime
sleep 12
# 공유 설정 변경 장면: 오른쪽 카드의 첫 스위치(야간 외출) 터치
adb shell input tap 1500 330
sleep 8
adb exec-out screencap -p > demo/screenshot_final.png
wait $REC || true
sleep 3
adb pull /sdcard/mosim_demo.mp4 demo/mosim_emulator_raw.mp4 || echo "pull failed"
adb logcat -d -s AlertSender:* CarApiSignalSource:* Voice:* AndroidRuntime:E > demo/logcat.txt || true
ls -la demo
