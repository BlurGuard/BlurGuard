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
 * camera logic.
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
        const val DEFAULT_PREFIX = "BlurGuard"

        private val UNSAFE_CHARS = Regex("[^A-Za-z0-9_-]")

        /** Prefix may come from user settings; strip anything filesystem-unsafe. */
        internal fun sanitizePrefix(prefix: String): String =
            prefix.trim().replace(UNSAFE_CHARS, "_").ifBlank { DEFAULT_PREFIX }

        /** Locale.US keeps the numeric timestamp stable across device locales. */
        internal fun generateFilename(prefix: String, now: Date = Date()): String {
            val timestamp = SimpleDateFormat(FILENAME_TIMESTAMP, Locale.US).format(now)
            return "${sanitizePrefix(prefix)}_$timestamp.mp4"
        }
    }
}
