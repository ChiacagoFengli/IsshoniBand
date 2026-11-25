package com.isshoniband.v2

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.isshoniband.v2.sync.Session
import com.isshoniband.v2.sync.Role

/**
 * 前台服务：让 App 切到后台时不被系统直接杀掉。
 *
 * 华为的 iAware 调度在 App 一离开前台就会 force stop，连接必断。
 * 挂一个 mediaPlayback 类型的前台服务后，进程优先级提高，能持续保活。
 */
class SessionService : Service() {

    private var roomAudio: com.isshoniband.v2.rehearsal.RoomController? = null
    private var hostLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "正在排练"
        startForeground(NOTIF_ID, buildNotification(text))
        val role = Session.state.value.role
        if (role == Role.NONE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (role == Role.HOST && hostLock == null) {
            hostLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IsshoniBand:host").apply { acquire() }
        } else if (role != Role.HOST) {
            releaseHostLock()
        }
        Session.startRoomAudio(applicationContext)
        roomAudio = Session.controller.value
        return START_NOT_STICKY
    }

    private fun releaseHostLock() {
        hostLock?.let { if (it.isHeld) it.release() }
        hostLock = null
    }

    override fun onDestroy() {
        releaseHostLock()
        if (roomAudio != null && roomAudio === Session.controller.value) Session.releaseRoomAudio()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("一休尼帮多")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "排练状态",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持排练连接不被系统清理"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "isshoni_session"
        private const val NOTIF_ID = 1001
        private const val EXTRA_TEXT = "text"

        fun start(context: Context, text: String) {
            val intent = Intent(context, SessionService::class.java).putExtra(EXTRA_TEXT, text)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w("IsshoniService", "前台服务操作失败", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, SessionService::class.java))
            } catch (e: Exception) {
                Log.w("IsshoniService", "前台服务操作失败", e)
            }
        }
    }
}
