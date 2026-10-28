#!/usr/bin/env bash
# Pleos Connect 에뮬레이터 v2.1.0(API 34)에서 실제 Pleos SDK 경로로 시연을 녹화합니다. (GitHub Actions용)
# 라이선스: 42dot Pleos Connect SDK License Agreement — 저장소 소유자(정세준)가 동의함 (2026-10-28).
# 차량 신호는 AAOS VHAL 주입(cmd car_service inject-vhal-event), 위치는 에뮬레이터 geo fix로 넣습니다.
set -uo pipefail
PKG="system-images;android-34;pleos_car_emulator;x86_64"
IMG_DIR_REL="system-images/android-34/pleos_car_emulator/x86_64"
XML_URL="https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml"
ZIP_URL="https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp_car_emulator.zip"
ZIP_SHA1="b4f858d5bcc6c71506d7edfc872bcb9e5a11acaa"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME}}"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$SDK/emulator:$PATH"
export ANDROID_AVD_HOME="$HOME/.android/avd"
OUT=demo/pleos
mkdir -p "$OUT" "$ANDROID_AVD_HOME"
: > "$HOME/.emulator_console_auth_token"   # adb emu geo fix 인증 생략
APP=com.hiddenframe.mosim
log() { echo "== $* =="; }

log "sdk components"
yes 2>/dev/null | sdkmanager --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
sdkmanager --sdk_root="$SDK" --install "platform-tools" "emulator" 2>&1 | tr '\r' '\n' | grep -vE "^\s*$|Loading|Fetch|%" | tail -3
emulator -version 2>&1 | head -1 | tee "$OUT/emulator_version.txt"

if [ ! -f "$SDK/$IMG_DIR_REL/system.img" ]; then
  log "pleos image via sdkmanager (user source)"
  printf '### User Sources for Android SDK Manager\ncount=1\nsrc00=%s\nenabled00=true\ndisp00=Pleos Connect\n' "$(echo "$XML_URL" | sed 's/:/\\:/')" > "$HOME/.android/repositories.cfg"
  yes 2>/dev/null | sdkmanager --sdk_root="$SDK" --licenses 2>&1 | grep -iE "pleos|accept" | head -5
  yes 2>/dev/null | sdkmanager --sdk_root="$SDK" --install "$PKG" 2>&1 | tr '\r' '\n' | grep -vE "^\s*$|%" | tail -8
fi
if [ ! -f "$SDK/$IMG_DIR_REL/system.img" ]; then
  log "pleos image via direct download"
  mkdir -p /mnt/pleos 2>/dev/null || sudo mkdir -p /mnt/pleos; sudo chown "$USER" /mnt/pleos 2>/dev/null || true
  Z=/mnt/pleos/crp.zip; [ -w /mnt/pleos ] || Z="$HOME/crp.zip"
  curl -sS -L --retry 3 -o "$Z" "$ZIP_URL"
  echo "$ZIP_SHA1  $Z" | sha1sum -c - || { echo "CHECKSUM MISMATCH"; exit 1; }
  T=$(dirname "$Z")/x; rm -rf "$T"; mkdir -p "$T"; unzip -q "$Z" -d "$T"; rm -f "$Z"
  SRC=$(dirname "$(find "$T" -name system.img | head -1)")
  mkdir -p "$SDK/$(dirname "$IMG_DIR_REL")"; rm -rf "$SDK/$IMG_DIR_REL"; mv "$SRC" "$SDK/$IMG_DIR_REL"
  # 라이선스 동의 기록(소유자 동의)
  mkdir -p "$SDK/licenses"; echo "accepted-by-owner-2026-10-28" > "$SDK/licenses/pleos-connect-sdk-license"
fi
ls "$SDK/$IMG_DIR_REL" | tee "$OUT/image_files.txt"
[ -f "$SDK/$IMG_DIR_REL/system.img" ] || { echo "PLEOS IMAGE MISSING"; exit 1; }
cat "$SDK/$IMG_DIR_REL/source.properties" 2>/dev/null | tee -a "$OUT/image_files.txt"

log "avd"
avdmanager list device 2>/dev/null | grep -iE "id:|name:" | grep -iE "pleos|automotive|crp" | head -10 | tee "$OUT/devices.txt"
DEV=$(avdmanager list device -c 2>/dev/null | grep -iE "pleos|crp" | head -1)
[ -n "$DEV" ] || DEV=automotive_1024p_landscape
echo "device: $DEV" | tee -a "$OUT/devices.txt"
echo "no" | avdmanager --verbose create avd -n pleos -k "$PKG" --device "$DEV" --force 2>&1 | tail -3
CFG=$(find "$HOME" -path '*pleos.avd/config.ini' 2>/dev/null | head -1)
[ -n "$CFG" ] || { echo "no" | avdmanager create avd -n pleos -k "$PKG" --force; CFG=$(find "$HOME" -path '*pleos.avd/config.ini' | head -1); }
[ -n "$CFG" ] || { echo "AVD CREATE FAILED"; exit 1; }
grep -q "hw.ramSize" "$CFG" && sed -i 's/^hw.ramSize.*/hw.ramSize=4096/' "$CFG" || echo "hw.ramSize=4096" >> "$CFG"
echo "disk.dataPartition.size=4096M" >> "$CFG"
echo "hw.keyboard=yes" >> "$CFG"
cp "$CFG" "$OUT/avd_config.ini"

log "boot"
nohup emulator -avd pleos -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot -no-metrics -memory 4096 \
  > "$OUT/emulator.log" 2>&1 &
EMU=$!
sleep 15
kill -0 $EMU 2>/dev/null || { echo "EMULATOR EXITED"; tail -40 "$OUT/emulator.log"; exit 1; }
timeout 420 adb wait-for-device || { echo "ADB WAIT TIMEOUT"; tail -40 "$OUT/emulator.log"; exit 1; }
BOOTED=0
for i in $(seq 1 150); do
  [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && { echo "booted after ${i}x5s"; BOOTED=1; break; }
  kill -0 $EMU 2>/dev/null || { echo "EMULATOR DIED"; tail -40 "$OUT/emulator.log"; exit 1; }
  sleep 5
done
[ "$BOOTED" = "1" ] || { echo "BOOT TIMEOUT"; tail -60 "$OUT/emulator.log"; exit 1; }
for i in $(seq 1 60); do adb shell pm path com.android.settings >/dev/null 2>&1 && break; sleep 5; done
sleep 40
adb root >/dev/null 2>&1; sleep 5; adb wait-for-device
adb shell getprop ro.build.fingerprint | tee "$OUT/fingerprint.txt"
adb shell wm size | tee -a "$OUT/fingerprint.txt"
adb shell pm list packages | sort > "$OUT/packages.txt"
grep -iE "pleos|42dot|console|vehicle|crp" "$OUT/packages.txt" | head -30
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell settings put system screen_off_timeout 1800000
adb exec-out screencap -p > "$OUT/00_boot.png"

log "vhal probe"
VH() { adb shell cmd car_service "$@" 2>&1 | head -6; }
{ for p in 0x11400400 0x13400CC8 0x11600307 0x11600106 0x11200402 0x11600207; do echo "## get-property $p"; VH get-property-value $p; done; } > "$OUT/vhal_probe.txt" 2>&1
cat "$OUT/vhal_probe.txt" | head -40

log "time 22:40 KST"
adb shell settings put global auto_time 0
adb shell settings put global auto_time_zone 0
adb shell service call alarm 3 s16 Asia/Seoul >/dev/null 2>&1 || adb shell setprop persist.sys.timezone Asia/Seoul
adb shell "date $(date -u +%m%d)1340$(date -u +%Y).00" 2>&1 | tail -1   # 13:40 UTC = 22:40 KST
adb shell date

log "install"
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
for i in $(seq 1 12); do adb shell pm path $APP >/dev/null 2>&1 && break; sleep 5; done
for p in pleos.car.permission.FUSED_LOCATION android.permission.POST_NOTIFICATIONS; do adb shell pm grant $APP $p 2>&1 | tail -1; done
adb shell dumpsys package $APP | grep -E "pleos.car.permission.*granted" | tee "$OUT/granted.txt"
adb logcat -c
adb shell am start -n $APP/.ui.MainActivity
sleep 12
adb exec-out screencap -p > "$OUT/01_home.png"

# 주차(P)에서 시작
VH inject-vhal-event 0x11400400 4 >/dev/null
VH inject-vhal-event 0x13400CC8 1 1 >/dev/null
sleep 3

GEO_LAT=37.5012; GEO_LON=127.0396   # 강남대로 부근
geo() { adb emu geo fix "$GEO_LON" "$GEO_LAT" >/dev/null 2>&1; }
drive() { # $1=초, $2=km/h
  local step; step=$(python3 -c "print($2/3.6/111320)")
  for _ in $(seq 1 "$1"); do GEO_LAT=$(python3 -c "print(f'{$GEO_LAT+$step:.7f}')"); geo; sleep 1; done
}

log "record"
adb shell "screenrecord --time-limit 150 --bit-rate 8000000 /data/local/tmp/mosim_pleos.mp4" &
REC=$!
sleep 4
# 1) 밤 10시 40분, 기어 D → 야간 안내 + (공유 허용 시) 가족 알림
VH inject-vhal-event 0x11400400 8 | tee -a "$OUT/inject.txt"
sleep 9
# 2) 비: 앞유리 와이퍼 ON
VH inject-vhal-event 0x13400CC8 1 2 | tee -a "$OUT/inject.txt"
sleep 4
# 3) 주행(위치) → 급제동 2회
geo; sleep 1
drive 6 50
drive 1 15
drive 4 35
drive 1 8
drive 3 30
adb exec-out screencap -p > "$OUT/02_rainy_night.png"
sleep 9
# 4) 연료 부족 (FUEL_LEVEL, ml)
CAP=$(adb shell cmd car_service get-property-value 0x11600106 2>/dev/null | grep -oE "floatValues: \[[0-9.]+" | grep -oE "[0-9.]+$" | head -1)
LOW=$(python3 -c "c=float('${CAP:-50000}' or 50000); print(int(c*0.07))")
VH inject-vhal-event 0x11600307 "$LOW" | tee -a "$OUT/inject.txt"
sleep 10
adb exec-out screencap -p > "$OUT/03_fuel.png"
# 5) 비 그치고 주차(P) → 운행 종료
VH inject-vhal-event 0x13400CC8 1 1 >/dev/null
VH inject-vhal-event 0x11400400 4 | tee -a "$OUT/inject.txt"
sleep 8
# 6) 장시간 정차는 20분이 걸려 시연 버튼(시뮬레이터)으로 보여 줍니다
adb shell am broadcast -a $APP.SIM -n $APP/.vehicle.SimulationReceiver --es scenario long_stop >/dev/null
sleep 12
adb exec-out screencap -p > "$OUT/04_before_toggle.png"
wait $REC || true
sleep 3
adb pull /data/local/tmp/mosim_pleos.mp4 "$OUT/mosim_pleos_raw.mp4" || echo "pull failed"
adb logcat -d -s PleosSignalSource:* Voice:* AlertSender:* CarApiSignalSource:* AndroidRuntime:E > "$OUT/logcat_app.txt"
adb logcat -d | grep -iE "pleos|vehicle|fused|tts" | tail -300 > "$OUT/logcat_pleos.txt"
adb exec-out screencap -p > "$OUT/05_final.png"
ls -la "$OUT"
