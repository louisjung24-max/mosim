#!/usr/bin/env bash
# Pleos Connect 에뮬레이터 v2.1.0(API 34)에서 실제 Pleos SDK 경로로 시연을 녹화합니다. (GitHub Actions용)
# 라이선스: 42dot Pleos Connect SDK License Agreement — 저장소 소유자(정세준)가 동의함 (2026-10-28).
# 차량 신호는 에뮬레이터 안의 Pleos Virtual Console(HTTP API)로 바꾸고, 알림 서버는 같은 러너에서 띄운 로컬 Worker(10.0.2.2:8787)로 실제 저장합니다.
set -uo pipefail
# 이미지의 source.properties가 태그를 android-automotive로 선언하므로 그 경로에 둡니다
PKG="system-images;android-34;android-automotive;x86_64"
IMG_DIR_REL="system-images/android-34/android-automotive/x86_64"
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

if [ ! -f "$SDK/$IMG_DIR_REL/system.img" ] && false; then
  log "pleos image via sdkmanager (user source)"
  printf '### User Sources for Android SDK Manager\ncount=1\nsrc00=%s\nenabled00=true\ndisp00=Pleos Connect\n' "$(echo "$XML_URL" | sed 's/:/\\:/')" > "$HOME/.android/repositories.cfg"
  yes 2>/dev/null | sdkmanager --sdk_root="$SDK" --licenses 2>&1 | grep -iE "pleos|accept" | head -5
  yes 2>/dev/null | sdkmanager --sdk_root="$SDK" --install "system-images;android-34;pleos_car_emulator;x86_64" 2>&1 | tr '\r' '\n' | grep -vE "^\s*$|%" | tail -8
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
[ "${1:-}" = "fetch" ] && exit 0

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

log "time zone: Asia/Seoul"
adb shell settings put global auto_time_zone 0
adb shell service call alarm 3 s16 Asia/Seoul >/dev/null 2>&1 || adb shell setprop persist.sys.timezone Asia/Seoul
adb shell date
# 런처·시스템 UI가 완전히 뜰 때까지 대기
for i in $(seq 1 40); do adb shell dumpsys activity activities 2>/dev/null | grep -q "ResumedActivity" && break; sleep 5; done
sleep 45

log "install"
for i in $(seq 1 8); do
  adb install -r -g app/build/outputs/apk/debug/app-debug.apk && adb shell pm path $APP >/dev/null 2>&1 && break
  echo "install retry $i"; sleep 15
done
adb shell pm path $APP || { echo "INSTALL FAILED"; exit 1; }
adb shell dumpsys package $APP | grep -E "permission.*granted=true" | tee "$OUT/granted.txt"
# 시연 전제: 지난 두 달의 운행 기록 — 디버그 빌드 전용 시연 데이터(실제 기록 아님)
adb shell am broadcast -a $APP.SIM -n $APP/.vehicle.SimulationReceiver --es scenario seed_month
adb logcat -c
for i in $(seq 1 12); do
  adb shell am start -W -n $APP/.ui.MainActivity >/dev/null 2>&1
  sleep 8
  adb shell dumpsys activity activities | grep -q "ResumedActivity.*$APP" && break
done
adb shell dumpsys activity activities | grep "ResumedActivity" | head -2
# AAOS는 앱이 사용자 10으로 돌아 run-as로 설정 파일을 못 읽습니다 → 디버그 빌드가 남기는 로그에서 가족 코드를 읽습니다
for i in $(seq 1 12); do
  CODE=$(adb logcat -d -s MosimDemo:I | grep -o 'family code [A-Z0-9]*' | head -1 | awk '{print $3}')
  [ -n "$CODE" ] && break; sleep 5
done
echo "family code: $CODE" | tee "$OUT/family_code.txt"
adb exec-out screencap -p > "$OUT/01_first_run.png"
curl -s -m 5 -o /dev/null -w "worker from host: %{http_code}\n" http://127.0.0.1:8787/ || true

log "virtual console"
adb forward tcp:8088 tcp:8088
( while true; do curl -s -m 3 -X POST "http://127.0.0.1:8088/browser/session?client=mosim-ci" >/dev/null 2>&1; sleep 1; done ) &
HB=$!
VC() { curl -sS -m 10 -X PUT "http://127.0.0.1:8088$1" -o /dev/null -w "PUT $1 -> %{http_code}\n" | tee -a "$OUT/vc_calls.txt"; }
ctl() { VC "/vehicle/controls?speed=$1&accelerator=$2&brake=$3&steering=0" >/dev/null; }
drive() { for _ in $(seq 1 "$1"); do ctl "$2" "$([ "${3:-0}" -gt 0 ] && echo 0 || echo 30)" "${3:-0}"; sleep 1; done; }
# uiautomator는 Pleos 이미지에서 연결이 안 되어, 디버그 빌드 전용 브로드캐스트로 같은 버튼 동작을 누릅니다
# Pleos는 기어 변경 시 주행 화면·내비가 앞으로 올 수 있어, 모심 화면을 다시 앞으로 가져옵니다(singleTask, 재생성 없음)
front() { adb shell am start -n $APP/.ui.MainActivity >/dev/null 2>&1; }
TAP() { adb shell am broadcast -a $APP.UI --es click "$1" >/dev/null; echo "click $1" | tee -a "$OUT/taps.txt"; }
ctl 0 0 0; VC "/vehicle/gear?gear=Park"; VC "/vehicle/body?front_wiper=Off"
python3 ci/vconsole.py set "soc|state.?of.?charge" 60 >/dev/null 2>&1 || true
sleep 3

for i in $(seq 1 10); do
  front; sleep 4
  adb shell dumpsys activity activities | grep -m1 "topResumedActivity" | grep -q "$APP" && break
done
adb shell dumpsys activity activities | grep -m1 "topResumedActivity"
adb exec-out screencap -p > "$OUT/01_before_record.png"

log "record"
T0=$(date +%s); mark() { echo "$(( $(date +%s) - T0 ))s $*" | tee -a "$OUT/timeline.txt"; }
adb shell "screenrecord --time-limit 140 --bit-rate 8000000 /data/local/tmp/mosim_pleos.mp4" &
REC=$!
sleep 3
# 1) 처음 한 번: 부모님이 "좋아요, 매달 보내 주세요"
mark "consent"
sleep 9
adb exec-out screencap -p > "$OUT/01_consent.png"
TAP consent; sleep 7
# 2) 이달 첫 출발(기어 D) → 지난달 운전 요약이 자동으로 가족에게(실제 서버 저장) → 보낸 뒤 차가 알려 드림
mark "gear D -> monthly summary"
VC "/vehicle/gear?gear=Drive"; sleep 1; front; sleep 9
python3 ci/family_shot.py "http://127.0.0.1:8787/f/$CODE" "$OUT/family_summary.png" || true
# 3) 비 오는 밤길: 와이퍼, 급제동 두 번(차 안에서만, 안내는 멈췄을 때)
mark "rain + hard brakes"
VC "/vehicle/body?front_wiper=Low"; sleep 2
drive 6 50
drive 1 15 100
drive 4 35
drive 1 8 100
drive 2 30
# 4) 배터리 8% → 그때그때 알림(가족에게) + 보낸 뒤 알려 드림
mark "battery 8%"
python3 ci/vconsole.py set "soc|state.?of.?charge" 30 | tee -a "$OUT/vc_calls.txt"
drive 2 30
python3 ci/vconsole.py set "soc|state.?of.?charge" 8 | tee -a "$OUT/vc_calls.txt"
drive 3 30
# 배터리 콜백이 오지 않았으면 한 번 더(에뮬레이터 콜백 지연 대비)
if [ "$(adb logcat -d -s AlertSender:* 2>/dev/null | grep -c 'api/event -> 200')" -lt 2 ]; then
  python3 ci/vconsole.py set "soc|state.?of.?charge" 7 | tee -a "$OUT/vc_calls.txt"
fi
drive 3 30
adb exec-out screencap -p > "$OUT/02_driving.png"
# 5) 천천히 멈춤 → 미뤄 둔 안내를 이때 말함
mark "slow stop"
drive 1 20 30; drive 1 10 30; drive 3 0 40
sleep 4
adb exec-out screencap -p > "$OUT/03_stopped.png"
python3 ci/family_shot.py "http://127.0.0.1:8787/f/$CODE" "$OUT/family_after_send.png" || true
# 6) 주차 → 이번 운행이 '나의 운전 기록'에 쌓임
mark "park"
VC "/vehicle/body?front_wiper=Off"; VC "/vehicle/gear?gear=Park"; sleep 1; front; sleep 6
# 7) 보내기 싫으면 설정에서 한 번: '매달 운전 요약' 끄기
mark "settings: monthly off"
TAP share:monthly; sleep 8
adb exec-out screencap -p > "$OUT/04_settings_off.png"
mark "end"
sleep 4
adb shell pkill -INT screenrecord || true
wait $REC || true
kill $HB 2>/dev/null || true
sleep 3
adb pull /data/local/tmp/mosim_pleos.mp4 "$OUT/mosim_pleos_raw.mp4" || echo "pull failed"
adb logcat -d -s PleosSignalSource:* Voice:* AlertSender:* CarApiSignalSource:* AndroidRuntime:E > "$OUT/logcat_app.txt"
ls -la "$OUT"
