package com.kolnovel.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.kolnovel.reader.data.KolSource
import com.kolnovel.reader.data.LibrarySync
import com.kolnovel.reader.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** "Account & sync": moves the library and reading progress between this PC, other PCs and the phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountPanel() {
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val folder = SettingsStore.settings.syncFolder
    var message by remember { mutableStateOf("") }
    val suggestions = remember { LibrarySync.suggestedFolders() }

    GlassPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AccountCircle, null, tint = colors.accent, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
            Text("الحساب والمزامنة", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Gap(6)
        Text(
            "موقع ملوك الروايات يحفظ \"الإشارات المرجعية\" داخل المتصفح فقط، فلا توجد مكتبة على حسابك في الموقع يمكن ربطها. " +
                "بدلاً من ذلك يزامن القارئ مكتبتك ومفضلتك وأين وصلت في كل رواية عبر مجلد سحابي (OneDrive أو Google Drive): " +
                "اختر نفس المجلد على كل جهاز وستنتقل مكتبتك بينها تلقائياً.",
            color = colors.muted, fontSize = 14.sp, lineHeight = 23.sp,
        )
        Gap(12)
        if (folder.isBlank()) {
            Text("المزامنة متوقفة.", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Gap(8)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { (label, path) ->
                    AccentButton("زامن عبر $label", Icons.Filled.Refresh, onClick = { LibrarySync.setFolder(path) })
                }
                GlassButton("اختر مجلداً...", onClick = { chooseFolder()?.let { LibrarySync.setFolder(it) } })
            }
            if (suggestions.isEmpty()) {
                Gap(6)
                Text("لم أجد OneDrive أو Google Drive على هذا الجهاز. ثبّت أحدهما ثم اختر مجلده.", color = colors.muted, fontSize = 13.sp)
            }
        } else {
            Text("المجلد: ${File(folder, "KolNovelReader").path}", color = colors.text, fontSize = 14.sp)
            val devices = LibrarySync.devices
            Text(
                if (devices.isEmpty()) "لا توجد أجهزة أخرى بعد. اختر نفس المجلد على جهازك الآخر."
                else "متزامن مع: ${devices.joinToString("، ")}",
                color = colors.muted, fontSize = 13.sp,
            )
            if (LibrarySync.lastSyncAt > 0) {
                Text("آخر مزامنة: ${SimpleDateFormat("HH:mm").format(Date(LibrarySync.lastSyncAt))}", color = colors.muted, fontSize = 13.sp)
            }
            if (LibrarySync.status.isNotBlank()) Text(LibrarySync.status, color = colors.accent, fontSize = 13.sp)
            Gap(8)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentButton("زامن الآن", Icons.Filled.Refresh, onClick = {
                    scope.launch { withContext(Dispatchers.IO) { LibrarySync.syncNow() } }
                })
                GlassButton("تغيير المجلد...", onClick = { chooseFolder()?.let { LibrarySync.setFolder(it) } })
                GlassButton("إيقاف المزامنة", Icons.Filled.Close, onClick = { LibrarySync.setFolder("") })
            }
        }
        Gap(14)
        Text("نقل يدوي", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("احفظ مكتبتك في ملف وافتحه على جهاز آخر؛ يُدمج مع مكتبته ولا يمسحها.", color = colors.muted, fontSize = 13.sp)
        Gap(8)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("حفظ المكتبة في ملف...", onClick = {
                saveFile()?.let { f ->
                    message = runCatching { LibrarySync.exportTo(f); "تم الحفظ في ${f.path}" }.getOrElse { "تعذر الحفظ: ${it.message}" }
                }
            })
            GlassButton("فتح ملف مكتبة...", onClick = {
                openFile()?.let { f ->
                    message = runCatching { "تم دمج ${LibrarySync.importFrom(f)} رواية." }.getOrElse { "هذا ليس ملف مكتبة صالحاً." }
                }
            })
            GlassButton("حسابي في الموقع", Icons.Filled.Share, onClick = { openInBrowser(KolSource.BASE_URL + "/account/") })
        }
        if (message.isNotBlank()) {
            Gap(6)
            Text(message, color = colors.muted, fontSize = 13.sp)
        }
    }
}

private fun chooseFolder(): String? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "اختر مجلد المزامنة (داخل OneDrive أو Google Drive)"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
}

private fun saveFile(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "حفظ المكتبة"
        selectedFile = File(System.getProperty("user.home"), "kolnovel-library.json")
        fileFilter = FileNameExtensionFilter("JSON", "json")
    }
    return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

private fun openFile(): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = "فتح ملف مكتبة"
        fileFilter = FileNameExtensionFilter("JSON", "json")
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}
