package com.leo.voicecounselpoc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
 * - Context 가 필요한 일 (마이크·알림 권한 확인·요청, 통화 중 뒤로가기, 알림의 통화 종료 인텐트)
 * - 화면 그리기
 */
class MainActivity : ComponentActivity() {

    private val viewModel: CallViewModel by viewModels()

    /**
     * 마이크와 알림 권한을 함께 묻는다. 마이크만 필수다 — 알림을 거부해도 통화와 백그라운드 유지는
     * 동작하고 "통화 중" 알림만 보이지 않는다 (이슈 #13).
     */
    private val requestCallPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val micGranted = results[Manifest.permission.RECORD_AUDIO] ?: isGranted(Manifest.permission.RECORD_AUDIO)
            if (micGranted) viewModel.startCall() else viewModel.onMicPermissionDenied()
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

        // 알림의 "통화 종료". singleTop 이라 기존 화면으로 온다.
        addOnNewIntentListener { intent ->
            if (intent.action == CallService.ACTION_END_CALL) viewModel.endCall()
        }

        enableEdgeToEdge()
        setContent {
            VoiceCounselPOCTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()

                // 통화 중 뒤로가기는 앱을 닫지 않고 백그라운드로 보낸다. 화면이 끝나면 ViewModel 과 함께
                // 통화도 끊기기 때문이다 (B1, 이슈 #13). 종료는 버튼이나 알림에서만 한다.
                BackHandler(enabled = state.isSessionOpen || state.isTransitioning) {
                    moveTaskToBack(true)
                }

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

    /** 통화 시작 전에 권한을 확인한다. 왜 필요한지 아는 시점에 묻는다. */
    private fun onStartCall() {
        val missing = buildList {
            if (!isGranted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !isGranted(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (missing.isEmpty()) {
            viewModel.startCall()
        } else {
            // 알림을 영구 거부했다면 시스템이 창 없이 바로 결과를 돌려준다. 통화는 막히지 않는다.
            requestCallPermissions.launch(missing.toTypedArray())
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
