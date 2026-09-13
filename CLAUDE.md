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
- [ ] 마이크 권한 요청 및 오디오 캡처 구현
- [ ] `liveModel` 초기화 및 세션 연결
- [ ] 오디오 스트리밍 송수신 구현
- [ ] 스피커 재생 확인
- [ ] 체감 지연시간/품질 기록

## 결정 기록
작업 중 내려진 결정과 그 이유를 아래 형식으로 기록한다. 새로운 결정이 나올 때마다 물어보지 않고 이 섹션에 바로 추가한다.

형식: `- YYYY-MM-DD: [결정 내용] - [이유] (영향받는 항목: ...)`

- 2026-09-13: Live API 모델은 `gemini-3.1-flash-live-preview`를 사용한다 - 2.5 native-audio 계열(`gemini-2.5-flash-native-audio-preview-12-2025`)은 2026년 10월 종료 예정이라 지금 채택하면 한 달 뒤 재작업이 발생한다 (영향받는 항목: `AiConfig.LIVE_MODEL_NAME`)
- 2026-09-13: 모델명을 `AiConfig` 상수로 분리한다 - 3.1이 preview 상태라 연결 실패 시 `AiConfig.FALLBACK_LIVE_MODEL_NAME`으로 한 줄만 바꿔 즉시 전환할 수 있어야 한다 (영향받는 항목: `app/src/main/java/com/leo/voicecounselpoc/AiConfig.kt`)
- 2026-09-13: Firebase 백엔드는 Gemini Developer API(`GenerativeBackend.googleAI()`)를 사용한다 - Phase 0은 백엔드 서버 없이 클라이언트에서 직접 호출하는 범위이고 Vertex AI 백엔드는 GCP 설정이 추가로 필요하다 (영향받는 항목: `liveModel` 초기화 코드)
- 2026-09-13: 의존성은 Firebase BoM `34.19.0` + `firebase-ai`로 관리한다 - 개별 아티팩트 버전을 직접 고정하면 Firebase 모듈 간 버전 충돌 위험이 있다. 실제 해석 결과 `firebase-ai 17.17.0` (영향받는 항목: `gradle/libs.versions.toml`, `app/build.gradle.kts`)
- 2026-09-13: `firebase-appcheck-debug`를 `implementation`으로 추가한다 - 2026년 7월부터 Firebase AI Logic에 App Check가 자동 강제되어 없으면 세션 연결이 거부된다. Phase 0은 디버그 빌드 전용이므로 debug provider로 충분하며, 릴리스 빌드를 만들 시점에 Play Integrity provider + `debugImplementation` 분리로 교체해야 한다 (영향받는 항목: `app/build.gradle.kts`, Firebase 콘솔의 디버그 토큰 등록)
- 2026-09-13: androidx 의존성을 AGP 8.11.2 / compileSdk 36 호환 버전으로 다운그레이드한다 (`core-ktx 1.19.0→1.17.0`, `lifecycle 2.11.0→2.9.4`, `activity-compose 1.13.0→1.11.0`, `ext-junit 1.3.0→1.2.1`, `espresso 3.7.0→3.6.1`) - Android Studio 템플릿이 생성한 버전들이 AGP 9.1+ 와 compileSdk 37을 요구해 Firebase 추가 이전부터 빌드가 실패하고 있었다. AGP 9 업그레이드는 Gradle wrapper 9.x + SDK 37 설치 + Kotlin 버전 재검토를 동반해 Phase 0 목표(음성 왕복 1회)와 무관한 작업량이 커진다 (영향받는 항목: `gradle/libs.versions.toml`)
- 2026-09-13: `.git` 디렉터리를 삭제하고 `git init`으로 재초기화한다 - 최초 커밋 `01d8b74`에 `google-services.json`(API 키 포함)이 들어가 있었고 remote가 없어 히스토리를 잃을 위험이 없다. 히스토리 재작성보다 단순하고 확실하다 (영향받는 항목: git 히스토리 전체, `.gitignore`)
