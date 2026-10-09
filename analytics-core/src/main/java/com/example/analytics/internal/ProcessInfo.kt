package com.example.analytics.internal

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

internal class ProcessInfo(val name: String, val packageName: String) {
    val isMainProcess: Boolean get() = name == packageName

    companion object {
        fun current(context: Context): ProcessInfo =
            ProcessInfo(currentProcessName(context), context.packageName)

        private fun currentProcessName(context: Context): String {
            if (Build.VERSION.SDK_INT >= 28) return Application.getProcessName()
            // API 23-27: /proc is the cheapest reliable source; ActivityManager is the fallback.
            try {
                val name = File("/proc/self/cmdline").readText().substringBefore('\u0000').trim()
                if (name.isNotEmpty()) return name
            } catch (_: Exception) {
            }
            try {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                manager.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.let { return it.processName }
            } catch (_: Exception) {
            }
            return context.packageName
        }
    }
}
