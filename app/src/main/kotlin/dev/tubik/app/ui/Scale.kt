package dev.tubik.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import dev.tubik.app.App

/** Применяет настройки масштаба: элементы — через плотность, текст — через fontScale. */
@Composable
fun Scaled(content: @Composable () -> Unit) {
    val prefs = App.instance.prefs
    val base = LocalDensity.current
    val ui = prefs.uiScale
    val text = prefs.textScale
    CompositionLocalProvider(
        LocalDensity provides Density(base.density * ui, base.fontScale * text),
        content = content,
    )
}
