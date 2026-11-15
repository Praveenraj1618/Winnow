package app.winnow.gallery.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

enum class Judge(val label: String) { PHOTOGRAPHER("Photographer"), POSTER("Poster") }

/** Every way a frame can earn its place. Each can be switched off in tuning. */
enum class Best(val label: String, val judge: Judge, val weight: Float, val blurb: String) {
    CRAFT("Best technical shot", Judge.PHOTOGRAPHER, 1f, "The sharpest, cleanest frame of the set"),
    VIBE("Most postable", Judge.POSTER, 1f, "The one you would actually share, even if it is not perfect"),
    FACE("Best face", Judge.POSTER, .9f, "Each person at their best: eyes open, smiling, in focus"),
    GROUP("Best group shot", Judge.POSTER, .9f, "The frame where the most people look good at once"),
    BACKDROP("Best backdrop", Judge.PHOTOGRAPHER, .8f, "The clearest, richest scene behind the subject"),
    MOMENT("Best moment", Judge.POSTER, .7f, "Peak expression with nobody blinking"),
    BODY("Best full-length shot", Judge.POSTER, .6f, "The whole outfit in frame"),
    LIGHT("Best light", Judge.PHOTOGRAPHER, .5f, "The best exposure and colour")
}

/** What you ask of a declutter. Everything here is a control in the app. */
data class Tuning(
    /** 0 keeps more frames, 1 frees more space. */
    val strictness: Float = .5f,
    /** 0 lets the photographer decide alone, 1 the poster. */
    val balance: Float = .5f,
    val maxKeep: Int = 3,
    val enabled: Set<Best> = Best.entries.toSet(),
    val protectFavorites: Boolean = true,
    val protectRecentDays: Int = 0,
    val clutterBlurry: Boolean = true,
    val clutterDark: Boolean = true,
    val clutterScreens: Boolean = true,
    val clutterDocs: Boolean = true,
    val clutterVideos: Boolean = true,
    val windowMinutes: Int = 15,
    val similarity: Float = .9f
) {
    companion object {
        val PRESETS: List<Pair<String, Tuning>> = listOf(
            "Balanced" to Tuning(),
            "Photographer" to Tuning(strictness = .65f, balance = .15f, maxKeep = 2),
            "Social" to Tuning(strictness = .3f, balance = .85f, maxKeep = 4),
            "Space saver" to Tuning(strictness = .9f, balance = .5f, maxKeep = 1)
        )
    }
}

/** What Winnow has learned from your approvals. Starts neutral and shifts with every session. */
class Taste(
    val craft: FloatArray = CRAFT_DEFAULT.copyOf(),
    val post: FloatArray = POST_DEFAULT.copyOf(),
    val trust: FloatArray = FloatArray(Best.entries.size) { 1f },
    var balanceShift: Float = 0f,
    var shown: Int = 0, var agreed: Int = 0, var changed: Int = 0, var loved: Int = 0
) {
    companion object {
        val CRAFT_KEYS = listOf("Sharp focus", "Balanced exposure", "Crisp subject", "Contrast", "Vivid colour", "Resolution", "Backdrop")
        val POST_KEYS = listOf("Good faces", "Smiles", "People up close", "Vivid colour", "Bright look", "Warm tones", "Backdrop", "Sharp focus")
        val CRAFT_DEFAULT = floatArrayOf(1f, .6f, .5f, .3f, .3f, .2f, .3f)
        val POST_DEFAULT = floatArrayOf(1f, .7f, .4f, .4f, .3f, .2f, .5f, .2f)
    }
}

class Reason(val best: Best, val note: String = "") {
    val text: String get() = if (note.isEmpty()) best.label else best.label + ", " + note
}

/** The verdict on one set of look-alike frames. */
class SetDecision(
    val ids: List<Long>,
    /** Frames to keep, each with the reasons a judge gave. */
    val keep: Map<Long, List<Reason>>,
    /** For each category that applied, the winning frame, kept or not. */
    val winners: List<Pair<Best, Long>>,
    val craftVec: Map<Long, FloatArray>, val postVec: Map<Long, FloatArray>,
    val craftScore: Map<Long, Float>, val postScore: Map<Long, Float>,
    val protectedIds: Set<Long>
) {
    val trash: List<Long> get() = ids.filter { it !in keep }
}

enum class CardKind { SET, BLURRY, DARK, SCREENS, DOCS, VIDEOS }

class Card(
    val kind: CardKind, val title: String, val blurb: String, val ids: List<Long>,
    val keep: Set<Long>, val reasons: Map<Long, List<String>>, val decision: SetDecision? = null
)

private fun dot(w: FloatArray, v: FloatArray): Float {
    var s = 0f; var n = 0f
    for (i in w.indices) { s += w[i] * v[i]; n += abs(w[i]) }
    return if (n > 0f) s / n else 0f
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

class Engine(all: List<Features>, val tuning: Tuning, val taste: Taste, val now: Long, private val loved: Set<Long> = emptySet()) {
    val feats: List<Features> = all.sortedBy { it.time }
    private val byId = feats.associateBy { it.id }
    val balance: Float = (tuning.balance + taste.balanceShift).coerceIn(0f, 1f)

    /** Library-wide medians, used to judge a photo on its own rather than against a set. */
    private val photos = feats.filter { !it.screenshot && !it.isDoc }
    private val sharpSorted = photos.map { it.sharp }.sorted()
    val sharpMedian: Float = if (sharpSorted.isEmpty()) 0f else sharpSorted[sharpSorted.size / 2]

    val sets: List<List<Features>> by lazy { group() }
    private val inSet: Set<Long> by lazy { sets.flatten().map { it.id }.toSet() }

    fun isBlurry(f: Features) = !f.screenshot && !f.isDoc && f.sharp < sharpMedian - 1.5f

    private fun group(): List<List<Features>> {
        val p = photos.sortedBy { it.time }
        val n = p.size
        val par = IntArray(n) { it }
        fun find(x0: Int): Int { var x = x0; while (par[x] != x) { par[x] = par[par[x]]; x = par[x] }; return x }
        val window = tuning.windowMinutes * 60_000L
        for (i in 0 until n) {
            var j = i + 1
            while (j < n && j - i <= 60) {
                val near = p[j].time - p[i].time < window
                if (!near && j - i > 12) break
                val s = Pixels.similarity(p[i].sig, p[j].sig)
                if ((near && s >= tuning.similarity) || s >= .985f) par[find(i)] = find(j)
                j++
            }
        }
        val map = LinkedHashMap<Int, MutableList<Features>>()
        for (i in 0 until n) map.getOrPut(find(i)) { mutableListOf() }.add(p[i])
        return map.values.filter { it.size > 1 }
    }

    private fun isProtected(f: Features): Boolean =
        (tuning.protectFavorites && (f.favorite || f.id in loved)) ||
            (tuning.protectRecentDays > 0 && now - f.time < tuning.protectRecentDays * 86_400_000L)

    private class Slot(var cx: Float, var cy: Float, n: Int) { val per = arrayOfNulls<FaceInfo>(n) }

    fun decide(set: List<Features>): SetDecision {
        val n = set.size
        fun norm(floor: Float, f: (Features) -> Float): FloatArray {
            val v = FloatArray(n) { f(set[it]) }
            val mn = v.min(); val mx = v.max(); val spread = max(mx - mn, floor); val mid = (mn + mx) / 2f
            return FloatArray(n) { (.5f + (v[it] - mid) / spread).coerceIn(0f, 1f) }
        }
        val sharpN = norm(.6f) { it.sharp }
        val centerN = norm(.6f) { it.center }
        val contrastN = norm(8f) { it.contrast }
        val colorN = norm(10f) { it.color }
        val warmN = norm(12f) { it.warm }
        val resN = norm(2e6f) { (it.w.toLong() * it.h).toFloat() }
        val bgSharpN = norm(.6f) { it.bgSharp }
        val bgColorN = norm(10f) { it.bgColor }
        val light = FloatArray(n) { .6f * set[it].expo + .2f * contrastN[it] + .2f * colorN[it] }
        val anySubject = set.any { it.hasSubject }
        val bd = FloatArray(n) { i ->
            val f = set[i]
            .35f * f.bgExpo + .25f * bgColorN[i] + .25f * bgSharpN[i] + .15f * (1f - min(1f, f.subjectFrac / .6f))
        }

        // The same person across frames: faces that sit in roughly the same place.
        val slots = mutableListOf<Slot>()
        val order = (0 until n).sortedByDescending { set[it].faces.size }
        for (i in order) {
            val used = HashSet<Slot>()
            for (face in set[i].faces.sortedByDescending { it.size }) {
                var bestSlot: Slot? = null; var bd2 = .2f
                for (s in slots) {
                    if (s in used) continue
                    val d = max(abs(s.cx - face.cx), abs(s.cy - face.cy))
                    if (d < bd2) { bd2 = d; bestSlot = s }
                }
                val s = bestSlot ?: Slot(face.cx, face.cy, n).also { slots.add(it) }
                s.per[i] = face; used.add(s)
            }
        }
        val faceQ = Array(n) { mutableListOf<Float>() }
        val slotScores = mutableListOf<Pair<Slot, FloatArray>>()
        for (s in slots) {
            val present = (0 until n).filter { s.per[it] != null }
            val mn = present.minOf { s.per[it]!!.sharp }; val mx = present.maxOf { s.per[it]!!.sharp }
            val spread = max(mx - mn, .6f); val mid = (mn + mx) / 2f
            val q = FloatArray(n) { -1f }
            for (i in present) {
                val f = s.per[i]!!
                val sh = (.5f + (f.sharp - mid) / spread).coerceIn(0f, 1f)
                q[i] = .35f * f.eyes + .25f * f.smile + .15f * f.frontal + .25f * sh
                faceQ[i].add(q[i])
            }
            val avgSize = present.map { s.per[it]!!.size }.average().toFloat()
            if (present.size >= 2 && avgSize >= .003f) slotScores.add(s to q)
        }
        val maxFaces = set.maxOf { it.faces.size }
        val meanQ = FloatArray(n) { if (faceQ[it].isEmpty()) 0f else faceQ[it].average().toFloat() }
        val minQ = FloatArray(n) { faceQ[it].minOrNull() ?: 0f }
        val maxSmile = FloatArray(n) { set[it].faces.maxOfOrNull { f -> f.smile } ?: 0f }
        val minEyes = FloatArray(n) { set[it].faces.minOfOrNull { f -> f.eyes } ?: 0f }
        val presence = FloatArray(n) { min(1f, (set[it].faces.maxOfOrNull { f -> f.size } ?: 0f) * 6f) }

        val craftVec = Array(n) { floatArrayOf(sharpN[it], set[it].expo, centerN[it], contrastN[it], colorN[it], resN[it], bd[it]) }
        val postVec = Array(n) { floatArrayOf(meanQ[it], maxSmile[it], presence[it], colorN[it], set[it].bright, warmN[it], bd[it], sharpN[it]) }
        val craft = FloatArray(n) { dot(taste.craft, craftVec[it]) }
        val vibe = FloatArray(n) { dot(taste.post, postVec[it]) }

        class Cand(val best: Best, val note: String, val score: FloatArray) {
            val winner: Int = score.indices.maxByOrNull { score[it] } ?: 0
            val judgeWeight = if (best.judge == Judge.PHOTOGRAPHER) 1f - balance else balance
            val priority = best.weight * (.35f + judgeWeight) * taste.trust[best.ordinal]
        }
        val cands = mutableListOf<Cand>()
        fun add(best: Best, score: FloatArray, note: String = "") { if (best in tuning.enabled) cands.add(Cand(best, note, score)) }
        add(Best.CRAFT, craft)
        add(Best.VIBE, vibe)
        for ((k, pair) in slotScores.sortedBy { it.first.cx }.withIndex()) {
            val note = if (slotScores.size < 2) "" else {
                val cx = pair.first.cx
                if (slotScores.size == 2) (if (k == 0) "left" else "right") else if (cx < .36f) "left" else if (cx > .64f) "right" else "middle"
            }
            add(Best.FACE, pair.second, note)
        }
        if (maxFaces >= 2) add(Best.GROUP, FloatArray(n) { (.6f * meanQ[it] + .4f * minQ[it]) * set[it].faces.size / maxFaces })
        if (anySubject || maxFaces > 0) add(Best.BACKDROP, bd)
        if (maxFaces > 0) add(Best.MOMENT, FloatArray(n) { if (set[it].faces.isEmpty()) .15f * sharpN[it] else .55f * maxSmile[it] * minEyes[it] + .25f * sharpN[it] + .2f * centerN[it] })
        if (set.maxOf { it.subjectHeight } >= .55f) add(Best.BODY, FloatArray(n) { set[it].subjectHeight * (if (set[it].subjectCut) .55f else 1f) })
        add(Best.LIGHT, light)
        if (cands.isEmpty()) cands.add(Cand(Best.CRAFT, "", craft))

        val keep = LinkedHashMap<Int, MutableList<Reason>>()
        val prot = HashSet<Long>()
        for (i in 0 until n) if (isProtected(set[i])) { keep[i] = mutableListOf(); prot.add(set[i].id) }
        val margin0 = lerp(.03f, .28f, tuning.strictness)
        val sorted = cands.sortedByDescending { it.priority }
        var picked = 0
        for ((k, c) in sorted.withIndex()) {
            val w = c.winner
            if (w in keep) { keep[w]!!.add(Reason(c.best, c.note)); if (k == 0) picked = max(picked, 1); continue }
            if (k > 0 && c.judgeWeight < .12f) continue
            if (k == 0 && picked == 0) { keep[w] = mutableListOf(Reason(c.best, c.note)); picked++; continue }
            if (picked >= tuning.maxKeep) continue
            val bestKept = keep.keys.maxOfOrNull { c.score[it] } ?: -1f
            val gain = c.score[w] - bestKept
            if (gain > margin0 / taste.trust[c.best.ordinal]) { keep[w] = mutableListOf(Reason(c.best, c.note)); picked++ }
        }
        if (keep.values.all { it.isEmpty() } && keep.size == n) { /* everything protected: nothing to add */ }
        return SetDecision(
            ids = set.map { it.id },
            keep = keep.entries.associate { set[it.key].id to it.value.toList() },
            winners = cands.map { it.best to set[it.winner].id },
            craftVec = (0 until n).associate { set[it].id to craftVec[it] },
            postVec = (0 until n).associate { set[it].id to postVec[it] },
            craftScore = (0 until n).associate { set[it].id to craft[it] },
            postScore = (0 until n).associate { set[it].id to vibe[it] },
            protectedIds = prot
        )
    }

    /** A photo judged on its own, for highlights and the quality ring. 0..1. */
    fun solo(f: Features): Float {
        val sh = ((f.sharp - (sharpMedian - 2f)) / 4f).coerceIn(0f, 1f)
        val col = (f.color / 70f).coerceIn(0f, 1f)
        val con = (f.contrast / 75f).coerceIn(0f, 1f)
        val fq = if (f.faces.isEmpty()) 0f else f.faces.map { .45f * it.eyes + .35f * it.smile + .2f * it.frontal }.average().toFloat()
        val presence = min(1f, (f.faces.maxOfOrNull { it.size } ?: 0f) * 6f)
        val craft = dot(taste.craft, floatArrayOf(sh, f.expo, sh, con, col, .5f, f.bgExpo))
        val post = if (f.faces.isEmpty()) dot(taste.post, floatArrayOf(.4f, .4f, .3f, col, f.bright, .5f, f.bgExpo, sh))
        else dot(taste.post, floatArrayOf(fq, f.faces.maxOf { it.smile }, presence, col, f.bright, .5f, f.bgExpo, sh))
        return lerp(craft, post, balance).coerceIn(0f, 1f)
    }

    /** The poster speaks up for a single imperfect photo: is there a person worth keeping in it? */
    private fun posterRescues(f: Features): Boolean {
        if (balance < .2f || tuning.strictness > .85f) return false
        return f.faces.any { it.size > .01f && it.eyes > .5f && it.smile > .4f }
    }

    fun cards(): List<Card> {
        val out = mutableListOf<Card>()
        for (set in sets.sortedByDescending { it.size }) {
            val d = decide(set)
            if (d.trash.isEmpty()) continue
            out.add(Card(CardKind.SET, "${set.size} near-identical shots", "", d.ids, d.keep.keys,
                d.keep.mapValues { e -> if (e.key in d.protectedIds && e.value.isEmpty()) listOf("Protected") else e.value.map { it.text } }, d))
        }
        fun clutter(kind: CardKind, on: Boolean, title: String, blurb: String, pick: (Features) -> Boolean) {
            if (!on) return
            val list = feats.filter { it.id !in inSet && pick(it) }
            if (list.isEmpty()) return
            val keep = HashMap<Long, List<String>>()
            for (f in list) {
                if (isProtected(f)) keep[f.id] = listOf("Protected")
                else if ((kind == CardKind.BLURRY || kind == CardKind.DARK) && posterRescues(f)) keep[f.id] = listOf("Poster: a good expression worth keeping")
            }
            if (keep.size == list.size) return
            out.add(Card(kind, title, blurb, list.map { it.id }, keep.keys, keep))
        }
        clutter(CardKind.BLURRY, tuning.clutterBlurry, "Possibly blurry", "Soft or shaky next to the rest of your library.") { isBlurry(it) }
        clutter(CardKind.DARK, tuning.clutterDark, "Too dark to read", "Almost no light reached these.") { it.isDark && !isBlurry(it) && !it.screenshot }
        clutter(CardKind.SCREENS, tuning.clutterScreens, "Screenshots", "They pile up fast. Keep the ones you still need.") { it.screenshot }
        clutter(CardKind.DOCS, tuning.clutterDocs, "Documents and receipts", "Photos of paper. Keep the ones you still need on your phone.") { it.isDoc }
        return out
    }

    companion object {
        /**
         * Learn from one approved set: push each judge's weights so the frames you kept would have
         * outranked the ones you trashed, and adjust how much each "best" category is trusted.
         */
        fun learn(taste: Taste, d: SetDecision, userKeep: Set<Long>, lr: Float = .3f) {
            val kept = d.ids.filter { it in userKeep }; val gone = d.ids.filter { it !in userKeep }
            taste.shown++
            if (userKeep == d.keep.keys) taste.agreed++ else taste.changed++
            if (kept.isEmpty() || gone.isEmpty()) return
            fun push(w: FloatArray, vec: Map<Long, FloatArray>) {
                for (a in kept) for (b in gone) {
                    val va = vec[a] ?: continue; val vb = vec[b] ?: continue
                    var z = 0f
                    for (i in w.indices) z += w[i] * (va[i] - vb[i])
                    val g = 1f - 1f / (1f + exp(-z))
                    for (i in w.indices) w[i] = (w[i] + lr * g * (va[i] - vb[i])).coerceIn(-3f, 3f)
                }
            }
            push(taste.craft, d.craftVec); push(taste.post, d.postVec)
            for ((best, winner) in d.winners) {
                val wasKept = winner in d.keep; val nowKept = winner in userKeep
                val t = taste.trust[best.ordinal]
                if (wasKept && !nowKept && d.keep[winner]?.all { it.best == best } == true) taste.trust[best.ordinal] = (t * .85f).coerceIn(.4f, 2.5f)
                else if (!wasKept && nowKept) taste.trust[best.ordinal] = (t * 1.2f).coerceIn(.4f, 2.5f)
            }
            // Whose favourites did you side with? Nudge the balance between the judges.
            val pc = kept.map { (d.postScore[it] ?: 0f) - (d.craftScore[it] ?: 0f) }.average() - gone.map { (d.postScore[it] ?: 0f) - (d.craftScore[it] ?: 0f) }.average()
            if (userKeep != d.keep.keys) taste.balanceShift = (taste.balanceShift + (pc.toFloat() * .25f).coerceIn(-.04f, .04f)).coerceIn(-.3f, .3f)
        }
    }
}
