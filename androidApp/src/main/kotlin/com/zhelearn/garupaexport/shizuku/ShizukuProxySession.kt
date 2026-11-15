package com.zhelearn.garupaexport.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

internal class ShizukuProxySession(context: Context) : ServiceConnection {
    private val args = Shizuku.UserServiceArgs(ComponentName(context, ProxySettingsService::class.java))
        .tag("proxy_settings")
        .processNameSuffix("proxy_settings")
        .daemon(false)
        .version(2)
    private var remote: IProxySettingsService? = null
    private var waiting: CompletableDeferred<IProxySettingsService>? = null
    private var bound = false
    private var configured = false

    suspend fun enable() {
        val service = service()
        configured = true
        withContext(Dispatchers.IO) { service.enableProxy() }
    }

    suspend fun clear() {
        if (!configured) return
        val service = service()
        withContext(Dispatchers.IO) { service.clearProxy() }
        configured = false
    }

    fun close() {
        waiting?.cancel()
        waiting = null
        remote = null
        if (bound && Shizuku.pingBinder()) {
            try {
                Shizuku.unbindUserService(args, this, true)
            } catch (error: RuntimeException) {
                Log.w("ShizukuProxySession", "Unable to unbind proxy settings service", error)
            }
        }
        bound = false
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val pending = waiting
        if (pending == null || !pending.isActive) {
            close()
            return
        }
        val service = IProxySettingsService.Stub.asInterface(binder)
        remote = service
        pending.complete(service)
    }

    override fun onServiceDisconnected(name: ComponentName) {
        remote = null
        waiting?.completeExceptionally(IllegalStateException("Shizuku 服务已断开"))
    }

    private suspend fun service(): IProxySettingsService {
        check(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            "Shizuku 未运行或未授权"
        }
        remote?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        return withTimeout(10_000) {
            val pending = CompletableDeferred<IProxySettingsService>()
            waiting = pending
            try {
                bound = true
                Shizuku.bindUserService(args, this@ShizukuProxySession)
                pending.await()
            } finally {
                waiting = null
            }
        }
    }
}
