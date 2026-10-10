package com.agent.app

import android.content.Context
import android.content.Intent
import com.agent.app.ui.MainActivity

object AgentStarter {
    fun start(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
