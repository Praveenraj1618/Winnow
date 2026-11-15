package app.winnow.gallery.data

import android.content.Context
import android.util.Base64
import app.winnow.gallery.engine.Best
import app.winnow.gallery.engine.FaceInfo
import app.winnow.gallery.engine.Features
import app.winnow.gallery.engine.Taste
import app.winnow.gallery.engine.Tuning
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Something Winnow sent to the phone's trash, remembered so it can be shown and restored. */
class TrashEntry(val id: Long, val uri: String, val name: String, val bytes: Long, val at: Long, val thumb: String, val video: Boolean)

/** Everything Winnow keeps lives in the app's private folder on the phone. Nothing is sent anywhere. */
class Store(private val ctx: Context) {
    private val prefs = ctx.getSharedPreferences("winnow", Context.MODE_PRIVATE)
    private val featFile = File(ctx.filesDir, "features.json")
    private val trashFile = File(ctx.filesDir, "trash.json")
    val thumbDir: File get() = File(ctx.filesDir, "trash-thumbs").also { it.mkdirs() }

    private fun write(f: File, text: String) { val tmp = File(f.parentFile, f.name + ".tmp"); tmp.writeText(text); tmp.renameTo(f) }

    fun loadFeatures(): Map<Long, Features> {
        val out = HashMap<Long, Features>()
        try {
            if (!featFile.exists()) return out
            val root = JSONObject(featFile.readText())
            if (root.optInt("v") != VERSION) return out
            val arr = root.getJSONArray("items")
            for (i in 0 until arr.length()) { val f = fromJson(arr.getJSONObject(i)); out[f.id] = f }
        } catch (e: Exception) { out.clear() }
        return out
    }

    fun saveFeatures(all: Collection<Features>) {
        try {
            val arr = JSONArray(); for (f in all) arr.put(toJson(f))
            write(featFile, JSONObject().put("v", VERSION).put("items", arr).toString())
        } catch (e: Exception) { }
    }

    private fun toJson(f: Features): JSONObject {
        val faces = JSONArray()
        for (a in f.faces) faces.put(JSONArray(listOf(a.cx, a.cy, a.size, a.smile, a.eyes, a.frontal, a.sharp).map { it.toDouble() }))
        return JSONObject().put("i", f.id).put("t", f.time).put("w", f.w).put("h", f.h).put("b", f.bytes)
            .put("n", JSONArray(listOf(f.sharp, f.center, f.expo, f.contrast, f.color, f.bright, f.warm, f.white, f.subjectFrac, f.subjectHeight, f.bgSharp, f.bgExpo, f.bgColor).map { it.toDouble() }))
            .put("s", Base64.encodeToString(f.sig, Base64.NO_WRAP)).put("c", f.tint).put("f", faces)
            .put("x", (if (f.hasSubject) 1 else 0) or (if (f.subjectCut) 2 else 0) or (if (f.screenshot) 4 else 0) or (if (f.favorite) 8 else 0))
    }

    private fun fromJson(o: JSONObject): Features {
        val n = o.getJSONArray("n"); fun v(i: Int) = n.getDouble(i).toFloat()
        val fa = o.getJSONArray("f"); val faces = ArrayList<FaceInfo>()
        for (i in 0 until fa.length()) { val a = fa.getJSONArray(i); val g = { k: Int -> a.getDouble(k).toFloat() }; faces.add(FaceInfo(g(0), g(1), g(2), g(3), g(4), g(5), g(6))) }
        val x = o.getInt("x")
        return Features(o.getLong("i"), o.getLong("t"), o.getInt("w"), o.getInt("h"), o.getLong("b"),
            v(0), v(1), v(2), v(3), v(4), v(5), v(6), v(7), Base64.decode(o.getString("s"), Base64.NO_WRAP), o.getInt("c"), faces,
            x and 1 != 0, v(8), v(9), x and 2 != 0, v(10), v(11), v(12), x and 4 != 0, x and 8 != 0)
    }

    fun loadTuning(): Tuning = try {
        val o = JSONObject(prefs.getString("tuning", null) ?: "{}"); val d = Tuning()
        val en = o.optJSONArray("enabled")
        Tuning(
            strictness = o.optDouble("strictness", d.strictness.toDouble()).toFloat(), balance = o.optDouble("balance", d.balance.toDouble()).toFloat(),
            maxKeep = o.optInt("maxKeep", d.maxKeep),
            enabled = if (en == null) d.enabled else (0 until en.length()).mapNotNull { k -> Best.entries.firstOrNull { it.name == en.getString(k) } }.toSet(),
            protectFavorites = o.optBoolean("fav", d.protectFavorites), protectRecentDays = o.optInt("recent", d.protectRecentDays),
            clutterBlurry = o.optBoolean("blurry", true), clutterDark = o.optBoolean("dark", true), clutterScreens = o.optBoolean("screens", true),
            clutterDocs = o.optBoolean("docs", true), clutterVideos = o.optBoolean("videos", true)
        )
    } catch (e: Exception) { Tuning() }

    fun saveTuning(t: Tuning) {
        prefs.edit().putString("tuning", JSONObject().put("strictness", t.strictness.toDouble()).put("balance", t.balance.toDouble()).put("maxKeep", t.maxKeep)
            .put("enabled", JSONArray(t.enabled.map { it.name })).put("fav", t.protectFavorites).put("recent", t.protectRecentDays)
            .put("blurry", t.clutterBlurry).put("dark", t.clutterDark).put("screens", t.clutterScreens).put("docs", t.clutterDocs).put("videos", t.clutterVideos).toString()).apply()
    }

    fun loadTaste(): Taste = try {
        val o = JSONObject(prefs.getString("taste", null) ?: "{}"); val t = Taste()
        fun fill(key: String, into: FloatArray) { val a = o.optJSONArray(key) ?: return; if (a.length() == into.size) for (i in into.indices) into[i] = a.getDouble(i).toFloat() }
        fill("craft", t.craft); fill("post", t.post); fill("trust", t.trust)
        t.balanceShift = o.optDouble("shift", 0.0).toFloat(); t.shown = o.optInt("shown"); t.agreed = o.optInt("agreed"); t.changed = o.optInt("changed"); t.loved = o.optInt("loved"); t
    } catch (e: Exception) { Taste() }

    fun saveTaste(t: Taste) {
        fun arr(a: FloatArray) = JSONArray(a.map { it.toDouble() })
        prefs.edit().putString("taste", JSONObject().put("craft", arr(t.craft)).put("post", arr(t.post)).put("trust", arr(t.trust))
            .put("shift", t.balanceShift.toDouble()).put("shown", t.shown).put("agreed", t.agreed).put("changed", t.changed).put("loved", t.loved).toString()).apply()
    }

    fun loadLoved(): Set<Long> = prefs.getStringSet("loved", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
    fun saveLoved(s: Set<Long>) { prefs.edit().putStringSet("loved", s.map { it.toString() }.toSet()).apply() }

    fun loadTrash(): List<TrashEntry> = try {
        if (!trashFile.exists()) emptyList() else {
            val a = JSONArray(trashFile.readText())
            (0 until a.length()).map { val o = a.getJSONObject(it); TrashEntry(o.getLong("id"), o.getString("uri"), o.getString("name"), o.getLong("bytes"), o.getLong("at"), o.optString("thumb"), o.optBoolean("video")) }
        }
    } catch (e: Exception) { emptyList() }

    fun saveTrash(list: List<TrashEntry>) {
        try {
            val a = JSONArray()
            for (e in list) a.put(JSONObject().put("id", e.id).put("uri", e.uri).put("name", e.name).put("bytes", e.bytes).put("at", e.at).put("thumb", e.thumb).put("video", e.video))
            write(trashFile, a.toString())
        } catch (e: Exception) { }
    }

    companion object { const val VERSION = 1 }
}
