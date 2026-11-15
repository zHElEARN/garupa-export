package com.zhelearn.garupaexport.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

internal enum class ShizukuStatus { NotInstalled, NotRunning, Unauthorized, Authorized }

internal class ShizukuRepository(private val context: Context) {
    private val mutableStatus = MutableStateFlow(ShizukuStatus.NotRunning)
    val status = mutableStatus.asStateFlow()

    init {
        val main = Handler(Looper.getMainLooper())
        Shizuku.addBinderReceivedListenerSticky({ refresh() }, main)
        Shizuku.addBinderDeadListener({ refresh() }, main)
        Shizuku.addRequestPermissionResultListener({ _, _ -> refresh() }, main)
        refresh()
    }

    fun refresh() {
        mutableStatus.value = when {
            !isInstalled() -> ShizukuStatus.NotInstalled
            !Shizuku.pingBinder() || Shizuku.isPreV11() -> ShizukuStatus.NotRunning
            else -> try {
                if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                    ShizukuStatus.Authorized
                } else ShizukuStatus.Unauthorized
            } catch (error: RuntimeException) {
                Log.w("ShizukuRepository", "Shizuku connection unavailable", error)
                ShizukuStatus.NotRunning
            }
        }
    }

    fun requestPermission() {
        refresh()
        if (status.value == ShizukuStatus.Unauthorized) Shizuku.requestPermission(PERMISSION_REQUEST)
    }

    private fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        private const val PERMISSION_REQUEST = 1
    }
}
