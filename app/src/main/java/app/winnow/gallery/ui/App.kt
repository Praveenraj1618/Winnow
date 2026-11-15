package app.winnow.gallery.ui

import android.app.Activity
import android.content.IntentSender
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.winnow.gallery.WinnowViewModel
import app.winnow.gallery.engine.CardKind
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class Tab(val title: String, val label: String, val glyph: Int) {
    LIBRARY("Library", "Library", G.GRID), DECLUTTER("Declutter", "Declutter", G.SPARK), TASTE("Your taste", "Taste", G.HEART), TRASH("Trash", "Trash", G.BIN)
}

@Composable
fun WinnowApp(vm: WinnowViewModel, requestAccess: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.LIBRARY) }
    var tune by remember { mutableStateOf(false) }
    var confetti by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { vm.onSystemResult(it.resultCode == Activity.RESULT_OK) }
    val launch: (IntentSender) -> Unit = { try { launcher.launch(IntentSenderRequest.Builder(it).build()) } catch (e: Exception) { vm.toast = "Android could not open its confirmation." } }
    val session = vm.session; val viewer = vm.viewer; val plan = vm.plan

    // Light for the stage, taken from whatever is in view.
    val ambient: List<Color> = run {
        fun three(ids: List<Long>): List<Color>? { val c = ids.mapNotNull { vm.feats[it]?.tint }.distinct().take(3).map { Color(it) }; return if (c.isEmpty()) null else List(3) { c[it % c.size] } }
        val vt = vm.viewerTint
        when {
            viewer != null && vt != null -> List(3) { Color(vt) }
            tab == Tab.TRASH -> listOf(Color(0xFF8A2F45), Color(0xFF3B2F6B), Color(0xFF243A5C))
            tab == Tab.DECLUTTER && session != null && !session.review -> three(session.cards[session.index].card.ids)
            else -> three(plan?.highlights?.map { it.second.id } ?: emptyList())
        } ?: W.Brand
    }

    BackHandler(enabled = viewer != null || tune || session != null) {
        if (viewer != null) { vm.viewer = null; vm.viewerTint = null } else if (tune) tune = false else if (session != null) { if (session.review || session.index > 0) vm.back() else vm.leaveSession() }
    }

    Box(Modifier.fillMaxSize()) {
        Ambient(ambient)
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            if (!vm.hasAccess) Welcome(requestAccess)
            else {
                TopBar(vm, tab, session != null, { tune = true })
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    AnimatedContent(tab, transitionSpec = { (fadeIn(tween(260)) + slideInVertically(bouncy()) { it / 14 }) togetherWith fadeOut(tween(140)) }, label = "tab") { t ->
                        when (t) {
                            Tab.LIBRARY -> LibraryScreen(vm)
                            Tab.DECLUTTER -> DeclutterScreen(vm, ambient, { tune = true }, { confetti++ })
                            Tab.TASTE -> TasteScreen(vm, ambient)
                            Tab.TRASH -> TrashScreen(vm) { vm.requestRestore(it, launch) }
                        }
                    }
                }
            }
        }

        // Floating dock, swapped for the session's action bar while you are judging.
        val inSession = tab == Tab.DECLUTTER && session != null
        Box(Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 14.dp, vertical = 14.dp)) {
            AnimatedVisibility(vm.hasAccess && !inSession && viewer == null, enter = slideInVertically(bouncy()) { it * 2 } + fadeIn(), exit = slideOutVertically { it * 2 } + fadeOut()) { Dock(tab, vm) { tab = it } }
            AnimatedVisibility(inSession && viewer == null, enter = slideInVertically(bouncy()) { it * 2 } + fadeIn(), exit = slideOutVertically { it * 2 } + fadeOut()) {
                if (session != null) ActionBar(vm, session) { vm.requestTrash(session.trashIds(), session, launch) }
            }
        }

        AnimatedVisibility(viewer != null, enter = fadeIn(tween(160)), exit = fadeOut(tween(200)) + scaleOut(targetScale = .92f)) {
            val v = viewer ?: remember { vm.viewer } ?: return@AnimatedVisibility
            Viewer(vm, v.first, v.second, { vm.viewer = null; vm.viewerTint = null }) { item -> vm.viewer = null; vm.viewerTint = null; vm.requestTrash(listOf(item.id), null, launch) }
        }

        // Tuning sheet
        AnimatedVisibility(tune, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)).clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClickLabel = "Close tuning") { tune = false })
        }
        AnimatedVisibility(tune, Modifier.align(Alignment.BottomCenter), enter = slideInVertically(bouncy()) { it }, exit = slideOutVertically(tween(220)) { it }) {
            val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
            Box(Modifier.fillMaxWidth().fillMaxHeight(.9f).clip(shape).background(Color(0xF216171F)).border(1.dp, W.Line, shape).windowInsetsPadding(WindowInsets.navigationBars)
                .clickable(remember { MutableInteractionSource() }, null) { }) { TuneSheet(vm) { tune = false } }
        }

        if (confetti > 0) key(confetti) { Confetti(ambient) }

        val busy = vm.busy
        if (busy != null) Box(Modifier.fillMaxSize().background(W.Bg.copy(alpha = .78f)).clickable(remember { MutableInteractionSource() }, null) { }, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) { Spinner(Modifier.size(72.dp)); Txt(busy, T.H3) }
        }

        val toast = vm.toast
        LaunchedEffect(toast) { if (toast != null) { delay(2800); vm.toast = null } }
        AnimatedVisibility(toast != null, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 104.dp, start = 16.dp, end = 16.dp),
            enter = slideInVertically(bouncy()) { it } + fadeIn() + scaleIn(initialScale = .8f), exit = fadeOut() + scaleOut(targetScale = .9f)) {
            Box(Modifier.clip(RoundedCornerShape(50)).background(W.Paper).padding(horizontal = 18.dp, vertical = 11.dp)) { Txt(toast ?: "", T.Button.copy(color = W.OnPaper), align = TextAlign.Center) }
        }
    }
}

@Composable
private fun TopBar(vm: WinnowViewModel, tab: Tab, inSession: Boolean, onTune: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                val inf = rememberInfiniteTransition(label = "beat")
                val s by inf.animateFloat(1f, 1.5f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "s")
                Box(Modifier.size(8.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape).background(Brush.linearGradient(listOf(W.Brand[0], W.Brand[1]))))
                Txt("WINNOW", T.Eyebrow)
            }
            AnimatedContent(tab, transitionSpec = { (slideInVertically(bouncy()) { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut(tween(120))) }, label = "title") { Txt(it.title, T.Title, maxLines = 1) }
            val items = vm.media.size; val held = vm.trash.sumOf { it.bytes }
            Txt(plural(items, "item") + " · " + bytesText(vm.media.sumOf { it.bytes }) + (if (held > 0) " · trash holds " + bytesText(held) else ""), T.Small, maxLines = 1)
        }
        if (inSession && tab == Tab.DECLUTTER) RoundBtn(G.X, "Leave session", { vm.leaveSession(); vm.toast = "Session closed. Nothing changed." })
        else if (tab == Tab.DECLUTTER) RoundBtn(G.TUNE, "Tune the declutter", onTune)
    }
}

@Composable
private fun Dock(tab: Tab, vm: WinnowViewModel, pick: (Tab) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    BoxWithConstraints(Modifier.widthIn(max = 430.dp).fillMaxWidth().clip(shape).background(W.Dock).border(1.dp, W.Line, shape).padding(6.dp)) {
        val tabs = Tab.entries; val w = maxWidth / tabs.size
        val x by animateDpAsState(w * tab.ordinal, bouncy(), label = "ind")
        Box(Modifier.offset(x = x).width(w).height(54.dp).clip(RoundedCornerShape(22.dp)).background(W.Glass2))
        Row {
            for (t in tabs) {
                val on = t == tab
                val lift by animateFloatAsState(if (on) 1f else 0f, bouncy(), label = "lift")
                Box(Modifier.width(w).height(54.dp).press({ pick(t) }, label = t.label, role = Role.Tab, scaleTo = .88f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Glyph(t.glyph, if (on) W.Ink else W.Muted, 22.dp, Modifier.graphicsLayer { translationY = -2f * lift; scaleX = 1f + .14f * lift; scaleY = 1f + .14f * lift })
                        Txt(t.label, T.Pill.copy(color = if (on) W.Ink else W.Muted))
                    }
                    val n = when (t) { Tab.DECLUTTER -> vm.plan?.cards?.size ?: 0; Tab.TRASH -> vm.trash.size; else -> 0 }
                    if (n > 0) Box(Modifier.align(Alignment.TopCenter).offset(x = 16.dp, y = 2.dp).clip(RoundedCornerShape(9.dp)).background(W.Paper).padding(horizontal = 5.dp, vertical = 1.dp)) { Txt(if (n > 99) "99+" else "$n", T.Pill.copy(color = W.OnPaper)) }
                }
            }
        }
    }
}

@Composable
private fun ActionBar(vm: WinnowViewModel, s: app.winnow.gallery.Session, commit: () -> Unit) {
    val shape = RoundedCornerShape(30.dp)
    Row(Modifier.widthIn(max = 430.dp).fillMaxWidth().clip(shape).background(W.Dock).border(1.dp, W.Line, shape).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundBtn(G.BACK, "Back", { vm.back() }, enabled = s.review || s.index > 0)
        if (s.review) {
            val n = s.trashIds().size
            if (n == 0) Btn("Done", { vm.leaveSession() }, Modifier.weight(1f), primary = true)
            else { Btn("Discard", { vm.leaveSession(); vm.toast = "Session discarded. Nothing changed." }, Modifier.weight(1f)); Btn("Approve", commit, Modifier.weight(1f), primary = true) }
        } else {
            val n = s.cards[s.index].trash().size
            Btn("Keep all", { SessionActions.keepAll?.invoke() ?: vm.decide(false) }, Modifier.weight(1f))
            Btn("Approve · trash $n", { SessionActions.approve?.invoke() ?: vm.decide(true) }, Modifier.weight(1.5f), primary = true, enabled = n > 0)
        }
    }
}

/** A short burst of coloured pieces when a session is approved. */
@Composable
private fun Confetti(colors: List<Color>) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(1700, easing = LinearEasing)) }
    if (t.value >= 1f) return
    val parts = remember {
        val r = Random(System.nanoTime()); val pal = colors + listOf(W.Keep, Color.White, Color(0xFFFFD166))
        List(90) { val a = r.nextFloat() * 6.283f; val v = .25f + r.nextFloat() * .75f; floatArrayOf(cos(a) * v, sin(a) * v - .45f, 3f + r.nextFloat() * 6f, it.toFloat() % pal.size, r.nextFloat()) } to pal
    }
    Canvas(Modifier.fillMaxSize()) {
        val p = t.value; val o = Offset(size.width / 2, size.height * .3f); val reach = size.minDimension * .9f
        for (q in parts.first) {
            val x = o.x + q[0] * reach * p; val y = o.y + q[1] * reach * p + 1.6f * reach * p * p
            val col = parts.second[q[3].toInt()].copy(alpha = (1f - p * p).coerceIn(0f, 1f)); val sz = q[2].dp.toPx()
            if (q[4] > .5f) drawCircle(col, sz * .6f, Offset(x, y)) else drawRect(col, Offset(x - sz, y - sz * .5f), Size(sz * 2, sz))
        }
    }
}
