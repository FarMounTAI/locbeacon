package com.yuanshan.locbeacon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 前台服务：维持与 AstrBot 插件的 WebSocket 长连接。
 *
 * 流程：
 *   连上 -> 发 hello -> 等 {"cmd":"get_location"} -> 取点 -> 回 {"type":"location"}
 *   断线 -> 指数退避重连（1s/2s/4s… 最多 60s）
 *
 * 为什么必须是前台服务：Android 8 之后后台应用不能起普通 Service，
 * 而且只有前台服务能豁免 Doze 的网络限制。代价是通知栏必须挂一条，这是规定。
 */
class LocationService : Service() {

    companion object {
        private const val TAG = "LocBeacon"
        private const val CHANNEL_ID = "loc_beacon_service"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.yuanshan.locbeacon.ACTION_STOP"

        /** 给界面读的运行状态，@Volatile 保证跨线程可见。 */
        @Volatile
        var status: String = "未启动"

        @Volatile
        var running: Boolean = false
    }

    private val handler = Handler(Looper.getMainLooper())

    private var webSocket: WebSocket? = null
    private var retryDelayMs = 1_000L

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)   // 协议层心跳，服务端会自动回 pong
        .retryOnConnectionFailure(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        running = true
        connect()
        return START_STICKY   // 被系统杀掉后尝试重启
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "service stopped")
        webSocket = null
        client.dispatcher.executorService.shutdown()
        status = "已停止"
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 前台通知

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "位置服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持与服务端的长连接"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            // 没有定位权限时 Android 14 会在这里抛 SecurityException
            Log.e(TAG, "startForeground 失败: ${e.message}")
            status = "启动失败：${e.message}"
            running = false
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("位置感知")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }

    private fun setStatus(text: String) {
        status = text
        Log.i(TAG, text)
        if (running) {
            try {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification())
            } catch (e: Exception) {
                Log.w(TAG, "更新通知失败: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- 连接

    private fun connect() {
        val prefs = Prefs(this)
        val url = prefs.url
        val token = prefs.token

        if (url.isBlank() || token.isBlank()) {
            setStatus("未配置服务器地址或 Token")
            return
        }
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            setStatus("地址必须以 ws:// 或 wss:// 开头")
            return
        }

        webSocket?.cancel()
        setStatus("连接中…")

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()

        webSocket = client.newWebSocket(request, socketListener)
    }

    private fun scheduleReconnect() {
        if (!running) return
        val delay = retryDelayMs
        handler.postDelayed({
            if (running) connect()
        }, delay)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
    }

    private val socketListener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            retryDelayMs = 1_000L
            setStatus("已连接")

            val hello = JSONObject()
                .put("type", "hello")
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("battery", batteryPercent())
            webSocket.send(hello.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = try {
                JSONObject(text)
            } catch (e: Exception) {
                Log.w(TAG, "收到非 JSON 消息: $text")
                return
            }

            when (obj.optString("cmd")) {
                "get_location" -> handleLocationRequest(webSocket, obj.optString("req_id"))
                "" -> { /* 服务端的 pong / ack，忽略即可 */ }
                else -> Log.d(TAG, "未知指令: ${obj.optString("cmd")}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "连接断开: ${t.message}")
            if (running) {
                setStatus("断开，${retryDelayMs / 1000} 秒后重连")
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "连接关闭: $code $reason")
            if (running) {
                setStatus("连接已关闭")
                scheduleReconnect()
            }
        }
    }

    // ---------------------------------------------------------------- 取点回传

    private fun handleLocationRequest(ws: WebSocket, reqId: String) {
        setStatus("收到取点指令，正在定位…")

        LocationFetcher.fetch(this) { loc ->
            val payload = JSONObject()
            if (loc != null) {
                payload.put("type", "location")
                    .put("req_id", reqId)
                    .put("lat", loc.latitude)
                    .put("lng", loc.longitude)
                    .put("acc", loc.accuracy.toDouble())
                    .put("battery", batteryPercent())
                    .put("src", "app")
                setStatus("已上报位置")
            } else {
                payload.put("type", "error")
                    .put("req_id", reqId)
                    .put("msg", "location unavailable")
                setStatus("定位失败")
            }

            val ok = ws.send(payload.toString())
            if (!ok) Log.w(TAG, "回传失败，连接可能已断")
        }
    }

    private fun batteryPercent(): Int {
        return try {
            val bm = getSystemService(BatteryManager::class.java)
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 0..100) level else -1
        } catch (e: Exception) {
            -1
        }
    }
}
