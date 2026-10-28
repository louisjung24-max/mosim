# 모심 (MOSIM)

고령 부모님의 차가 야간·우천·급제동 같은 위험 순간을 스스로 알아채고 다정한 음성으로 말을 걸며, 부모님이 허락한 이상 신호만 자녀에게 전하는 Pleos Connect 동승 케어 앱.

> 부모님이 보여주고 싶은 만큼만, 자녀는 이상 신호만.

현대자동차그룹 차량용 앱 개발 공모전(Code your App into Pleos App Market) 출품작.

## 구성

| 폴더 | 내용 |
| --- | --- |
| `app/` | 차량용 앱 (Kotlin, Android Automotive OS, minSdk 28) |
| `worker/` | 알림 서버 (Cloudflare Workers) — 자녀 웹 페이지, 카카오톡 전송 |
| `apk/` | 릴리스 APK (`정세준_모심_release.apk`, CI 자동 빌드) |
| `demo/` | 시연 영상 `모심_시연영상_90초.mp4`, 에뮬레이터 원본 녹화, 화면 캡처 (CI 자동 녹화 + 편집) |

## 실행 방법

### 차량용 앱
1. Android Studio에서 프로젝트 열기 (JDK 17, Gradle 8.9, AGP 8.5)
2. Automotive 에뮬레이터 생성: Device Manager → Automotive → Automotive (1024p landscape), API 33 x86_64
3. 실행 후 화면 하단 **데모** 버튼으로 시나리오 재생, 또는 adb:
   ```
   adb shell am broadcast -a com.hiddenframe.mosim.SIM -n com.hiddenframe.mosim/.vehicle.SimulationReceiver --es scenario rainy_night
   adb shell am broadcast -a com.hiddenframe.mosim.SIM -n com.hiddenframe.mosim/.vehicle.SimulationReceiver --es scenario tire_low
   adb shell am broadcast -a com.hiddenframe.mosim.SIM -n com.hiddenframe.mosim/.vehicle.SimulationReceiver --es scenario long_stop
   adb shell am broadcast -a com.hiddenframe.mosim.SIM -n com.hiddenframe.mosim/.vehicle.SimulationReceiver --es scenario daytime
   ```
4. APK 직접 설치: `adb install -r apk/정세준_모심_release.apk`

### 알림 서버 (선택)
```
cd worker && npm i
npx wrangler kv namespace create EVENTS      # 출력된 id를 wrangler.toml에 기입
npx wrangler secret put KAKAO_REST_KEY       # Secret으로만 설정
npx wrangler secret put KAKAO_REFRESH_TOKEN
npx wrangler deploy
```
배포 주소를 GitHub Secret `MOSIM_ALERT_ENDPOINT`에 넣으면 CI 빌드 APK가 서버로 알림을 보냅니다. 자녀 페이지: `https://<worker>/f/<가족코드>`

## 테스트 환경
- 에뮬레이터: Android Automotive 13 (API 33) x86_64, automotive_1024p_landscape
- 한국어 TTS 엔진이 없는 에뮬레이터에서는 음성 대신 화면 자막으로 표시됩니다.
- Pleos Connect 에뮬레이터 v2.1.0(API 34)에서는 Virtual Console로 기어·와이퍼·연료를 바꾸면 실제 SDK 경로(`PleosSignalSource`)가 동작합니다.
- 일반 AAOS 에뮬레이터에서는 Pleos 서비스가 없으므로 3초 안에 응답이 없으면 AAOS 기본 차량 속성(`CarApiSignalSource`)과 Android TTS로 자동 전환됩니다.

## 사용한 Pleos Connect SDK (모두 공개 API)
| SDK | 버전 | 쓰는 곳 |
|---|---|---|
| Vehicle | 2.3.2 | 기어 P↔D(운행 시작·종료), 앞유리 와이퍼(비), 연료 잔량(차량 관리), 이번 운행 거리(장거리), 브레이크 페달(급제동 보조) |
| FusedLocation | 1.1.3 | 위치 변화로 감속도 계산 → 급제동·장시간 정차 판정 (Vehicle SDK에 차속 API가 없어 대체). 위치는 차 밖으로 보내지 않음 |
| TextToSpeech | 3.3.1 | 차 안 음성 안내. ON_DEVICE 모드라 앱 등록(Client ID/Secret)이 필요 없고 문장이 서버로 나가지 않음 |

권한: `pleos.car.permission.CAR_DRIVE · CAR_POWERTRAIN · CAR_ENERGY · CAR_MILEAGE · CAR_INFO · READ_WINDSHIELD_WIPERS · TTS_SERVICE · FUSED_LOCATION`(실행 중 허용 요청). 제어(CONTROL_*) 권한은 쓰지 않습니다.

## 구조
```
vehicle/   VehicleSignalSource — PleosSignalSource(실차) · CarApiSignalSource(일반 AAOS) · SimulatedSignalSource(시연)
rules/     AnomalyDetector — 차 안에서 판정, 결과만 밖으로
data/      Baseline(평소 패턴, 차량 내 저장) · ShareSettings(부모님이 고르는 공유 범위) · WeeklyStore
voice/     VoiceOutput — PleosVoiceOutput(Pleos TTS, ON_DEVICE) → 없으면 AndroidVoiceOutput
alert/     AlertSender — 허용된 신호만 서버로
service/   DriveMonitorService — 시동 중 상시 동작
ui/        MainActivity — 대화면 큰 글씨, 공유 설정, 데모 버튼
```

## 개인정보 설계
- 원시 주행 데이터(속도·제동·위치)는 차량 밖으로 나가지 않습니다. 판정 결과(제목·한 줄 설명)만 전송됩니다.
- 공유 항목은 차량 소유자가 직접 켜고 끄며, 전체 공유를 한 번에 끌 수 있습니다.
- 실시간 위치·운전 점수는 기본값이 꺼짐입니다.
