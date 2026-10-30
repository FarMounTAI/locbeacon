package com.yuanshan.locbeacon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 设置页：填服务器地址和 Token，控制服务启停，申请后台运行权限。
 * 界面极简是故意的——这个 App 的全部价值在后台那个 Service 里。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var etToken: EditText
    private lateinit var tvStatus: TextView
    private lateinit var prefs: Prefs

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        etUrl = findViewById(R.id.etUrl)
        etToken = findViewById(R.id.etToken)
        tvStatus = findViewById(R.id.tvStatus)

        etUrl.setText(prefs.url)
        etToken.setText(prefs.token)

        findViewById<Button>(R.id.btnConnect).setOnClickListener { startTracking() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopTracking() }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestIgnoreBattery() }

        requestPermissionsIfNeeded()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ---------------------------------------------------------------- 操作

    private fun startTracking() {
        prefs.url = etUrl.text.toString()
        prefs.token = etToken.text.toString()

        if (prefs.url.isBlank() || prefs.token.isBlank()) {
            toast("先把服务器地址和 Token 填上")
            return
        }

        // Android 14 起，location 类型的前台服务必须在已有定位权限时才能启动，
        // 否则 startForeground 直接抛 SecurityException。先挡一道。
        val hasLocation =
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            toast("先授予定位权限")
            requestPermissionsIfNeeded()
            return
        }

        try {
            val intent = Intent(this, LocationService::class.java)
            ContextCompat.startForegroundService(this, intent)
            toast("已启动，看通知栏")
        } catch (e: Exception) {
            toast("启动失败：${e.message}")
            return
        }
        handler.postDelayed({ refreshStatus() }, 500)
    }

    private fun stopTracking() {
        stopService(Intent(this, LocationService::class.java))
        toast("已停止")
        handler.postDelayed({ refreshStatus() }, 300)
    }

    /**
     * 申请电池优化豁免。这是让长连接在 Doze 模式下不被断网的关键，
     * 比“自启动权限”重要得多——自启动只负责被杀后拉起，管不了 Doze。
     */
    private fun requestIgnoreBattery() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("已经允许后台运行了")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            // 部分 ROM 不支持直达页，退到电池优化列表让用户自己找
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast("这台机器不支持自动跳转，去设置里手动关电池优化")
            }
        }
    }

    // ---------------------------------------------------------------- 权限与状态

    private fun requestPermissionsIfNeeded() {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMS)
        }
    }

    private fun refreshStatus() {
        val batteryOk = getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(packageName)

        val lines = buildString {
            append("服务：")
            append(if (LocationService.running) "运行中" else "未运行")
            append("\n状态：")
            append(LocationService.status)
            append("\n后台运行：")
            append(if (batteryOk) "已允许" else "未允许（点上面第三个按钮）")
        }
        tvStatus.text = lines
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_PERMS = 100
    }
}
