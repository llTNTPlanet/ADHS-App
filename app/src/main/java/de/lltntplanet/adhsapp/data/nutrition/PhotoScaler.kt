package de.lltntplanet.adhsapp.data.nutrition

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Verkleinert ein Foto für die Erkennung (spart Datenvolumen und Zeit) und dreht es nach EXIF richtig. */
object PhotoScaler {
    private const val MAX_EDGE = 1280

    suspend fun toJpeg(resolver: ContentResolver, uri: Uri, maxEdge: Int = MAX_EDGE, quality: Int = 82): ByteArray =
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                ?: throw IllegalStateException("Foto konnte nicht gelesen werden")
            var sample = 1
            while (bounds.outWidth / sample > maxEdge * 2 || bounds.outHeight / sample > maxEdge * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: throw IllegalStateException("Foto konnte nicht gelesen werden")
            val rotation = resolver.openInputStream(uri)?.use { runCatching { ExifInterface(it).rotationDegrees }.getOrDefault(0) } ?: 0
            val scale = minOf(1f, maxEdge.toFloat() / maxOf(decoded.width, decoded.height))
            val matrix = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                if (rotation != 0) postRotate(rotation.toFloat())
            }
            val bitmap = if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (bitmap !== decoded) bitmap.recycle()
                decoded.recycle()
                out.toByteArray()
            }
        }
}
