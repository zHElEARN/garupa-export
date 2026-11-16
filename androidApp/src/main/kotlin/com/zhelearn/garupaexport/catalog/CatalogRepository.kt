package com.zhelearn.garupaexport.catalog

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class CatalogState(
    val cache: CatalogCache? = null,
    val loading: Boolean = true,
    val error: String? = null,
    val upToDate: Boolean = false,
) {
    val available: Boolean get() = cache != null
}

internal class CatalogRepository(
    private val store: CatalogStore,
    private val client: CatalogClient,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(CatalogState())
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            val cached = withContext(Dispatchers.IO) {
                try {
                    store.load()
                } catch (error: Exception) {
                    Log.w("CardCatalog", "Ignoring invalid card cache", error)
                    null
                }
            }
            mutableState.value = CatalogState(cache = cached, loading = false)
        }
    }

    suspend fun ensureAvailable() {
        state.first { !it.loading }
        if (state.value.available) return
        refresh()
        val result = state.first { !it.loading }
        check(result.available) { result.error ?: "Bestdori 卡牌数据下载失败，请重试" }
    }

    fun refresh() {
        if (mutableState.value.loading) return
        val previous = mutableState.value.cache
        mutableState.value = CatalogState(cache = previous, loading = true)
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    val downloaded = client.download(previous)
                    if (downloaded !== previous) store.save(downloaded)
                    downloaded
                }
                mutableState.value = CatalogState(cache = updated, loading = false, upToDate = updated === previous)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w("CardCatalog", "Card data refresh failed", error)
                mutableState.value = CatalogState(cache = previous, loading = false,
                    error = "${error.message ?: "下载失败"}。请稍后重试" +
                        if (previous != null) "，仍可使用已缓存的数据" else "")
            }
        }
    }
}
