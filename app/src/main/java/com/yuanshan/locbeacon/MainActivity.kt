package com.yuanshan.locbeacon

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 设置页，用 Miuix（HyperOS 设计语言）实现。
 * 界面只有三个东西：填地址和 Token、连接/断开、状态显示。
 */
class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        prefs = Prefs(this)
        requestPermissionsIfNeeded()

        setContent {
            MiuixTheme(
                controller = remember { ThemeController(ColorSchemeMode.System) },
                textStyles = MiuixTheme.textStyles,
            ) {
                var urlField by remember { mutableStateOf(TextFieldValue(prefs.url)) }
                var tokenField by remember { mutableStateOf(TextFieldValue(prefs.token)) }
                var statusText by remember { mutableStateOf("正在读取状态…") }
                var batteryFree by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    while (true) {
                        statusText = statusSnapshot()
                        batteryFree = isBatteryUnrestricted()
                        delay(1000)
                    }
                }

                SettingsScreen(
                    urlField = urlField,
                    onUrlChange = { urlField = it },
                    tokenField = tokenField,
                    onTokenChange = { tokenField = it },
                    statusText = statusText,
                    batteryFree = batteryFree,
                    onConnect = {
                        prefs.url = urlField.text
                        prefs.token = tokenField.text
                        startTracking()
                    },
                    onStop = { stopTracking() },
                    onBatteryClick = { requestIgnoreBattery() },
                )
            }
        }
    }

    // ---------------------------------------------------------------- 界面

    @Composable
    private fun SettingsScreen(
        urlField: TextFieldValue,
        onUrlChange: (TextFieldValue) -> Unit,
        tokenField: TextFieldValue,
        onTokenChange: (TextFieldValue) -> Unit,
        statusText: String,
        batteryFree: Boolean,
        onConnect: () -> Unit,
        onStop: () -> Unit,
        onBatteryClick: () -> Unit,
    ) {
        val scrollBehavior = MiuixScrollBehavior()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = "位置感知",
                    subtitle = "只在 Bot 要位置时才取点",
                    scrollBehavior = scrollBehavior,
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card {
                    TextField(
                        value = urlField,
                        onValueChange = onUrlChange,
                        label = "服务器地址",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    TextField(
                        value = tokenField,
                        onValueChange = onTokenChange,
                        label = "Token",
                        useLabelAsPlaceholder = true,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = onConnect,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("保存并连接")
                    }
                    Button(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("断开")
                    }
                }

                Card {
                    SwitchPreference(
                        title = "允许后台运行",
                        summary = "关闭电池优化，长连接才不会被系统掐断",
                        checked = batteryFree,
                        onCheckedChange = { onBatteryClick() },
                    )
                }

                SmallTitle("状态")

                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = statusText,
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 服务控制

    private fun startTracking() {
        if (prefs.url.isBlank() || prefs.token.isBlank()) {
            toast("先把服务器地址和 Token 填上")
            return
        }

        // Android 14 起，location 类型前台服务必须在已有定位权限时才能启动
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
            ContextCompat.startForegroundService(
                this, Intent(this, LocationService::class.java)
            )
            toast("已启动，通知栏有常驻提示")
        } catch (e: Exception) {
            toast("启动失败：${e.message}")
        }
    }

    private fun stopTracking() {
        stopService(Intent(this, LocationService::class.java))
        toast("已停止")
    }

    /**
     * 电池优化豁免是长连接能活下来的关键，比“自启动”重要得多——
     * 自启动只负责被杀后拉起，管不了 Doze 断网。
     */
    private fun requestIgnoreBattery() {
        if (isBatteryUnrestricted()) {
            toast("已经允许后台运行了")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                toast("这台机器不支持自动跳转，去设置里手动关电池优化")
            }
        }
    }

    // ---------------------------------------------------------------- 状态与权限

    private fun isBatteryUnrestricted(): Boolean =
        getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(packageName) == true

    private fun statusSnapshot(): String = buildString {
        append(if (LocationService.running) "服务运行中" else "服务未运行")
        append("\n")
        append(LocationService.status)
    }

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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_PERMS = 100
    }
}
