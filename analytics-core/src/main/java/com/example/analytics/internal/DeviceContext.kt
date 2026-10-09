package com.example.analytics.internal

import android.content.Context
import android.content.res.Resources
import android.os.Build
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/** Static device/app facts, computed once on the writer thread. */
internal class DeviceContext private constructor(
    val appVersion: String,
    private val appBuild: Long,
    val osVersion: String,
    private val process: String,
    private val screenWidthDp: Int,
    private val screenHeightDp: Int,
) {
    /** Locale and timezone are re-read per event: users change them while the app is alive. */
    fun toJson(networkType: String): JSONObject = JSONObject()
        .put("app_build", appBuild)
        .put("os", "android")
        .put("api_level", Build.VERSION.SDK_INT)
        .put("manufacturer", Build.MANUFACTURER)
        .put("model", Build.MODEL)
        .put("screen_width_dp", screenWidthDp)
        .put("screen_height_dp", screenHeightDp)
        .put("locale", Locale.getDefault().toLanguageTag())
        .put("timezone", TimeZone.getDefault().id)
        .put("network", networkType)
        .put("process", process)

    companion object {
        fun create(context: Context, processName: String): DeviceContext {
            var versionName = "unknown"
            var versionCode = 0L
            try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                versionName = info.versionName ?: "unknown"
                versionCode = if (Build.VERSION.SDK_INT >= 28) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            } catch (_: Exception) {
            }
            val metrics = Resources.getSystem().displayMetrics
            val density = if (metrics.density > 0f) metrics.density else 1f
            return DeviceContext(
                appVersion = versionName,
                appBuild = versionCode,
                osVersion = Build.VERSION.RELEASE ?: "unknown",
                process = processName,
                screenWidthDp = (metrics.widthPixels / density).toInt(),
                screenHeightDp = (metrics.heightPixels / density).toInt(),
            )
        }
    }
}
