package app.winnow.gallery.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.winnow.gallery.Session
import app.winnow.gallery.SessionCard
import app.winnow.gallery.WinnowViewModel
import app.winnow.gallery.data.MediaItem
import app.winnow.gallery.engine.CardKind
import app.winnow.gallery.engine.Judge
import kotlinx.coroutines.launch
import kotlin.math.abs

private fun kindNoun(k: CardKind) = when (k) {
    CardKind.SET -> "look-alike sets"; CardKind.BLURRY -> "blurry"; CardKind.DARK -> "too dark"
    CardKind.SCREENS -> "screenshots"; CardKind.DOCS -> "documents"; CardKind.VIDEOS -> "large videos"
}

@Composable
fun DeclutterScreen(vm: WinnowViewModel, ambient: List<Color>, onTune: () -> Unit, onCelebrate: () -> Unit) {
    val s = vm.session
    if (s != null) { SessionView(vm, s); return }
    val plan = vm.plan
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 132.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val o = vm.outcome
        if (o != null) {
            LaunchedEffect(o) { onCelebrate() }
            Glass {
                Txt("SESSION APPROVED", T.Eyebrow)
                val (n, unit) = bytesParts((countUp(o.bytes.toFloat())).toLong())
                Row(verticalAlignment = Alignment.Bottom) { Txt(n, T.Huge); Txt(unit, T.H2, Modifier.padding(start = 6.dp, bottom = 8.dp), color = W.Muted) }
                Txt("${plural(o.count, "item")} moved to the phone's trash. Android keeps them for 30 days, then frees the space." + (if (o.keepers > 0) " ${plural(o.keepers, "keeper")} stayed." else ""), T.Lead)
            }
        }
        if (vm.scanning || (vm.scanTotal > 0 && vm.scanDone < vm.scanTotal)) {
            Glass(pad = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spinner(Modifier.size(26.dp))
                    Column(Modifier.weight(1f)) {
                        Txt("Reading your photos on this phone", T.Strong)
                        Txt("${vm.scanDone} of ${vm.scanTotal} · nothing is uploaded", T.Small)
                    }
                }
                val p by animateFloatAsState(if (vm.scanTotal == 0) 0f else vm.scanDone.toFloat() / vm.scanTotal, label = "scan")
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(W.Glass2)) { Box(Modifier.fillMaxWidth(p).fillMaxHeight().background(W.Ink)) }
            }
        }
        if (plan == null) { Txt("Getting ready", T.Lead); return@Column }
        if (plan.cards.isEmpty()) {
            Glass { Txt(if (vm.scanning) "Nothing to clear yet" else "All clear", T.H2); Txt(if (vm.scanning) "Winnow is still reading your library. Proposals appear here as it goes." else "No look-alikes, blur or screenshots worth clearing right now.", T.Lead) }
        } else {
            val total = vm.media.sumOf { it.bytes }.coerceAtLeast(1L)
            Ring(plan.reclaimBytes.toFloat() / total, Modifier.align(Alignment.CenterHorizontally).size(248.dp), stroke = 12.dp, brush = Brush.linearGradient(listOf(ambient[0], ambient[1]))) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Txt("COULD GO", T.Eyebrow)
                    val (n, unit) = bytesParts(countUp(plan.reclaimBytes.toFloat()).toLong())
                    Row(verticalAlignment = Alignment.Bottom) { Txt(n, T.Huge); Txt(unit, T.H2, Modifier.padding(start = 4.dp, bottom = 8.dp), color = W.Muted) }
                    Txt("${plural(plan.reclaimCount, "item")} · ${(plan.reclaimBytes * 100 / total).toInt()}% of the library", T.Small)
                }
            }
            Btn("Start session", { vm.startSession() }, Modifier.fillMaxWidth(), primary = true, big = true)
            Txt("Winnow proposes, you decide. Nothing moves until you approve at the end.", T.Small, Modifier.fillMaxWidth(), align = TextAlign.Center)
            val finds = plan.cards.groupBy { it.kind }.map { (k, v) -> Triple(if (k == CardKind.SET) v.size else v.sumOf { it.ids.size }, kindNoun(k), if (k == CardKind.SET) plural(v.sumOf { it.ids.size }, "photo") else bytesText(v.sumOf { c -> c.ids.sumOf { vm.byId[it]?.bytes ?: 0L } })) }
            for (row in finds.chunked(2)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in row) Glass(Modifier.weight(1f), RoundedCornerShape(22.dp), 14.dp) {
                    Column { Txt("${f.first}", T.H2.copy(fontSize = 34.sp, lineHeight = 36.sp)); Txt(f.second, T.Body1); Txt(f.third, T.Small) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        JudgeStrip(vm, onTune)
    }
}

/** The two judges at a glance, with the door to the full tuning sheet. */
@Composable
fun JudgeStrip(vm: WinnowViewModel, onTune: () -> Unit) {
    val t = vm.tuning; val tick = vm.tasteTick
    val bal = (t.balance + (if (tick >= 0) vm.taste.balanceShift else 0f)).coerceIn(0f, 1f)
    Glass(pad = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(G.CAMERA, W.Ink, 18.dp); Txt("Photographer", T.Small.copy(color = W.Ink), Modifier.padding(start = 6.dp).weight(1f))
            Txt("Poster", T.Small.copy(color = W.Ink), Modifier.padding(end = 6.dp)); Glyph(G.HEART, W.Ink, 18.dp)
        }
        val a by animateFloatAsState(bal, bouncy(), label = "bal")
        BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
            Box(Modifier.align(Alignment.Center).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(W.Glass2))
            Box(Modifier.offset(x = (maxWidth - 14.dp) * a.coerceIn(0f, 1f)).size(14.dp).clip(CircleShape).background(W.Paper))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val strict = if (t.strictness < .34f) "keeps generously" else if (t.strictness < .67f) "balanced" else "frees more space"
            Txt("Up to ${t.maxKeep} per set · $strict · ${t.enabled.size} of 8 reasons on", T.Small, Modifier.weight(1f))
            Btn("Tune", onTune, glyph = G.TUNE)
        }
    }
}

@Composable
fun Spinner(modifier: Modifier = Modifier) {
    val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val r by inf.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "r")
    androidx.compose.foundation.Canvas(modifier.rotate(r)) {
        val sw = size.minDimension * .14f
        drawArc(W.Glass2, 0f, 360f, false, androidx.compose.ui.geometry.Offset(sw / 2, sw / 2), androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        drawArc(W.Ink, 0f, 100f, false, androidx.compose.ui.geometry.Offset(sw / 2, sw / 2), androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw), style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

@Composable
private fun Bars(s: Session) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val shown = s.cards.size.coerceAtMost(40)
        for (k in 0 until shown) {
            val idx = if (s.cards.size <= 40) k else k * s.cards.size / 40
            val f by animateFloatAsState(if (s.review || idx < s.index) 1f else if (idx == s.index) .5f else 0f, tween(500), label = "bar")
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(W.Glass2)) { Box(Modifier.fillMaxWidth(f).fillMaxHeight().background(W.Ink)) }
        }
    }
}

@Composable
private fun SessionView(vm: WinnowViewModel, s: Session) {
    Column(Modifier.fillMaxSize()) {
        Bars(s)
        if (s.review) { Review(vm, s); return@Column }
        val card = s.cards[s.index]
        key(s.index) { Deck(vm, s, card) }
    }
}

/** One proposal as a card. Drag right to approve, left to keep everything, or use the buttons. */
@Composable
private fun Deck(vm: WinnowViewModel, s: Session, sc: SessionCard) {
    val x = remember { Animatable(0f) }
    val enter = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    LaunchedEffect(Unit) { enter.animateTo(1f, bouncy()) }
    val canApprove = sc.trash().isNotEmpty()
    fun fly(dir: Int) { scope.launch { x.animateTo(dir * widthPx * 1.3f, tween(260)); vm.decide(dir > 0) } }
    // The floating action bar in App.kt calls back through these.
    DisposableEffect(sc) { SessionActions.approve = { if (sc.trash().isNotEmpty()) fly(1) }; SessionActions.keepAll = { fly(-1) }; onDispose { SessionActions.approve = null; SessionActions.keepAll = null } }
    val c = sc.card
    val items = remember(c) { c.ids.mapNotNull { vm.byId[it] } }
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer {
                translationX = x.value; rotationZ = x.value / widthPx * 14f
                val e = enter.value; translationY = (1f - e) * 120f; scaleX = .88f + .12f * e; scaleY = .88f + .12f * e; alpha = e.coerceIn(0f, 1f)
            }
            .pointerInput(sc) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val v = x.value
                        if (v > widthPx * .28f && sc.trash().isNotEmpty()) fly(1) else if (v < -widthPx * .28f) fly(-1) else scope.launch { x.animateTo(0f, bouncy()) }
                    },
                    onDragCancel = { scope.launch { x.animateTo(0f, bouncy()) } }
                ) { _, dx -> scope.launch { x.snapTo(x.value + dx) } }
            }
    ) {
        LazyVerticalGrid(
            GridCells.Fixed(if (items.size > 4) 3 else 2), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 150.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt("${s.index + 1} OF ${s.cards.size} · " + (if (c.kind == CardKind.SET) "LOOK-ALIKES" else "CLUTTER"), T.Eyebrow)
                    Txt(c.title, T.H2)
                    val d = c.decision
                    if (d != null) {
                        val p = d.keep.values.flatten().count { it.best.judge == Judge.PHOTOGRAPHER }; val q = d.keep.values.flatten().count { it.best.judge == Judge.POSTER }
                        Txt("The judges would keep ${d.keep.size} of ${d.ids.size}. " + (if (p > 0 && q > 0) "Both found something worth saving." else if (q > 0) "The poster made the case." else "The photographer made the case.") + " Tap a shot to flip it.", T.Lead)
                    } else Txt(c.blurb + " Tap a shot to flip it.", T.Lead)
                }
            }
            items(items, key = { it.id }) { item -> PickTile(sc, item) }
            item(span = { GridItemSpan(maxLineSpan) }) { Txt("Swipe the card right to approve, left to keep everything.", T.Small, Modifier.fillMaxWidth().padding(top = 6.dp), align = TextAlign.Center) }
        }
        Stamp("APPROVE", W.Keep, (x.value / (widthPx * .25f)).coerceIn(0f, 1f) * (if (canApprove) 1f else .3f), Modifier.align(Alignment.TopStart).padding(start = 22.dp, top = 120.dp).rotate(-9f))
        Stamp("KEEP ALL", W.Ink, (-x.value / (widthPx * .25f)).coerceIn(0f, 1f), Modifier.align(Alignment.TopEnd).padding(end = 22.dp, top = 120.dp).rotate(9f))
    }
}

/** Lets the floating bar drive the card on screen without threading callbacks through every layer. */
object SessionActions { var approve: (() -> Unit)? = null; var keepAll: (() -> Unit)? = null }

@Composable
private fun Stamp(text: String, color: Color, a: Float, modifier: Modifier) {
    if (a <= 0.01f) return
    Box(modifier.alpha(a).clip(RoundedCornerShape(14.dp)).background(W.Bg.copy(alpha = .7f)).border(3.dp, color, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 8.dp)) { Txt(text, T.H3.copy(fontSize = 24.sp), color = color) }
}

@Composable
private fun PickTile(sc: SessionCard, item: MediaItem) {
    val keep = sc.keep[item.id] == true
    val scale by animateFloatAsState(if (keep) 1f else .92f, bouncy(), label = "s")
    val dim by animateFloatAsState(if (keep) 1f else .62f, tween(300), label = "d")
    val ring by animateColorAsState(if (keep) W.Keep else Color.Transparent, label = "ring")
    val spin by animateFloatAsState(if (keep) 0f else 90f, bouncy(), label = "spin")
    val shape = RoundedCornerShape(24.dp)
    Box(Modifier.aspectRatio(.75f).graphicsLayer { scaleX = scale; scaleY = scale }
        .press({ if (keep) sc.keep.remove(item.id) else sc.keep[item.id] = true }, label = item.name + if (keep) ", keeping" else ", to trash", scaleTo = .9f)
        .clip(shape).border(3.dp, ring, shape)) {
        Thumb(item, 512, Modifier.fillMaxSize().alpha(dim))
        Box(Modifier.align(Alignment.TopEnd).padding(9.dp).size(30.dp).rotate(spin).clip(CircleShape).background(if (keep) W.Keep else Color(0x9E0A0B10)), contentAlignment = Alignment.Center) {
            Glyph(if (keep) G.CHECK else G.X, if (keep) Color(0xFF04150E) else W.Trash, 16.dp)
        }
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (keep) {
                val reasons = sc.card.decision?.keep?.get(item.id)
                if (reasons != null && reasons.isNotEmpty()) {
                    for (r in reasons.take(3)) Pill(r.text, glyph = if (r.best.judge == Judge.PHOTOGRAPHER) G.CAMERA else G.HEART)
                    if (reasons.size > 3) Pill("+${reasons.size - 3} more")
                } else {
                    val notes = sc.card.reasons[item.id]
                    if (notes != null && notes.isNotEmpty()) for (n in notes.take(2)) Pill(n) else Pill(if (item.id in sc.card.keep) "Keep" else "Your pick")
                }
            } else Pill("Trash", fg = W.Trash)
            if (item.video) Pill(duration(item.durationMs) + " · " + bytesText(item.bytes), glyph = G.PLAY)
        }
    }
}

@Composable
private fun Review(vm: WinnowViewModel, s: Session) {
    val ids = remember(s) { s.trashIds() }
    val items = remember(ids) { ids.mapNotNull { vm.byId[it] } }
    LazyVerticalGrid(GridCells.Fixed(5), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 150.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt("REVIEW", T.Eyebrow)
                if (items.isEmpty()) { Txt("You kept everything", T.H2); Txt("Nothing to move to trash this time.", T.Lead) }
                else {
                    Row(verticalAlignment = Alignment.Bottom) { Txt("${countUp(items.size.toFloat()).toInt()}", T.Huge); Txt("to trash", T.H2, Modifier.padding(start = 8.dp, bottom = 8.dp), color = W.Muted) }
                    val k = s.keeperCount()
                    Txt("Frees about ${bytesText(items.sumOf { it.bytes })} once Android empties its trash in 30 days. Until then everything is recoverable." + (if (k > 0) " Your ${plural(k, "keeper")} also teach Winnow what you like." else ""), T.Lead)
                }
            }
        }
        items(items, key = { it.id }) { Thumb(it, 256, Modifier.aspectRatio(1f).clip(RoundedCornerShape(14.dp))) }
    }
}
