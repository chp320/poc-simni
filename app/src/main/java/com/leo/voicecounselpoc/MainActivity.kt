package com.leo.voicecounselpoc

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.initialize
import com.leo.voicecounselpoc.ui.theme.VoiceCounselPOCTheme

/**
 * Phase 0 / App Check 단계.
 *
 * 여기서 하는 일은 딱 하나: App Check 디버그 프로바이더를 등록하고 토큰을 한 번 요청해서
 * Logcat에 디버그 시크릿이 찍히게 만드는 것. `liveModel` 연동은 아직 하지 않는다.
 *
 * 디버그 시크릿 확인 방법:
 *   adb logcat -s DebugAppCheckProvider
 * 출력된 UUID를 Firebase 콘솔 > App Check > 앱 > (메뉴) 디버그 토큰 관리 에 등록한다.
 * 등록하지 않으면 Firebase AI Logic 호출이 거부된다(2026년 7월부터 자동 강제).
 */
class MainActivity : ComponentActivity() {

    private var status by mutableStateOf("App Check 토큰 요청 중…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Firebase.initialize(context = this)
        Firebase.appCheck.installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance()
        )

        // 토큰을 실제로 한 번 요청한다. 성공/실패로 콘솔 등록이 끝났는지 바로 알 수 있다.
        Firebase.appCheck.getAppCheckToken(false)
            .addOnSuccessListener {
                Log.i(TAG, "App Check 토큰 획득 성공 — 디버그 토큰이 콘솔에 등록되어 있다.")
                status = "✅ App Check OK\n\n디버그 토큰이 Firebase 콘솔에 등록되어 있습니다.\n2단계로 진행 가능합니다."
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "App Check 토큰 획득 실패 — 콘솔에 디버그 토큰을 등록해야 한다.", e)
                status = "❌ App Check 실패\n\n" +
                        "Logcat에서 'DebugAppCheckProvider' 태그의 디버그 시크릿(UUID)을 찾아\n" +
                        "Firebase 콘솔 > App Check > 디버그 토큰 관리에 등록하고 다시 실행하세요.\n\n" +
                        "원인: ${e.message}"
            }

        enableEdgeToEdge()
        setContent {
            VoiceCounselPOCTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Text(
                        text = status,
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(24.dp)
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "VoiceCounselPOC"
    }
}
