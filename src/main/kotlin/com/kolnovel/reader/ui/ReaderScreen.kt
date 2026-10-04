package com.kolnovel.reader.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kolnovel.reader.data.ChapterContent
import com.kolnovel.reader.data.ChapterStore
import com.kolnovel.reader.data.LibraryStore
import com.kolnovel.reader.data.SettingsStore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@Composable
fun ReaderScreen(screen: Screen.Reader) {
    val services = LocalServices.current
    val settings = SettingsStore.settings
    // The reader can have its own theme; empty means "same as the app".
    val colors = colorsFor(settings, settings.readerTheme)
    var chapter by remember(screen.chapterUrl) { mutableStateOf<ChapterContent?>(null) }
    var error by remember(screen.chapterUrl) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var showBars by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    val listState = remember(screen.chapterUrl) { LazyListState(firstVisibleItemIndex = if (screen.startParagraph > 0) screen.startParagraph + 1 else 0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(screen.chapterUrl, reload) {
        error = null
        val saved = ChapterStore.load(screen.chapterUrl)
        if (saved != null) {
            chapter = saved
        } else {
            try {
                chapter = services.source.chapter(screen.chapterUrl).also { ChapterStore.save(it) }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
        }
        // Fetch the next chapter quietly so turning the page is instant.
        chapter?.nextUrl?.let { next ->
            if (!ChapterStore.has(next)) runCatching { ChapterStore.save(services.source.chapter(next)) }
        }
    }

    // Remember where the reader is; reaching the end marks the chapter read.
    LaunchedEffect(chapter) {
        val c = chapter ?: return@LaunchedEffect
        val novelUrl = c.novelUrl ?: screen.novel.url
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            Triple((listState.firstVisibleItemIndex - 1).coerceAtLeast(0), last >= info.totalItemsCount - 1, info.totalItemsCount)
        }.debounce(600).collect { (paragraph, finished, total) ->
            if (total > 0) {
                LibraryStore.saveProgress(novelUrl, screen.novel.title.ifBlank { c.novelTitle.orEmpty() }, screen.novel.cover, c, paragraph, finished)
            }
        }
    }

    fun open(url: String?) {
        if (url != null) services.nav.go(Screen.Reader(screen.novel, url))
    }

    DisposableEffect(chapter) {
        services.readerKeys = { e ->
            when {
                e.key == Key.DirectionLeft && !e.isCtrlPressed -> { open(chapter?.nextUrl); true }
                e.key == Key.DirectionRight && !e.isCtrlPressed -> { open(chapter?.prevUrl); true }
                e.key == Key.Spacebar || e.key == Key.PageDown -> { scope.launch { listState.animateScrollBy(600f) }; true }
                e.key == Key.PageUp -> { scope.launch { listState.animateScrollBy(-600f) }; true }
                e.key == Key.DirectionDown -> { scope.launch { listState.animateScrollBy(120f) }; true }
                e.key == Key.DirectionUp -> { scope.launch { listState.animateScrollBy(-120f) }; true }
                e.key == Key.MoveHome -> { scope.launch { listState.scrollToItem(0) }; true }
                e.key == Key.MoveEnd -> { scope.launch { listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }; true }
                e.isCtrlPressed && (e.key == Key.Equals || e.key == Key.Plus || e.key == Key.NumPadAdd) -> {
                    SettingsStore.update { it.copy(readerFontSize = (it.readerFontSize + 1).coerceAtMost(40)) }; true
                }
                e.isCtrlPressed && (e.key == Key.Minus || e.key == Key.NumPadSubtract) -> {
                    SettingsStore.update { it.copy(readerFontSize = (it.readerFontSize - 1).coerceAtLeast(12)) }; true
                }
                else -> false
            }
        }
        onDispose { services.readerKeys = null }
    }

    KolTheme(colors) {
        Box(Modifier.fillMaxSize().background(colors.background)) {
            val c = chapter
            when {
                c != null -> ChapterText(c, listState, settings.readerWidth, onTap = { showBars = !showBars }, onOpen = ::open)
                error != null -> ErrorBox(error!!, onRetry = { reload++ }, modifier = Modifier.align(Alignment.Center))
                else -> Loading(Modifier.align(Alignment.Center))
            }
            AnimatedVisibility(showBars, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                ReaderTopBar(c, screen, listState, onSettings = { showSettings = !showSettings })
            }
            AnimatedVisibility(showSettings, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart)) {
                ReaderSettingsPanel(onClose = { showSettings = false })
            }
        }
    }
}

@Composable
private fun ChapterText(c: ChapterContent, listState: LazyListState, width: Int, onTap: () -> Unit, onOpen: (String?) -> Unit) {
    val colors = LocalAppColors.current
    val s = SettingsStore.settings
    val font = readerFontFamily(s.readerFont)
    Box(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        contentAlignment = Alignment.TopCenter,
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxHeight().widthIn(max = width.dp).fillMaxWidth(),
                contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 110.dp, bottom = 40.dp),
            ) {
                item {
                    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        c.novelTitle?.let { Text(it, color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                        Text(
                            c.title, color = colors.text, fontSize = (s.readerFontSize + 6).sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, fontFamily = font,
                        )
                    }
                }
                itemsIndexed(c.paragraphs) { _, p ->
                    Text(
                        p,
                        color = colors.text,
                        fontSize = s.readerFontSize.sp,
                        lineHeight = (s.readerFontSize * s.readerLineHeight).sp,
                        fontFamily = font,
                        textAlign = if (s.readerJustify) TextAlign.Justify else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth().padding(bottom = s.readerParagraphGap.dp),
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 30.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    ) {
                        if (c.prevUrl != null) GlassButton("السابق", Icons.AutoMirrored.Filled.KeyboardArrowLeft, onClick = { onOpen(c.prevUrl) })
                        if (c.nextUrl != null) AccentButton("الفصل التالي", Icons.AutoMirrored.Filled.KeyboardArrowRight, { onOpen(c.nextUrl) })
                        else Text("هذا أخر فصل منشور.", color = colors.muted, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderTopBar(c: ChapterContent?, screen: Screen.Reader, listState: LazyListState, onSettings: () -> Unit) {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    val total = listState.layoutInfo.totalItemsCount
    val progress = if (total <= 1) 0f else (listState.firstVisibleItemIndex.toFloat() / (total - 1)).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(12.dp).glass(colors, RoundedCornerShape(18.dp), strong = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") { services.nav.back() }
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    c?.novelTitle ?: screen.novel.title, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(c?.title.orEmpty(), color = colors.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconBtn(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "الفصل السابق", enabled = c?.prevUrl != null) {
                c?.prevUrl?.let { services.nav.go(Screen.Reader(screen.novel, it)) }
            }
            IconBtn(Icons.AutoMirrored.Filled.List, "قائمة الفصول") {
                val novel = c?.novelUrl?.let { screen.novel.copy(url = it) } ?: screen.novel
                services.nav.go(Screen.Details(novel))
            }
            IconBtn(Icons.AutoMirrored.Filled.KeyboardArrowRight, "الفصل التالي", enabled = c?.nextUrl != null) {
                c?.nextUrl?.let { services.nav.go(Screen.Reader(screen.novel, it)) }
            }
            Spacer(Modifier.width(6.dp))
            IconBtn(Icons.Filled.Settings, "إعدادات القراءة", onClick = onSettings)
        }
        LinearProgressIndicator(
            progress = { progress }, color = colors.accent, trackColor = colors.glassFill,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 6.dp),
        )
    }
}

@Composable
fun IconBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LocalAppColors.current
    Icon(
        icon, label,
        tint = if (enabled) colors.text else colors.muted.copy(alpha = 0.4f),
        modifier = Modifier.clip(RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick) else Modifier)
            .padding(7.dp).size(22.dp),
    )
}

@Composable
private fun ReaderSettingsPanel(onClose: () -> Unit) {
    val colors = LocalAppColors.current
    val s = SettingsStore.settings
    GlassPanel(Modifier.padding(16.dp).width(330.dp), strong = true) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("إعدادات القراءة", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                IconBtn(Icons.Filled.Close, "إغلاق", onClick = onClose)
            }
            LabeledSlider("حجم الخط", s.readerFontSize.toFloat(), 12f..40f, "${s.readerFontSize}") { v ->
                SettingsStore.update { it.copy(readerFontSize = v.toInt()) }
            }
            LabeledSlider("تباعد الأسطر", s.readerLineHeight, 1.2f..2.8f, "%.1f".format(s.readerLineHeight)) { v ->
                SettingsStore.update { it.copy(readerLineHeight = v) }
            }
            LabeledSlider("المسافة بين الفقرات", s.readerParagraphGap.toFloat(), 0f..40f, "${s.readerParagraphGap}") { v ->
                SettingsStore.update { it.copy(readerParagraphGap = v.toInt()) }
            }
            LabeledSlider("عرض النص", s.readerWidth.toFloat(), 500f..1400f, "${s.readerWidth}") { v ->
                SettingsStore.update { it.copy(readerWidth = v.toInt()) }
            }
            Gap(6)
            Text("الخط", color = colors.muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("cairo" to "Cairo", "system" to "خط النظام", "serif" to "خط كلاسيكي").forEach { (id, label) ->
                    GlassChip(label, s.readerFont == id) { SettingsStore.update { it.copy(readerFont = id) } }
                }
            }
            Gap(10)
            SettingSwitch("محاذاة النص من الجهتين", s.readerJustify) { v -> SettingsStore.update { it.copy(readerJustify = v) } }
            Gap(6)
            Text("ألوان القارئ", color = colors.muted, fontSize = 13.sp)
            ThemeSwatches(selected = s.readerTheme, includeFollow = true) { id -> SettingsStore.update { it.copy(readerTheme = id) } }
        }
    }
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    val colors = LocalAppColors.current
    Column(Modifier.padding(vertical = 2.dp)) {
        Row {
            Text(label, color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(display, color = colors.muted, fontSize = 13.sp)
        }
        Slider(
            value, onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.glassFillStrong),
        )
    }
}

@Composable
fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = colors.onAccent),
        )
    }
}
