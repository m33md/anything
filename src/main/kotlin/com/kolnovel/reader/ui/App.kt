package com.kolnovel.reader.ui

import androidx.compose.material.icons.filled.AccountCircle
import com.kolnovel.reader.data.LibrarySync
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kolnovel.reader.data.Downloads
import com.kolnovel.reader.data.KolSource
import com.kolnovel.reader.data.NovelSummary
import com.kolnovel.reader.data.SettingsStore

enum class Tab(val label: String, val icon: ImageVector) {
    Home("الرئيسية", Icons.Filled.Home),
    Browse("تصفح", Icons.AutoMirrored.Filled.List),
    Library("مكتبتي", Icons.Filled.Favorite),
    Downloads("التحميلات", DownloadIcon),
    Settings("الإعدادات", Icons.Filled.Settings),
}

sealed interface Screen {
    data class Main(val tab: Tab) : Screen
    data class Search(val query: String) : Screen
    /** A genre or type page from the site, e.g. https://kolnovel.com/genre/action/. */
    data class Listing(val title: String, val url: String) : Screen
    data class Details(val novel: NovelSummary) : Screen
    data class Reader(val novel: NovelSummary, val chapterUrl: String, val startParagraph: Int = 0) : Screen
}

class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Main(Tab.Home))
    val current: Screen get() = stack.last()

    fun go(screen: Screen) {
        if (screen is Screen.Main) {
            stack.clear()
            stack.add(screen)
        } else if (stack.last() != screen) {
            // Opening a chapter from inside the reader replaces it instead of piling up.
            if (screen is Screen.Reader && stack.last() is Screen.Reader) stack.removeAt(stack.lastIndex)
            stack.add(screen)
        }
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}

class AppServices(val source: KolSource = KolSource()) {
    val downloads = Downloads(source)
    val nav = Navigator()
    /** Set by the reader so arrow keys turn chapters. */
    var readerKeys: ((KeyEvent) -> Boolean)? = null
    /** Reader lock: only the text shows. Kept here so it stays on while turning chapters. */
    var readerLocked by mutableStateOf(false)
}

val LocalServices = staticCompositionLocalOf<AppServices> { error("no services") }

private val tabKeys = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five)

fun handleGlobalKey(services: AppServices, event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    services.readerKeys?.let { if (it(event)) return true }
    return when {
        event.key == Key.Escape || (event.isAltPressed && event.key == Key.DirectionLeft) || event.key == Key.Back -> services.nav.back()
        event.isCtrlPressed && event.key == Key.F -> { services.nav.go(Screen.Search("")); true }
        event.isCtrlPressed && event.key in tabKeys -> {
            Tab.entries.getOrNull(tabKeys.indexOf(event.key))?.let { services.nav.go(Screen.Main(it)) }
            true
        }
        else -> false
    }
}

@Composable
fun App(services: AppServices) {
    val settings = SettingsStore.settings
    val colors = colorsFor(settings)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalServices provides services) {
        KolTheme(colors) {
            val screen = services.nav.current
            val reading = screen is Screen.Reader
            LaunchedEffect(reading) { if (!reading) services.readerLocked = false }
            LiveBackground(enabled = settings.liveBackground && !reading, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    if (!reading) TopBar(services)
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        modifier = Modifier.fillMaxSize(),
                        contentKey = { it.toString() },
                    ) { s ->
                        when (s) {
                            is Screen.Main -> when (s.tab) {
                                Tab.Home -> HomeScreen()
                                Tab.Browse -> BrowseScreen()
                                Tab.Library -> LibraryScreen()
                                Tab.Downloads -> DownloadsScreen()
                                Tab.Settings -> SettingsScreen()
                            }
                            is Screen.Search -> SearchScreen(s.query)
                            is Screen.Listing -> ListingScreen(s.title, s.url)
                            is Screen.Details -> DetailsScreen(s.novel)
                            is Screen.Reader -> ReaderScreen(s)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(services: AppServices) {
    val colors = LocalAppColors.current
    val nav = services.nav
    val current = nav.current
    // No card of its own: the bar sits straight on the app background so it reads as part of the page.
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (nav.stack.size > 1) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = colors.text,
                modifier = Modifier.clip(CircleShape).pointerHoverIcon(PointerIcon.Hand)
                    .clickable { nav.back() }.padding(6.dp).size(22.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Image(
            painterResource("app_icon.png"), "ملوك الروايات",
            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).pointerHoverIcon(PointerIcon.Hand).clickable { nav.go(Screen.Main(Tab.Home)) },
        )
        Spacer(Modifier.width(10.dp))
        Text("ملوك", color = colors.accent, fontWeight = FontWeight.Black, fontSize = 22.sp)
        Text(" الروايات", color = colors.text, fontWeight = FontWeight.Black, fontSize = 22.sp)
        Spacer(Modifier.width(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tab.entries.forEach { tab ->
                val selected = current is Screen.Main && current.tab == tab
                Row(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (selected) colors.accent else colors.glassFill.copy(alpha = 0f))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { nav.go(Screen.Main(tab)) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(tab.icon, null, tint = if (selected) colors.onAccent else colors.muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tab.label, color = if (selected) colors.onAccent else colors.text, fontWeight = FontWeight.SemiBold)
                    val active = services.downloads.activeCount
                    if (tab == Tab.Downloads && active > 0) {
                        Spacer(Modifier.width(6.dp))
                        Pill("$active", if (selected) colors.onAccent else colors.accent, if (selected) colors.accent else colors.onAccent)
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Filled.AccountCircle, "الحساب والمزامنة", tint = if (LibrarySync.folder != null) colors.accent else colors.muted,
            modifier = Modifier.clip(CircleShape).pointerHoverIcon(PointerIcon.Hand)
                .clickable { nav.go(Screen.Main(Tab.Settings)) }.padding(6.dp).size(26.dp),
        )
        Spacer(Modifier.width(8.dp))
        SearchBox(initial = (current as? Screen.Search)?.query.orEmpty()) { q ->
            if (q.isNotBlank()) nav.go(Screen.Search(q.trim()))
        }
    }
}

@Composable
fun SearchBox(initial: String, modifier: Modifier = Modifier, onSearch: (String) -> Unit) {
    val colors = LocalAppColors.current
    var text by remember(initial) { mutableStateOf(initial) }
    Row(
        modifier.width(300.dp).glass(colors, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = colors.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text("ابحث عن رواية...", color = colors.muted, fontSize = 14.sp)
            BasicTextField(
                text, { text = it }, singleLine = true,
                textStyle = TextStyle(color = colors.text, fontSize = 14.sp, fontFamily = Cairo),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(text) }),
                modifier = Modifier.fillMaxWidth().onEnter { onSearch(text) },
            )
        }
    }
}

/** Desktop text fields don't always fire IME actions on Enter, so catch the key too. */
fun Modifier.onEnter(action: () -> Unit): Modifier = this.then(
    Modifier.onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { action(); true } else false
    }
)

@Composable
fun ScreenPadding(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 16.dp)) { content() }
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))
