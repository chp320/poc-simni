package com.leo.voicecounselpoc

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 통화 화면 (요구사항 2.2 / 이슈 #20).
 *
 * 사용자에게는 "통화를 건다"는 한 번의 행위다. 내부의 세션 연결과 대화 시작은 드러내지 않는다.
 * 화면은 대기 / 연결 중 / 통화 중 셋뿐이고, 통화 중에는 화면을 계속 볼 필요가 없어야 한다.
 */
@Composable
fun CallScreen(
    state: CallUiState,
    onStartCall: () -> Unit,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (state.phase) {
            CallPhase.Idle -> IdleContent(state, onStartCall)
            CallPhase.Connecting -> {
                KeepScreenOn()
                ConnectingContent()
            }
            CallPhase.Connected, CallPhase.InCall -> {
                KeepScreenOn()
                InCallContent(state, onEndCall, onToggleMute)
            }
            is CallPhase.Failed -> FailedContent(state.phase.message, onDismissError)
        }
    }
}

/**
 * 이 컴포저블이 화면에 있는 동안 화면 자동 꺼짐을 막는다 (이슈 #32).
 *
 * 화면이 꺼지면 약 6초 뒤 SDK 가 대화를 스스로 중단한다. Galaxy S26 은 자동 꺼짐이 30초라
 * 침묵하거나 듣기만 해도 통화가 끊겼다. 전원 버튼·다른 앱 전환까지 막으려면
 * 포그라운드 서비스가 필요하다 (이슈 #13).
 */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

// ---- 대기 -----------------------------------------------------------------

@Composable
private fun IdleContent(state: CallUiState, onStartCall: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        state.endedReason?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }

        Button(
            onClick = onStartCall,
            shape = CircleShape,
            modifier = Modifier.size(180.dp),
        ) {
            Text("통화 시작", style = MaterialTheme.typography.titleLarge)
        }

        Disclaimer(short = true)

        // App Check 가 실패하면 통화 자체가 불가능하므로 미리 알린다.
        if (state.appCheck is AppCheckStatus.Failed) {
            Text(
                "⚠️ 인증에 문제가 있어 통화가 되지 않을 수 있어요.\n${state.appCheck.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---- 연결 중 ---------------------------------------------------------------

/**
 * 연결에 걸리는 약 1.8초를 면책 고지에 쓴다.
 *
 * 고지를 별도 화면으로 띄우면 방해가 되지만, 어차피 기다리는 시간에 얹으면 비용이 0이고
 * 읽힐 확률도 올라간다. 이슈 #18 의 "세션 시작 시 고지 빈도" 미결정이 이걸로 해소됐다.
 */
@Composable
private fun ConnectingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Pulse()
        Text("연결 중…", style = MaterialTheme.typography.titleMedium)
        Disclaimer(short = false)
    }
}

// ---- 통화 중 ---------------------------------------------------------------

@Composable
private fun InCallContent(
    state: CallUiState,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Text(
            if (state.isMuted) "마이크 꺼짐" else "통화 중",
            style = MaterialTheme.typography.titleMedium,
        )
        // 상한이 있으니 사용자가 얼마나 지났는지 알 수 있어야 한다.
        Text(state.elapsedLabel, style = MaterialTheme.typography.displaySmall)

        Pulse(active = !state.isMuted)

        state.notice?.let { NoticeText(it) }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onToggleMute) {
                Text(if (state.isMuted) "마이크 켜기" else "마이크 끄기")
            }
            Button(
                onClick = onEndCall,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("통화 종료")
            }
        }

        // 전사는 기본 숨김. 부정확한 전사가 보이면 모델이 못 알아들었다는 오해만 만든다.
        if (state.showTranscript) {
            Spacer(Modifier.height(8.dp))
            if (state.inputTranscript.isNotBlank()) Text("[내 말] ${state.inputTranscript}")
            if (state.outputTranscript.isNotBlank()) Text("[모델] ${state.outputTranscript}")
        }
    }
}

@Composable
private fun NoticeText(notice: CallNotice) {
    Text(
        text = when (notice) {
            CallNotice.FiveMinutesLeft -> "5분 남았어요"
            CallNotice.EndingSoon -> "곧 통화가 끝나요"
            CallNotice.StillThere -> "아직 계신가요?"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

// ---- 오류 ------------------------------------------------------------------

@Composable
private fun FailedContent(message: String, onDismiss: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.padding(32.dp),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onDismiss) { Text("확인") }
    }
}

// ---- 공용 ------------------------------------------------------------------

/**
 * 듣고 있다는 신호. 정적인 화면은 연결이 끊긴 것처럼 느껴진다.
 *
 * 실제 음량에 연동하지는 않는다 — `startAudioConversation()` 이 SDK 내부 `AudioRecord` 를
 * 쓰기 때문에 진폭을 얻을 수 없다.
 */
@Composable
private fun Pulse(active: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )

    Box(
        modifier = Modifier
            .size(120.dp)
            .scale(if (active) scale else 1f)
            .alpha(if (active) 1f else 0.3f)
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                shape = CircleShape,
            ),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
}

/**
 * 면책 고지 (이슈 #18).
 *
 * 법률 문구처럼 차갑게 쓰지 않는다. 힘들 때 여는 앱이라 첫 화면이 경고문 같으면
 * 그 자체가 진입장벽이 된다. 다만 "전문 상담을 대체하지 않는다"는 흐리지 않는다.
 *
 * 전체판과 동의 절차는 S4 에서 붙인다.
 */
@Composable
private fun Disclaimer(short: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

    if (short) {
        Text(
            "AI와의 대화예요. 전문 상담을 대신하지는 않아요.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
            textAlign = TextAlign.Center,
        )
        return
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "AI와의 대화예요.\n전문 심리 상담이나 의료 행위를 대신하지 않아요.",
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            textAlign = TextAlign.Center,
        )
        Text(
            "대화 내용은 AI 처리를 위해 외부로 전송돼요.",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
            textAlign = TextAlign.Center,
        )
        Text(
            "많이 힘드시다면 혼자 견디지 마세요.\n자살예방상담 109 · 정신건강위기상담 1577-0199 (24시간)",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Unspecified,
            textAlign = TextAlign.Center,
        )
    }
}
