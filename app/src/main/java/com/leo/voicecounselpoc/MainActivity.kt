package com.leo.voicecounselpoc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.initialize
import com.leo.voicecounselpoc.ui.theme.VoiceCounselPOCTheme

/**
 * Phase 1 / S1 — 아키텍처 정리.
 *
 * 상태와 로직은 [CallViewModel] 이 소유한다. 이 Activity 가 담당하는 것은 셋뿐이다:
 * - Firebase / App Check 앱 수준 초기화
 * - Context 가 필요한 일 (마이크 권한 확인·요청)
 * - 화면 그리기
 *
 * 화면 구성은 Phase 0 디버그 UI 그대로다. 통화 UX 교체는 S2에서 한다.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: CallViewModel by viewModels()

    /** 권한 팝업이 뜬 이유. 허용된 뒤 무엇을 이어서 할지 기억해 둔다. */
    private var pendingMicAction: (() -> Unit)? = null

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                pendingMicAction?.invoke()
            } else {
                viewModel.onMicPermissionDenied()
            }
            pendingMicAction = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // App Check 설치는 앱 수준 설정이라 Activity 에 둔다.
        // 토큰 요청과 그 결과 상태는 ViewModel 이 관리한다.
        Firebase.initialize(context = this)
        Firebase.appCheck.installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance()
        )
        viewModel.verifyAppCheck()

        enableEdgeToEdge()
        setContent {
            VoiceCounselPOCTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DebugScreen(
                        state = state,
                        onToggleSession = viewModel::toggleSession,
                        onToggleConversation = ::onToggleConversation,
                        onToggleMicProbe = ::onToggleMicProbe,
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(24.dp),
                    )
                }
            }
        }
    }

    // ---- 권한이 필요한 동작 ------------------------------------------------

    private fun onToggleConversation() {
        if (viewModel.uiState.value.isInCall) {
            viewModel.stopConversation()
        } else {
            withMicPermission { viewModel.startConversation() }
        }
    }

    private fun onToggleMicProbe() {
        if (viewModel.uiState.value.micProbe.isRecording) {
            viewModel.stopMicProbe()
        } else {
            withMicPermission { viewModel.startMicProbe() }
        }
    }

    /** 권한이 있으면 바로 실행하고, 없으면 요청 후 허용됐을 때 실행한다. */
    private fun withMicPermission(action: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            action()
        } else {
            pendingMicAction = action
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        super.onStop()
        // 화면을 벗어나면 마이크 검증용 캡처는 놓아준다.
        // Live 세션은 유지한다 — 화면 회전으로도 onStop 이 호출되기 때문이다.
        viewModel.stopMicProbe()
    }
}

/** Phase 0 디버그 화면. S2에서 통화 UI로 교체된다. */
@Composable
private fun DebugScreen(
    state: CallUiState,
    onToggleSession: () -> Unit,
    onToggleConversation: () -> Unit,
    onToggleMicProbe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            when (val check = state.appCheck) {
                AppCheckStatus.Checking -> "App Check 토큰 요청 중…"
                AppCheckStatus.Ok -> "✅ App Check OK"
                is AppCheckStatus.Failed -> "❌ App Check 실패: ${check.message}"
            }
        )

        Text(
            when (val phase = state.phase) {
                CallPhase.Idle -> "Live 세션: 미연결"
                CallPhase.Connecting -> "Live 세션: 연결 중… (${AiConfig.LIVE_MODEL_NAME})"
                CallPhase.Connected -> "✅ Live 세션 연결됨 (${AiConfig.LIVE_MODEL_NAME})"
                CallPhase.InCall -> "🎙 대화 중 — 말해보세요"
                is CallPhase.Failed -> "❌ ${phase.message}"
            }
        )

        Button(onClick = onToggleSession, enabled = !state.isTransitioning) {
            Text(if (state.isSessionOpen) "세션 해제" else "세션 연결")
        }

        Button(
            onClick = onToggleConversation,
            enabled = state.isSessionOpen && !state.isTransitioning,
        ) {
            Text(if (state.isInCall) "대화 종료" else "🎙 대화 시작")
        }

        if (state.inputTranscript.isNotBlank()) Text("[내 말] ${state.inputTranscript}")
        if (state.outputTranscript.isNotBlank()) Text("[모델] ${state.outputTranscript}")

        Text(state.micProbe.message)

        Button(onClick = onToggleMicProbe) {
            Text(if (state.micProbe.isRecording) "녹음 정지" else "녹음 시작")
        }

        Text("진폭: ${state.micProbe.amplitude} / ${AudioRecorder.MAX_AMPLITUDE}")
        LinearProgressIndicator(
            progress = { state.micProbe.amplitude.toFloat() / AudioRecorder.MAX_AMPLITUDE },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
