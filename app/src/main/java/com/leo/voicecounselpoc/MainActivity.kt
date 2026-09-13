package com.leo.voicecounselpoc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.firebase.Firebase
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.initialize
import com.leo.voicecounselpoc.ui.theme.VoiceCounselPOCTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Phase 0 / 1~3단계.
 *
 * - App Check 디버그 프로바이더 등록 및 토큰 획득 (1단계)
 * - 마이크 런타임 권한 요청 + PCM 캡처, 진폭을 화면과 Logcat에 표시 (2단계)
 *
 * - Live 세션 연결/해제 (3단계)
 *
 * 캡처한 오디오를 세션으로 보내는 것은 4단계 범위다.
 */
class MainActivity : ComponentActivity() {

    private var appCheckStatus by mutableStateOf("App Check 토큰 요청 중…")
    private var micStatus by mutableStateOf("마이크 대기 중")
    private var isRecording by mutableStateOf(false)
    private var amplitude by mutableIntStateOf(0)

    private var recordJob: Job? = null

    private val liveSession = LiveSessionManager()
    private var sessionStatus by mutableStateOf("Live 세션: 미연결")
    private var isSessionConnected by mutableStateOf(false)
    private var isSessionBusy by mutableStateOf(false)

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startRecording()
            } else {
                micStatus = "마이크 권한이 거부되었습니다. 설정에서 허용 후 다시 시도하세요."
                Log.w(TAG, "RECORD_AUDIO 권한 거부됨")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Firebase.initialize(context = this)
        Firebase.appCheck.installAppCheckProviderFactory(
            DebugAppCheckProviderFactory.getInstance()
        )
        Firebase.appCheck.getAppCheckToken(false)
            .addOnSuccessListener {
                Log.i(TAG, "App Check 토큰 획득 성공 — 디버그 토큰이 콘솔에 등록되어 있다.")
                appCheckStatus = "✅ App Check OK"
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "App Check 토큰 획득 실패 — 콘솔에 디버그 토큰을 등록해야 한다.", e)
                appCheckStatus = "❌ App Check 실패: ${e.message}"
            }

        enableEdgeToEdge()
        setContent {
            VoiceCounselPOCTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(appCheckStatus)

                        Text(sessionStatus)
                        Button(onClick = ::onToggleSession, enabled = !isSessionBusy) {
                            Text(if (isSessionConnected) "세션 해제" else "세션 연결")
                        }

                        Text(micStatus)

                        Button(onClick = ::onToggleRecording) {
                            Text(if (isRecording) "녹음 정지" else "녹음 시작")
                        }

                        Text("진폭: $amplitude / ${AudioRecorder.MAX_AMPLITUDE}")
                        LinearProgressIndicator(
                            progress = { amplitude.toFloat() / AudioRecorder.MAX_AMPLITUDE },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    private fun onToggleSession() {
        if (isSessionBusy) return
        lifecycleScope.launch {
            isSessionBusy = true
            try {
                if (isSessionConnected) {
                    liveSession.disconnect()
                    isSessionConnected = false
                    sessionStatus = "Live 세션: 해제됨"
                } else {
                    sessionStatus = "Live 세션: 연결 중… (${AiConfig.LIVE_MODEL_NAME})"
                    liveSession.connect(AiConfig.LIVE_MODEL_NAME)
                    isSessionConnected = true
                    sessionStatus = "✅ Live 세션 연결됨 (${AiConfig.LIVE_MODEL_NAME})"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Live 세션 오류", e)
                isSessionConnected = false
                sessionStatus = "❌ 연결 실패: ${e.message}"
            } finally {
                isSessionBusy = false
            }
        }
    }

    private fun onToggleRecording() {
        when {
            isRecording -> stopRecording()

            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED -> startRecording()

            else -> requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        if (isRecording) return
        isRecording = true
        micStatus = "🎙 녹음 중 — 말해보세요"
        Log.i(TAG, "오디오 캡처 시작 (${AudioRecorder.SAMPLE_RATE}Hz / 16-bit / mono)")

        var chunkCount = 0
        recordJob = lifecycleScope.launch {
            try {
                AudioRecorder().start().collect { chunk ->
                    amplitude = chunk.peakAmplitude
                    // 청크는 100ms마다 오므로 로그는 약 0.5초에 한 번만 남긴다.
                    if (chunkCount++ % 5 == 0) {
                        Log.d(TAG, "진폭=${chunk.peakAmplitude} (청크 ${chunk.pcm.size}바이트)")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "오디오 캡처 실패", e)
                micStatus = "캡처 실패: ${e.message}"
                isRecording = false
                amplitude = 0
            }
        }
    }

    private fun stopRecording() {
        recordJob?.cancel()
        recordJob = null
        isRecording = false
        amplitude = 0
        micStatus = "마이크 정지됨"
        Log.i(TAG, "오디오 캡처 정지")
    }

    override fun onStop() {
        super.onStop()
        // 화면을 벗어나면 마이크를 놓아준다. Phase 0은 포그라운드 전용이다.
        if (isRecording) stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        // lifecycleScope 는 이 시점에 이미 취소되므로 별도 스코프에서 정리한다.
        if (liveSession.isConnected) {
            CoroutineScope(Dispatchers.IO).launch { liveSession.disconnect() }
        }
    }

    companion object {
        private const val TAG = "VoiceCounselPOC"
    }
}
