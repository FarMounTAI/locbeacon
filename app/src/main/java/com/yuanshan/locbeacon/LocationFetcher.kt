package com.yuanshan.locbeacon

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 取一次当前位置。
 *
 * 用 getCurrentLocation 而不是 requestLocationUpdates：
 * 后者会让 GPS 一直开着，正是我们想避免的事。
 * GPS 冷启动可能十几秒，所以这里加 15 秒兜底，拿不到就返回 null，不无限等。
 */
object LocationFetcher {

    private const val TAG = "LocBeacon"
    private const val TIMEOUT_MS = 15_000L

    @SuppressLint("MissingPermission")
    fun fetch(context: Context, callback: (Location?) -> Unit) {
        val app = context.applicationContext

        val fine = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            app, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fine && !coarse) {
            Log.w(TAG, "没有定位权限")
            callback(null)
            return
        }

        val finished = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        val cts = CancellationTokenSource()

        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) {
                cts.cancel()
                Log.w(TAG, "定位超时")
                callback(null)
            }
        }

        val deliver = { loc: Location? ->
            if (finished.compareAndSet(false, true)) {
                main.removeCallbacks(timeout)
                callback(loc)
            }
        }

        try {
            LocationServices.getFusedLocationProviderClient(app)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cts.token)
                .addOnSuccessListener { loc -> deliver(loc) }
                .addOnFailureListener { e ->
                    Log.w(TAG, "定位失败: ${e.message}")
                    deliver(null)
                }
        } catch (e: Exception) {
            Log.w(TAG, "定位异常: ${e.message}")
            deliver(null)
            return
        }

        main.postDelayed(timeout, TIMEOUT_MS)
    }
}
