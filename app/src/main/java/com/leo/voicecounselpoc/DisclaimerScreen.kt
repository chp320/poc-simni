package com.leo.voicecounselpoc

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

/**
 * 면책 고지 전체판 (요구사항 5.3 / 이슈 #18).
 *
 * 최초 실행 때 한 번 보여주고 "확인했습니다"를 받는다. 이후에는 대기 화면의 "이 앱에 대해"로
 * 언제든 다시 열 수 있다. 설정 화면이 생기면 그쪽으로 옮긴다.
 *
 * 법률 문구처럼 차갑게 쓰지 않는다 — 힘들 때 여는 앱이라 첫 화면이 경고문 같으면 그 자체가
 * 진입장벽이 된다. 다만 "전문 상담을 대체하지 않는다"는 흐리지 않는다.
 *
 * ⚠️ 문구 최종 확정 전 전문가 검토가 필요하다.
 *
 * @param confirmLabel 최초 실행이면 "확인했습니다", 다시 보기면 "닫기"
 */
@Composable
fun DisclaimerScreen(
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 32.dp),
    ) {
        Text(
            "이 앱에 대해 알아두세요",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Paragraph("이 앱은 AI와 나누는 대화입니다.")
        // 이 문장은 부드럽게 쓰되 흐리지 않는다 (#18).
        Paragraph("전문 심리 상담이나 의료 행위를 대체하지 않으며, 진단이나 치료를 제공하지 않습니다.")
        Paragraph("AI의 말은 참고일 뿐입니다. 중요한 결정은 스스로, 또는 믿을 수 있는 사람과 함께 내려주세요.")

        // 2026-09-15 보강: Gemini API 유료 약관(학습 미사용, 금지 사용 탐지용 한시 보관)과 데이터 정책(#19) 기준.
        // 출시 전 #35(전사 로그 제거)가 선행되어야 마지막 문장이 사실이 된다.
        Paragraph(
            "대화 음성은 AI 처리를 위해 Google 서버로 전송됩니다. " +
                "AI 학습에는 사용되지 않지만, 안전한 서비스 운영을 위해 일정 기간 보관될 수 있습니다. " +
                "대화 원문과 음성은 이 기기에 저장하지 않습니다."
        )

        Spacer(Modifier.height(4.dp))
        Paragraph("지금 많이 힘드시다면 혼자 견디지 마세요.")
        Column {
            HelplineButton("자살예방상담전화 109 (24시간)", "109")
            HelplineButton("정신건강위기상담전화 1577-0199 (24시간)", "15770199")
            HelplineButton("긴급한 상황이라면 112", "112")
            HelplineButton("긴급한 상황이라면 119", "119")
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Text(confirmLabel)
        }
    }
}

@Composable
private fun Paragraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun HelplineButton(label: String, number: String) {
    val context = LocalContext.current
    TextButton(onClick = { openDialer(context, number) }) {
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 전화 앱을 번호가 입력된 상태로 연다. 전화를 거는 것은 사용자가 통화 버튼을 눌러서 한다.
 *
 * `ACTION_DIAL` 은 전화 권한(`CALL_PHONE`)이 필요 없다. 바로 거는 `ACTION_CALL` 은 권한이 필요하고,
 * 실수로 눌렀을 때 되돌릴 수 없어 쓰지 않는다. 태블릿처럼 전화 앱이 없는 기기에서는 안내만 띄운다.
 */
fun openDialer(context: Context, number: String) {
    val intent = Intent(Intent.ACTION_DIAL, "tel:$number".toUri())
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w("VoiceCounselPOC", "전화 앱 없음 — $number 연결 불가", e)
        Toast.makeText(context, "이 기기에서는 전화를 걸 수 없어요. $number 로 연락해 주세요.", Toast.LENGTH_LONG).show()
    }
}
