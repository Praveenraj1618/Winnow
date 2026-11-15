package app.winnow.gallery

import app.winnow.gallery.engine.*
import org.junit.Assert.*
import org.junit.Test

class EngineTest {
    private fun sig(seed: Int, jitter: Int = 0): ByteArray {
        val r = java.util.Random(seed.toLong()); val j = java.util.Random(1000L + jitter)
        return ByteArray(192) { (r.nextInt(200) - 100 + (if (jitter == 0) 0 else j.nextInt(9) - 4)).coerceIn(-127, 127).toByte() }
    }
    private fun face(cx: Float, smile: Float, eyes: Float, sharp: Float = 5f) = FaceInfo(cx, .4f, .03f, smile, eyes, .9f, sharp)
    private fun f(id: Long, t: Long, seed: Int, sharp: Float = 6f, faces: List<FaceInfo> = emptyList(), expo: Float = .8f,
                  bgSharp: Float = 5f, bgColor: Float = 40f, bgExpo: Float = .7f, subj: Boolean = false, height: Float = 0f,
                  shot: Boolean = false, fav: Boolean = false, bright: Float = .5f, white: Float = 0f, color: Float = 40f) =
        Features(id, t, 3000, 4000, 3_000_000, sharp, sharp, expo, 50f, color, bright, 10f, white, sig(seed, id.toInt()), 0,
            faces, subj, if (subj) .3f else 0f, height, false, bgSharp, bgExpo, bgColor, shot, fav)

    private val t0 = 1_700_000_000_000L
    /** A: technically best but someone blinks. B: softer, everyone smiling. C: best backdrop. D: worst at everything. */
    private fun burst() = listOf(
        f(1, t0, 7, sharp = 7.4f, faces = listOf(face(.3f, .5f, .1f, 6f), face(.7f, .6f, .9f, 6f)), subj = true, bgSharp = 4.6f, bgColor = 30f, bgExpo = .6f),
        f(2, t0 + 2000, 7, sharp = 6.6f, faces = listOf(face(.3f, .95f, .95f, 5.6f), face(.7f, .9f, .95f, 5.6f)), subj = true, bgSharp = 4.6f, bgColor = 30f, bgExpo = .6f),
        f(3, t0 + 4000, 7, sharp = 6.5f, faces = listOf(face(.3f, .3f, .6f, 5f), face(.7f, .3f, .6f, 5f)), subj = true, bgSharp = 6.4f, bgColor = 62f, bgExpo = .95f),
        f(4, t0 + 6000, 7, sharp = 5.2f, expo = .4f, faces = listOf(face(.3f, .1f, .2f, 4f), face(.7f, .1f, .3f, 4f)), subj = true, bgSharp = 4f, bgColor = 25f, bgExpo = .4f)
    )

    @Test fun groupsLookAlikesOnly() {
        val all = burst() + f(9, t0 + 9_000_000, 99) + f(10, t0 + 9_500_000, 123)
        val e = Engine(all, Tuning(), Taste(), t0)
        assertEquals(1, e.sets.size); assertEquals(4, e.sets[0].size)
    }
    @Test fun bothJudgesKeepTheirPick() {
        val e = Engine(burst(), Tuning(), Taste(), t0)
        val d = e.decide(e.sets[0])
        println("balanced keep=" + d.keep.mapValues { it.value.map { r -> r.text } })
        assertTrue("photographer's sharp frame kept", 1L in d.keep)
        assertTrue("poster's smiling frame kept", 2L in d.keep)
        assertFalse("worst frame goes", 4L in d.keep)
    }
    @Test fun backdropWinsAPlaceWhenLenient() {
        val e = Engine(burst(), Tuning(strictness = .1f, maxKeep = 4), Taste(), t0)
        val d = e.decide(e.sets[0])
        println("lenient keep=" + d.keep.mapValues { it.value.map { r -> r.text } })
        assertTrue(3L in d.keep); assertTrue(d.keep[3L]!!.any { it.best == Best.BACKDROP }); assertFalse(4L in d.keep)
    }
    @Test fun spaceSaverKeepsOne() {
        val e = Engine(burst(), Tuning(strictness = .9f, maxKeep = 1), Taste(), t0)
        assertEquals(1, e.decide(e.sets[0]).keep.size)
    }
    @Test fun balanceChoosesTheJudge() {
        val p = Engine(burst(), Tuning(balance = 0f, maxKeep = 1), Taste(), t0); val s = Engine(burst(), Tuning(balance = 1f, maxKeep = 1), Taste(), t0)
        assertEquals(setOf(1L), p.decide(p.sets[0]).keep.keys); assertEquals(setOf(2L), s.decide(s.sets[0]).keep.keys)
    }
    @Test fun switchedOffCategoriesStaySilent() {
        val e = Engine(burst(), Tuning(strictness = .1f, maxKeep = 4, enabled = setOf(Best.CRAFT)), Taste(), t0)
        val d = e.decide(e.sets[0]); assertEquals(setOf(1L), d.keep.keys)
    }
    @Test fun favouritesAreProtected() {
        val b = burst().toMutableList(); b[3] = f(4, t0 + 6000, 7, sharp = 5.2f, fav = true)
        val e = Engine(b, Tuning(), Taste(), t0); assertTrue(4L in e.decide(e.sets[0]).keep)
    }
    @Test fun learningMovesTowardYourChoice() {
        val taste = Taste(); val e = Engine(burst(), Tuning(maxKeep = 1, balance = 0f), taste, t0)
        val d = e.decide(e.sets[0]); assertEquals(setOf(1L), d.keep.keys)
        repeat(6) { Engine.learn(taste, d, setOf(2L)) }
        val e2 = Engine(burst(), Tuning(maxKeep = 1, balance = 0f), taste, t0)
        println("after learning shift=${taste.balanceShift} craft=${taste.craft.toList()} keep=${e2.decide(e2.sets[0]).keep.keys}")
        assertTrue(taste.balanceShift > 0f); assertEquals(6, taste.changed)
    }
    @Test fun clutterCardsAndPosterRescue() {
        val normal = (20..40).map { f(it.toLong(), t0 + it * 9_000_000L, it * 31) }
        val blurry = f(50, t0 + 500_000_000, 501, sharp = 3f)
        val blurrySmile = f(51, t0 + 600_000_000, 502, sharp = 3f, faces = listOf(face(.5f, .9f, .9f)))
        val shot = f(52, t0 + 700_000_000, 503, shot = true)
        val doc = f(53, t0 + 800_000_000, 504, white = .7f, color = 8f)
        val e = Engine(normal + blurry + blurrySmile + shot + doc, Tuning(), Taste(), t0)
        val cards = e.cards(); println(cards.map { it.kind.name + ":" + it.ids + " keep=" + it.keep })
        val b = cards.first { it.kind == CardKind.BLURRY }
        assertTrue(50L in b.ids && 50L !in b.keep); assertTrue(51L in b.keep)
        assertTrue(cards.any { it.kind == CardKind.SCREENS }); assertTrue(cards.any { it.kind == CardKind.DOCS })
    }
    @Test fun pixelsMeasureFocusAndSimilarity() {
        val w = 160; val h = 120; val r = java.util.Random(3)
        val sharp = IntArray(w * h) { val v = if (((it % w) / 4 + (it / w) / 4) % 2 == 0) 220 else 40; (0xFF shl 24) or (v shl 16) or (v shl 8) or (v / 2 + r.nextInt(20)) }
        val soft = IntArray(w * h) { i -> val x = i % w; val y = i / w; var s = 0; var c = 0
            for (dy in -2..2) for (dx in -2..2) { val xx = x + dx; val yy = y + dy; if (xx in 0 until w && yy in 0 until h) { s += (sharp[yy * w + xx] shr 16) and 255; c++ } }
            val v = s / c; (0xFF shl 24) or (v shl 16) or (v shl 8) or (v / 2) }
        val a = Pixels.analyse(sharp, w, h); val b = Pixels.analyse(soft, w, h)
        assertTrue(a.lap > b.lap + 1f); assertTrue(Pixels.similarity(a.sig, b.sig) > .9f)
        val other = IntArray(w * h) { (0xFF shl 24) or ((it % w) * 255 / w shl 16) or (40 shl 8) or 200 }
        assertTrue(Pixels.similarity(a.sig, Pixels.analyse(other, w, h).sig) < .6f)
        val mask = FloatArray(w * h) { val x = it % w; val y = it / w; if (x in 60..100 && y in 20..119) 1f else 0f }
        val bd = Pixels.backdrop(sharp, a.gray, w, h, mask, w, h)
        assertTrue(bd.hasSubject); assertTrue(bd.height > .8f); assertTrue(bd.cut)
    }
}
