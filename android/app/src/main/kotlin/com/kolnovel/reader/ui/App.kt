package com.kolnovel.reader.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
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
    Downloads("التنزيلات", KolIcons.Download),
    Settings("الإعدادات", Icons.Filled.Settings),
}

sealed interface Screen {
    data class Main(val tab: Tab) : Screen
    data class Search(val query: String) : Screen
    /** A genre or type page from the site, e.g. https://kolnovel.com/genre/action/. */
    data class Listing(val title: String, val url: String) : Screen
    data class Details(val novel: NovelSummary) : Screen
    data class Reader(val novel: NovelSummary, val chapterUrl: String, val startParagraph: Int = 0) : Screen
    /** The site in a small browser, to pass Cloudflare's "are you human" check. */
    data object Verify : Screen
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
        if (stack.size <= 1) {
            // Back from another tab goes to the home tab first, like most phone apps.
            if (current != Screen.Main(Tab.Home)) {
                go(Screen.Main(Tab.Home))
                return true
            }
            return false
        }
        stack.removeAt(stack.lastIndex)
        return true
    }
}

class AppServices(val source: KolSource) {
    val nav = Navigator()
    val downloads = Downloads
    /** Asks for the notification permission (Android 13+), so download progress can show. */
    var askNotifications: () -> Unit = {}
    /** (light background, keep screen on) -> sets the system bar icon colors and the screen timeout. */
    var systemBars: (Boolean, Boolean) -> Unit = { _, _ -> }
    /** Set by the reader: volume down scrolls forward, volume up back. */
    var readerVolume: ((Boolean) -> Unit)? = null
}

val LocalServices = staticCompositionLocalOf<AppServices> { error("no services") }

@Composable
fun App(services: AppServices) {
    val settings = SettingsStore.settings
    val colors = colorsFor(settings)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalServices provides services) {
        KolTheme(colors) {
            val screen = services.nav.current
            val reading = screen is Screen.Reader
            BackHandler(enabled = services.nav.stack.size > 1 || screen != Screen.Main(Tab.Home)) { services.nav.back() }
            if (!reading) {
                SideEffect { services.systemBars(!colors.dark, false) }
            }
            LiveBackground(enabled = settings.liveBackground && !reading, modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    if (!reading && screen != Screen.Verify) TopBar(services)
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentKey = { it.toString() },
                        label = "screen",
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
                            Screen.Verify -> VerifyScreen()
                        }
                    }
                    if (!reading && screen != Screen.Verify) BottomBar(services)
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
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)
            .glass(colors, RoundedCornerShape(18.dp), strong = true)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (nav.stack.size > 1) {
            IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") { nav.back() }
            Spacer(Modifier.width(4.dp))
        }
        if (current is Screen.Search) {
            SearchBox(initial = current.query, modifier = Modifier.weight(1f), autoFocus = current.query.isBlank()) { q ->
                if (q.isNotBlank()) {
                    // Replace the current search instead of stacking one per query.
                    nav.back()
                    nav.go(Screen.Search(q.trim()))
                }
            }
        } else {
            Text("ملوك", color = colors.accent, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text(" الروايات", color = colors.text, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Spacer(Modifier.weight(1f))
            if (Downloads.running) {
                Row(
                    Modifier.clip(CircleShape).clickable { nav.go(Screen.Main(Tab.Downloads)) }.padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(KolIcons.Download, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                    Text("${Downloads.jobs.sumOf { it.pending.size }}", color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            IconBtn(Icons.Filled.Search, "بحث") { nav.go(Screen.Search("")) }
        }
    }
}

@Composable
private fun BottomBar(services: AppServices) {
    val colors = LocalAppColors.current
    val current = services.nav.current
    Row(
        Modifier.fillMaxWidth()
            .glass(colors, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), strong = true)
            .navigationBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceAround,
    ) {
        Tab.entries.forEach { tab ->
            val selected = current is Screen.Main && current.tab == tab
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                    .clickable { services.nav.go(Screen.Main(tab)) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.clip(CircleShape)
                        .background(if (selected) colors.accent else colors.accent.copy(alpha = 0f))
                        .padding(horizontal = 14.dp, vertical = 3.dp),
                ) {
                    Icon(tab.icon, null, tint = if (selected) colors.onAccent else colors.muted, modifier = Modifier.size(22.dp))
                }
                Text(
                    tab.label, color = if (selected) colors.text else colors.muted, fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
                )
            }
        }
    }
}

@Composable
fun SearchBox(initial: String, modifier: Modifier = Modifier, autoFocus: Boolean = false, onSearch: (String) -> Unit) {
    val colors = LocalAppColors.current
    var text by remember(initial) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focus.requestFocus() } }
    Row(
        modifier.glass(colors, CircleShape).padding(horizontal = 12.dp, vertical = 8.dp),
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
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (text.isNotEmpty()) {
            Icon(Icons.Filled.Close, "مسح", tint = colors.muted, modifier = Modifier.size(18.dp).clickable { text = "" })
        }
    }
}

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))
