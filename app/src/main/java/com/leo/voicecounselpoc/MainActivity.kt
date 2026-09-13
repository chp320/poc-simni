package com.leo.voicecounselpoc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.initialize
import com.leo.voicecounselpoc.ui.theme.VoiceCounselPOCTheme

/**
 * Phase 1 / S2 — 통화 UX.
 *
 * 상태와 로직은 [CallViewModel] 이 소유한다. 이 Activity 가 담당하는 것은 셋뿐이다:
 * - Firebase / App Check 앱 수준 초기화
 * - Context 가 필요한 일 (마이크 권한 확인·요청)
 * - 화면 그리기
 */
class MainActivity : ComponentActivity() {

    private val viewModel: CallViewModel by viewModels()

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.startCall() else viewModel.onMicPermissionDenied()
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
                    CallScreen(
                        state = state,
                        onStartCall = ::onStartCall,
                        onEndCall = viewModel::endCall,
                        onToggleMute = viewModel::toggleMute,
                        onDismissError = viewModel::dismissError,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }

    /** 통화 시작 전에 마이크 권한을 확인한다. 왜 필요한지 아는 시점에 묻는다. */
    private fun onStartCall() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            viewModel.startCall()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
