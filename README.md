# VoiceCounselPOC

AI와 실시간 음성 대화(통화 느낌)를 나누는 Android 앱의 **기술 검증(PoC)** 저장소.

최종 목표는 심리 상담을 병행하는 AI 음성 대화 앱이며, 이 저장소는 그중 **음성 연동 방식이 성립하는지**만 확인하기 위한 별도 스파이크다. 프로덕션 코드가 아니다.

> ⚠️ 이 앱은 의료 행위나 전문 심리 상담을 대체하지 않는다. 현재는 기술 검증 단계이며 위기 대응 로직이 구현되어 있지 않다.

---

## 현재 상태: Phase 0 완료 ✅

**"말하면 음성으로 답이 온다"가 실기기에서 성립함을 확인했다.**

| 완료 기준 | 결과 |
|---|---|
| 마이크 입력 캡처 | ✅ 무음 진폭 402 → 발화 최대 8672 |
| Live API로 오디오 전송 | ✅ 입력 전사가 정확히 회신 |
| 음성 응답 수신 및 스피커 재생 | ✅ 실기기 스피커로 청취 확인 |
| 왕복 1회 성립 | ✅ 다회 성공 |

검증 기기: **Alldocube iPlay60 mini Pro** (Android 14 / API 34 / arm64-v8a)

### 주요 측정값

- **응답 지연**: 입력 전사 도착 → 출력 전사 시작까지 11~29ms. 체감 "거의 즉시"
- **세션 연결**: 약 1.7~1.8초
- **한국어 인식**: 장문은 거의 완벽, 짧은 발화는 취약 (한 마디가 스페인어로 오인식된 사례 있음)
- **끼어들기**: `enableInterruptions = true`로 즉시 중단 확인. 모델이 끊김을 인지하고 대응함

### 기록해 둘 만한 발견

**전사(transcription) 품질과 모델의 실제 이해도는 다르다.** "오늘은 일요일이고"가 전사에서는 "응. 응. 이들이고"로 깨졌으나, 모델은 "네, 일요일 오후 12시 14분이군요"라고 정확히 응답했다. native-audio 모델은 원본 오디오를 직접 이해하며 전사는 별도 ASR 경로다. **전사 로그만 보고 한국어 인식 성능을 판단하면 실제보다 나쁘게 오판하게 된다.**

---

## 기술 스택

| 항목 | 값 |
|---|---|
| 언어 | Kotlin |
| UI | Jetpack Compose (Material 3) |
| AI | Firebase AI Logic → Gemini Live API |
| 모델 | `gemini-3.1-flash-live-preview` |
| 백엔드 | Gemini Developer API (`GenerativeBackend.googleAI()`) — 서버 없음 |
| Firebase BoM | 34.19.0 (`firebase-ai` 17.17.0) |
| AGP / Kotlin / Gradle | 8.11.2 / 2.0.21 / 8.13 |
| minSdk / compileSdk | 23 / 36 |

오디오 포맷은 Live API 규격에 맞춘 **PCM 16-bit / 24kHz / mono**다.

---

## 빌드 전 준비

이 저장소만 clone해서는 **빌드가 되지 않는다.** 시크릿이 커밋되어 있지 않기 때문이다.

### 1. `google-services.json` 배치

Firebase 콘솔에서 Android 앱 설정 파일을 내려받아 `app/google-services.json`에 둔다.
`.gitignore`에 등록되어 있으므로 커밋되지 않는다.

```
app/google-services.json
```

### 2. Firebase 콘솔 설정

세 가지가 모두 켜져 있어야 한다. 하나라도 빠지면 세션 연결이 거부된다.

- **AI Logic** 활성화 → Gemini Developer API 선택
- **Firebase App Check API**(`firebaseappcheck.googleapis.com`) 활성화
  — 토큰 등록보다 **먼저** 해야 한다 ([#3](../../issues/3))
- **App Check 디버그 토큰** 등록

### 3. App Check 디버그 토큰 등록

2026년 7월부터 Firebase AI Logic에 App Check가 자동 강제된다. 등록하지 않으면 호출이 전부 거부된다.

앱을 한 번 실행하면 Logcat에 토큰이 출력된다:

```bash
adb logcat -d | grep "Firebase App Check debug token"
```

출력된 UUID를 Firebase 콘솔 > App Check > 앱 > 디버그 토큰 관리에 등록한다.

**토큰은 기기마다 새로 생성된다.** 에뮬레이터용 토큰은 실기기에서 쓸 수 없다.

이름은 `{기기구분}-{용도}-{등록일자}` 형식으로 붙인다:

```
alldocube-iplay60-dev-20260913
emulator-pixel2-dev-20260913
```

> 🔒 디버그 토큰은 App Check를 우회할 수 있는 시크릿이다. 저장소·문서·커밋 메시지에 값을 남기지 않는다.

### 4. 빌드

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 사용법

앱 화면에 버튼이 세 개 있다. 디버그용 UI이며 디자인 작업은 하지 않았다.

| 버튼 | 동작 |
|---|---|
| **세션 연결** | Live API 세션을 연다. 대화 전에 먼저 눌러야 한다 |
| **대화 시작** | 음성 대화 시작. 마이크 캡처·전송·수신·재생을 SDK가 처리 |
| **녹음 시작** | 마이크 캡처만 테스트. 진폭이 화면과 Logcat에 표시됨 |

대화 중에는 내 말과 모델의 말이 전사되어 화면과 Logcat에 함께 표시된다.

```bash
adb logcat -s VoiceCounselPOC
```

> **대화 중 "진폭"이 0으로 고정되는 것은 정상이다.** `startAudioConversation()`은 SDK 자체 `AudioRecord`를 쓰며, 진폭을 계산하는 `AudioRecorder`를 거치지 않는다. 두 버튼은 별개 경로다.

---

## 구조

```
app/src/main/java/com/leo/voicecounselpoc/
├── MainActivity.kt         App Check 초기화, 디버그 UI, 상태 표시
├── LiveSessionManager.kt   Live 세션 연결/해제, 음성 대화, 전사 핸들러
├── AudioRecorder.kt        PCM 캡처 + 진폭 계산 (2단계 검증용)
└── AiConfig.kt             모델명·오디오 포맷 상수
```

**`LiveSession` 타입은 `LiveSessionManager` 밖으로 나가지 않는다.** Live API는 `@PublicPreviewAPI` opt-in을 요구하는데, 타입이 노출되면 `@OptIn`이 호출부까지 전염된다. preview 표면을 한 파일에 가두면 SDK 변경 시 수정 범위도 한 파일로 제한된다.

**`AudioRecorder`는 현재 대화 경로에서 쓰이지 않지만 삭제하지 않았다.** 마이크가 실제로 동작하는지 독립적으로 검증한 수단이었고, 나중에 저수준 스트리밍(`sendAudioRealtime` / `receive()`)이 필요해지면 출발점이 된다.

---

## 다음 단계

Phase 0으로 **기술적 실현 가능성은 확인됐다.** 다음은 무엇을 만들지 정하는 일이다.

| 우선순위 | 작업 |
|---|---|
| 1 | [요구사항 문서 작성](../../issues/14) — 제품 정체성, 사용 패턴, 안전 정책, 데이터 |
| 2 | [비용 측정](../../issues/9) — 사업성 판단의 선행 조건. 세션 길이·빈도가 정해져야 계산 가능 |
| 3 | [세션 안정성 검증](../../issues/10) — 장시간 대화, `GoAway`, 재연결. 가장 큰 미검증 리스크 |

전체 과제는 [이슈 목록](../../issues)에 정리되어 있다. Phase 0에서 겪은 문제와 해결 방법은 [닫힌 이슈](../../issues?q=is%3Aissue+is%3Aclosed)에 기록해 두었다.

### 아직 검증하지 않은 것

Phase 0은 "왕복 1회 성립"이 목표였다. 아래는 **의도적으로 범위 밖에 둔 것**이며, 리스크로 남아 있다.

- 장시간 대화 안정성 / 네트워크 불안정 대응
- 비용 — 상담 앱은 세션이 길어 사업성에 직결됨
- 에코 캔슬 — 현재 `AudioSource.MIC`, 스피커폰 환경 미검증
- 보안 — 클라이언트가 Firebase를 직접 호출 (백엔드·토큰 발급은 Phase 2)
- 기기 호환성 — 태블릿 1대만 확인
- `gemini-3.1-flash-live-preview`의 GA 일정

---

## 문서

작업 중 내려진 모든 결정과 그 이유는 [`CLAUDE.md`](CLAUDE.md)의 **결정 기록** 섹션에 남아 있다. Phase 0 검증 결과 원본도 같은 문서에 있다.
