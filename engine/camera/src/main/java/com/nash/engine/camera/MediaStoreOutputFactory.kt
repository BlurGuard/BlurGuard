package com.nash.engine.camera

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.video.MediaStoreOutputOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds MediaStore output options and safe filenames for anonymized
 * recordings. Pure output-destination policy: no recording state, no
 * camera knowledge.
 */
@Singleton
class MediaStoreOutputFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    fun create(fileNamePrefix: String): MediaStoreOutputOptions {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, generateFilename(fileNamePrefix))
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$OUTPUT_DIRECTORY"
                )
            }
        }
        return MediaStoreOutputOptions.Builder(
            context.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(contentValues).build()
    }

    internal companion object {
        const val MIME_TYPE = "video/mp4"
        const val OUTPUT_DIRECTORY = "BlurGuard"
        const val FILENAME_TIMESTAMP = "yyyy-MM-dd_HH-mm"

        internal fun generateFilename(prefix: String, now: Date = Date()): String {
            val timestamp =
                SimpleDateFormat(FILENAME_TIMESTAMP, Locale.getDefault()).format(now)
            return "${prefix}_$timestamp.mp4"
        }
    }
}