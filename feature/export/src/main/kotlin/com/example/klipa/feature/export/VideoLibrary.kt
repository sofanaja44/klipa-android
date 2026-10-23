package com.example.klipa.feature.export

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/** Where finished exports go. */
interface VideoLibrary {
    /** Copies [file] into the phone's videos as [name]; null if that is not possible. */
    fun save(file: File, name: String): Uri?

    /** A shareable link to [file] in app storage, for when it could not be saved. */
    fun shareable(file: File): Uri
}

/** The folder exports appear in, under the phone's Movies. */
const val EXPORT_FOLDER = "Klipa"

/**
 * Saves into Movies/Klipa through MediaStore. Android 10 and later need no permission for
 * that; older versions need storage permission, without which exports stay in the app.
 */
class MediaStoreLibrary(private val context: Context) : VideoLibrary {
    override fun save(file: File, name: String): Uri? = try {
        if (Build.VERSION.SDK_INT >= 29) saveScoped(file, name) else saveLegacy(file, name)
    } catch (e: Exception) {
        // Full storage, a removed card or a provider error: the export is kept in the app.
        Log.w(TAG, "Could not save the export to the gallery", e)
        null
    }

    override fun shareable(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.exports", file)

    @RequiresApi(29)
    private fun saveScoped(file: File, name: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, MP4)
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$EXPORT_FOLDER")
            // Hidden from other apps until the copy is complete.
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        try {
            val stream = resolver.openOutputStream(uri) ?: error("No output stream for $uri")
            stream.use { output -> file.inputStream().use { it.copyTo(output) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION") // The file path column is how media were added before Android 10.
    private fun saveLegacy(file: File, name: String): Uri? {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), EXPORT_FOLDER)
        if (!folder.isDirectory && !folder.mkdirs()) return null
        val target = File(folder, name)
        file.copyTo(target, overwrite = true)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DATA, target.absolutePath)
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, MP4)
        }
        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
    }

    private companion object {
        const val TAG = "KlipaExport"
        const val MP4 = "video/mp4"
    }
}
