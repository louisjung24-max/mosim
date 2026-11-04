# 모심 (MOSIM)

차가 밤길·빗길·배터리 부족 같은 순간을 알아채 부모님께 먼저 다정하게 말을 걸고, **부모님이 고르고 차 화면에서 먼저 확인한** '평소와 다른 신호'만 자녀에게 전하는 Pleos Connect 동승 케어 앱.

> 부모님이 보여주고 싶은 만큼만, 자녀는 이상 신호만.

현대자동차그룹 차량용 앱 개발 공모전(Code your App into Pleos App Market) 출품작.

## 핵심: 부모님 먼저 (Parent-first)

| 단계 | 부모님(차 안) | 자녀 |
|---|---|---|
| 고르기 | 설치 직후 공유 항목은 **모두 꺼짐**. 주차(P) 중에 직접 켬 | — |
| 먼저 보기 | 가족에게 갈 신호는 차 화면에 **30초 먼저** 표시 | — |
| 거부하기 | 멈춰 있을 때 **'보내지 않기'** → 보내지 않음 | 받지 않음 |
| 끊기 | 주차 중 **'연결 해제'** → 서버 기록 삭제, 새 QR | "전달된 소식이 없습니다" |

- 위치·속도·제동 원값을 보내는 항목과 코드가 없습니다. 나가는 것은 제목과 한 줄 설명뿐입니다.
- '가족에게 전송됨'은 서버 저장이 확인됐을 때만 표시합니다(보내지 않음·서버 미설정·실패를 구분).
- 운전 중에는 설정이 잠기고, 급하지 않은 안내는 다음에 멈췄을 때 말합니다.
- 운행 10회(약 2주) 학습 전에는 '평소와 다름' 신호를 만들지 않습니다(배터리·연료 부족 제외).

## 구성

| 폴더 | 내용 |
| --- | --- |
| `app/` | 차량용 앱 (Kotlin, Android Automotive OS, minSdk 28) |
| `worker/` | 알림 서버 (Cloudflare Workers + KV) — 자녀 웹 페이지, 입력 검증, 연결 해제(기록 삭제), 조회 제한 |
| `apk/` | 릴리스 APK (`정세준_모심_release.apk`, CI 자동 빌드) |
| `demo/` | 시연 영상 `모심_시연영상_90초.mp4`, Pleos 에뮬레이터 원본 녹화·캡처(`demo/pleos/`) |
| `ci/` | Pleos 에뮬레이터 자동 시연 스크립트(Virtual Console HTTP API 조작) |

## 실행 방법

### 차량용 앱 (Pleos Connect 에뮬레이터 권장)
1. Android Studio(JDK 17)에서 프로젝트 열기
2. SDK Manager → SDK Update Sites에 Pleos 이미지 사이트 추가 → Automotive → **Pleos Connect v2.1.0**(API 34) AVD 생성, RAM 4GB
3. `adb install -r apk/정세준_모심_release.apk` 후 실행 → 첫 화면에서 공유 항목 고르기 → '이대로 시작하기'
4. 에뮬레이터 Virtual Console에서 기어(D/P), 앞 와이퍼, 속도·브레이크, 배터리(SOC)를 바꾸면 실제 SDK 신호로 동작합니다.
5. 릴리스 APK에서 시연 버튼이 필요하면 앱 이름 '모심'을 길게 누르세요(시연 모드). 외부에서 신호를 주입하는 수신기는 디버그 빌드에만 있습니다.

### 알림 서버
```
cd worker && npm i
npx wrangler kv namespace create EVENTS      # 출력된 id를 wrangler.toml에 기입
npx wrangler secret put DEVICE_TOKEN         # Secret으로만 설정(앱 빌드의 MOSIM_DEVICE_TOKEN과 같은 값)
npx wrangler deploy
```
- 배포 주소는 GitHub Secret `MOSIM_ALERT_ENDPOINT`, 기기 토큰은 GitHub Secret `MOSIM_DEVICE_TOKEN`으로 넣으면 CI 빌드 APK에 들어갑니다. 저장소에는 어떤 비밀값도 두지 않습니다.
- 자녀 페이지: 차 화면의 QR(`https://<worker>/f/<가족코드 10자리>`).
- 카카오톡 '나에게 보내기'(KAKAO_* Secret)는 설정된 계정 한 곳으로만 가는 시연용 경로입니다. 가족별 카카오 알림은 사업자 알림톡 연동이 필요합니다(2차 과제).

## 시연 영상은 어떻게 만들었나 (`.github/workflows/demo-pleos.yml`)
GitHub Actions에서 Pleos Connect 에뮬레이터 v2.1.0을 띄우고, 같은 러너에서 알림 서버(wrangler dev)를 실행합니다. 디버그 APK는 `http://10.0.2.2:8787`로 실제 전송하고, 자녀 페이지는 Playwright로 캡처합니다.
- 차량 신호: Virtual Console HTTP API(`/vehicle/gear`, `/vehicle/body?front_wiper=`, `/vehicle/controls?speed=&brake=`, `/vehicle/ev-battery`)
- 화면 버튼: 이 이미지에서 uiautomator가 동작하지 않아, 디버그 빌드 전용 브로드캐스트로 같은 버튼 동작(performClick)을 재현
- 시연을 위해 앞당긴 것: '2주 학습'(디버그 전용 학습 데이터), '운행 중 20분 정차'(시연용 신호). 영상에 표시합니다.

## 테스트 환경과 확인 범위
- Pleos Connect 에뮬레이터 v2.1.0 (Android 14, API 34, x86_64): 기어·와이퍼·배터리·브레이크·차속 신호, 서버 저장, 연결 해제 후 삭제 확인
- 일반 AAOS 에뮬레이터: Pleos 서비스가 없으면 15초 뒤 AAOS 표준 차량 속성으로 대체
- 확인하지 못한 것: 실차 주행, 한국어 음성 재생(에뮬레이터에 음성 엔진 없음 → 화면 문구), 운영 서버 배포

## 사용한 Pleos Connect SDK (모두 공개 API)
| SDK | 버전 | 쓰는 곳 |
|---|---|---|
| Vehicle | 2.3.2 | 기어 P↔D(운행 시작·종료), 앞유리 와이퍼(비), 전기차 배터리(SOC)·연료 잔량, 이번 운행 거리, 브레이크 페달 |
| TextToSpeech | 3.3.1 | 차 안 음성 안내. ON_DEVICE 모드(앱 등록 키 불필요, 문장이 차 밖으로 나가지 않음). 실패 시 Android TTS |
| FusedLocation | 1.1.3 | 차속 신호가 없을 때만 위치 변화로 감속을 계산하는 보조 경로(실차 검증 전) |
| (표준 AAOS) CarPropertyManager | – | 차속(PERF_VEHICLE_SPEED) → 급제동·오래 멈춤·운행 거리. Vehicle SDK 2.3.2에 차속 API가 없음 |

권한: `pleos.car.permission.CAR_DRIVE · CAR_ENERGY · CAR_INFO · CAR_WINDOWS · TTS_SERVICE · FUSED_LOCATION`, `android.car.permission.CAR_SPEED`(위치·차속은 실행 중 허용 요청). 제어(CONTROL_*) 권한은 쓰지 않습니다.

## 판정 기준 (실험 규칙, 실차 데이터로 조정 예정)
| 신호 | 기준 |
|---|---|
| 평소와 다른 밤 외출 | 학습 후, 평소 운행 이력이 없는 21~5시에 출발 |
| 평소보다 먼 길 | 이번 운행 거리 ≥ 평소 거리(지수이동평균) × 3 |
| 운행 중 오래 멈춤 | 기어 D에서 2 km/h 미만 20분 |
| 급제동이 잦은 날 | 3.5 m/s² 이상 감속 2회 이상이고 평소의 2배 이상 (기본 공유 꺼짐) |
| 배터리·연료 부족 | 10% 이하 (학습과 무관) |

## 구조
```
vehicle/   PleosSignalSource(Pleos SDK) · CarApiSignalSource(표준 AAOS 차속) · SimulatedSignalSource(시연)
rules/     AnomalyDetector — 차 안에서 판정, 결과 한 줄만 밖으로
data/      Baseline(평소 패턴, 차 안 저장) · ShareSettings(공유 항목·가족 코드) · WeeklyStore
alert/     AlertGate(부모님 먼저: 30초 확인·보내지 않기) · AlertSender(전송·연결 해제, 결과 구분)
voice/     PleosVoiceOutput(Pleos TTS) → AndroidVoiceOutput
service/   DriveMonitorService — 신호 병합, 오래 멈춤·운행 거리, 정차 시 말하기
ui/        MainActivity — 대화면 한 장(첫 설정, 확인 카드, QR 연결·해제)
```
