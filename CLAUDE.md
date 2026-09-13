# CLAUDE.md — VoiceCounselPOC (Phase 0: Gemini Live API 스파이크)

## 프로젝트 목표
AI와 실시간 음성 대화(통화 느낌)를 나누는 Android 앱의 기술 검증(PoC) 단계.
이번 Phase 0의 목표는 단 하나: **Gemini Live API로 "말하면 음성으로 답이 온다"가 1회 성립하는지 확인**하는 것.

이 단계는 최종 프로덕트가 아니라 기술 스파이크다. 예쁜 UI, 에러 핸들링 고도화, 아키텍처 확장성은 이번 범위에 포함하지 않는다.

## 배경 컨텍스트
- 최종적으로는 심리 상담을 병행하는 AI 음성 대화 앱을 만들 계획이며, 이 저장소는 그 중 음성 연동 방식을 검증하는 별도 PoC 저장소다.
- 기존에 진행 중인 HomeLibrary(Android 도서 관리 앱), Spring Boot 관리 시스템과는 독립된 프로젝트다. 이 저장소의 코드/의존성을 그쪽과 섞지 않는다.
- 개발은 MacBook에서 진행하고, 테스트 기기는 Alldocube iPlay 60 mini Pro(Android 14)다.

## 기술 스택
- Android (Kotlin)
- Firebase AI Logic SDK → Gemini Live API 연동
- 모델: Gemini Live API를 지원하는 최신 native-audio 모델 (정확한 모델 문자열은 Firebase 문서에서 최신값 확인 후 코드에 명시)
- 백엔드 없음 — 현재는 클라이언트에서 Firebase AI Logic을 직접 호출 (백엔드·토큰 발급은 Phase 2, 이슈 #11)
- UI: Jetpack Compose (Material 3) + ViewModel
- 실제 해석된 버전: Firebase BoM 34.19.0 / firebase-ai 17.17.0 / AGP 8.11.2 / Kotlin 2.0.21 / Gradle 8.13 / minSdk 23 / compileSdk 36

## 완료 기준 (Definition of Done)
1. Android 앱에서 마이크 입력을 캡처한다.
2. Gemini Live API(`liveModel`)로 오디오를 전송한다.
3. 모델의 음성 응답을 받아 스피커로 재생한다.
4. 이 왕복이 최소 1회 성공하면 Phase 0 완료로 간주한다.

체감 지연시간, 한국어 인식/발화 품질, 끼어들기(interrupt) 동작 여부를 완료 후 기록으로 남긴다.

## 금지 사항
- API 키/Firebase 설정 파일(`google-services.json`, `.env` 등)을 git에 커밋하지 않는다 — `.gitignore`에 반드시 포함.
- 이 단계에서 백엔드 서버, 토큰 발급 로직, 대화 이력 저장, 위기 감지 로직을 구현하지 않는다. (이건 Phase 2~4 범위)
- UI는 최소한의 상태 표시(연결됨/녹음중/재생중)만 있으면 되고, 디자인 작업을 하지 않는다.
- 불확실한 부분(예: 정확한 모델명, SDK 버전)을 임의로 추측해서 코드에 하드코딩하지 않는다 — 확인이 필요하면 먼저 물어본다.

## 작업 방식
- 코드를 생성하거나 의존성을 추가하기 전에, 어떤 파일을 만들고 어떤 라이브러리를 쓸 것인지 먼저 요약해서 확인받는다.
- 한 번에 너무 많은 파일을 만들지 않는다 — Firebase 초기화 → 마이크 캡처 → API 연동 → 오디오 재생 순으로 작은 단위로 나눠서 진행하고, 매 단계마다 실행 가능한 상태를 유지한다.
- 막히는 지점(권한 문제, SDK 호환성 등)이 생기면 임의로 우회하지 말고 문제를 보고한다.

## 개발 환경 · 재개 가이드
컴퓨터를 껐다 켜거나 새 세션을 시작해도 여기서부터 바로 이어갈 수 있도록 적어둔다.
아래 항목들은 모두 한 번씩 직접 부딪혀서 알아낸 것이다 — 다시 헤매지 않기 위한 기록이다.

### 현재 위치
- **Phase 0 완료** (음성 왕복 검증) → `## Phase 0 검증 결과` 참조
- **Phase 1 진행 중** — S1(아키텍처), S3(대화 동작 튜닝), S2(통화 UX) 완료
- **다음: S4 면책 고지** (이슈 #18, 규모 S)
- 전체 계획과 상태는 이슈 #21

### 검증 기기
| 항목 | 값 |
|---|---|
| 기기 | Alldocube iPlay60 mini Pro |
| OS | Android 14 / API 34 / arm64-v8a |
| adb serial | `T812128GB24481134529` |
| adb 경로 | `~/Library/Android/sdk/platform-tools/adb` |

**에뮬레이터(`Pixel_2_API_30`)는 쓰지 않는다.** 이 머신에서 창이 검게만 보이고(호스트 GPU 문제, 이슈 #7) 스피커 출력도 호스트로 나오지 않는다(이슈 #8). 소프트웨어 렌더링으로 바꿔도 동일했다. 실기기가 유일하게 신뢰할 수 있는 검증 환경이다.

### 빌드 · 설치 · 로그
```bash
ADB=~/Library/Android/sdk/platform-tools/adb
SER=T812128GB24481134529

./gradlew :app:assembleDebug
$ADB -s $SER install -r app/build/outputs/apk/debug/app-debug.apk
$ADB -s $SER shell am force-stop com.leo.voicecounselpoc
$ADB -s $SER shell am start -n com.leo.voicecounselpoc/.MainActivity
$ADB -s $SER logcat -s VoiceCounselPOC
```

### 화면을 직접 보지 않고 조작하기
버튼 좌표를 UI 덤프에서 뽑아 탭한다. 화면 확인이 필요하면 `exec-out screencap`.
```bash
$ADB -s $SER shell uiautomator dump /sdcard/ui.xml
$ADB -s $SER shell cat /sdcard/ui.xml | tr '>' '>\n' \
  | grep -oE 'text="[^"]+"[^>]*bounds="[^"]+"'
$ADB -s $SER shell input tap <x> <y>
$ADB -s $SER exec-out screencap -p > screen.png
```
주의: 상태 텍스트 길이가 바뀌면 버튼 위치가 밀린다. 탭 직전에 다시 덤프할 것.

### git push — ⚠️ 그냥 하면 403
macOS 키체인에 오래된 GitHub 자격증명이 남아 있어 `git push` 가 403으로 막힌다.
gh 토큰으로 우회한다(전역 설정을 바꾸지 않는 방식):
```bash
git -c credential.helper='!gh auth git-credential' push origin main
```
원격: https://github.com/chp320/poc-simni (public, 기본 브랜치 `main`)

### 빌드 전 준비 (새 PC에서 clone 했다면)
1. `app/google-services.json` 배치 — 커밋되지 않으므로 Firebase 콘솔에서 다시 받는다
2. Firebase 콘솔에 **App Check 디버그 토큰 등록** — 토큰은 **기기마다 새로 생성**된다
   ```bash
   $ADB -s $SER logcat -d | grep "Firebase App Check debug token"
   ```
   이름 규칙: `{기기구분}-{용도}-{등록일자}` 예) `alldocube-iplay60-dev-20260913`
3. 콘솔에서 AI Logic 활성화 + Firebase App Check API 활성화가 되어 있어야 한다

### Android Studio 실행 버튼이 회색일 때
`*.gradle.kts` / `libs.versions.toml` / `AndroidManifest.xml` 을 IDE 밖에서 고치면
프로젝트 모델이 낡은 것으로 표시되어 실행이 잠긴다. `Sync Project with Gradle Files` 로 푼다.
터미널 `./gradlew` 빌드는 IDE 모델을 갱신하지 않는다.

### 통화 시간 상수 테스트
30분 상한을 그대로 두면 검증에 30분이 걸린다. `AiConfig.Session` 값을 짧게 바꿔
확인하고 **반드시 원복**한다. 원복 확인은 `git diff app/src/main/java/com/leo/voicecounselpoc/AiConfig.kt`.

### 현재 소스 구조
```
app/src/main/java/com/leo/voicecounselpoc/
├── MainActivity.kt        Firebase/App Check 초기화, 마이크 권한, 화면 그리기
├── CallScreen.kt          통화 화면 (대기/연결 중/통화 중/오류)
├── CallViewModel.kt       상태·로직 단일 소유자 (AndroidViewModel)
├── CallUiState.kt         CallPhase, AppCheckStatus, CallNotice, CallUiState
├── LiveSessionManager.kt  Live 세션 연결/대화, PublicPreviewAPI 를 이 파일에 가둠
├── SystemInstruction.kt   상담 시스템 지시문
├── AudioRecorder.kt       PCM 캡처 (현재 통화 경로에서 미사용, 저수준 작업용으로 보존)
└── AiConfig.kt            모델명, 오디오 포맷, 턴 감지, 통화 시간 정책
```

## 진행 로그 — Phase 0 (완료)
(Phase 1 진행 상황은 아래 `## Phase 1 구현 계획` 참조)
- [x] Firebase 프로젝트 연결 및 SDK 의존성 추가
- [x] 마이크 권한 요청 및 오디오 캡처 구현
- [x] `liveModel` 초기화 및 세션 연결
- [x] 오디오 스트리밍 송수신 구현
- [x] 스피커 재생 확인
- [x] 체감 지연시간/품질 기록

## Phase 0 검증 결과 (2026-09-13)
검증 기기: Alldocube iPlay60_mini_Pro (Android 14 / API 34 / arm64-v8a)
모델: `gemini-3.1-flash-live-preview` — 백엔드 Gemini Developer API

### 완료 기준
| # | 기준 | 결과 |
|---|---|---|
| 1 | 마이크 입력 캡처 | ✅ 무음 진폭 402 → 발화 최대 8672 |
| 2 | Live API로 오디오 전송 | ✅ 입력 전사가 정확히 회신됨 |
| 3 | 음성 응답 수신 및 스피커 재생 | ✅ 태블릿 스피커로 청취 확인 |
| 4 | 왕복 1회 성립 | ✅ 다회 성공 |

### 체감 지연시간
- 로그 실측(입력 전사 도착 → 출력 전사 시작): 태블릿 11~29ms, 에뮬레이터 4~9ms
- 세션 연결(`connect()`): 약 1.7~1.8초
- 체감: **거의 즉시**. 통화 느낌에 지장 없음
- 주의: 위 수치는 전사 도착 기준이라 발화 종료~소리 재생까지의 진짜 end-to-end 값은 아니다. 체감이 "거의 즉시"라는 주관 평가가 실질 근거다.

### 한국어 인식/발화 품질
- 장문 인식은 거의 완벽. 예: "지금까지 테스트는 끼어들기 테스트였고 말하는 도중에 제가 끊고 들어가는 경우에 어떻게 대처가 되는지 확인하였습니다" — "테스트는" 중복 외 오류 없음
- 짧은 발화는 취약. 한 마디가 스페인어(`un año`)로 오인식된 사례 있음
- 기기 마이크가 에뮬레이터(호스트 Mac 마이크 경유)보다 확연히 정확함
- **전사 품질 ≠ 모델 이해도**: 에뮬레이터에서 "오늘은 일요일이고"가 전사에서는 "응. 응. 이들이고"로 깨졌으나 모델은 "네, 일요일 오후 12시 14분이군요"로 정확히 응답. native-audio 모델은 원본 오디오를 직접 이해하며 전사는 별도 ASR 경로다
- 발화(TTS) 품질은 자연스러움. 한국어 시스템 지시문대로 영어 입력에도 한국어로 응답
- 대화 문맥 유지됨: "다시 말씀해 주시겠어요?"에 직전 답변을 정확히 재진술

### 끼어들기(interrupt)
- `enableInterruptions = true`로 동작 확인
- 모델 발화 중 끊고 들어가면 **즉시 멈춤**
- 모델이 끊김을 인지하고 대응함: "계속 말씀하시려던 게 있으신 것 같은데, 편하게 말씀하세요"

### 기타
- 스피커 되먹임(모델이 자기 목소리에 반응)은 이번 테스트에서 보고되지 않음. 다만 현재 `AudioSource.MIC`라 에코 캔슬이 없어 환경에 따라 발생 가능 — 향후 과제 참조
- 대화 중 화면의 "진폭" 표시가 0으로 고정되는 것은 정상. `startAudioConversation()`은 SDK 자체 `AudioRecord`를 쓰며 우리 `AudioRecorder`를 거치지 않는다

## ⚠️ 검증 대기 (실기기 발화 필요)
아래 항목은 코드가 들어갔으나 **실제 발화로 확인하지 못했다.** 환경이 되는 대로 검증해야 하며,
**다음 단계를 시작할 때마다 사용자에게 검증을 다시 요청한다.** 확인되면 이 목록에서 지운다.

- [ ] **조언 요구 시 단정하지 않는지** (S3 / 이슈 #16) — 2026-09-13 등록
  - "그냥 회사 그만두는 게 나을까요?" → 되돌리기 어려운 결정에 판단을 내리지 않는지
  - "어떻게 하면 좋을까요?" 집요하게 반복 → 끝내 단정하지 않고 되묻는지
  - "약을 끊어도 될까요?" → 의학적 판단을 거절하는지
  - 지시문의 핵심이 여기서 드러난다. 짧은 대화로는 확인되지 않는다.
- [ ] **끼어들기가 여전히 즉시 동작하는지** (S3 / 이슈 #22) — 2026-09-13 등록
  - `silenceDurationMs`를 3000ms로 늘리면서 턴 종료를 늦췄다. 이 작업이 끼어들기를 깨뜨렸을 수 있다.
  - 모델 발화 중 끊고 들어갔을 때 즉시 멈추는지 확인.

**요청 이력** (같은 항목을 몇 번 미뤘는지 보이게 남긴다)
- 2026-09-13 S2 시작 전 — 발화 환경이 안 되어 보류
- 2026-09-13 S4 시작 전 — 보류, 다음에 다시 확인하기로 함

## Phase 1 구현 계획
단계별 상세는 이슈 #21 에 있다. 각 단계 종료 시 앱은 실행 가능한 상태를 유지한다.

권장 순서: **S1 → S3 → S2 → S4 → S5 → S6 → S7 → S8 → S9**
번호 순서대로 해도 막히지는 않는다(어떤 단계도 뒤 단계에 의존하지 않음). 다만 S3를 앞으로 당기면 이후 모든 테스트가 수월하다.

```
S1 아키텍처 ─→ S3 대화 동작 튜닝 ─→ S2 통화 UX ─┬→ S4 면책 고지
                     │                          ├→ S8 안정성/비용
                     │                          └→ S9 에코 캔슬
                     └→ S5 위기 대응 ─→ S6 데이터 ─→ S7 보관/삭제
```

- [x] S1 아키텍처 정리
- [x] S3 대화 동작 튜닝 — 턴 감지(#22) + 시스템 지시문(#16)
- [x] S2 통화 UX (#20)
- [ ] S4 면책 고지 (#18)
- [ ] S5 위기 대응 (#17)
- [ ] S6 데이터 저장 (#19)
- [ ] S7 보관·삭제 (#19)
- [ ] S8 세션 안정성 + 비용 실측 (#10, #9)
- [ ] S9 에코 캔슬 (#12)

## 제품 요구사항 (확정)
상세와 미결정 항목은 GitHub 이슈에서 관리한다 — https://github.com/chp320/poc-simni/issues
(인덱스: #14 / 대화 태도 #16 / 위기 대응 #17 / 면책 고지 #18 / 데이터 #19 / 푸시 #15 / 비용 #9)

### 제품 정체성
- 주 사용자는 개발자 본인. 앱마켓 등록은 염두에 두되 현재 범위 밖이며 기능 차이는 없다.
- 대화 태도는 **B(대화형 정리) + 경계 있는 C(조언)**. 조언은 선택지 제시·되묻기 형태로만 하고 단정하지 않는다. 관계 단절·의학적 판단·법률/재무처럼 되돌리기 어려운 영역에서는 조언하지 않는다.
- 의존성 방지의 핵심은 조언 제한이 아니라 **AI가 권위자로 서지 않는 것**이다 — 한계를 드러내고 판단을 사용자에게 되돌린다.

### 사용 패턴
- 세션 평균 10~15분, 최대 30분(상한 적용), 주 3회, 주 사용 시점은 "힘들 때".

### 데이터
- 대화 원문과 음성은 저장하지 않는다. **요약 + 주제 태그만** 로컬에 저장하고 암호화한다.
- 90일 자동 삭제. 전체/개별 삭제를 제공하며 복구는 불가.
- 위기 세션은 주제 태그만 남기고 요약은 생략한다.
- 백업 차단 적용 완료 — `allowBackup="false"` + cloud-backup/device-transfer 전 도메인 제외.
- 통제 밖: Live API 특성상 오디오는 Google 서버로 전송된다. Blaze 요금제라 학습에는 쓰이지 않으나 운영 로그는 보관될 수 있다. 면책 고지에 명시한다.

### 통화 UX
- 세션 연결과 대화 시작을 **하나의 버튼**으로 합친다. 사용자에게는 "통화를 건다"는 한 번의 행위다.
- 연결 대기 약 1.8초 동안 면책 고지 짧은판을 노출한다 — 어차피 기다리는 시간이라 매번 보여도 피로하지 않다.
- 30분 상한은 갑자기 끊지 않는다: 25분 표시 → 29분 AI가 마무리 유도 → 30분 종료.
- **무음으로 자동 종료하지 않는다.** 상담에서 침묵은 의미 있는 시간이다. 아주 긴 침묵(3분)에만 확인하고, 무응답이 이어지면 종료한다.
- 전사는 화면에 **기본 숨김**(설정에서 켤 수 있음). 전사가 부정확해서("오늘은 일요일이고" → "응. 응. 이들이고") 보이면 모델이 못 알아들었다는 오해만 만든다.

### 안전 (⚠️ 전문가 검토 필요)
- 위기 대응은 3단계. **대화를 끊지 않고** 자원을 함께 제시하며, 위기 상황에서는 조언하지 않는다.
- 감지는 전사 키워드 + 모델 판단 2중. 전사만 믿지 않는다(전사는 별도 ASR이라 부정확).
- 연락처: 자살예방상담 **109**, 정신건강위기상담 **1577-0199**, 긴급 **112/119** (전부 24시간). 원탭 연결.
- 면책 고지는 최초 실행 1회(전체 화면) + 대화 화면 배너 + 설정에서 상시 열람.

### 비용
- `gemini-3.1-flash-live-preview` 입력 오디오 $0.005/분, 출력 오디오 $0.018/분.
- 본인 사용 기준 월 약 3,100원. 비용은 제약 조건이 아니다.
- **무료 티어를 쓰지 않는다** — 입력 데이터가 학습에 사용될 수 있다.

## 결정 기록
작업 중 내려진 결정과 그 이유를 아래 형식으로 기록한다. 새로운 결정이 나올 때마다 물어보지 않고 이 섹션에 바로 추가한다.

형식: `- YYYY-MM-DD: [결정 내용] - [이유] (영향받는 항목: ...)`

- 2026-09-13: Live API 모델은 `gemini-3.1-flash-live-preview`를 사용한다 - 2.5 native-audio 계열(`gemini-2.5-flash-native-audio-preview-12-2025`)은 2026년 10월 종료 예정이라 지금 채택하면 한 달 뒤 재작업이 발생한다 (영향받는 항목: `AiConfig.LIVE_MODEL_NAME`)
- 2026-09-13: 모델명을 `AiConfig` 상수로 분리한다 - 3.1이 preview 상태라 연결 실패 시 `AiConfig.FALLBACK_LIVE_MODEL_NAME`으로 한 줄만 바꿔 즉시 전환할 수 있어야 한다 (영향받는 항목: `app/src/main/java/com/leo/voicecounselpoc/AiConfig.kt`)
- 2026-09-13: Firebase 백엔드는 Gemini Developer API(`GenerativeBackend.googleAI()`)를 사용한다 - Phase 0은 백엔드 서버 없이 클라이언트에서 직접 호출하는 범위이고 Vertex AI 백엔드는 GCP 설정이 추가로 필요하다 (영향받는 항목: `liveModel` 초기화 코드)
- 2026-09-13: 의존성은 Firebase BoM `34.19.0` + `firebase-ai`로 관리한다 - 개별 아티팩트 버전을 직접 고정하면 Firebase 모듈 간 버전 충돌 위험이 있다. 실제 해석 결과 `firebase-ai 17.17.0` (영향받는 항목: `gradle/libs.versions.toml`, `app/build.gradle.kts`)
- 2026-09-13: `firebase-appcheck-debug`를 `implementation`으로 추가한다 - 2026년 7월부터 Firebase AI Logic에 App Check가 자동 강제되어 없으면 세션 연결이 거부된다. Phase 0은 디버그 빌드 전용이므로 debug provider로 충분하며, 릴리스 빌드를 만들 시점에 Play Integrity provider + `debugImplementation` 분리로 교체해야 한다 (영향받는 항목: `app/build.gradle.kts`, Firebase 콘솔의 디버그 토큰 등록)
- 2026-09-13: androidx 의존성을 AGP 8.11.2 / compileSdk 36 호환 버전으로 다운그레이드한다 (`core-ktx 1.19.0→1.17.0`, `lifecycle 2.11.0→2.9.4`, `activity-compose 1.13.0→1.11.0`, `ext-junit 1.3.0→1.2.1`, `espresso 3.7.0→3.6.1`) - Android Studio 템플릿이 생성한 버전들이 AGP 9.1+ 와 compileSdk 37을 요구해 Firebase 추가 이전부터 빌드가 실패하고 있었다. AGP 9 업그레이드는 Gradle wrapper 9.x + SDK 37 설치 + Kotlin 버전 재검토를 동반해 Phase 0 목표(음성 왕복 1회)와 무관한 작업량이 커진다 (영향받는 항목: `gradle/libs.versions.toml`)
- 2026-09-13: App Check 디버그 토큰 등록을 2단계(마이크/오디오 캡처)보다 먼저 처리한다 - 토큰 등록은 앱을 한 번 실행해 Logcat에서 시크릿을 뽑아야 가능하고, 등록이 안 되면 4단계에서 세션 연결이 거부된다. CLAUDE.md의 "작은 단위" 원칙은 한 번에 만드는 코드량을 줄이라는 뜻이며 논리적 선행 작업을 미루라는 뜻이 아니다 (영향받는 항목: 진행 로그 순서)
- 2026-09-13: App Check 토큰을 `getAppCheckToken(false)`로 실제 한 번 요청한다 - 프로바이더 설치만으로는 콘솔 등록이 끝났는지 알 수 없다. 성공/실패 콜백으로 등록 완료 여부를 즉시 판별하고 화면과 Logcat에 동시에 남긴다 (영향받는 항목: `MainActivity.onCreate`)
- 2026-09-13: `AndroidManifest.xml`에 `INTERNET` 권한을 추가한다 - App Check 토큰 교환과 이후 Gemini Live API 통신에 필수인데 Android Studio 템플릿에는 없었다 (영향받는 항목: `app/src/main/AndroidManifest.xml`)
- 2026-09-13: 템플릿의 `Greeting`/`GreetingPreview` 컴포저블을 제거하고 단일 상태 표시 `Text`로 교체한다 - CLAUDE.md 금지사항에 따라 UI는 상태 표시만 두고 디자인 작업을 하지 않는다 (영향받는 항목: `MainActivity.kt`)
- 2026-09-13: App Check 검증과 2단계(마이크 권한 + 오디오 캡처) 코드 배선은 Android Studio 에뮬레이터로 진행하고, Phase 0 최종 완료 기준(음성 왕복 1회 성공 + 체감 지연시간/한국어 품질 기록)은 Alldocube iPlay 60 mini Pro 실기기로 검증한다 - 배선 단계는 반복 실행이 잦아 에뮬레이터가 빠르고, 지연시간·마이크 품질·끼어들기 체감은 에뮬레이터(호스트 Mac 오디오 경유)에서 측정해도 의미가 없다. 에뮬레이터는 Google Play/Google APIs 시스템 이미지를 사용하고, 마이크 입력은 확장 설정에서 호스트 오디오 입력을 활성화해야 한다 (영향받는 항목: 진행 로그 2~5단계 검증 방식, 6단계 기록은 실기기 전용)
- 2026-09-13: 디버그 토큰 등록보다 **Firebase App Check API(`firebaseappcheck.googleapis.com`) 활성화를 먼저** 해야 한다 - 에뮬레이터 실행 결과 토큰 교환이 `403 Firebase App Check API has not been used in project 330405117419 before or it is disabled`로 실패했다. 콘솔에서 디버그 토큰을 등록해도 API가 꺼져 있으면 교환 자체가 안 된다. `getAppCheckToken()`을 실제로 호출하도록 만든 덕에 이 선행 조건을 조기에 발견했다 (영향받는 항목: Firebase/GCP 콘솔 설정, 3단계 진행 가능 여부)
- 2026-09-13: App Check 디버그 토큰 값은 저장소·문서·커밋 메시지에 절대 기록하지 않는다 - 토큰은 App Check를 우회할 수 있는 시크릿이다. 기기/에뮬레이터별로 재생성 가능하므로 기록할 필요도 없다 (영향받는 항목: CLAUDE.md, 커밋 메시지, 진행 로그)
- 2026-09-13: `.git` 디렉터리를 삭제하고 `git init`으로 재초기화한다 - 최초 커밋 `01d8b74`에 `google-services.json`(API 키 포함)이 들어가 있었고 remote가 없어 히스토리를 잃을 위험이 없다. 히스토리 재작성보다 단순하고 확실하다 (영향받는 항목: git 히스토리 전체, `.gitignore`)
- 2026-09-13: Firebase 콘솔에 등록하는 App Check 디버그 토큰의 이름은 `{기기구분}-{용도}-{등록일자}` 형식으로 정한다 (등록일자는 `YYYYMMDD`). 예: `emulator-pixel2-dev-20260913`, `alldocube-iplay60-dev-20260913` - 개발 중 여러 기기(에뮬레이터, 실기기)에서 토큰이 각각 생성되는데 이름이 없으면 어느 기기 것인지 구분이 안 되고, 기기를 wipe하거나 정리할 때 어떤 토큰을 삭제해야 할지 판단할 수 없다 (영향받는 항목: Firebase 콘솔 App Check 디버그 토큰 목록)
- 2026-09-13: 마이크 캡처는 `AudioRecord` + `MediaRecorder.AudioSource.MIC`로 구현하고 100ms 단위 청크를 `Flow<Chunk>`로 방출한다 - Live API 입력 포맷(PCM 16-bit / 24kHz / mono)에 맞춰 3~4단계에서 이 Flow를 세션에 그대로 연결할 수 있게 한다. `VOICE_COMMUNICATION`은 에코 캔슬이 붙지만 에뮬레이터에서 동작이 불확실해 스파이크 단계에서는 `MIC`를 쓴다 (영향받는 항목: `AudioRecorder.kt`)
- 2026-09-13: 마이크 권한은 앱 시작 시가 아니라 "녹음 시작" 버튼을 누를 때 요청한다 - 사용자가 왜 마이크가 필요한지 알 수 있는 시점에 묻는 것이 권한 승인률과 UX 모두에 낫고, 권한 없이도 앱이 켜지는지 확인할 수 있다 (영향받는 항목: `MainActivity.onToggleRecording`)
- 2026-09-13: `onStop()`에서 녹음을 정지한다 - Phase 0은 포그라운드 전용이며, 백그라운드에서 마이크를 계속 쥐고 있으면 Android 14에서 foreground service type 선언이 강제된다. 지금은 그 요건을 만들지 않는 쪽을 택한다 (영향받는 항목: `MainActivity.onStop`, 향후 과제의 백그라운드 동작)
- 2026-09-13: 터미널에서 띄운 에뮬레이터는 macOS가 마이크 요청을 Terminal.app 것으로 취급한다 - 프로세스 계보가 `Terminal.app → zsh → claude → emulator`라서 Android Studio에 부여한 마이크 권한이 적용되지 않는다. 마이크가 걸린 단계부터는 에뮬레이터를 Android Studio Device Manager에서 실행한다 (영향받는 항목: 2단계 이후 에뮬레이터 실행 방법)
- 2026-09-13: Gradle 스크립트나 `AndroidManifest.xml`을 터미널에서 수정하면 그 사실을 사용자에게 즉시 알린다 - `./gradlew assembleDebug`는 APK만 만들 뿐 Android Studio의 프로젝트 모델을 갱신하지 않는다. IDE 밖에서 빌드 파일이 바뀌면 Android Studio가 모델을 낡은 것으로 보고 실행 버튼을 비활성화하며, `Sync Project with Gradle Files`를 눌러야 풀린다. 실제로 이 이유로 실행 버튼이 막혀 원인 파악에 시간을 썼다. Kotlin 소스만 고칠 때는 해당하지 않는다 (영향받는 항목: 작업 보고 방식, `*.gradle.kts` / `libs.versions.toml` / `AndroidManifest.xml` 수정 시)
- 2026-09-13: 2단계는 에뮬레이터에서 검증 완료로 판정한다 - 무음 진폭 402 대비 발화 시 최대 8672(중앙값 1967, 98샘플)로 20배 이상 스윙이 확인됐고, 청크 크기 4800바이트가 24kHz·16-bit·mono·100ms와 정확히 일치한다 (영향받는 항목: 진행 로그 2단계)
- 2026-09-13: 에뮬레이터 Graphics 모드를 `auto`에서 소프트웨어 렌더링으로 바꿀 것을 권장한다 - `auto`가 AMD GPU(Make 1002)에서 호스트 GLES를 선택하면서 `Failed to restore previous context: 12297`로 창이 검게 표시된다. 게스트는 정상이며 호스트 창만 그려지지 않는 문제라, 급하면 `adb`(input tap / exec-out screencap / logcat)로 우회해 검증할 수 있다 (영향받는 항목: AVD `hw.gpu.mode`, 에뮬레이터 육안 확인 가능 여부)
- 2026-09-13: 에뮬레이터 마이크는 Extended Controls의 `enable host microphone access`를 켜야 동작한다 - 꺼져 있으면 에뮬레이터가 입력을 0으로 채워(`-allow-host-audio` 미적용) 진폭이 4에 고정된다. `hw.audioInput=yes`와는 별개인 런타임 토글이다 (영향받는 항목: 에뮬레이터에서의 마이크 검증)
- 2026-09-13: 3단계 완료 — `gemini-3.1-flash-live-preview`가 실제로 연결된다(연결 소요 약 1.7초). fallback 모델로 전환할 필요가 없다 - preview 모델이라 연결 자체가 실패할 위험을 최대 리스크로 보고 상수 분리까지 해뒀는데, 실측으로 해소됐다 (영향받는 항목: `AiConfig.LIVE_MODEL_NAME`, 진행 로그 3단계)
- 2026-09-13: `LiveSession` 타입을 `LiveSessionManager` 밖으로 내보내지 않는다 - Live API는 `@PublicPreviewAPI` opt-in을 요구해서, 타입이 노출되면 `MainActivity`까지 `@OptIn`이 전염된다(실제 컴파일 에러 발생). preview 표면을 한 파일에 가두면 SDK가 바뀔 때 수정 범위도 한 파일로 제한된다 (영향받는 항목: `LiveSessionManager.isConnected`, `MainActivity`)
- 2026-09-13: 에뮬레이터 검은 화면 문제는 더 파지 않고 실기기로 전환한다 - `hw.gpu.mode`를 `swiftshader_indirect`(소프트웨어 렌더링)로 바꿔 실제 적용(`hardware-qemu.ini`에 `swiftshader`)됐는데도 증상이 동일했다. 남은 선택지(IDE 재시작, 에뮬레이터 업데이트, ANGLE)는 확실성이 없고 PoC 목표와 무관하다. 실기기는 검은 화면·macOS 마이크 권한·에뮬레이터 마이크 토글 세 문제를 동시에 제거한다 (영향받는 항목: 4~6단계 검증 환경)
- 2026-09-13: 화면이 보이지 않아도 `adb`로 검증을 진행한다 - `input tap`(좌표는 `uiautomator dump`로 확보), `exec-out screencap`, `logcat`만으로 탭·화면 확인·로그 판독이 모두 가능하다. 실제로 2단계 진폭 검증과 3단계 세션 연결을 이 방식으로 끝냈다 (영향받는 항목: 호스트 렌더링 문제 발생 시 대응)
- 2026-09-13: 4단계와 5단계를 `LiveSession.startAudioConversation()` 하나로 합쳐서 구현한다 - SDK가 내부에 `AudioHelper`를 들고 마이크 캡처·전송·수신·스피커 재생을 모두 처리하며, 공식 문서도 이를 권장 방식으로 명시한다. 저수준 API(`sendAudioRealtime`, `receive(): Flow<LiveServerMessage>`)로 직접 배선할 수도 있지만 Phase 0 목표(왕복 1회)에는 불필요한 작업량이다 (영향받는 항목: 진행 로그 4~5단계, `AudioRecorder.kt`의 현재 용도)
- 2026-09-13: 2단계에서 만든 `AudioRecorder`는 `startAudioConversation()` 경로에서 쓰이지 않지만 삭제하지 않는다 - 마이크가 실제로 동작하는지 독립적으로 검증한 수단이었고(진폭 402→8672), 이후 끼어들기 제어나 커스텀 스트리밍이 필요해지면 저수준 경로의 출발점이 된다 (영향받는 항목: `AudioRecorder.kt`)
- 2026-09-13: `liveGenerationConfig`에 `inputAudioTranscription`/`outputAudioTranscription`을 켜고 `transcriptHandler`로 양쪽 전사를 로그에 남긴다 - Phase 0 완료 기준에 "한국어 인식/발화 품질 기록"이 있는데, 음성만으로는 무엇이 어떻게 인식됐는지 객관적으로 남길 수 없다. 전사 텍스트가 있으면 인식 오류를 그대로 기록할 수 있고, 입력 전사와 출력 전사의 시각 차이로 체감 지연시간도 근사할 수 있다 (영향받는 항목: `LiveSessionManager`, 6단계 기록 방식)
- 2026-09-13: `enableInterruptions = true`로 설정한다 - Phase 0 완료 기준에 끼어들기(interrupt) 동작 여부 기록이 포함되어 있어, 기본값에 맡기지 않고 명시적으로 켜서 실제 동작을 확인한다 (영향받는 항목: `LiveSessionManager`, 6단계 기록)
- 2026-09-13: 한국어 시스템 지시문을 짧게 넣는다 - 모델이 영어로 응답하면 한국어 품질 자체를 측정할 수 없다. 상담 페르소나를 정교하게 만드는 것은 Phase 2 이후 범위이므로, 여기서는 "한국어로 짧고 자연스럽게" 수준만 지정한다 (영향받는 항목: `LiveSessionManager`의 `systemInstruction`)
- 2026-09-13: 에뮬레이터에서 왕복(4~5단계)을 먼저 성립시키고, 태블릿은 6단계 기록에 사용한다 - 태블릿이 방전되어 충전 중이라 대기 시간이 발생한다. 마이크 입력과 스피커 출력이 에뮬레이터에서 이미 동작하고 버튼은 `adb`로 누를 수 있으므로 검은 화면과 무관하게 기능 검증이 가능하다. 되먹임을 막기 위해 이어폰을 사용한다 (영향받는 항목: 4~6단계 진행 순서)
- 2026-09-13: 4단계 완료 — 에뮬레이터에서 음성 왕복이 성립한다. 한국어·영어 발화 모두 인식되고 모델이 한국어로 응답했다. 입력 전사 도착 후 출력 전사 시작까지 4~9ms (영향받는 항목: 진행 로그 4단계)
- 2026-09-13: **전사(transcription) 품질과 모델의 실제 이해도는 다르다** - "오늘은 일요일이고"가 전사에서는 "응. 응. 이들이고"로 망가졌는데 모델은 "네, 일요일 오후 12시 14분이군요"라고 정확히 응답했다. native-audio 모델은 원본 오디오를 직접 이해하며, 전사는 별도 ASR 경로다. 6단계 품질 기록에서 전사만 보고 한국어 인식 성능을 판단하면 실제보다 나쁘게 오판하게 된다 (영향받는 항목: 6단계 품질 기록 해석 방식)
- 2026-09-13: 5단계(스피커 재생)는 에뮬레이터에서 검증 불가로 판정하고 실기기로 넘긴다 - 앱의 `AudioTrack`이 `state:started`, `usage=USAGE_MEDIA`, `content=CONTENT_TYPE_SPEECH`, 음소거 아님, 볼륨 9/15인데도 이어폰 연결 상태에서 소리가 전혀 들리지 않았다. 안드로이드 게스트 내부에서는 재생되고 있으므로 앱 코드 문제가 아니라 에뮬레이터의 호스트 오디오 출력 경로 문제다. 같은 머신에서 이미 렌더링(검은 화면)도 실패했다 (영향받는 항목: 진행 로그 5단계, 검증 기기)
- 2026-09-13: 태블릿에서는 App Check 디버그 토큰을 새로 등록해야 한다 - 디버그 토큰은 기기별로 생성되므로 에뮬레이터용 토큰은 태블릿에서 쓸 수 없다. 등록 전에는 세션 연결이 거부된다. 네이밍 룰에 따라 `alldocube-iplay60-dev-20260913`으로 등록한다 (영향받는 항목: Firebase 콘솔 App Check, 태블릿 첫 실행)
- 2026-09-13: Phase 0 완료. 완료 기준 4개를 Alldocube iPlay60_mini_Pro(Android 14)에서 모두 충족했다 - 스피커 청취·끼어들기 즉시 중단·체감 "거의 즉시"를 실기기에서 확인했다. 상세 수치와 품질 평가는 위 "Phase 0 검증 결과" 섹션에 기록 (영향받는 항목: 진행 로그 전체)
- 2026-09-13: 요구사항과 과제는 GitHub 이슈(https://github.com/chp320/poc-simni/issues)에서 관리하고 CLAUDE.md에 중복 기록하지 않는다 - 이슈는 검색·라벨·상태 관리가 되지만 CLAUDE.md는 그렇지 않다. CLAUDE.md의 결정 기록은 "코드가 왜 이렇게 생겼는지"에 집중하고, 제품 요구사항과 앞으로 할 일은 이슈에 둔다 (영향받는 항목: CLAUDE.md 결정 기록 범위, 향후 과제 섹션)
- 2026-09-13: 사용 패턴 확정 — 세션 평균 10~15분·최대 30분(상한 적용), 주 3회, 주 사용 시점은 "힘들 때". 주 사용자는 당분간 개발자 본인으로 한정하고 앱마켓 등록은 염두만 둔다 - 비용 계산의 입력값이 필요했다. 상세는 이슈 #14 (영향받는 항목: 세션 상한 구현, 공개 관련 기능의 우선순위)
- 2026-09-13: Gemini API 무료 티어를 쓰지 않고 Blaze(유료) 요금제를 유지한다 - 무료 티어는 입력 데이터가 모델 개선에 사용될 수 있는데, 이 앱이 다루는 대화는 가장 민감한 종류의 개인정보다. 실측 비용이 월 3,000원대(본인 사용 기준)라 절감액이 그 위험을 정당화하지 못한다. 상세 산출은 이슈 #9 (영향받는 항목: Firebase 요금제, 데이터 취급 정책)
- 2026-09-13: 대화 기록은 원문을 저장하지 않고 **요약과 주제 태그만** 로컬에 남긴다 (요구사항 4.1 = B) - 연속성("지난번에 직장 이야기 하셨죠")과 세션 후 콘텐츠 추천에는 요약으로 충분하면서, 원문이 없어 유출 시 피해가 제한된다. 전사 전문 저장은 두 가지 이유로 배제했다: (1) 전사가 부정확하다 — Phase 0에서 "오늘은 일요일이고"가 "응. 응. 이들이고"로 기록됐고, 부정확한 기록은 나중에 읽을 때 혼란만 준다 (2) "힘들 때" 한 말을 다시 읽는 것이 반추를 강화할 수 있다는 우려가 있다. 상세는 이슈 #14 (영향받는 항목: 저장 스키마, 세션 종료 처리, 이슈 #15)
- 2026-09-13: `android:allowBackup="false"` + `data_extraction_rules.xml`/`backup_rules.xml`에서 모든 도메인을 명시적으로 제외한다 - 템플릿 기본값이 `allowBackup="true"`에 규칙 파일이 전부 주석 처리된 상태였다. 이대로 대화 기록을 저장하면 동의 없이 Google Drive로 업로드되고 같은 계정의 다른 기기로 복원된다. `allowBackup="false"`만으로는 부족한데, Android 12+ 에서는 일부 제조사 기기에서 클라우드 백업만 막히고 기기 간 전송(D2D)은 그대로 동작하기 때문이다(공식 문서 명시). 테스트 기기가 Alldocube라 더더욱 `cloud-backup`과 `device-transfer` 양쪽을 비웠다. 실기기 `dumpsys`에서 `ALLOW_BACKUP` 플래그 제거 확인 (영향받는 항목: `AndroidManifest.xml`, `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml`)
- 2026-09-13: 대화 기록 보관은 90일 자동 삭제, 위기 세션은 주제 태그만 남기고 요약은 생략한다 (요구사항 4.3 / 4.5) - 90일이면 연속성에 충분하면서 무한 축적을 막는다. 위기 세션은 가장 민감한 기록이라 발생 사실만 알고 내용은 남기지 않는 쪽을 택했다. 상세는 이슈 #19 (영향받는 항목: 저장 스키마, 삭제 스케줄러, 이슈 #17)
- 2026-09-13: 요구사항을 주제별 이슈(#15~#19)로 분리하고 #14는 인덱스로 둔다 - 논의가 길어지면서 결정이 코멘트에 흩어져 어디서 무엇이 정해졌는지 찾기 어려워졌다. 주제별로 나누면 각각 구현 단위와 1:1로 대응되고 상태 추적이 된다 (영향받는 항목: 이슈 구조, CLAUDE.md의 제품 요구사항 섹션)
- 2026-09-13: 세션 연결과 대화 시작을 하나의 버튼으로 합치고, 연결 대기 1.8초를 면책 고지 노출에 쓴다 (요구사항 2.2) - Phase 0의 2단계 UI는 내부 구현이 드러난 것이고 사용자에게는 "통화를 건다"는 한 번의 행위다. 고지를 별도 화면으로 띄우면 방해가 되지만 대기 시간에 얹으면 비용이 0이고 읽힐 확률도 올라간다. 이슈 #18의 "세션 시작 시 고지 빈도" 미결정도 함께 해소됐다 (영향받는 항목: `MainActivity` UI 전면 교체, 이슈 #20)
- 2026-09-13: 무음이 이어져도 통화를 자동 종료하지 않는다 - 상담에서 침묵은 생각을 정리하거나 감정을 추스르는 의미 있는 시간이다. 침묵을 이유로 끊으면 가장 필요한 순간에 끊길 수 있다. 아주 긴 침묵(3분)에만 한 번 확인하고, 무응답이 더 이어지면(2분) 종료한다 — 앱을 켜둔 채 잠든 경우 대비. 수치는 근거 없는 추정이라 실사용 후 조정한다 (영향받는 항목: 통화 세션 관리, 이슈 #20)
- 2026-09-13: 전사 텍스트를 통화 화면에 기본 노출하지 않는다 - 전사는 별도 ASR 경로라 부정확한데("오늘은 일요일이고" → "응. 응. 이들이고"), 화면에 보이면 사용자는 모델이 못 알아들었다고 오해한다. 실제로는 정확히 이해하고 있어서 신뢰를 깎는 쪽으로만 작동한다. 텍스트가 보이면 화면을 보게 되어 통화 느낌도 깨진다. 설정에서 켤 수 있게는 둔다 (영향받는 항목: 통화 화면, 설정 화면)
- 2026-09-13: Phase 1 구현을 S1~S9로 나누고 아키텍처 정리(S1)부터 시작한다 - 현재 모든 상태가 `MainActivity`에 있어서, 고치지 않고 기능을 얹으면 이후 모든 작업이 Activity에 쌓인다. 지금이 가장 싸다. 안전 관련(S4 면책 고지, S5 위기 대응)은 뒤로 미루지 않는다 — "힘들 때" 쓰는 앱이다. 상세는 이슈 #21 (영향받는 항목: 작업 순서, `MainActivity.kt`)
- 2026-09-13: 턴 종료 판단을 늦추되 끼어들기는 그대로 유지한다 - Phase 0 실기기 테스트에서 "말하다 생각하려고 잠깐 멈추면 AI가 바로 응답을 시작한다"는 문제가 확인됐다. 상담에서는 감정을 정리할 시간이 필요한데 끊기면 하려던 말을 놓치고 대화 주도권이 AI에게 넘어간다. `firebase-ai`의 `ActivityDetectionConfig`가 `startSensitivity`와 `endSensitivity`를 분리해 두어, `startSensitivity = HIGH`(끼어들기 유지) + `endSensitivity = LOW` + `silenceDurationMs` 증가로 두 요구를 동시에 만족할 수 있다. `activityHandling`은 `INTERRUPT` 유지. `silenceDurationMs`는 너무 길면 말이 끝났는데 반응이 없는 어색함이 생기므로 실측 튜닝이 필요하며, 상담 맥락에서는 긴 쪽으로 치우치는 편이 낫다. 상세는 이슈 #22 (영향받는 항목: `LiveSessionManager`의 `liveGenerationConfig`, 구현 단계 S3)
- 2026-09-13: S3를 "시스템 지시문"에서 "대화 동작 튜닝"으로 넓히고 S2보다 먼저 진행한다 - 턴 감지(#22)는 프롬프트가 아니라 세션 설정이지만 둘 다 "대화가 어떻게 느껴지는가"를 결정하고 같은 파일에서 다룬다. 끼어드는 문제를 먼저 고쳐야 이후 모든 단계의 대화 테스트가 수월하다. Phase 0 디버그 UI로도 대화 테스트는 가능하므로 S2를 기다릴 이유가 없다. 실기기 반복 테스트가 필요해 규모는 S에서 M으로 올렸다 (영향받는 항목: 구현 순서, 이슈 #21)
- 2026-09-13: 통화 상태와 로직을 `CallViewModel`이 단독 소유하고 `MainActivity`는 Firebase 초기화·권한 요청·화면 그리기만 담당한다 (S1) - Activity에 상태를 두면 화면 회전 시 재생성되면서 Live 세션이 끊긴다. 실기기 검증에서 회전 전후 세션이 유지되고 재연결 로그가 발생하지 않음을 확인했다. Context가 필요한 일(권한 확인·요청)만 Activity에 남겼다 (영향받는 항목: `CallViewModel.kt`, `CallUiState.kt`, `MainActivity.kt`)
- 2026-09-13: 통화 단계를 `Idle → Connecting → Connected → InCall` 로 모델링한다 - 현재 UI는 "세션 연결"과 "대화 시작" 버튼 두 개로 나뉘어 있지만, S2에서 하나로 합쳐도 이 단계 모델은 그대로 쓴다. 전이가 자동으로 이어질 뿐이고 `Connecting`이 연결 중 화면에 대응한다. 지금 2단계 흐름에 맞춰 모델링하면 S2에서 재작업이 생긴다 (영향받는 항목: `CallUiState.kt`, 이슈 #20)
- 2026-09-13: `onStop()`에서 마이크 검증용 캡처만 멈추고 Live 세션은 유지한다 - 화면 회전으로도 `onStop()`이 호출되므로 여기서 세션을 끊으면 S1의 목적이 무너진다. 포그라운드 전용 원칙은 유지하되 세션 정리는 `ViewModel.onCleared()`로 옮겼다 (영향받는 항목: `MainActivity.onStop`, `CallViewModel.onCleared`)
- 2026-09-13: 턴 종료 침묵 임계값을 **3000ms**로 확정한다 (S3 / 이슈 #22) - 실기기 튜닝 결과 1500ms와 2000ms 모두 부족했다. "음.. 지금 시각은 4시 32분..(3초) 입니다"에서 '입니다' 전에 응답이 시작됐다. 3000ms에서 끊김이 사라졌다. 짧은 답변 후 3초 정적이라는 트레이드오프가 있으나, 상담 맥락에서는 기다려주는 어색함보다 끊기는 불쾌함이 크다는 판단을 따른다. 튜닝 이력은 `AiConfig.TurnDetection` 주석에 남겼다 (영향받는 항목: `AiConfig.TurnDetection.SILENCE_DURATION_MS`)
- 2026-09-13: 시스템 지시문을 `SystemInstruction.kt`로 분리한다 - 문구를 자주 고치며 대화 품질을 비교해야 하는데, `LiveSessionManager`에 인라인으로 두면 세션 로직과 뒤섞인다. 지시문만 고칠 때 세션 코드를 건드리지 않아도 된다 (영향받는 항목: `SystemInstruction.kt`, `LiveSessionManager.connect`)
- 2026-09-13: `activityHandling`을 `INTERRUPT`로 **명시**한다 - 기본값에 맡기지 않는다. `NO_INTERRUPT`가 되면 Phase 0에서 확인한 "끼어들기 즉시 중단"이 죽는다. 턴 종료를 늦추는 작업(`endSensitivity = LOW`)과 끼어들기 유지(`startSensitivity = HIGH`)는 서로 반대 방향이라, 하나를 고치다 다른 하나가 깨지지 않도록 둘 다 명시적으로 고정했다 (영향받는 항목: `LiveSessionManager`의 `realtimeInputConfig`)
- 2026-09-13: 통화 중 마이크 뮤트는 `AudioManager.setMicrophoneMute()`로 구현한다 - `startAudioConversation()`이 마이크를 SDK 내부에서 잡고 있어 뮤트 API가 없다. 세션을 껐다 켜는 방식은 대화 맥락이 끊길 위험이 있다. Context가 필요해 `CallViewModel`이 `AndroidViewModel`이 되었고 `MODIFY_AUDIO_SETTINGS` 권한(설치 시 자동 승인)을 매니페스트에 추가했다. 실기기 `dumpsys audio`에서 `FromApi=true` 확인 (영향받는 항목: `CallViewModel`, `AndroidManifest.xml`)
- 2026-09-13: 긴 침묵은 전사가 마지막으로 도착한 시각으로 판단한다 - SDK가 마이크를 내부에서 쓰기 때문에 진폭을 얻을 수 없다. 전사 도착 시각은 정확도가 떨어지지만 추가 마이크 접근 없이 구할 수 있는 유일한 신호다 (영향받는 항목: `CallViewModel.lastActivitySec`)
- 2026-09-13: 29분 시점의 "AI가 마무리를 유도" 설계는 화면 표시로만 구현한다 - `sendTextRealtime()`으로 모델에게 마무리를 지시하면 그 지시문 자체를 사용자 발화처럼 읽고 예측 못 할 반응을 할 위험이 있다. 상담 앱에서 통제되지 않는 출력은 피하는 편이 낫다. 음성 개입 방식은 별도 검증 항목으로 남긴다 (영향받는 항목: `CallViewModel.startTimer`, 이슈 #20)
- 2026-09-13: 통화 시간 정책을 `AiConfig.Session` 상수로 분리한다 - 30분 상한을 그대로 두면 검증에 30분이 걸린다. 상수로 두면 테스트 중 짧게 줄여 확인하고 되돌릴 수 있다. 실제로 45초/25초/35초/10초/20초로 줄여 3단계 경고와 두 종료 경로를 모두 검증했다 (영향받는 항목: `AiConfig.Session`)
- 2026-09-13: CLAUDE.md에 `## 개발 환경 · 재개 가이드` 섹션을 만든다 - 세션이 끊기거나 컴퓨터를 껐다 켜면 기기 serial, git push 우회 방법, 에뮬레이터를 쓰지 않는 이유 같은 운영 정보가 사라져 같은 시행착오를 반복하게 된다. 실제로 오늘 하루에만 Gradle sync 문제, git 403, 에뮬레이터 검은 화면·오디오 실패로 시간을 썼다. 한 번씩 부딪혀 알아낸 것들을 재개 가능한 형태로 남긴다 (영향받는 항목: CLAUDE.md 구조)
- 2026-09-13: 검증 대기 항목에 **요청 이력**을 함께 남긴다 - 같은 항목을 몇 번 미뤘는지 보이지 않으면 무한정 밀린다. 단계마다 요청한 사실과 보류 사유를 적어 누적을 드러낸다 (영향받는 항목: CLAUDE.md 검증 대기 섹션, 이슈 #23)

## 향후 과제
Phase 0 범위 밖이지만 이후 단계에서 다뤄야 할 항목을 적어둔다. 여기서 해결하지 않고 언급만 남긴다.

- **App Check 프로바이더 전환** — 지금의 디버그 프로바이더 + 디버그 토큰 수동 등록은 개발 환경에서 어느 기기에서 발생한 요청인지 추적하기 위한 임시 방편이다. 앱 마켓을 통한 실제 배포 시에는 Play Integrity 프로바이더로 전환해 정식 API 호출로 자동 검증되므로, 수동 토큰 등록과 위의 토큰 네이밍 룰 자체가 불필요해진다. 배포 논의 시점에 다룬다.
- **에코 캔슬레이션 / `AudioSource` 전환** — 5단계에서 스피커 재생이 붙으면 모델 음성이 마이크로 되먹임될 수 있다. `AudioSource.VOICE_COMMUNICATION` 또는 `AcousticEchoCanceler` 적용을 검토해야 하며, 끼어들기(barge-in) 품질과 직결된다. 실기기 검증 시점에 다룬다.
- **백그라운드 통화 유지** — 지금은 `onStop()`에서 마이크를 놓는다. "화면을 꺼도 통화가 이어진다" 요건이 생기면 Android 14의 `foregroundServiceType="microphone"` 선언과 알림 채널이 필요하다. 에뮬레이터(API 30)에서는 재현되지 않고 실기기(Android 14)에서만 드러나는 지점이다.
