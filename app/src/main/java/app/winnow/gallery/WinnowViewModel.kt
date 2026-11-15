package app.winnow.gallery

import android.app.Application
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.winnow.gallery.data.MediaItem
import app.winnow.gallery.data.MediaRepo
import app.winnow.gallery.data.Store
import app.winnow.gallery.data.Thumbs
import app.winnow.gallery.data.TrashEntry
import app.winnow.gallery.engine.Analyzer
import app.winnow.gallery.engine.Card
import app.winnow.gallery.engine.CardKind
import app.winnow.gallery.engine.Engine
import app.winnow.gallery.engine.Features
import app.winnow.gallery.engine.Taste
import app.winnow.gallery.engine.Tuning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class Moment(val name: String, val start: Long, val items: List<MediaItem>)

/** A snapshot of what the engine currently thinks, rebuilt off the main thread whenever inputs change. */
class Plan(
    val cards: List<Card>, val setSize: Map<Long, Int>, val solo: Map<Long, Float>,
    val moments: List<Moment>, val highlights: List<Pair<Moment, MediaItem>>,
    val reclaimBytes: Long, val reclaimCount: Int, val setCount: Int
)

class SessionCard(val card: Card) {
    val keep = mutableStateMapOf<Long, Boolean>().apply { card.keep.forEach { put(it, true) } }
    /** 0 undecided, 1 approved, 2 kept everything. */
    var status by mutableIntStateOf(0)
    fun trash(): List<Long> = card.ids.filter { keep[it] != true }
}

class Session(val cards: List<SessionCard>) {
    var index by mutableIntStateOf(0)
    var review by mutableStateOf(false)
    fun trashIds(): List<Long> = cards.filter { it.status == 1 }.flatMap { it.trash() }.distinct()
    fun keeperCount(): Int = cards.filter { it.status == 1 && it.card.kind == CardKind.SET }.sumOf { c -> c.card.ids.count { c.keep[it] == true } }
}

class Outcome(val count: Int, val bytes: Long, val keepers: Int)

private class PendingOp(val restore: Boolean, val entries: List<TrashEntry>, val session: Session?)

class WinnowViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    var hasAccess by mutableStateOf(false)
    var media by mutableStateOf<List<MediaItem>>(emptyList())
    var byId: Map<Long, MediaItem> = emptyMap()
    var feats by mutableStateOf<Map<Long, Features>>(emptyMap())
    var scanDone by mutableIntStateOf(0)
    var scanTotal by mutableIntStateOf(0)
    var scanning by mutableStateOf(false)
    var tuning by mutableStateOf(store.loadTuning())
    var taste: Taste = store.loadTaste()
    var tasteTick by mutableIntStateOf(0)
    var plan by mutableStateOf<Plan?>(null)
    var session by mutableStateOf<Session?>(null)
    var outcome by mutableStateOf<Outcome?>(null)
    var trash by mutableStateOf(store.loadTrash().filter { System.currentTimeMillis() - it.at < 30L * DAY })
    var loved by mutableStateOf(store.loadLoved())
    var viewer by mutableStateOf<Pair<List<MediaItem>, Int>?>(null)
    var viewerTint by mutableStateOf<Int?>(null)
    var toast by mutableStateOf<String?>(null)
    var busy by mutableStateOf<String?>(null)

    private var pending: PendingOp? = null
    private var scanJob: Job? = null
    private var planJob: Job? = null
    private var loadedCache = false
    private val analyzer by lazy { Analyzer(app) }

    fun onAccess(granted: Boolean) { hasAccess = granted; if (granted) refresh() }

    fun refresh() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val list = withContext(Dispatchers.IO) { MediaRepo.query(app) }
            media = list; byId = list.associateBy { it.id }
            if (!loadedCache) {
                loadedCache = true
                val cached = withContext(Dispatchers.IO) { store.loadFeatures() }
                feats = cached.filterKeys { it in byId }
            } else feats = feats.filterKeys { it in byId }
            replan(); startScan()
        }
    }

    /** Measures every photo not yet seen, newest first. Results are cached, so this runs once per photo. */
    private fun startScan() {
        if (scanJob?.isActive == true) return
        val todo = media.filter { !it.video && it.id !in feats }
        scanTotal = media.count { !it.video }; scanDone = scanTotal - todo.size
        if (todo.isEmpty()) { scanning = false; return }
        scanning = true
        scanJob = viewModelScope.launch(Dispatchers.Default) {
            val acc = HashMap(feats); var since = 0; var lastPlan = System.currentTimeMillis()
            for (item in todo) {
                if (!isActive) break
                val f = analyzer.analyse(item)
                if (f != null) acc[f.id] = f
                since++
                if (since % 8 == 0 || item === todo.last()) {
                    val snap = HashMap(acc); val done = scanTotal - todo.size + since
                    withContext(Dispatchers.Main) { feats = snap.filterKeys { it in byId }; scanDone = done }
                    if (System.currentTimeMillis() - lastPlan > 4000) { lastPlan = System.currentTimeMillis(); withContext(Dispatchers.Main) { replan() } }
                }
                if (since % 150 == 0) store.saveFeatures(acc.values)
            }
            store.saveFeatures(acc.values)
            withContext(Dispatchers.Main) { scanning = false; scanJob = null; replan(); startScan() }
        }
    }

    fun replan() {
        planJob?.cancel()
        val m = media; val f = feats; val t = tuning; val lv = loved
        planJob = viewModelScope.launch(Dispatchers.Default) {
            val p = buildPlan(m, f, t, taste, lv)
            withContext(Dispatchers.Main) { plan = p }
        }
    }

    fun setTuningAndPlan(t: Tuning) { tuning = t; store.saveTuning(t); replan() }
    fun resetTaste() { taste = Taste(); store.saveTaste(taste); tasteTick++; replan() }

    fun toggleLove(item: MediaItem) {
        val on = item.id !in loved
        loved = if (on) loved + item.id else loved - item.id
        if (on) { taste.loved++; store.saveTaste(taste); tasteTick++ }
        store.saveLoved(loved); replan()
    }

    fun startSession() {
        val p = plan ?: return
        if (p.cards.isEmpty()) return
        outcome = null
        session = Session(p.cards.map { SessionCard(it) })
    }

    fun decide(approve: Boolean) {
        val s = session ?: return; val c = s.cards[s.index]
        if (!approve) c.card.ids.forEach { c.keep[it] = true }
        c.status = if (approve && c.trash().isNotEmpty()) 1 else 2
        if (s.index < s.cards.size - 1) s.index++ else s.review = true
    }

    fun back() { val s = session ?: return; if (s.review) s.review = false else if (s.index > 0) s.index-- }
    fun leaveSession() { session = null }

    private fun entryFor(item: MediaItem): TrashEntry {
        val app = getApplication<Application>()
        val f = File(store.thumbDir, "${item.id}.jpg")
        try {
            val b = Thumbs.load(app, item, 256)
            if (b != null) FileOutputStream(f).use { b.copy(Bitmap.Config.ARGB_8888, false).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } catch (e: Exception) { }
        return TrashEntry(item.id, item.uri.toString(), item.name, item.bytes, System.currentTimeMillis(), f.absolutePath, item.video)
    }

    /** Asks Android to move items to its own 30-day trash. Android shows one confirmation for the whole batch. */
    fun requestTrash(ids: List<Long>, fromSession: Session?, launch: (IntentSender) -> Unit) {
        val items = ids.mapNotNull { byId[it] }
        if (items.isEmpty()) { if (fromSession != null) session = null; return }
        busy = "Getting ready"
        viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                val entries = withContext(Dispatchers.IO) { items.map { entryFor(it) } }
                val pi = MediaStore.createTrashRequest(app.contentResolver, items.map { it.uri }, true)
                pending = PendingOp(false, entries, fromSession)
                busy = null
                launch(pi.intentSender)
            } catch (e: Exception) { busy = null; toast = "Android would not open its trash for these items." }
        }
    }

    fun requestRestore(entries: List<TrashEntry>, launch: (IntentSender) -> Unit) {
        if (entries.isEmpty()) return
        try {
            val app = getApplication<Application>()
            val pi = MediaStore.createTrashRequest(app.contentResolver, entries.map { Uri.parse(it.uri) }, false)
            pending = PendingOp(true, entries, null)
            launch(pi.intentSender)
        } catch (e: Exception) { toast = "Could not restore. The item may already be gone." }
    }

    fun onSystemResult(ok: Boolean) {
        val op = pending ?: return; pending = null
        if (!ok) { toast = if (op.restore) "Nothing restored." else "Nothing moved. Your photos are untouched."; return }
        if (op.restore) {
            val ids = op.entries.map { it.id }.toSet()
            trash = trash.filter { it.id !in ids }; store.saveTrash(trash)
            op.entries.forEach { try { File(it.thumb).delete() } catch (e: Exception) { } }
            toast = if (ids.size == 1) "Restored to your gallery" else "${ids.size} items restored"
            refresh(); return
        }
        val s = op.session
        if (s != null) {
            for (c in s.cards) {
                val d = c.card.decision ?: continue
                if (c.status == 1) Engine.learn(taste, d, c.card.ids.filter { c.keep[it] == true }.toSet())
            }
            store.saveTaste(taste); tasteTick++
            outcome = Outcome(op.entries.size, op.entries.sumOf { it.bytes }, s.keeperCount())
            session = null
        } else toast = "In the trash for 30 days"
        trash = (op.entries + trash).distinctBy { it.id }; store.saveTrash(trash)
        val gone = op.entries.map { it.id }.toSet()
        feats = feats.filterKeys { it !in gone }
        viewModelScope.launch(Dispatchers.IO) { store.saveFeatures(feats.values) }
        refresh()
    }

    override fun onCleared() { scanJob?.cancel(); try { analyzer.close() } catch (e: Exception) { } }

    companion object {
        const val DAY = 86_400_000L

        fun moments(media: List<MediaItem>): List<Moment> {
            val out = ArrayList<Moment>(); var cur = ArrayList<MediaItem>(); var last = 0L
            val day = SimpleDateFormat("EEE d MMM", Locale.getDefault()); val cal = Calendar.getInstance()
            fun flush() {
                if (cur.isEmpty()) return
                val start = cur.last().time; cal.timeInMillis = start
                val hr = cal.get(Calendar.HOUR_OF_DAY)
                val part = if (hr < 5) "night" else if (hr < 12) "morning" else if (hr < 17) "afternoon" else if (hr < 21) "evening" else "night"
                out.add(Moment(day.format(Date(start)) + ", " + part, start, cur)); cur = ArrayList()
            }
            for (m in media) { // newest first
                if (cur.isNotEmpty() && last - m.time > 2 * 3_600_000L) flush()
                cur.add(m); last = m.time
            }
            flush(); return out
        }

        fun buildPlan(media: List<MediaItem>, feats: Map<Long, Features>, tuning: Tuning, taste: Taste, loved: Set<Long>): Plan {
            val live = media.mapNotNull { feats[it.id] }
            val engine = Engine(live, tuning, taste, System.currentTimeMillis(), loved)
            val cards = engine.cards().toMutableList()
            if (tuning.clutterVideos) {
                val big = media.filter { it.video && it.bytes > 30_000_000L }.sortedByDescending { it.bytes }
                if (big.isNotEmpty()) cards.add(Card(CardKind.VIDEOS, "Large videos", "Videos take the most space. Winnow keeps them all unless you flip one.", big.map { it.id }, big.map { it.id }.toSet(), emptyMap()))
            }
            val setSize = HashMap<Long, Int>(); engine.sets.forEach { s -> s.forEach { setSize[it.id] = s.size } }
            val solo = HashMap<Long, Float>(); for (f in live) solo[f.id] = engine.solo(f)
            val moments = moments(media)
            val hl = ArrayList<Pair<Moment, MediaItem>>()
            for (m in moments) {
                if (hl.size >= 8) break
                val best = m.items.filter { !it.video && !it.screenshot }.mapNotNull { i -> feats[i.id]?.takeIf { !it.isDoc && !engine.isBlurry(it) && !it.isDark }?.let { i to (solo[i.id] ?: 0f) } }.maxByOrNull { it.second }
                if (best != null) hl.add(m to best.first)
            }
            val byId = media.associateBy { it.id }
            val out = cards.flatMap { c -> c.ids.filter { it !in c.keep } }
            return Plan(cards, setSize, solo, moments, hl, out.sumOf { byId[it]?.bytes ?: 0L }, out.size, engine.sets.size)
        }
    }
}
