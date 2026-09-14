package com.leo.voicecounselpoc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 통화 중에 앱이 화면에서 사라져도 마이크를 쓸 수 있게 붙잡는 포그라운드 서비스 (이슈 #13).
 *
 * Android 는 화면에 보이지 않는 앱의 마이크를 막는다. 화면이 꺼지거나 다른 앱으로 전환하면
 * SDK 가 녹음에 실패하고 대화를 스스로 멈춘다 (이슈 #32 — 화면 꺼짐 약 6초 뒤 종료).
 * 마이크 타입 포그라운드 서비스와 상시 알림이 있으면 예외로 허용된다.
 *
 * **B1 설계**: 이 서비스는 유지 역할만 한다. Live 세션과 통화 로직은 [CallViewModel] 이 계속
 * 소유한다. 그래서 알림의 "통화 종료"도 여기서 직접 끊지 않고 [MainActivity] 로 인텐트를 보낸다.
 */
class CallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            Log.i(TAG, "통화 유지 서비스 시작 — 포그라운드 (마이크)")
        }.onFailure {
            // 백그라운드에서 시작했거나 마이크 권한이 없으면 거부된다. 통화는 화면이 켜진 동안만 유지된다.
            Log.w(TAG, "통화 유지 서비스 시작 실패 — 백그라운드 통화가 유지되지 않는다", it)
            stopSelf()
        }
        // 프로세스가 죽었다가 살아나도 세션은 ViewModel 과 함께 사라졌으므로 다시 만들지 않는다.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "통화 유지 서비스 종료")
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // 중요도 LOW — 상담 중에 소리나 진동으로 방해하지 않는다.
        val channel = NotificationChannel(CHANNEL_ID, "통화 중", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle("통화 중")
        .setContentText("AI와 대화하고 있어요. 눌러서 돌아가기")
        .setCategory(NotificationCompat.CATEGORY_CALL)
        .setOngoing(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(activityIntent(action = null, requestCode = 0))
        .addAction(0, "통화 종료", activityIntent(action = ACTION_END_CALL, requestCode = 1))
        .build()

    private fun activityIntent(action: String?, requestCode: Int): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val TAG = "VoiceCounselPOC"
        private const val CHANNEL_ID = "call"
        private const val NOTIFICATION_ID = 1

        /** 알림의 "통화 종료" 버튼이 [MainActivity] 로 보내는 액션. */
        const val ACTION_END_CALL = "com.leo.voicecounselpoc.action.END_CALL"

        /**
         * 서비스를 시작한다. 앱이 화면에 보이는 동안 호출해야 한다 — Android 12+ 는 백그라운드에서
         * 포그라운드 서비스를 시작할 수 없고, 마이크 타입은 특히 제한이 강하다.
         */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))
            }.onFailure { Log.w(TAG, "통화 유지 서비스 시작 요청 실패", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
