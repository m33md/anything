package com.kolnovel.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kolnovel.reader.data.AppDirs
import com.kolnovel.reader.data.ChapterStore
import com.kolnovel.reader.data.SettingsStore
import com.kolnovel.reader.data.Settings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val colors = LocalAppColors.current
    val s = SettingsStore.settings
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 700.dp).fillMaxWidth().verticalScroll(scroll).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlassPanel(Modifier.fillMaxWidth()) {
                Text("المظهر", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Gap(8)
                Text("الخلفية", color = colors.muted, fontSize = 13.sp)
                Gap(4)
                ThemeSwatches(selected = s.theme, includeFollow = false) { id -> SettingsStore.update { it.copy(theme = id) } }
                Gap(12)
                Text("اللون الأساسي", color = colors.muted, fontSize = 13.sp)
                Gap(4)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Accents.forEach { (id, pair) ->
                        val (label, color) = pair
                        val selected = s.customAccent.isBlank() && s.accent == id
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(74.dp)) {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape)
                                    .background(if (id == "mono") Brush.linearGradient(listOf(Color.White, Color.Black)) else SolidColor(color))
                                    .border(2.dp, if (selected) colors.text else Color.Transparent, CircleShape)
                                    .clickable { SettingsStore.update { it.copy(accent = id, customAccent = "") } },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) Icon(Icons.Filled.Check, null, tint = if (id == "mono" || id == "gold") Color.Black else Color.White, modifier = Modifier.size(18.dp))
                            }
                            Text(label, color = colors.muted, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
                Gap(10)
                CustomAccentField(s)
                Gap(12)
                LabeledSlider("شفافية الزجاج", s.glassOpacity, 0.15f..0.9f, "${(s.glassOpacity * 100).toInt()}%") { v ->
                    SettingsStore.update { it.copy(glassOpacity = v) }
                }
                SettingSwitch("خلفية حيّة (توهج ملون يتحرك ببطء خلف الزجاج)", s.liveBackground) { v ->
                    SettingsStore.update { it.copy(liveBackground = v) }
                }
                LabeledSlider("حجم البطاقات", s.gridColumnsMin.toFloat(), 90f..180f, "${s.gridColumnsMin}") { v ->
                    SettingsStore.update { it.copy(gridColumnsMin = v.toInt()) }
                }
            }

            GlassPanel(Modifier.fillMaxWidth()) {
                Text("القراءة", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("نفس الإعدادات موجودة داخل القارئ (زر الترس).", color = colors.muted, fontSize = 13.sp)
                Gap(6)
                LabeledSlider("حجم الخط", s.readerFontSize.toFloat(), 12f..40f, "${s.readerFontSize}") { v ->
                    SettingsStore.update { it.copy(readerFontSize = v.toInt()) }
                }
                LabeledSlider("تباعد الأسطر", s.readerLineHeight, 1.2f..2.8f, "%.1f".format(s.readerLineHeight)) { v ->
                    SettingsStore.update { it.copy(readerLineHeight = v) }
                }
                SettingSwitch("إبقاء الشاشة مضاءة أثناء القراءة", s.readerKeepScreenOn) { v -> SettingsStore.update { it.copy(readerKeepScreenOn = v) } }
                SettingSwitch("التمرير بأزرار الصوت", s.readerVolumeKeys) { v -> SettingsStore.update { it.copy(readerVolumeKeys = v) } }
                Text("ألوان القارئ", color = colors.muted, fontSize = 13.sp)
                Gap(4)
                ThemeSwatches(selected = s.readerTheme, includeFollow = true) { id -> SettingsStore.update { it.copy(readerTheme = id) } }
            }

            GlassPanel(Modifier.fillMaxWidth()) {
                Text("التنزيل", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Gap(4)
                SettingSwitch("التنزيل عبر Wi-Fi فقط", s.wifiOnly) { v -> SettingsStore.update { it.copy(wifiOnly = v) } }
                Text(
                    "الفصول المنزّلة تُفتح بدون إنترنت من صفحة الرواية أو من قائمة التنزيلات.",
                    color = colors.muted, fontSize = 12.sp,
                )
            }

            SyncPanel()

            StoragePanel()

            GlassPanel(Modifier.fillMaxWidth()) {
                Text("عن التطبيق", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Gap(4)
                Text(
                    "قارئ غير رسمي لموقع ملوك الروايات (kolnovel.com). كل النصوص والصور من الموقع نفسه وتبقى ملكًا له ولمترجميه. " +
                        "التطبيق للاستخدام الشخصي فقط؛ لا تنشر نصوص الموقع خارج الموقع.",
                    color = colors.muted, fontSize = 14.sp, lineHeight = 22.sp,
                )
                Gap(6)
                Text(
                    "في القارئ: اضغط على النص لإظهار أو إخفاء الشريط، وأزرار الصوت تقلب الصفحة. اضغط مطولًا على فصل في صفحة الرواية لتحديد عدة فصول.",
                    color = colors.muted, fontSize = 12.sp, lineHeight = 20.sp,
                )
                Gap(4)
                Text("الإصدار ${com.kolnovel.reader.BuildConfig.VERSION_NAME}", color = colors.muted, fontSize = 12.sp)
            }
        }
        FastScrollbar(scroll)
    }
}

@Composable
private fun CustomAccentField(s: Settings) {
    val colors = LocalAppColors.current
    var text by remember { mutableStateOf(s.customAccent) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("لون خاص (#RRGGBB):", color = colors.text, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(130.dp).glass(colors, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
            if (text.isEmpty()) Text("#6d0015", color = colors.muted, fontSize = 14.sp)
            BasicTextField(
                text,
                { v ->
                    text = v.take(7)
                    if (parseHex(text) != null) SettingsStore.update { it.copy(customAccent = text) }
                    if (text.isBlank()) SettingsStore.update { it.copy(customAccent = "") }
                },
                singleLine = true,
                textStyle = TextStyle(color = colors.text, fontSize = 14.sp, fontFamily = Cairo),
                cursorBrush = SolidColor(colors.accent),
            )
        }
        Spacer(Modifier.width(8.dp))
        parseHex(text)?.let { Box(Modifier.size(24.dp).clip(CircleShape).background(it)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoragePanel() {
    val colors = LocalAppColors.current
    var size by remember { mutableLongStateOf(ChapterStore.sizeBytes()) }
    var confirm by remember { mutableStateOf(false) }
    GlassPanel(Modifier.fillMaxWidth()) {
        Text("التخزين", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Gap(4)
        Text("الفصول المحفوظة: %.1f ميغابايت".format(size / 1_048_576.0), color = colors.text, fontSize = 14.sp)
        Gap(8)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("حذف كل الفصول المحفوظة", Icons.Filled.Delete, onClick = { confirm = true })
            GlassButton("حذف صور الأغلفة", Icons.Filled.Delete, onClick = { AppDirs.images.listFiles()?.forEach { it.delete() } })
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("حذف كل الفصول المحفوظة؟") },
            text = { Text("ستحتاج للإنترنت لقراءتها مرة أخرى.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    ChapterStore.deleteAll()
                    size = ChapterStore.sizeBytes()
                }) { Text("حذف", color = colors.accent) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("إلغاء", color = colors.text) } },
            containerColor = colors.spec.backgroundAlt,
            titleContentColor = colors.text,
            textContentColor = colors.text,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSwatches(selected: String, includeFollow: Boolean, onPick: (String) -> Unit) {
    val colors = LocalAppColors.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (includeFollow) {
            Swatch("مثل التطبيق", colors.spec.background, colors.spec.text, selected.isEmpty()) { onPick("") }
        }
        Themes.forEach { t -> Swatch(t.name, t.background, t.text, selected == t.id) { onPick(t.id) } }
    }
}

@Composable
private fun Swatch(label: String, bg: Color, fg: Color, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalAppColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(78.dp)) {
        Box(
            Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(12.dp)).background(bg)
                .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.muted.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text("أب", color = fg, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Text(label, color = colors.muted, fontSize = 11.sp, maxLines = 1)
    }
}
