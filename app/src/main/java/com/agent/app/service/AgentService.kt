package com.agent.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.agent.app.chat.ChatController
import com.agent.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground background service:
 * - keeps the agent loop alive (after pressing Home)
 * - live status notification + Quick Reply action (RemoteInput) so the user
 *   can answer from the notification shade without leaving the current app
 * - "task done / question asked" notification with the same reply action
 * - floating overlay status bubble on top of all apps — drag + tap = open app
 */
class AgentService : Service() {

    private val obsScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var statusJob: Job? = null
    private var procJob: Job? = null
    private var bubbleJob: Job? = null
    private var wasProcessing = false
    private var notification: NotificationCompat.Builder? = null
    private var bubbleView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "agent_status"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Agent Status", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel("agent_done", "Agent Reply", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                }
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val base = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(com.agent.app.R.drawable.ic_launcher_fg)
            .setContentTitle("Galahad-X-Agent")
            .setContentText("Ready")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(replyAction())
        notification = base
        startForeground(NOTIF_ID, base.build())

        val ctl = ChatController.instance ?: run { stopSelf(); return }

        // live status in the notification
        statusJob = obsScope.launch {
            ctl.agentStatus.collect { s ->
                val text = when {
                    s.isNotBlank() -> s
                    ctl.isProcessing.value -> "Working..."
                    else -> "Ready"
                }
                notifyUpdate(text)
            }
        }

        // run finished -> reply notification carrying the agent's last answer
        procJob = obsScope.launch {
            ctl.isProcessing.collect { p ->
                if (wasProcessing && !p) {
                    postDoneNotification(ctl)
                    stopSelf()
                }
                wasProcessing = p
            }
        }

        // floating status bubble (requires overlay permission)
        bubbleJob = obsScope.launch {
            ctl.agentStatus.collect { s ->
                updateBubble(
                    s.ifBlank { if (ctl.isProcessing.value) "Working..." else "Ready" },
                    ctl.isProcessing.value
                )
            }
        }
    }

    /** Notification Reply button — works from the shade inside any app */
    private fun replyAction(): NotificationCompat.Action {
        val replyIntent = Intent(this, ReplyReceiver::class.java).setAction("AGENT_REPLY")
        val replyPI = PendingIntent.getBroadcast(
            this, 11, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val remoteInput = RemoteInput.Builder("key_reply")
            .setLabel("Reply to agent...")
            .build()
        return NotificationCompat.Action.Builder(
            com.agent.app.R.drawable.ic_launcher_fg, "Reply", replyPI
        ).addRemoteInput(remoteInput).build()
    }

    private fun postDoneNotification(ctl: ChatController) {
        try {
            val lastAnswer = ctl.messages.value
                .lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
                ?.content?.take(400) ?: "Task done — if a question was asked, reply right here"
            val openApp = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(this, "agent_done")
                .setSmallIcon(com.agent.app.R.drawable.ic_launcher_fg)
                .setContentTitle("Agent is waiting for your reply")
                .setContentText(lastAnswer.take(100))
                .setStyle(NotificationCompat.BigTextStyle().bigText(lastAnswer))
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .addAction(replyAction())
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            getSystemService(NotificationManager::class.java).notify(102, notif)
        } catch (_: Exception) {}
    }

    // ===== floating bubble =====
    private var bubbleParams: WindowManager.LayoutParams? = null

    private fun ensureBubble() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return
        if (bubbleView != null) return
        try {
            val density = resources.displayMetrics.density
            val sizePx = (50 * density).toInt()
            val ctx = this
            val frame = FrameLayout(this)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.parseColor("#1B2334"))
                setStroke((2 * density).toInt(), android.graphics.Color.parseColor("#6C5CE7"))
            }
            frame.background = bg
            val label = TextView(this).apply {
                text = "🤖"
                textSize = 19f
                gravity = Gravity.CENTER
            }
            frame.addView(label, FrameLayout.LayoutParams(sizePx, sizePx))
            frame.setOnClickListener {
                startActivity(
                    Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            frame.setOnTouchListener(object : View.OnTouchListener {
                private var startX = 0f
                private var startY = 0f
                private var posX = 0
                private var posY = 0
                private var moved = false
                override fun onTouch(v: View, ev: android.view.MotionEvent): Boolean {
                    when (ev.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            startX = ev.rawX; startY = ev.rawY
                            posX = bubbleParams?.x ?: 0; posY = bubbleParams?.y ?: 0
                            moved = false
                            return true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val dx = (ev.rawX - startX).toInt()
                            val dy = (ev.rawY - startY).toInt()
                            if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                            bubbleParams?.let {
                                it.x = posX + dx
                                it.y = posY + dy
                                try { getSystemService(WindowManager::class.java).updateViewLayout(v, it) } catch (_: Exception) {}
                            }
                            return true
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            if (!moved) v.performClick()
                            return true
                        }
                    }
                    return false
                }
            })
            val params = WindowManager.LayoutParams(
                sizePx, sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 8 * density.toInt()
                y = 200 * density.toInt()
            }
            getSystemService(WindowManager::class.java).addView(frame, params)
            bubbleView = frame
            bubbleParams = params
        } catch (_: Exception) {}
    }

    private fun updateBubble(status: String, processing: Boolean) {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return
        ensureBubble()
        val v = bubbleView ?: return
        try {
            (v as? FrameLayout)?.let { frame ->
                (frame.getChildAt(0) as? TextView)?.text = if (processing) "⚙️" else "🤖"
                val density = resources.displayMetrics.density
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(android.graphics.Color.parseColor("#1B2334"))
                    setStroke(
                        (2 * density).toInt(),
                        android.graphics.Color.parseColor(if (processing) "#F5B14C" else "#6C5CE7")
                    )
                }
                frame.background = bg
                frame.contentDescription = status
            }
        } catch (_: Exception) {}
    }

    private fun notifyUpdate(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, notification?.setContentText(text)?.build() ?: return)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        obsScope.cancel()
        bubbleView?.let {
            try { getSystemService(WindowManager::class.java).removeView(it) } catch (_: Exception) {}
        }
        try {
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 101
    }
}
