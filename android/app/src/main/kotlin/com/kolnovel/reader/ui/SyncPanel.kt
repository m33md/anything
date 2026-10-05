package com.kolnovel.reader.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kolnovel.reader.data.LibrarySync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "الحساب والمزامنة": carries the library, progress and read marks between this phone and the PC app. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SyncPanel() {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            LibrarySync.setFolder(uri)
            message = null
        }
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching { LibrarySync.exportTo(uri); "تم حفظ نسخة من مكتبتك." }.getOrElse { "تعذر الحفظ: ${it.message}" }
            }
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching { "تم دمج ${LibrarySync.importFrom(uri)} رواية في مكتبتك." }.getOrElse { "هذا ليس ملف مكتبة صالحًا." }
            }
        }
    }

    GlassPanel(Modifier.fillMaxWidth()) {
        Text("الحساب والمزامنة", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Gap(4)
        Text(
            "موقع ملوك الروايات لا يحفظ المكتبة في حسابك (المفضلة على الموقع محفوظة في المتصفح فقط)، " +
                "لذلك تنتقل مكتبتك وتقدّم القراءة بين الهاتف والكمبيوتر عبر مجلد سحابي مشترك " +
                "(Google Drive أو OneDrive...). اختر على الهاتف نفس المجلد الذي اخترته في برنامج الكمبيوتر.",
            color = colors.muted, fontSize = 13.sp, lineHeight = 21.sp,
        )
        Gap(10)
        val uri = LibrarySync.folderUri
        if (uri == null) {
            Text("المزامنة متوقفة.", color = colors.text, fontSize = 14.sp)
        } else {
            val name = uri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: uri.toString()
            Text("المجلد: $name", color = colors.text, fontSize = 14.sp)
            val last = LibrarySync.lastSyncAt
            Text(
                when {
                    LibrarySync.running -> "يزامن الآن..."
                    last > 0 -> "آخر مزامنة: " + SimpleDateFormat("HH:mm  d/M", Locale.getDefault()).format(Date(last))
                    else -> "لم تتم المزامنة بعد."
                },
                color = colors.muted, fontSize = 12.sp,
            )
            if (LibrarySync.devices.isNotEmpty()) {
                Text("أجهزة أخرى: ${LibrarySync.devices.joinToString("، ")}", color = colors.muted, fontSize = 12.sp)
            }
        }
        if (LibrarySync.status.isNotEmpty()) {
            Gap(4)
            Text(LibrarySync.status, color = colors.accent, fontSize = 12.sp)
        }
        Gap(10)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentButton(if (uri == null) "اختر مجلد المزامنة" else "تغيير المجلد", onClick = { pickFolder.launch(null) })
            if (uri != null) {
                GlassButton("مزامنة الآن", Icons.Filled.Refresh, onClick = { LibrarySync.syncInBackground() })
                GlassButton("إيقاف", Icons.Filled.Close, onClick = { LibrarySync.setFolder(null) })
            }
        }
        Gap(12)
        Text(
            "إذا لم يظهر مجلدك السحابي في القائمة: احفظ نسخة من المكتبة في أي مكان (مثل Google Drive) " +
                "ثم افتحها من الجهاز الآخر بزر \"استيراد\". الاستيراد يدمج ولا يحذف شيئًا.",
            color = colors.muted, fontSize = 12.sp, lineHeight = 20.sp,
        )
        Gap(8)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("حفظ نسخة من المكتبة", KolIcons.Download, onClick = { exportFile.launch("kolnovel-library.json") })
            GlassButton("استيراد مكتبة", onClick = { importFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) })
        }
        message?.let {
            Gap(6)
            Text(it, color = colors.text, fontSize = 13.sp)
        }
    }
}
