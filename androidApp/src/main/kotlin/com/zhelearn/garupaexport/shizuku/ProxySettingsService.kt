package com.zhelearn.garupaexport.shizuku

import androidx.annotation.Keep
import com.zhelearn.garupaexport.capture.proxy.ProxyServer
import kotlin.system.exitProcess

@Keep
class ProxySettingsService : IProxySettingsService.Stub() {
    override fun enableProxy() {
        val host = "127.0.0.1"
        val port = ProxyServer.PORT.toString()
        settings("put", "global", "http_proxy", "$host:$port")
        settings("put", "global", "global_http_proxy_host", host)
        settings("put", "global", "global_http_proxy_port", port)
        settings("put", "global", "global_http_proxy_exclusion_list", "")
        settings("put", "global", "global_proxy_pac_url", "")
        check(settings("get", "global", "http_proxy") == "$host:$port") { "系统代理设置未生效" }
    }

    override fun clearProxy() {
        settings("put", "global", "http_proxy", ":0")
        settings("delete", "global", "global_http_proxy_host")
        settings("put", "global", "global_http_proxy_port", "0")
        settings("delete", "global", "global_http_proxy_exclusion_list")
        settings("delete", "global", "global_proxy_pac_url")
        check(settings("get", "global", "http_proxy") == ":0") { "系统代理未清除" }
    }

    override fun destroy() = exitProcess(0)

    private fun settings(vararg args: String): String {
        val process = ProcessBuilder("/system/bin/settings", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0) { "系统代理操作失败：${output.take(200)}" }
        return output
    }
}
