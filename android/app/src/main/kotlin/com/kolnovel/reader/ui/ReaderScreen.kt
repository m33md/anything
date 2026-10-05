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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import com.kolnovel.reader.data.ChapterRef
import com.kolnovel.reader.data.KolSource
import com.kolnovel.reader.data.NovelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.kolnovel.reader.data.ChapterContent
import com.kolnovel.reader.data.ChapterStore
import com.kolnovel.reader.data.LibraryStore
import com.kolnovel.reader.data.SettingsStore
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Remembers the last novel's chapter list, so turning chapters doesn't re-read it from disk. */
private object ChapterLists {
    var novelUrl: String? = null
    var chapters: List<ChapterRef> = emptyList()
}

/**
 * Previous/next from the novel's own chapter list (sorted by chapter number). The site's
 * prev/next links follow its publish order and can jump over dozens of chapters.
 */
private suspend fun withListNeighbours(source: KolSource, c: ChapterContent, fallbackNovelUrl: String): ChapterContent {
    val novelUrl = c.novelUrl ?: fallbackNovelUrl
    val list = withContext(Dispatchers.IO) {
        if (ChapterLists.novelUrl == novelUrl && ChapterLists.chapters.any { it.url == c.url }) return@withContext ChapterLists.chapters
        var chapters = NovelStore.load(novelUrl)?.chapters?.takeIf { l -> l.any { it.url == c.url } }
        if (chapters == null) {
            chapters = runCatching { source.details(novelUrl).also { NovelStore.save(it) }.chapters }.getOrNull()
        }
        if (chapters != null) {
            ChapterLists.novelUrl = novelUrl
            ChapterLists.chapters = chapters
            LibraryStore.noteChapterCount(novelUrl, chapters.size)
        }
        chapters
    } ?: return c
    val i = list.indexOfFirst { it.url == c.url }
    if (i < 0) return c
    return c.copy(prevUrl = list.getOrNull(i - 1)?.url, nextUrl = list.getOrNull(i + 1)?.url)
}

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
    val locked = services.readerLocked
    val listState = remember(screen.chapterUrl) { LazyListState(firstVisibleItemIndex = if (screen.startParagraph > 0) screen.startParagraph + 1 else 0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(screen.chapterUrl, reload) {
        error = null
        var loaded = ChapterStore.load(screen.chapterUrl)
        if (loaded == null) {
            try {
                loaded = services.source.chapter(screen.chapterUrl).also { ChapterStore.save(it) }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
        }
        if (loaded != null) {
            chapter = loaded
            chapter = withListNeighbours(services.source, loaded, screen.novel.url)
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

    // One volume press scrolls most of a screen, keeping a couple of lines for context.
    val pageStep = LocalConfiguration.current.screenHeightDp * LocalDensity.current.density * 0.8f
    DisposableEffect(chapter) {
        services.readerVolume = { forward ->
            scope.launch { listState.animateScrollBy(if (forward) pageStep else -pageStep) }
        }
        onDispose { services.readerVolume = null }
    }
    SideEffect { services.systemBars(!colors.dark, settings.readerKeepScreenOn, locked) }
    DisposableEffect(Unit) { onDispose { services.systemBars(!colors.dark, false, false) } }

    KolTheme(colors) {
        Box(
            Modifier.fillMaxSize().background(colors.background)
                .then(if (locked) Modifier else Modifier.statusBarsPadding().navigationBarsPadding())
        ) {
            val c = chapter
            when {
                c != null -> ChapterText(
                    c, listState, topPadding = if (locked) 36.dp else 86.dp,
                    onTap = { if (!locked) showBars = !showBars },
                    onOpen = ::open,
                )
                error != null -> ErrorBox(error!!, onRetry = { reload++ }, modifier = Modifier.align(Alignment.Center))
                else -> Loading(Modifier.align(Alignment.Center))
            }
            AnimatedVisibility(showBars && !locked, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
                ReaderTopBar(
                    c, screen, listState,
                    onSettings = { showSettings = !showSettings },
                    onLock = {
                        showSettings = false
                        services.readerLocked = true
                    },
                )
            }
            AnimatedVisibility(showSettings && !locked, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                ReaderSettingsPanel(onClose = { showSettings = false })
            }
            if (locked) {
                // The only thing left on screen: a faint lock in the corner. Tap it to bring the controls back.
                Icon(
                    Icons.Filled.Lock, "إلغاء القفل", tint = colors.muted,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).alpha(0.35f)
                        .clip(CircleShape).clickable {
                            services.readerLocked = false
                            showBars = true
                        }
                        .padding(8.dp).size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ChapterText(c: ChapterContent, listState: LazyListState, topPadding: Dp, onTap: () -> Unit, onOpen: (String?) -> Unit) {
    val colors = LocalAppColors.current
    val s = SettingsStore.settings
    val font = readerFontFamily(s.readerFont)
    val swipe = with(LocalDensity.current) { 110.dp.toPx() }
    val latestChapter by rememberUpdatedState(c)
    Box(
        Modifier.fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap)
            // Swipe sideways to change chapter: like turning an Arabic book's page, dragging right goes forward.
            .pointerInput(Unit) {
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        if (dx > swipe) onOpen(latestChapter.nextUrl)
                        else if (dx < -swipe) onOpen(latestChapter.prevUrl)
                    },
                ) { _, amount -> dx += amount }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 22.dp, top = topPadding, bottom = 40.dp),
            ) {
                item {
                    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        c.novelTitle?.let { Text(it, color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
                        Text(
                            c.title, color = colors.text, fontSize = (s.readerFontSize + 4).sp, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, fontFamily = font, lineHeight = ((s.readerFontSize + 4) * 1.5f).sp,
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
        FastScrollbar(listState)
    }
}

@Composable
private fun ReaderTopBar(c: ChapterContent?, screen: Screen.Reader, listState: LazyListState, onSettings: () -> Unit, onLock: () -> Unit) {
    val services = LocalServices.current
    val colors = LocalAppColors.current
    val total = listState.layoutInfo.totalItemsCount
    val progress = if (total <= 1) 0f else (listState.firstVisibleItemIndex.toFloat() / (total - 1)).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(8.dp).glass(colors, RoundedCornerShape(18.dp), strong = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") { services.nav.back() }
            Spacer(Modifier.width(2.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    c?.novelTitle ?: screen.novel.title, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(c?.title.orEmpty(), color = colors.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconBtn(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "الفصل السابق", enabled = c?.prevUrl != null) {
                c?.prevUrl?.let { services.nav.go(Screen.Reader(screen.novel, it)) }
            }
            IconBtn(Icons.AutoMirrored.Filled.List, "قائمة الفصول") {
                val novel = c?.novelUrl?.let { screen.novel.copy(url = it) } ?: screen.novel
                // Coming from the novel page: go back to it instead of opening it twice.
                val below = services.nav.stack.getOrNull(services.nav.stack.size - 2)
                if (below is Screen.Details && below.novel.url == novel.url) services.nav.back() else services.nav.go(Screen.Details(novel))
            }
            IconBtn(Icons.AutoMirrored.Filled.KeyboardArrowRight, "الفصل التالي", enabled = c?.nextUrl != null) {
                c?.nextUrl?.let { services.nav.go(Screen.Reader(screen.novel, it)) }
            }
            IconBtn(Icons.Filled.Lock, "قفل: إخفاء كل الأزرار", onClick = onLock)
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
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(7.dp).size(22.dp),
    )
}

@Composable
private fun ReaderSettingsPanel(onClose: () -> Unit) {
    val colors = LocalAppColors.current
    val s = SettingsStore.settings
    GlassPanel(Modifier.padding(8.dp).fillMaxWidth().heightIn(max = 460.dp), strong = true) {
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
            Gap(6)
            Text("الخط", color = colors.muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("cairo" to "Cairo", "system" to "خط النظام", "serif" to "خط كلاسيكي").forEach { (id, label) ->
                    GlassChip(label, s.readerFont == id) { SettingsStore.update { it.copy(readerFont = id) } }
                }
            }
            Gap(10)
            SettingSwitch("محاذاة النص من الجهتين", s.readerJustify) { v -> SettingsStore.update { it.copy(readerJustify = v) } }
            SettingSwitch("إبقاء الشاشة مضاءة أثناء القراءة", s.readerKeepScreenOn) { v -> SettingsStore.update { it.copy(readerKeepScreenOn = v) } }
            SettingSwitch("التمرير بأزرار الصوت", s.readerVolumeKeys) { v -> SettingsStore.update { it.copy(readerVolumeKeys = v) } }
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
