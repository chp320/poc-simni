# CLAUDE.md — VoiceCounselPOC (Phase 0: Gemini Live API 스파이크)

## 프로젝트 목표
AI와 실시간 음성 대화(통화 느낌)를 나누는 Android 앱의 기술 검증(PoC) 단계.
이번 Phase 0의 목표는 단 하나: **Gemini Live API로 "말하면 음성으로 답이 온다"가 1회 성립하는지 확인**하는 것.

이 단계는 최종 프로덕트가 아니라 기술 스파이크다. 예쁜 UI, 에러 핸들링 고도화, 아키텍처 확장성은 이번 범위에 포함하지 않는다.

## 배경 컨텍스트
- 최종적으로는 심리 상담을 병행하는 AI 음성 대화 앱을 만들 계획이며, 이 저장소는 그 중 음성 연동 방식을 검증하는 별도 PoC 저장소다.
- 기존에 진행 중인 HomeLibrary(Android 도서 관리 앱), Spring Boot 관리 시스템과는 독립된 프로젝트다. 이 저장소의 코드/의존성을 그쪽과 섞지 않는다.
- 개발은 MacBook에서 진행하고, 테스트 기기는 Alldocube iPlay 60 mini Pro(Android 14)다.

## 기술 스택 (Phase 0 한정)
- Android (Kotlin)
- Firebase AI Logic SDK → Gemini Live API 연동
- 모델: Gemini Live API를 지원하는 최신 native-audio 모델 (정확한 모델 문자열은 Firebase 문서에서 최신값 확인 후 코드에 명시)
- 백엔드 없음 — Phase 0에서는 클라이언트에서 Firebase AI Logic을 직접 호출

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

## 진행 로그
(이 섹션은 진행하면서 계속 업데이트한다)
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

## 향후 과제
Phase 0 범위 밖이지만 이후 단계에서 다뤄야 할 항목을 적어둔다. 여기서 해결하지 않고 언급만 남긴다.

- **App Check 프로바이더 전환** — 지금의 디버그 프로바이더 + 디버그 토큰 수동 등록은 개발 환경에서 어느 기기에서 발생한 요청인지 추적하기 위한 임시 방편이다. 앱 마켓을 통한 실제 배포 시에는 Play Integrity 프로바이더로 전환해 정식 API 호출로 자동 검증되므로, 수동 토큰 등록과 위의 토큰 네이밍 룰 자체가 불필요해진다. 배포 논의 시점에 다룬다.
- **에코 캔슬레이션 / `AudioSource` 전환** — 5단계에서 스피커 재생이 붙으면 모델 음성이 마이크로 되먹임될 수 있다. `AudioSource.VOICE_COMMUNICATION` 또는 `AcousticEchoCanceler` 적용을 검토해야 하며, 끼어들기(barge-in) 품질과 직결된다. 실기기 검증 시점에 다룬다.
- **백그라운드 통화 유지** — 지금은 `onStop()`에서 마이크를 놓는다. "화면을 꺼도 통화가 이어진다" 요건이 생기면 Android 14의 `foregroundServiceType="microphone"` 선언과 알림 채널이 필요하다. 에뮬레이터(API 30)에서는 재현되지 않고 실기기(Android 14)에서만 드러나는 지점이다.
