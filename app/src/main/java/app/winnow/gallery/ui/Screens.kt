@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.winnow.gallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.winnow.gallery.WinnowViewModel
import app.winnow.gallery.data.TrashEntry
import app.winnow.gallery.engine.Best
import app.winnow.gallery.engine.Judge
import app.winnow.gallery.engine.Taste
import app.winnow.gallery.engine.Tuning
import kotlin.math.cos
import kotlin.math.sin

@Composable
private fun Toggle(title: String, sub: String, on: Boolean, glyph: Int = -1, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (glyph >= 0) Glyph(glyph, W.Muted, 18.dp)
        Column(Modifier.weight(1f)) { Txt(title, T.Strong); if (sub.isNotEmpty()) Txt(sub, T.Small) }
        Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = W.Keep, checkedThumbColor = W.OnPaper, uncheckedTrackColor = W.Glass2, uncheckedThumbColor = W.Paper, uncheckedBorderColor = Color.Transparent, checkedBorderColor = Color.Transparent))
    }
}

/** A thin track with a round knob. Tap or drag anywhere along it. */
@Composable
private fun SlimSlider(value: Float, set: (Float) -> Unit, done: () -> Unit, label: String) {
    val onSet by rememberUpdatedState(set); val onDone by rememberUpdatedState(done)
    val knob by animateFloatAsState(value.coerceIn(0f, 1f), bouncy(), label = "knob")
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(40.dp)
            .semantics { contentDescription = label; progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f); setProgress { v -> onSet(v.coerceIn(0f, 1f)); onDone(); true } }
            .pointerInput(Unit) { detectTapGestures { o -> onSet((o.x / size.width).coerceIn(0f, 1f)); onDone() } }
            .pointerInput(Unit) { detectHorizontalDragGestures(onDragEnd = { onDone() }, onDragCancel = { onDone() }) { change, _ -> onSet((change.position.x / size.width).coerceIn(0f, 1f)) } },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(W.Glass2))
        Box(Modifier.width((maxWidth - 24.dp) * knob + 12.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(W.Paper))
        Box(Modifier.offset(x = (maxWidth - 24.dp) * knob).size(24.dp).clip(CircleShape).background(W.Paper))
    }
}

@Composable
private fun Dial(title: String, left: String, right: String, value: Float, set: (Float) -> Unit, done: () -> Unit) {
    Column {
        Txt(title, T.Strong)
        SlimSlider(value, set, done, title)
        Row { Txt(left, T.Small, Modifier.weight(1f)); Txt(right, T.Small) }
    }
}

/** Every control over what a declutter asks for. Changes apply as soon as you let go. */
@Composable
fun TuneSheet(vm: WinnowViewModel, onClose: () -> Unit) {
    var t by remember { mutableStateOf(vm.tuning) }
    fun commit(n: Tuning) { t = n; vm.setTuningAndPlan(n) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Txt("Tune the declutter", T.H2, Modifier.weight(1f)); RoundBtn(G.X, "Close", onClose) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((name, preset) in Tuning.PRESETS) {
                val on = t.strictness == preset.strictness && t.balance == preset.balance && t.maxKeep == preset.maxKeep
                Chip(name, on, { commit(t.copy(strictness = preset.strictness, balance = preset.balance, maxKeep = preset.maxKeep)) })
            }
        }
        Glass {
            Dial("Who decides", "Photographer", "Poster", t.balance, { t = t.copy(balance = it) }, { commit(t) })
            Dial("How strict", "Keep more", "Free more space", t.strictness, { t = t.copy(strictness = it) }, { commit(t) })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { Txt("Keep up to ${t.maxKeep} per set", T.Strong); Txt("However many reasons the judges find", T.Small) }
                RoundBtn(G.BACK, "Fewer", { if (t.maxKeep > 1) commit(t.copy(maxKeep = t.maxKeep - 1)) }, enabled = t.maxKeep > 1, size = 40.dp)
                RoundBtn(G.PLUS, "More", { if (t.maxKeep < 6) commit(t.copy(maxKeep = t.maxKeep + 1)) }, enabled = t.maxKeep < 6, size = 40.dp)
            }
        }
        Glass {
            Txt("WHAT EARNS A PLACE", T.Eyebrow)
            for (b in Best.entries) Toggle(b.label, b.blurb, b in t.enabled, if (b.judge == Judge.PHOTOGRAPHER) G.CAMERA else G.HEART) { on -> commit(t.copy(enabled = if (on) t.enabled + b else t.enabled - b)) }
        }
        Glass {
            Txt("NEVER PROPOSE", T.Eyebrow)
            Toggle("Loved and favourite photos", "Anything you hearted here or starred in your gallery", t.protectFavorites) { commit(t.copy(protectFavorites = it)) }
            Txt("Recent photos", T.Strong)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { for ((d, l) in listOf(0 to "Off", 7 to "7 days", 30 to "30 days", 90 to "90 days")) Chip(l, t.protectRecentDays == d, { commit(t.copy(protectRecentDays = d)) }) }
        }
        Glass {
            Txt("CLUTTER TO LOOK FOR", T.Eyebrow)
            Toggle("Blurry shots", "", t.clutterBlurry) { commit(t.copy(clutterBlurry = it)) }
            Toggle("Too-dark shots", "", t.clutterDark) { commit(t.copy(clutterDark = it)) }
            Toggle("Screenshots", "", t.clutterScreens) { commit(t.copy(clutterScreens = it)) }
            Toggle("Documents and receipts", "", t.clutterDocs) { commit(t.copy(clutterDocs = it)) }
            Toggle("Large videos", "Over 30 MB. Kept unless you flip one.", t.clutterVideos) { commit(t.copy(clutterVideos = it)) }
        }
    }
}

@Composable
private fun WeightRow(label: String, w: Float, def: Float, n: Int) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(w) { a.animateTo((kotlin.math.abs(w) / 2f).coerceIn(.02f, 1f), tween(900, delayMillis = n * 50)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Txt(label, T.Body1)
            val d = w - def
            Txt(if (kotlin.math.abs(d) > .1f) (if (d > 0) "up" else "down") + " from " + String.format("%.2f", def) + " to " + String.format("%.2f", w) else "weight " + String.format("%.2f", w), T.Small, color = if (d > .1f) W.Keep else if (d < -.1f) W.Trash else W.Muted)
        }
        Box(Modifier.weight(.8f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(W.Glass2)) {
            Box(Modifier.fillMaxWidth(a.value).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(if (w < 0) Brush.linearGradient(listOf(W.Trash, W.Trash)) else Brush.linearGradient(listOf(W.Brand[0], W.Brand[1]))))
        }
    }
}

/** What the app has learned: a shape that bends as you approve sessions, and the numbers behind it. */
@Composable
fun TasteScreen(vm: WinnowViewModel, ambient: List<Color>) {
    val tick = vm.tasteTick; val taste = vm.taste
    val bal = (vm.tuning.balance + taste.balanceShift).coerceIn(0f, 1f)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 132.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Txt(if (taste.shown > 0) "You agreed with ${taste.agreed} of ${taste.shown} verdicts and changed ${taste.changed}." + (if (taste.loved > 0) " You loved ${plural(taste.loved, "photo")}." else "")
            else "Nothing approved yet, so this is the starting shape. It bends toward you with every session.", T.Lead)
        key(tick) {
            val grow = remember { Animatable(0f) }
            LaunchedEffect(Unit) { grow.animateTo(1f, bouncy()) }
            val bests = Best.entries
            Box(Modifier.fillMaxWidth().aspectRatio(1.08f), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(34.dp)) {
                    val c = Offset(size.width / 2, size.height / 2); val R = size.minDimension / 2
                    for (k in 1..3) drawCircle(W.Line, R * k / 3f, c, style = Stroke(1.dp.toPx()))
                    val n = bests.size
                    val pts = bests.mapIndexed { i, b ->
                        val ang = -Math.PI / 2 + i * 2 * Math.PI / n
                        val jw = if (b.judge == Judge.PHOTOGRAPHER) 1f - bal else bal
                        val v = if (b in vm.tuning.enabled) ((.35f + jw) * b.weight * taste.trust[b.ordinal] / 1.6f).coerceIn(.12f, 1f) else .06f
                        val r = R * (.1f + (v - .1f) * grow.value)
                        drawLine(W.Line, c, Offset(c.x + cos(ang).toFloat() * R, c.y + sin(ang).toFloat() * R), 1.dp.toPx())
                        Offset(c.x + cos(ang).toFloat() * r, c.y + sin(ang).toFloat() * r)
                    }
                    val path = Path()
                    path.moveTo(pts[0].x, pts[0].y)
                    for (i in 0 until n) {
                        val p0 = pts[(i - 1 + n) % n]; val p1 = pts[i]; val p2 = pts[(i + 1) % n]; val p3 = pts[(i + 2) % n]
                        path.cubicTo(p1.x + (p2.x - p0.x) / 5f, p1.y + (p2.y - p0.y) / 5f, p2.x - (p3.x - p1.x) / 5f, p2.y - (p3.y - p1.y) / 5f, p2.x, p2.y)
                    }
                    path.close()
                    drawPath(path, Brush.radialGradient(listOf(ambient[1].copy(alpha = .75f), ambient[0].copy(alpha = .55f)), c, R))
                    drawPath(path, W.Ink, style = Stroke(1.6.dp.toPx()))
                }
                // labels around the shape
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val n = bests.size
                    for ((i, b) in bests.withIndex()) {
                        val ang = -Math.PI / 2 + i * 2 * Math.PI / n
                        val x = maxWidth / 2 + (maxWidth / 2 - 30.dp) * cos(ang).toFloat(); val y = maxHeight / 2 + (maxHeight / 2 - 14.dp) * sin(ang).toFloat()
                        Txt(b.label.removePrefix("Best ").replaceFirstChar { it.uppercase() }, T.Small.copy(fontSize = 10.5.sp), Modifier.offset(x - 40.dp, y - 8.dp).width(80.dp), align = TextAlign.Center, maxLines = 1)
                    }
                }
            }
        }
        Glass(pad = 16.dp) {
            Row { Txt("Photographer", T.Small.copy(color = W.Ink), Modifier.weight(1f)); Txt("Poster", T.Small.copy(color = W.Ink)) }
            BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
                Box(Modifier.align(Alignment.Center).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(W.Glass2))
                Box(Modifier.offset(x = (maxWidth - 14.dp) * bal).size(14.dp).clip(CircleShape).background(W.Paper))
            }
            Txt(if (kotlin.math.abs(taste.balanceShift) > .02f) "Your approvals have pulled the balance " + (if (taste.balanceShift > 0) "toward the poster." else "toward the photographer.") else "Where your setting puts the balance. Your approvals will pull it.", T.Small)
        }
        Txt("THE PHOTOGRAPHER LOOKS FOR", T.Eyebrow, Modifier.padding(top = 6.dp))
        Column { Taste.CRAFT_KEYS.forEachIndexed { i, k -> WeightRow(k, taste.craft[i], Taste.CRAFT_DEFAULT[i], i) } }
        Txt("THE POSTER LOOKS FOR", T.Eyebrow, Modifier.padding(top = 6.dp))
        Column { Taste.POST_KEYS.forEachIndexed { i, k -> WeightRow(k, taste.post[i], Taste.POST_DEFAULT[i], i) } }
        Txt("Each time you approve a set, Winnow compares the shots you kept with the ones you trashed and shifts these weights so your choice would have won. It all stays on this phone.", T.Small)
        Btn("Forget what you learned", { vm.resetTaste() })
    }
}

@Composable
fun TrashScreen(vm: WinnowViewModel, restore: (List<TrashEntry>) -> Unit) {
    val list = vm.trash; val now = System.currentTimeMillis()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 132.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Txt(if (list.isEmpty()) "Empty. Anything you approve waits in the phone's own trash for 30 days." else "${plural(list.size, "item")}, ${bytesText(list.sumOf { it.bytes })}. Android deletes each one for good 30 days after it was trashed.", T.Lead)
                Glass(pad = 14.dp) { Txt("This is Android's own trash, so your gallery app shows the same items. The space comes back when Android empties it. A compressed space-saver trash comes in a later build.", T.Small) }
                if (list.size > 1) Btn("Restore all", { restore(list) }, glyph = G.UNDO)
            }
        }
        items(list, key = { it.id }) { e ->
            val left = (30 - (now - e.at) / WinnowViewModel.DAY).toInt().coerceIn(0, 30)
            Row(Modifier.animateItem().fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(W.Glass).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Ring(left / 30f, Modifier.size(62.dp), stroke = 3.dp) { FileThumb(e.thumb, Modifier.size(50.dp).clip(CircleShape)) }
                Column(Modifier.weight(1f)) {
                    Txt(e.name, T.Strong.copy(fontSize = 14.sp), maxLines = 1)
                    Row { Txt(plural(left, "day") + " left", T.Small, color = W.Keep); Txt(" · " + bytesText(e.bytes), T.Small) }
                }
                RoundBtn(G.UNDO, "Restore " + e.name, { restore(listOf(e)) })
            }
        }
    }
}

@Composable
fun Welcome(onAllow: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Spacer(Modifier.height(40.dp))
        Txt("WINNOW", T.Eyebrow)
        Txt("Keep the shots worth keeping.", T.Title.copy(fontSize = 46.sp, lineHeight = 46.sp))
        Txt("Two judges go through your look-alike photos together: a photographer and someone who loves to post. A frame goes only when neither can find a reason to keep it, and only after you approve.", T.Lead)
        Glass {
            for ((g, t) in listOf(G.CAMERA to "Everything is read on this phone. The app has no internet permission.", G.CHECK to "Nothing moves until you approve a session.", G.UNDO to "Approved items wait in Android's trash for 30 days."))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { Glyph(g, W.Keep, 20.dp); Txt(t, T.Body1, Modifier.weight(1f)) }
        }
        Btn("Allow photo access", onAllow, Modifier.fillMaxWidth(), primary = true, big = true)
        Txt("Choose \"Allow all\" so Winnow can compare look-alike shots across your whole gallery.", T.Small, Modifier.fillMaxWidth(), align = TextAlign.Center)
    }
}
