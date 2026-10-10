package com.agent.app.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SessionMeta(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val count: Int
)

data class SessionData(
    val meta: SessionMeta,
    val messages: List<ChatMessage>,
    val llmHistory: List<Map<String, String>> = emptyList()
)

/**
 * Chat session storage — one JSON file per session under filesDir/sessions/
 * Format: {id, title, createdAt, updatedAt, messages:[{id,role,content,timestamp,attachments:[...]}]}
 */
class SessionStore(context: Context) {
    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("agent_sessions", Context.MODE_PRIVATE)

    private fun file(id: String) = File(dir, "$id.json")

    /** Current session; creates a fresh one if none exists */
    fun currentId(): String {
        val id = prefs.getString("current", null)
        if (id != null && file(id).exists()) return id
        val fresh = System.currentTimeMillis().toString()
        writeSession(fresh, "New chat", System.currentTimeMillis(), emptyList())
        setCurrent(fresh)
        return fresh
    }

    fun setCurrent(id: String) {
        prefs.edit().putString("current", id).apply()
    }

    /** Create a new session and make it current */
    fun createSession(): String {
        val id = System.currentTimeMillis().toString()
        writeSession(id, "New chat", System.currentTimeMillis(), emptyList())
        setCurrent(id)
        return id
    }

    fun loadSession(id: String): SessionData? {
        return try {
            val f = file(id)
            if (!f.exists()) return null
            val obj = JSONObject(f.readText())
            val meta = SessionMeta(
                id = obj.optString("id", id),
                title = obj.optString("title", "Untitled"),
                createdAt = obj.optLong("createdAt", 0),
                updatedAt = obj.optLong("updatedAt", 0),
                count = obj.optJSONArray("messages")?.length() ?: 0
            )
            val arr = obj.optJSONArray("messages") ?: JSONArray()
            val msgs = (0 until arr.length()).mapNotNull { i ->
                val m = arr.optJSONObject(i) ?: return@mapNotNull null
                val atts = m.optJSONArray("attachments")
                val attachments = (0 until (atts?.length() ?: 0)).mapNotNull { j ->
                    val a = atts?.optJSONObject(j) ?: return@mapNotNull null
                    Attachment(
                        path = a.optString("path"),
                        name = a.optString("name"),
                        mime = a.optString("mime"),
                        isImage = a.optBoolean("isImage", false)
                    )
                }
                ChatMessage(
                    id = m.optLong("id"),
                    role = m.optString("role"),
                    content = m.optString("content"),
                    timestamp = m.optLong("timestamp", 0),
                    attachments = attachments
                )
            }
            val lha = obj.optJSONArray("llmHistory") ?: JSONArray()
            val llmHist = (0 until lha.length()).mapNotNull { i ->
                val h = lha.optJSONObject(i) ?: return@mapNotNull null
                val m = mutableMapOf<String, String>()
                val keys = h.keys()
                while (keys.hasNext()) { val k = keys.next(); m[k] = h.optString(k) }
                if (m.containsKey("role")) m else null
            }
            SessionData(meta, msgs, llmHist)
        } catch (e: Exception) {
            android.util.Log.e("SessionStore", "load failed: $id", e)
            null
        }
    }

    /** Persist all messages of a session (full rewrite — fine for chat size) */
    fun saveMessages(id: String, messages: List<ChatMessage>, llmHistory: List<Map<String, String>> = emptyList()) {
        val existing = loadSession(id)
        val createdAt = existing?.meta?.createdAt ?: System.currentTimeMillis()
        var title = existing?.meta?.title ?: "New chat"
        if (title == "New chat") {
            val firstUser = messages.firstOrNull { it.role == "user" }
            if (firstUser != null) {
                title = firstUser.content.take(35).replace("\n", " ") +
                        if (firstUser.content.length > 35) "…" else ""
            }
        }
        writeSession(id, title, createdAt, messages, llmHistory)
    }

    private fun writeSession(
        id: String, title: String, createdAt: Long,
        messages: List<ChatMessage>,
        llmHistory: List<Map<String, String>> = emptyList()
    ) {
        try {
            val arr = JSONArray()
            messages.forEach { m ->
                val mo = JSONObject()
                    .put("id", m.id)
                    .put("role", m.role)
                    .put("content", m.content)
                    .put("timestamp", m.timestamp)
                if (m.attachments.isNotEmpty()) {
                    val aa = JSONArray()
                    m.attachments.forEach { a ->
                        aa.put(
                            JSONObject()
                                .put("path", a.path)
                                .put("name", a.name)
                                .put("mime", a.mime)
                                .put("isImage", a.isImage)
                        )
                    }
                    mo.put("attachments", aa)
                }
                arr.put(mo)
            }
            // raw LLM history (preserves the tool+observation pattern) — last 80
            val lh = JSONArray()
            llmHistory.takeLast(80).forEach { h ->
                val o = JSONObject()
                h.forEach { (k, v) -> o.put(k, v) }
                lh.put(o)
            }
            val obj = JSONObject()
                .put("id", id)
                .put("title", title)
                .put("createdAt", createdAt)
                .put("updatedAt", System.currentTimeMillis())
                .put("messages", arr)
                .put("llmHistory", lh)
            file(id).writeText(obj.toString())
        } catch (e: Exception) {
            android.util.Log.e("SessionStore", "save failed: $id", e)
        }
    }

    /** List all sessions, newest first */
    fun listSessions(): List<SessionMeta> {
        return dir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { f ->
                try {
                    val obj = JSONObject(f.readText())
                    SessionMeta(
                        id = obj.optString("id", f.nameWithoutExtension),
                        title = obj.optString("title", "Untitled"),
                        createdAt = obj.optLong("createdAt", 0),
                        updatedAt = obj.optLong("updatedAt", 0),
                        count = obj.optJSONArray("messages")?.length() ?: 0
                    )
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    /**
     * Delete a session. If it was the current one, promote the next session
     * and return: the new current session id, or null if none was left
     */
    fun deleteSession(id: String): String? {
        file(id).delete()
        if (prefs.getString("current", null) == id) {
            val next = listSessions().firstOrNull()
            return if (next != null) {
                setCurrent(next.id)
                next.id
            } else {
                val fresh = System.currentTimeMillis().toString()
                writeSession(fresh, "New chat", System.currentTimeMillis(), emptyList())
                setCurrent(fresh)
                fresh
            }
        }
        return null
    }
}
