package dev.tubik.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateListOf

/** Простейший стек экранов поверх вкладок: пока это только страницы каналов. */
object Nav {
    val channels = mutableStateListOf<String>()

    fun openChannel(url: String) {
        if (url.isBlank()) return
        if (channels.lastOrNull() != url) channels.add(url)
    }

    fun back(): Boolean {
        if (channels.isEmpty()) return false
        channels.removeAt(channels.lastIndex)
        return true
    }

    fun clear() = channels.clear()

    /** Открыть канал из плеера: поднимаем главный экран и кладём канал наверх стека. */
    fun openChannelFrom(context: Context, url: String) {
        openChannel(url)
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }
}
