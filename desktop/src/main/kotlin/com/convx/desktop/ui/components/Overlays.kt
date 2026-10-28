/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.convx.desktop.ui.theme.ConvxColors
import com.convx.desktop.ui.theme.ConvxShapes
import com.convx.desktop.ui.theme.ConvxType
import kotlinx.coroutines.delay

/** App-wide modal dialog and toast host, drawn by the shell above everything else. */
object Overlays {
    var dialog by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    internal val toasts = mutableStateListOf<Toast>()
    private var nextToast = 0L

    data class Toast(val id: Long, val text: String, val icon: String?)

    fun showDialog(content: @Composable () -> Unit) {
        dialog = content
    }

    fun dismissDialog() {
        dialog = null
    }

    fun toast(text: String, icon: String? = "check") {
        toasts += Toast(nextToast++, text, icon)
        while (toasts.size > 3) toasts.removeAt(0)
    }

    /** Asks for a name, e.g. a new playlist. */
    fun prompt(title: String, initial: String = "", confirm: String = "Save", onConfirm: (String) -> Unit) {
        showDialog {
            var text by remember { mutableStateOf(initial) }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { focus.requestFocus() }
            DialogCard(title) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(ConvxShapes.Control)
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = ConvxType.Body.copy(color = Color.White),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (text.isNotBlank()) {
                                onConfirm(text.trim())
                                dismissDialog()
                            }
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onPreviewKeyEvent {
                                when (it.key) {
                                    Key.Enter, Key.NumPadEnter -> {
                                        if (text.isNotBlank()) {
                                            onConfirm(text.trim())
                                            dismissDialog()
                                        }
                                        true
                                    }
                                    Key.Escape -> {
                                        dismissDialog()
                                        true
                                    }
                                    else -> false
                                }
                            },
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    PillButton("Cancel", { dismissDialog() })
                    PillButton(confirm, {
                        if (text.isNotBlank()) {
                            onConfirm(text.trim())
                            dismissDialog()
                        }
                    }, filled = true)
                }
            }
        }
    }

    fun confirm(title: String, message: String, confirm: String, onConfirm: () -> Unit) {
        showDialog {
            DialogCard(title) {
                Text(message, style = ConvxType.Body, color = ConvxColors.SecondaryLabel)
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    PillButton("Cancel", { dismissDialog() })
                    PillButton(confirm, {
                        onConfirm()
                        dismissDialog()
                    }, accent = Color(0xFFFF453A))
                }
            }
        }
    }
}

@Composable
fun DialogCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .width(420.dp)
            .shadow(32.dp, ConvxShapes.CardLarge)
            .clip(ConvxShapes.CardLarge)
            .background(Color(0xFF232325))
            .glassRim(ConvxShapes.CardLarge, 0.1f)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(24.dp),
    ) {
        Text(title, style = ConvxType.Title3)
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
fun OverlayHost() {
    val dialog = Overlays.dialog
    AnimatedVisibility(dialog != null, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { Overlays.dismissDialog() },
            contentAlignment = Alignment.Center,
        ) {
            dialog?.invoke()
        }
    }
    Box(Modifier.fillMaxSize().padding(bottom = 112.dp), contentAlignment = Alignment.BottomCenter) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (toast in Overlays.toasts) {
                androidx.compose.runtime.key(toast.id) { ToastView(toast) }
            }
        }
    }
}

@Composable
private fun ToastView(toast: Overlays.Toast) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(toast.id) {
        visible = true
        delay(2600)
        visible = false
        delay(300)
        Overlays.toasts.remove(toast)
    }
    AnimatedVisibility(visible, enter = slideInVertically { it / 2 } + fadeIn(), exit = fadeOut()) {
        Row(
            Modifier
                .shadow(16.dp, ConvxShapes.Capsule)
                .clip(ConvxShapes.Capsule)
                .background(Color.White)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (toast.icon != null) ConvxIcon(toast.icon, tint = Color.Black, size = 18.dp)
            Text(toast.text, style = ConvxType.SubheadStrong, color = Color.Black)
        }
    }
}
