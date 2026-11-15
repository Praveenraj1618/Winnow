package app.winnow.gallery.engine

import android.content.Context
import android.graphics.Bitmap
import app.winnow.gallery.data.MediaItem
import app.winnow.gallery.data.Thumbs
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reads one photo and measures it. Both vision models ship inside the app and run on the phone;
 * the app has no network permission, so nothing can leave the device.
 */
class Analyzer(private val ctx: Context) {
    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setMinFaceSize(.05f).build()
        )
    }
    private val segmenter by lazy {
        Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
    }

    fun analyse(item: MediaItem): Features? {
        val big = Thumbs.decode(ctx, item.uri, 1280) ?: return null
        try {
            val bw = big.width; val bh = big.height
            if (bw < 16 || bh < 16) return null
            val bpx = IntArray(bw * bh); big.getPixels(bpx, 0, bw, 0, 0, bw, bh)
            val bgray = Pixels.grayOf(bpx)

            // Focus: the sharpest quarter of a 4x4 grid, so an off-centre subject still counts.
            val tiles = ArrayList<Float>(16)
            for (ty in 0 until 4) for (tx in 0 until 4)
                tiles.add(Pixels.lapVar(bgray, bw, bh, tx * bw / 4, ty * bh / 4, (tx + 1) * bw / 4, (ty + 1) * bh / 4))
            tiles.sortDescending()
            val sharp = tiles.take(4).average().toFloat()

            val sc = min(1f, 512f / max(bw, bh))
            val small = if (sc < 1f) Bitmap.createScaledBitmap(big, max(16, (bw * sc).toInt()), max(16, (bh * sc).toInt()), true) else big
            val w = small.width; val h = small.height
            val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
            val st = Pixels.analyse(px, w, h)

            val faces = ArrayList<FaceInfo>()
            try {
                for (f in Tasks.await(faceDetector.process(InputImage.fromBitmap(big, 0)))) {
                    val b = f.boundingBox
                    val l = b.left.coerceIn(0, bw - 1); val t = b.top.coerceIn(0, bh - 1); val r = b.right.coerceIn(l + 1, bw); val bt = b.bottom.coerceIn(t + 1, bh)
                    val le = f.leftEyeOpenProbability ?: .6f; val re = f.rightEyeOpenProbability ?: .6f
                    faces.add(FaceInfo(
                        cx = (l + r) / 2f / bw, cy = (t + bt) / 2f / bh,
                        size = (r - l).toFloat() * (bt - t) / (bw.toFloat() * bh),
                        smile = f.smilingProbability ?: .4f, eyes = min(le, re),
                        frontal = 1f - min(1f, (abs(f.headEulerAngleY) + abs(f.headEulerAngleZ) * .5f) / 45f),
                        sharp = Pixels.lapVar(bgray, bw, bh, l, t, r, bt)
                    ))
                }
            } catch (e: Exception) { /* a photo the detector cannot read simply has no faces */ }

            var mask: FloatArray? = null; var mw = 0; var mh = 0
            if (faces.isNotEmpty()) {
                try {
                    val m = Tasks.await(segmenter.process(InputImage.fromBitmap(small, 0)))
                    mw = m.width; mh = m.height
                    val buf = m.buffer.duplicate().order(ByteOrder.nativeOrder()); buf.rewind()
                    val fb = buf.asFloatBuffer()
                    if (fb.remaining() >= mw * mh) { mask = FloatArray(mw * mh); fb.get(mask) }
                } catch (e: Exception) { mask = null }
            }
            val bd = Pixels.backdrop(px, st.gray, w, h, mask, mw, mh)
            if (small !== big) small.recycle()
            return Features(
                id = item.id, time = item.time, w = if (item.w > 0) item.w else bw, h = if (item.h > 0) item.h else bh, bytes = item.bytes,
                sharp = sharp, center = st.center, expo = st.expo, contrast = st.contrast, color = st.color,
                bright = st.bright, warm = st.warm, white = st.white, sig = st.sig, tint = st.tint, faces = faces,
                hasSubject = bd.hasSubject, subjectFrac = bd.frac, subjectHeight = bd.height, subjectCut = bd.cut,
                bgSharp = bd.sharp, bgExpo = bd.expo, bgColor = bd.color,
                screenshot = item.screenshot, favorite = item.favorite
            )
        } catch (e: OutOfMemoryError) { return null } catch (e: Exception) { return null } finally { big.recycle() }
    }

    fun close() { try { faceDetector.close(); segmenter.close() } catch (e: Exception) { } }
}
