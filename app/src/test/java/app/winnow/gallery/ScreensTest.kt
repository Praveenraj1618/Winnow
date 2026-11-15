package app.winnow.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import app.winnow.gallery.data.MediaItem
import app.winnow.gallery.data.Thumbs
import app.winnow.gallery.data.TrashEntry
import app.winnow.gallery.engine.FaceInfo
import app.winnow.gallery.engine.Features
import app.winnow.gallery.engine.Pixels
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders every screen with made-up photos, so the interface can be seen without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class ScreensTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val palette = listOf(0xFFE0603E, 0xFF2F7FA6, 0xFF3F8A45, 0xFF8C3FC4, 0xFFF2B01E, 0xFF1B2452, 0xFFD0456F, 0xFF1F8F96).map { it.toInt() }
    private fun scene(id: Long) = (id / 10).toInt()

    private fun picture(item: MediaItem, px: Int): Bitmap {
        val w = px; val h = if (item.h > 0) px * item.h / item.w else px
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val s = scene(item.id); val a = palette[s % palette.size]; val z = palette[(s + 3) % palette.size]
        p.shader = LinearGradient(0f, 0f, w * .3f, h.toFloat(), a, z, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
        p.color = 0x33FFFFFF; for (k in 0 until 6) c.drawCircle(w * (.15f + .16f * k), h * (.25f + .08f * ((k + s) % 4)), w * .09f, p)
        p.color = 0xFF1A1420.toInt(); c.drawRect(0f, h * .78f, w.toFloat(), h.toFloat(), p)
        if (s % 2 == 0) { // a person
            val dx = (item.id % 10) * w * .012f
            p.color = 0xFFEFC7A6.toInt(); c.drawCircle(w * .5f + dx, h * .42f, w * .13f, p)
            p.color = palette[(s + 5) % palette.size]; c.drawRoundRect(w * .3f + dx, h * .56f, w * .7f + dx, h * 1.1f, w * .12f, w * .12f, p)
        }
        return b
    }

    private fun sig(seed: Int, jitter: Int): ByteArray { val r = java.util.Random(seed.toLong()); val j = java.util.Random(1000L + jitter); return ByteArray(192) { (r.nextInt(200) - 100 + j.nextInt(9) - 4).coerceIn(-127, 127).toByte() } }
    private fun face(cx: Float, smile: Float, eyes: Float, sharp: Float) = FaceInfo(cx, .4f, .05f, smile, eyes, .9f, sharp)

    private fun settle(ms: Long = 1600) { repeat(6) { Thread.sleep(120); rule.mainClock.advanceTimeBy(ms / 6); rule.waitForIdle() } }
    private fun shot(name: String) { settle(); rule.onRoot().captureRoboImage("build/shots/$name.png") }

    @Test fun renderEveryScreen() {
        rule.mainClock.autoAdvance = false
        Thumbs.source = { item, px -> picture(item, px) }
        shot("0-welcome")
        val vm = rule.activity.vm
        val now = System.currentTimeMillis(); val media = ArrayList<MediaItem>(); val feats = HashMap<Long, Features>()
        // eight moments; even ones are bursts of people, odd ones single scenes
        for (s in 0 until 8) {
            val n = if (s % 2 == 0) 4 else 2
            for (k in 0 until n) {
                val id = s * 10L + k; val t = now - s * 26L * 3_600_000L - (n - k) * 3000L
                val item = MediaItem(id, Uri.parse("content://media/external/images/media/$id"), "IMG_2026${1100 + s}_18${10 + k}05.jpg", t, 2_400_000L + k * 310_000L, 3000, 4000, false, 0, "Camera", false)
                media.add(item)
                val faces = if (s % 2 == 0) listOf(
                    listOf(face(.5f, .5f, .15f, 6f)), listOf(face(.5f, .95f, .95f, 5.6f)), listOf(face(.5f, .3f, .7f, 5f)), listOf(face(.5f, .1f, .2f, 4f)))[k] else emptyList()
                val sharp = listOf(7.4f, 6.6f, 6.5f, 5.2f)[k]
                feats[id] = Features(id, t, 3000, 4000, item.bytes, sharp, sharp, if (k == 3) .4f else .8f, 50f, 40f, .5f, 10f, 0f,
                    if (s % 2 == 0) sig(s + 1, k) else sig(s * 100 + k * 37, 0), Pixels.vivid((palette[s % 8] shr 16) and 255, (palette[s % 8] shr 8) and 255, palette[s % 8] and 255),
                    faces, s % 2 == 0, .3f, .7f, false, if (k == 2) 6.4f else 4.6f, if (k == 2) .95f else .6f, if (k == 2) 62f else 30f, false, false)
            }
        }
        for (k in 0 until 3) { val id = 900L + k; media.add(MediaItem(id, Uri.parse("content://x/$id"), "Screenshot_2026110${k}.png", now - 50L * 3_600_000L - k * 60_000L, 600_000L, 1080, 2400, false, 0, "Screenshots", false))
            feats[id] = Features(id, media.last().time, 1080, 2400, 600_000L, 6f, 6f, .7f, 40f, 20f, .8f, 0f, .3f, sig(9000 + k * 91, 0), 0xFF3D4A7A.toInt(), emptyList(), false, 0f, 0f, false, 5f, .7f, 20f, true, false) }
        media.add(MediaItem(990L, Uri.parse("content://x/990"), "VID_20261101.mp4", now - 70L * 3_600_000L, 184_000_000L, 1920, 1080, true, 94_000L, "Camera", false))
        media.sortByDescending { it.time }
        rule.runOnUiThread {
            vm.hasAccess = true; vm.media = media; vm.byId = media.associateBy { it.id }; vm.feats = feats
            vm.scanTotal = media.count { !it.video }; vm.scanDone = vm.scanTotal
            vm.plan = WinnowViewModel.buildPlan(media, feats, vm.tuning, vm.taste, emptySet())
        }
        println("PLAN cards=" + vm.plan!!.cards.map { it.kind.name + ":" + it.ids.size + " keep=" + it.keep.size })
        shot("1-library")
        rule.runOnUiThread { vm.viewer = media to 1 }
        shot("2-viewer")
        rule.runOnUiThread { vm.viewer = null; vm.viewerTint = null }
        settle(600)
        rule.onNodeWithContentDescription("Declutter").performClick()
        shot("3-declutter")
        rule.runOnUiThread { vm.startSession() }
        shot("4-card")
        rule.onAllNodesWithText("Trash")[0].performClick()
        shot("5-card-flipped")
        val n = vm.session!!.cards.size
        repeat(n) { rule.runOnUiThread { vm.decide(true) }; settle(300) }
        shot("6-review")
        rule.runOnUiThread { vm.leaveSession() }
        settle(600)
        rule.onNodeWithContentDescription("Tune the declutter").performClick()
        shot("7-tune")
        rule.onNodeWithContentDescription("Close").performClick()
        settle(600)
        rule.onNodeWithContentDescription("Taste").performClick()
        shot("8-taste")
        rule.runOnUiThread { vm.trash = listOf(TrashEntry(1, "content://x/1", "IMG_20261104_181305.jpg", 2_700_000L, now - 3 * 86_400_000L, "", false), TrashEntry(2, "content://x/2", "Screenshot_20261101.png", 600_000L, now - 12 * 86_400_000L, "", false)) }
        rule.onNodeWithContentDescription("Trash").performClick()
        shot("9-trash")
    }
}
