package com.agent.app.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.agent.app.chat.ChatController

/**
 * Quick-reply receiver from the notification — the user can answer
 * from the notification shade inside any app (Telegram etc.).
 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val results = RemoteInput.getResultsFromIntent(intent)
        val text = results?.getCharSequence("key_reply")?.toString()?.trim()
        if (!text.isNullOrBlank()) {
            ChatController.instance?.sendMessage(text, emptyList())
        }
        try {
            context.getSystemService(NotificationManager::class.java).cancel(102)
        } catch (_: Exception) {}
    }
}
