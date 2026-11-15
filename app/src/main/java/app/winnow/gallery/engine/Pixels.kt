package app.winnow.gallery.engine

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One face as the on-device detector reports it, reduced to what the judges need. All values 0..1. */
class FaceInfo(
    val cx: Float, val cy: Float,
    /** Face box area as a fraction of the frame. */
    val size: Float,
    val smile: Float,
    /** The less-open of the two eyes. */
    val eyes: Float,
    /** 1 = looking at the camera, 0 = turned well away. */
    val frontal: Float,
    /** Log focus measure inside the face box. */
    val sharp: Float
)

/** Everything Winnow remembers about one photo. Computed once on the phone, then cached. */
class Features(
    val id: Long, val time: Long, val w: Int, val h: Int, val bytes: Long,
    val sharp: Float, val center: Float, val expo: Float, val contrast: Float, val color: Float,
    val bright: Float, val warm: Float, val white: Float,
    val sig: ByteArray, val tint: Int,
    val faces: List<FaceInfo>,
    val hasSubject: Boolean, val subjectFrac: Float, val subjectHeight: Float, val subjectCut: Boolean,
    val bgSharp: Float, val bgExpo: Float, val bgColor: Float,
    val screenshot: Boolean, val favorite: Boolean
) {
    val isDoc: Boolean get() = !screenshot && white > .5f && color < 22f
    val isDark: Boolean get() = bright < .13f
}

class PixelStats(
    val center: Float, val lap: Float, val expo: Float, val contrast: Float, val color: Float,
    val bright: Float, val warm: Float, val white: Float, val sig: ByteArray, val tint: Int,
    val gray: FloatArray, val w: Int, val h: Int
)

class Backdrop(
    val hasSubject: Boolean, val frac: Float, val height: Float, val cut: Boolean,
    val sharp: Float, val expo: Float, val color: Float
)

/** Plain-arithmetic image measures. No Android classes, so it runs and tests anywhere. */
object Pixels {
    const val SIG = 8

    fun analyse(px: IntArray, w: Int, h: Int): PixelStats {
        val n = w * h
        val g = FloatArray(n)
        var sum = 0.0; var sq = 0.0; var lo = 0; var hi = 0
        var srg = 0.0; var syb = 0.0; var qrg = 0.0; var qyb = 0.0; var warm = 0.0; var white = 0; var cn = 0
        var cr = 0.0; var cg = 0.0; var cb = 0.0; var cw = 0.0
        for (i in 0 until n) {
            val p = px[i]
            val r = (p shr 16) and 255; val gg = (p shr 8) and 255; val b = p and 255
            val l = .299f * r + .587f * gg + .114f * b
            g[i] = l; sum += l; sq += l * l
            if (l < 10f) lo++ else if (l > 245f) hi++
            if (i and 3 == 0) {
                val rg = (r - gg).toDouble(); val yb = .5 * (r + gg) - b
                srg += rg; syb += yb; qrg += rg * rg; qyb += yb * yb; warm += r - b; cn++
                val mx = max(r, max(gg, b)); val mn = min(r, min(gg, b))
                val s = if (mx == 0) 0f else (mx - mn).toFloat() / mx
                if (l > 205f && s < .12f) white++
                val wt = s * s * mx / 255.0 + .004
                cr += r * wt; cg += gg * wt; cb += b * wt; cw += wt
            }
        }
        val mean = sum / n
        val varL = sq / n - mean * mean
        val lap = lapVar(g, w, h, 1, 1, w - 1, h - 1)
        val cen = lapVar(g, w, h, w / 4, h / 4, w - w / 4, h - h / 4)
        val mrg = srg / cn; val myb = syb / cn
        val colour = sqrt(max(0.0, qrg / cn - mrg * mrg) + max(0.0, qyb / cn - myb * myb)) + .3 * sqrt(mrg * mrg + myb * myb)
        val m = (mean / 255).toFloat(); val clip = (lo + hi).toFloat() / n
        return PixelStats(
            center = cen, lap = lap,
            expo = (1f - abs(m - .48f) * 1.8f - clip * 1.5f).coerceIn(0f, 1f),
            contrast = sqrt(max(0.0, varL)).toFloat(), color = colour.toFloat(), bright = m,
            warm = (warm / cn).toFloat(), white = white.toFloat() / cn,
            sig = signature(px, w, h), tint = vivid((cr / cw).toInt(), (cg / cw).toInt(), (cb / cw).toInt()),
            gray = g, w = w, h = h
        )
    }

    /** Log of the Laplacian variance over a rectangle: higher means more fine detail in focus. */
    fun lapVar(g: FloatArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        val ax = max(1, x0); val ay = max(1, y0); val bx = min(w - 1, x1); val by = min(h - 1, y1)
        var s = 0.0; var q = 0.0; var c = 0
        for (y in ay until by) {
            var i = y * w + ax
            for (x in ax until bx) {
                val v = 4f * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]
                s += v; q += v * v; c++; i++
            }
        }
        if (c == 0) return 0f
        val mean = s / c
        return ln(1.0 + max(0.0, q / c - mean * mean)).toFloat()
    }

    fun grayOf(px: IntArray): FloatArray = FloatArray(px.size) { i ->
        val p = px[i]; .299f * ((p shr 16) and 255) + .587f * ((p shr 8) and 255) + .114f * (p and 255)
    }

    /** 8x8 grid of mean colour, zero-mean, scaled into bytes. Compared with [similarity]. */
    fun signature(px: IntArray, w: Int, h: Int): ByteArray {
        val v = FloatArray(SIG * SIG * 3)
        for (gy in 0 until SIG) for (gx in 0 until SIG) {
            val ax = gx * w / SIG; val bx = (gx + 1) * w / SIG; val ay = gy * h / SIG; val by = (gy + 1) * h / SIG
            var r = 0L; var gr = 0L; var b = 0L; var c = 0
            var y = ay
            while (y < by) {
                var x = ax
                while (x < bx) { val p = px[y * w + x]; r += (p shr 16) and 255; gr += (p shr 8) and 255; b += p and 255; c++; x += 2 }
                y += 2
            }
            val o = (gy * SIG + gx) * 3; val d = max(1, c)
            v[o] = r.toFloat() / d; v[o + 1] = gr.toFloat() / d; v[o + 2] = b.toFloat() / d
        }
        val mean = v.average().toFloat()
        var mx = 1e-3f
        for (i in v.indices) { v[i] -= mean; mx = max(mx, abs(v[i])) }
        return ByteArray(v.size) { (v[it] / mx * 127f).toInt().toByte() }
    }

    /** 1 = same layout and colours, 0 = unrelated. Unaffected by overall brightness or haze. */
    fun similarity(a: ByteArray, b: ByteArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var d = 0L; var na = 0L; var nb = 0L
        for (i in a.indices) { val x = a[i].toInt(); val y = b[i].toInt(); d += x * y; na += x * x; nb += y * y }
        if (na == 0L || nb == 0L) return 0f
        return (d / sqrt(na.toDouble() * nb.toDouble())).toFloat()
    }

    /**
     * Splits the frame into subject and backdrop using a person mask (confidence 0..1, any resolution)
     * and measures the backdrop on its own.
     */
    fun backdrop(px: IntArray, gray: FloatArray, w: Int, h: Int, mask: FloatArray?, mw: Int, mh: Int): Backdrop {
        if (mask == null || mw <= 0 || mh <= 0) return wholeFrame(px, gray, w, h)
        var subj = 0; var top = h; var bottom = -1
        var s = 0.0; var q = 0.0; var c = 0
        var lum = 0.0; var lo = 0; var hi = 0
        var srg = 0.0; var syb = 0.0; var qrg = 0.0; var qyb = 0.0; var bn = 0
        val rowMin = max(2, w / 50)
        for (y in 1 until h - 1) {
            val my = y * mh / h
            var rowSubj = 0
            for (x in 1 until w - 1) {
                val m = mask[my * mw + x * mw / w]
                val i = y * w + x
                if (m > .6f) { subj++; rowSubj++ }
                else if (m < .3f) {
                    val v = 4f * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w]
                    s += v; q += v * v; c++
                    val l = gray[i]; lum += l
                    if (l < 10f) lo++ else if (l > 245f) hi++
                    if (i and 3 == 0) {
                        val p = px[i]; val r = (p shr 16) and 255; val gg = (p shr 8) and 255; val b = p and 255
                        val rg = (r - gg).toDouble(); val yb = .5 * (r + gg) - b
                        srg += rg; syb += yb; qrg += rg * rg; qyb += yb * yb; bn++
                    }
                }
            }
            if (rowSubj >= rowMin) { if (y < top) top = y; bottom = y }
        }
        val total = (w - 2) * (h - 2)
        val frac = subj.toFloat() / total
        if (frac < .03f || c < total / 20 || bn < 16) return wholeFrame(px, gray, w, h)
        val mean = s / c
        val m = (lum / c / 255).toFloat(); val clip = (lo + hi).toFloat() / c
        val mrg = srg / bn; val myb = syb / bn
        return Backdrop(
            hasSubject = true, frac = frac,
            height = if (bottom >= top) (bottom - top + 1).toFloat() / h else 0f,
            cut = bottom >= (h * .97f).toInt(),
            sharp = ln(1.0 + max(0.0, q / c - mean * mean)).toFloat(),
            expo = (1f - abs(m - .48f) * 1.8f - clip * 1.5f).coerceIn(0f, 1f),
            color = (sqrt(max(0.0, qrg / bn - mrg * mrg) + max(0.0, qyb / bn - myb * myb)) + .3 * sqrt(mrg * mrg + myb * myb)).toFloat()
        )
    }

    private fun wholeFrame(px: IntArray, gray: FloatArray, w: Int, h: Int): Backdrop {
        val st = analyse(px, w, h)
        return Backdrop(false, 0f, 0f, false, st.lap, st.expo, st.color)
    }

    /** A saturated, mid-light version of a colour, used to tint the app around the photo. */
    fun vivid(r0: Int, g0: Int, b0: Int): Int {
        val r = r0 / 255f; val g = g0 / 255f; val b = b0 / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        if (d < .05f) return 0xFF3D4A7A.toInt()
        var hue = when (mx) { r -> ((g - b) / d) % 6f; g -> (b - r) / d + 2f; else -> (r - g) / d + 4f } * 60f
        if (hue < 0) hue += 360f
        val l = ((mx + mn) / 2f).coerceIn(.4f, .56f)
        val s = max(.6f, min(.9f, d / (1f - abs(mx + mn - 1f)).coerceAtLeast(.05f)))
        return hsl(hue, s, l)
    }

    fun hsl(h: Float, s: Float, l: Float): Int {
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = ((h % 360f) + 360f) % 360f / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r, g, b) = when (hp.toInt()) { 0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x); 3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x) }
        val m = l - c / 2f
        fun ch(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
