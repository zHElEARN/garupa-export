package com.zhelearn.garupaexport.export

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import androidx.core.content.FileProvider
import com.zhelearn.garupaexport.R
import com.zhelearn.garupaexport.profile.ExportedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class ProfileActions(private val context: Context) {
    fun copy(json: String) {
        val clip = ClipData.newPlainText(context.getString(R.string.profile_title), json)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    suspend fun save(json: String, uri: Uri) = withContext(Dispatchers.IO) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法打开所选文件")
        output.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
    }

    suspend fun share(profile: ExportedProfile) {
        val file = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "shared-profiles").apply { check(isDirectory || mkdirs()) }
            File(directory, profile.fileName).apply { writeText(profile.json, Charsets.UTF_8) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.profiles", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, context.getString(R.string.profile_title), uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_profile)))
    }
}
