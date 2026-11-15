package app.winnow.gallery.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import kotlin.math.max

/** One photo or video as the phone's own media library describes it. */
class MediaItem(
    val id: Long, val uri: Uri, val name: String, val time: Long, val bytes: Long,
    val w: Int, val h: Int, val video: Boolean, val durationMs: Long, val bucket: String, val favorite: Boolean
) {
    val screenshot: Boolean get() = !video && (name.contains("screenshot", true) || bucket.contains("screenshot", true))
}

object MediaRepo {
    fun query(ctx: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        for (video in listOf(false, true)) {
            val base = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val cols = mutableListOf(
                MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.IS_FAVORITE, MediaStore.MediaColumns.ORIENTATION
            )
            if (video) cols.add(MediaStore.MediaColumns.DURATION)
            try {
                ctx.contentResolver.query(base, cols.toTypedArray(), null, null, null)?.use { c ->
                    val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val iName = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val iTaken = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                    val iMod = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    val iAdd = c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                    val iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val iW = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                    val iH = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                    val iBucket = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    val iFav = c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
                    val iRot = c.getColumnIndex(MediaStore.MediaColumns.ORIENTATION)
                    val iDur = if (video) c.getColumnIndex(MediaStore.MediaColumns.DURATION) else -1
                    while (c.moveToNext()) {
                        val id = c.getLong(iId)
                        var t = if (iTaken >= 0) c.getLong(iTaken) else 0L
                        if (t <= 0L && iMod >= 0) t = c.getLong(iMod) * 1000L
                        if (t <= 0L && iAdd >= 0) t = c.getLong(iAdd) * 1000L
                        var w = if (iW >= 0) c.getInt(iW) else 0
                        var h = if (iH >= 0) c.getInt(iH) else 0
                        val rot = if (iRot >= 0) c.getInt(iRot) else 0
                        if (rot == 90 || rot == 270) { val x = w; w = h; h = x }
                        out.add(MediaItem(
                            id, ContentUris.withAppendedId(base, id),
                            (if (iName >= 0) c.getString(iName) else null) ?: "item $id", t,
                            if (iSize >= 0) c.getLong(iSize) else 0L, w, h, video,
                            if (iDur >= 0) c.getLong(iDur) else 0L,
                            (if (iBucket >= 0) c.getString(iBucket) else null) ?: "",
                            iFav >= 0 && c.getInt(iFav) == 1
                        ))
                    }
                }
            } catch (e: Exception) { /* no access yet, or a provider hiccup: show what we have */ }
        }
        return out.sortedByDescending { it.time }
    }
}

/** Small pictures for the grid, kept in memory. Everything comes from the phone's own thumbnail store. */
object Thumbs {
    /** Test hook: supplies pictures without a media library. */
    @Volatile var source: ((MediaItem, Int) -> Bitmap?)? = null

    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).coerceAtMost(96L shl 20).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun peek(item: MediaItem, px: Int): Bitmap? = cache.get("${item.id}:$px")

    fun load(ctx: Context, item: MediaItem, px: Int): Bitmap? {
        val key = "${item.id}:$px"
        cache.get(key)?.let { return it }
        val b = source?.invoke(item, px) ?: try { ctx.contentResolver.loadThumbnail(item.uri, Size(px, px), null) } catch (e: Exception) { null }
        if (b != null) cache.put(key, b)
        return b
    }

    /** A software bitmap whose longest side is close to [maxPx], upright. */
    fun decode(ctx: Context, uri: Uri, maxPx: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.setTargetSampleSize(max(1, max(info.size.width, info.size.height) / maxPx))
        }
    } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    fun file(path: String): Bitmap? = try { BitmapFactory.decodeFile(path) } catch (e: Exception) { null }
}
