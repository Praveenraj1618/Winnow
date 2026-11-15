package app.winnow.gallery.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.winnow.gallery.Moment
import app.winnow.gallery.Plan
import app.winnow.gallery.WinnowViewModel
import app.winnow.gallery.data.MediaItem
import app.winnow.gallery.engine.Features
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class Section(val title: String, val items: List<MediaItem>)

private val FACETS = listOf("Moments", "Months", "Folders", "Type", "Quality", "Size")

fun sectionsFor(facet: Int, media: List<MediaItem>, plan: Plan?, loved: Set<Long>): List<Section> {
    val out = ArrayList<Section>()
    fun push(t: String, l: List<MediaItem>) { if (l.isNotEmpty()) out.add(Section(t, l)) }
    when (facet) {
        0 -> (plan?.moments ?: WinnowViewModel.moments(media)).forEach { push(it.name, it.items) }
        1 -> { val f = SimpleDateFormat("MMMM yyyy", Locale.getDefault()); media.groupBy { f.format(Date(it.time)) }.forEach { (k, v) -> push(k, v) } }
        2 -> media.groupBy { it.bucket.ifEmpty { "Other" } }.entries.sortedByDescending { it.value.size }.forEach { push(it.key, it.value) }
        3 -> {
            push("Loved", media.filter { it.id in loved || it.favorite })
            push("Photos", media.filter { !it.video && !it.screenshot })
            push("Videos", media.filter { it.video })
            push("Look-alike sets", media.filter { plan?.setSize?.containsKey(it.id) == true })
            push("Screenshots", media.filter { it.screenshot })
        }
        4 -> {
            val solo = plan?.solo ?: emptyMap()
            val scored = media.filter { !it.video && !it.screenshot && solo.containsKey(it.id) }.sortedByDescending { solo[it.id] ?: 0f }
            val a = maxOf(1, scored.size / 4); val b = maxOf(a, scored.size - scored.size / 4)
            push("Top shots", scored.take(a)); push("Solid", scored.subList(minOf(a, scored.size), minOf(b, scored.size))); push("Weakest", scored.drop(b))
            push("Not read yet", media.filter { !it.video && !it.screenshot && !solo.containsKey(it.id) })
        }
        else -> {
            val s = media.sortedByDescending { it.bytes }
            push("Over 50 MB", s.filter { it.bytes > 50_000_000L }); push("10 to 50 MB", s.filter { it.bytes in 10_000_001L..50_000_000L })
            push("3 to 10 MB", s.filter { it.bytes in 3_000_001L..10_000_000L }); push("Under 3 MB", s.filter { it.bytes <= 3_000_000L })
        }
    }
    return out
}

fun duration(ms: Long): String { val s = ms / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(vm: WinnowViewModel) {
    val media = vm.media; val plan = vm.plan; val loved = vm.loved
    var facet by rememberSaveable { mutableIntStateOf(0) }
    val sections = remember(media, plan, facet, loved) { sectionsFor(facet, media, plan, loved) }
    if (media.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            Glass { Txt("No photos found", T.H2); Txt("Winnow reads the photos and videos on this phone. Take a few, or check the access you granted.", T.Lead) }
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 132.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val hl = plan?.highlights ?: emptyList()
        if (hl.size > 1) item(key = "highlights", span = { GridItemSpan(maxLineSpan) }) { Highlights(hl) { item -> vm.viewer = media to media.indexOf(item).coerceAtLeast(0) } }
        item(key = "facets", span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(FACETS.indices.toList()) { i -> Chip(FACETS[i], facet == i, { facet = i }) }
            }
        }
        for ((si, s) in sections.withIndex()) {
            item(key = "h:$facet:$si", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(top = 12.dp, bottom = 4.dp).animateItem(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Txt(s.title, T.H3, Modifier.weight(1f, fill = false), maxLines = 1)
                    Txt("${s.items.size} · ${bytesText(s.items.sumOf { it.bytes })}", T.Small)
                }
            }
            items(s.items, key = { "$facet:$si:${it.id}" }) { item ->
                Box(Modifier.animateItem().aspectRatio(1f).press({ vm.viewer = s.items to s.items.indexOf(item) }, label = item.name).clip(RoundedCornerShape(18.dp))) {
                    Thumb(item, 256, Modifier.fillMaxSize())
                    val n = plan?.setSize?.get(item.id)
                    if (item.video) Pill(duration(item.durationMs), Modifier.align(Alignment.TopEnd).padding(6.dp), glyph = G.PLAY)
                    else if (n != null) Pill("×$n", Modifier.align(Alignment.TopEnd).padding(6.dp))
                    if (item.id in loved || item.favorite) Glyph(G.HEART_FULL, Color(0xFFFF7D8E), 16.dp, Modifier.align(Alignment.BottomStart).padding(7.dp))
                }
            }
        }
    }
}

/** The best shot of each recent moment, as a row of large cards you can flick through. */
@Composable
private fun Highlights(hl: List<Pair<Moment, MediaItem>>, open: (MediaItem) -> Unit) {
    val state = rememberPagerState { hl.size }
    HorizontalPager(state, Modifier.fillMaxWidth().padding(top = 4.dp), pageSpacing = 12.dp, contentPadding = PaddingValues(end = 104.dp)) { page ->
        val (m, item) = hl[page]
        val off = abs((state.currentPage - page) + state.currentPageOffsetFraction).coerceIn(0f, 1f)
        Box(
            Modifier.fillMaxWidth().aspectRatio(.8f)
                .graphicsLayer { val s = 1f - .12f * off; scaleX = s; scaleY = s; alpha = 1f - .4f * off; rotationZ = -3f * ((state.currentPage - page) + state.currentPageOffsetFraction).coerceIn(-1f, 1f) }
                .press({ open(item) }, label = "Best shot, " + m.name, scaleTo = .96f).clip(RoundedCornerShape(30.dp))
        ) {
            Thumb(item, 720, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .72f)), startY = 380f)))
            Pill("Best shot", Modifier.padding(14.dp), glyph = G.SPARK)
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(m.name, T.H3, color = Color.White, maxLines = 2)
                Txt(plural(m.items.size, "item"), T.Small, color = Color.White.copy(alpha = .82f))
            }
        }
    }
}

fun traits(f: Features?, plan: Plan?, sharpHint: Float): List<String> {
    if (f == null) return listOf("Not read yet")
    val out = ArrayList<String>()
    if (f.faces.isNotEmpty()) {
        out.add(plural(f.faces.size, "face"))
        if (f.faces.all { it.eyes > .6f }) out.add("Eyes open") else out.add("A blink")
        if (f.faces.any { it.smile > .6f }) out.add("Smiling")
    }
    if (f.expo > .75f) out.add("Good light")
    if (f.hasSubject && f.bgExpo > .7f) out.add("Clear backdrop")
    if (f.hasSubject && f.subjectHeight > .6f && !f.subjectCut) out.add("Full length")
    plan?.setSize?.get(f.id)?.let { out.add("1 of $it look-alikes") }
    return out.take(5)
}

/** Full-screen photo. Swipe sideways for the next one, drag down to close. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Viewer(vm: WinnowViewModel, list: List<MediaItem>, start: Int, onClose: () -> Unit, onTrash: (MediaItem) -> Unit) {
    if (list.isEmpty()) { LaunchedEffect(Unit) { onClose() }; return }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, list.size - 1)) { list.size }
    val item = list[pager.currentPage.coerceIn(0, list.size - 1)]
    val f = vm.feats[item.id]
    LaunchedEffect(item.id, f) { vm.viewerTint = f?.tint }
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, bouncy()) }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = (enter.value * 1.6f).coerceIn(0f, 1f) * (1f - drag.value / 1600f).coerceIn(.35f, 1f) }) { Ambient(List(3) { Color(f?.tint ?: 0xFF3D4A7A.toInt()) }) }
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 64.dp, bottom = 218.dp), pageSpacing = 16.dp, key = { list[it].id }) { page ->
            val it = list[page]
            Box(
                Modifier.fillMaxSize().padding(horizontal = 10.dp)
                    .graphicsLayer {
                        val d = if (page == pager.currentPage) drag.value else 0f
                        translationY = d; val s = (.86f + .14f * enter.value) * (1f - d / 2600f); scaleX = s; scaleY = s; alpha = enter.value.coerceIn(0f, 1f)
                    }
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = { if (drag.value > 240f) onClose() else scope.launch { drag.animateTo(0f, bouncy()) } },
                            onDragCancel = { scope.launch { drag.animateTo(0f, bouncy()) } }
                        ) { _, dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } }
                    },
                contentAlignment = Alignment.Center
            ) {
                FullImage(it)
                if (it.video) {
                    val ctx = LocalContext.current
                    Btn("Play video", {
                        try { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(it.uri, "video/*").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)) } catch (e: Exception) { vm.toast = "No app on this phone can play that video." }
                    }, glyph = G.PLAY, primary = true)
                }
            }
        }
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt("${pager.currentPage + 1} / ${list.size}", T.Strong, Modifier.weight(1f), color = Color.White.copy(alpha = .85f))
            RoundBtn(G.X, "Close", onClose)
        }
        Glass(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp).fillMaxWidth()
            .graphicsLayer { translationY = (1f - enter.value) * 500f }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val solo = vm.plan?.solo?.get(item.id)
                if (solo != null && !item.video && !item.screenshot) Ring(solo, Modifier.size(54.dp)) { Txt("${(solo * 100).toInt()}", T.H3.copy(fontSize = 17.sp)) }
                Column(Modifier.weight(1f)) {
                    Txt(item.name, T.H3.copy(fontSize = 19.sp), maxLines = 1)
                    Txt(SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(item.time)) + " · " + bytesText(item.bytes) + (if (item.w > 0) " · ${item.w}×${item.h}" else ""), T.Small, maxLines = 1)
                }
            }
            if (!item.video) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in traits(f, vm.plan, 0f)) Pill(t)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val on = item.id in vm.loved
                Btn(if (on) "Loved" else "Love it", { vm.toggleLove(item) }, Modifier.weight(1f), glyph = if (on) G.HEART_FULL else G.HEART, tint = if (on) Color(0xFFFF7D8E) else Color.Unspecified)
                Btn("Trash", { onTrash(item) }, Modifier.weight(1f), glyph = G.BIN)
            }
        }
    }
}

@Composable
private fun FullImage(item: MediaItem) {
    val ctx = LocalContext.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.id) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.winnow.gallery.data.Thumbs.load(ctx, item, 512) }?.asImageBitmap()
        if (!item.video && app.winnow.gallery.data.Thumbs.source == null) {
            val full = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.winnow.gallery.data.Thumbs.decode(ctx, item.uri, 1800) }
            if (full != null) value = full.asImageBitmap()
        }
    }
    val b = bmp
    if (b != null) androidx.compose.foundation.Image(b, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
}
