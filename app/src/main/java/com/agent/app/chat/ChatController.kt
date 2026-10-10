package com.agent.app.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import android.util.Log
import com.agent.app.agent.ToolRegistry
import com.agent.app.llm.LLMEngine
import com.agent.app.memory.AgentMemory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Chat Controller - real chat + tool-use loop
 * 
 * Flow:
 * 1. User message + system prompt (with the tool list) go to the LLM
 * 2. The LLM either answers with text (normal chat)
 *    or returns tool JSON (to do work)
 * 3. The tool really executes and an observation comes back
 * 4. The loop continues until done
 * 5. Every step is shown live in the chat
 */
class ChatController(
    private val context: Context,
    private val llmEngine: LLMEngine,
    private val memory: AgentMemory,
    private val toolRegistry: ToolRegistry
) {
    companion object {
        private const val TAG = "ChatController"
        private const val MAX_TOOL_STEPS = 40
        private const val MAX_HISTORY = 40

        /** Process-wide instance — survives Activity death */
        var instance: ChatController? = null
    }

    init {
        instance = this
    }

    /** This controller owns the LLM engine - UI settings must apply here */
    val engine: LLMEngine get() = llmEngine

    /** This controller owns the tool registry */
    val registry: ToolRegistry get() = toolRegistry

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val msgSeq = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
    private var runJob: kotlinx.coroutines.Job? = null
    private data class QueuedItem(val display: String, val historyText: String)
    private val queued = ArrayDeque<QueuedItem>()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    // live agent state (working vs waiting on the model)
    private val _agentStatus = MutableStateFlow("")
    val agentStatus: StateFlow<String> = _agentStatus.asStateFlow()

    // ===== sessions & persistence =====
    private val store = SessionStore(context)
    private val _sessions = MutableStateFlow<List<SessionMeta>>(emptyList())
    val sessions: StateFlow<List<SessionMeta>> = _sessions.asStateFlow()
    private var sessionId: String = store.currentId()
    private val _currentId = MutableStateFlow(sessionId)
    val currentSessionId: StateFlow<String> = _currentId.asStateFlow()

    private val conversationHistory = mutableListOf<Map<String, String>>()

    // files the agent wants to attach to its reply
    private val pendingOutgoing = mutableListOf<Attachment>()

    /**
     * Copy a picked file into the app inbox
     */
    private fun copyToInbox(uri: Uri): Attachment? {
        return try {
        val cr = context.contentResolver
        var name = "file_${System.currentTimeMillis()}"
        cr.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx) ?: name
            }
        }
        val mime = cr.getType(uri) ?: "*/*"
        val dir = File(context.filesDir, "inbox").apply { mkdirs() }
        val out = File(dir, "${System.currentTimeMillis()}_$name")
        val stream = cr.openInputStream(uri) ?: return null
        stream.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        Attachment(out.absolutePath, name, mime, mime.startsWith("image/"))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "copyToInbox failed", e)
            null
        }
    }

    init {
        val saved = store.loadSession(sessionId)
        val msgs = saved?.messages ?: emptyList()
        if (msgs.isEmpty()) {
            addMessage(
                ChatMessage(
                    id = System.currentTimeMillis(), role = "assistant",
                    content = "Welcome to **Galahad-X-Agent** v0.14.4! 🤖\n\nI can control your phone, write & run code, browse the web, see images, and build websites and apps.\n\nJust tell me what to do.\n\n🐞 Found a bug? Report it: Telegram @hitlerfortest • Instagram galahad_mamad"
                )
            )
        } else {
            _messages.value = msgs
            // restore raw LLM history (tool+observation pattern preserved)
            val raw = saved?.llmHistory ?: emptyList()
            if (raw.isNotEmpty()) {
                conversationHistory.clear()
                conversationHistory.addAll(raw)
            } else {
                rebuildHistory(msgs)
            }
        }
        refreshSessions()
    }

    /** Rebuild LLM history from saved messages (user/assistant only, no tool pills) */
    private fun rebuildHistory(msgs: List<ChatMessage>) {
        conversationHistory.clear()
        msgs.filter { it.role == "user" || it.role == "assistant" }
            .filter { it.id != 0L && !it.content.startsWith("Hi! I am") }
            .takeLast(30)
            .forEach { m ->
                conversationHistory.add(mapOf("role" to m.role, "content" to m.content))
            }
    }

    private fun refreshSessions() {
        _sessions.value = store.listSessions()
    }

    /** New session */
    fun newSession() {
        if (_isProcessing.value) return
        sessionId = store.createSession()
        _currentId.value = sessionId
        _messages.value = emptyList()
        conversationHistory.clear()
        addMessage(
            ChatMessage(
                id = System.currentTimeMillis(), role = "assistant",
                content = "New session started. What should I do? 🤖"
            )
        )
        refreshSessions()
    }

    /** Switch to another session */
    fun switchToSession(id: String) {
        if (_isProcessing.value || id == sessionId) return
        val data = store.loadSession(id) ?: return
        sessionId = id
        _currentId.value = id
        store.setCurrent(id)
        _messages.value = data.messages
        rebuildHistory(data.messages)
        refreshSessions()
    }

    /** Delete a session */
    fun deleteSession(id: String) {
        if (_isProcessing.value) return
        val nextCurrent = store.deleteSession(id)
        if (id == sessionId) {
            val target = nextCurrent ?: store.currentId()
            sessionId = target
            _currentId.value = target
            val data = store.loadSession(target)
            _messages.value = data?.messages ?: emptyList()
            conversationHistory.clear()
            if (_messages.value.isEmpty()) {
                addMessage(ChatMessage(System.currentTimeMillis(), "assistant", "New session. What should I do? 🤖"))
            } else {
                rebuildHistory(_messages.value)
            }
        }
        refreshSessions()
    }

    private fun startAgentService() {
        try {
            val i = android.content.Intent(context, com.agent.app.service.AgentService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        } catch (e: Exception) {
            Log.w(TAG, "startAgentService: ${e.message}")
        }
    }

    /** Stop button: cancel current work + drain the queue */
    fun stopRun() {
        if (!_isProcessing.value) return
        Log.d(TAG, "stopRun: cancelling active request + job")
        llmEngine.cancelActive()
        runJob?.cancel()
        queued.clear()
        _agentStatus.value = ""
        addMessage(ChatMessage(System.currentTimeMillis(), "tool", "⏹ Stopped by the user"))
    }

    /** Run the next queued message (after the current job ends) */
    private fun drainQueue() {
        val next = queued.removeFirstOrNull() ?: return
        Log.d(TAG, "dequeuing: ${next.display.take(60)}")
        startAgentService()
        conversationHistory.add(mapOf("role" to "user", "content" to next.historyText))
        runJob = scope.launch {
            _isProcessing.value = true
            try {
                runAgentLoop(next.display)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "queued chat error", e)
                addMessage(ChatMessage(System.currentTimeMillis(), "assistant", "❌ Error: ${e.message}"))
            } finally {
                _agentStatus.value = ""
                _isProcessing.value = false
                drainQueue()
            }
        }
    }

    fun sendMessage(userMessage: String, uris: List<Uri> = emptyList()) {
        if (userMessage.isBlank() && uris.isEmpty()) return

        // mid-job: the message is queued and runs after the current job
        if (_isProcessing.value) {
            val atts = uris.mapNotNull { copyToInbox(it) }
            addMessage(ChatMessage(System.currentTimeMillis(), "user",
                userMessage.ifBlank { "(file attached)" }, attachments = atts))
            addMessage(ChatMessage(System.currentTimeMillis(), "tool",
                "🕓 Queued — will run after the current job"))
            val hist = buildString {
                append(userMessage)
                atts.forEach { a -> append("\n[att: ${a.path} | type: ${a.mime} | name: ${a.name}]") }
            }
            queued.addLast(QueuedItem(userMessage.ifBlank { "(file attached)" }, hist))
            return
        }

        // start the background service so Home does not kill the job
        startAgentService()

        // direct tool path (dev/test): !{"name":"...", "args":{...}}
        if (userMessage.trim().startsWith("!")) {
            addMessage(ChatMessage(System.currentTimeMillis(), "user", userMessage))
            runJob = scope.launch {
                _isProcessing.value = true
                try {
                    val json = JSONObject(userMessage.trim().removePrefix("!").trim())
                    val name = json.optString("name").ifBlank { json.optString("tool") }
                    val args = json.optJSONObject("args") ?: JSONObject()
                    addMessage(ChatMessage(System.currentTimeMillis(), "tool", "🔧 " + describeStep(name, args)))
                    val obs = withContext(Dispatchers.IO) { toolRegistry.execute(name, args) }
                    // if it was send_file, attach it to the reply
                    if (obs.startsWith("FILE_ATTACHED:")) {
                        val p = obs.removePrefix("FILE_ATTACHED:").trim()
                        val f = java.io.File(p)
                        if (f.exists()) {
                            pendingOutgoing.add(
                                Attachment(p, f.name,
                                    android.webkit.MimeTypeMap.getSingleton()
                                        .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*",
                                    f.extension.lowercase() in listOf("png","jpg","jpeg","webp","gif","bmp"))
                            )
                        }
                        addMessage(ChatMessage(System.currentTimeMillis(), "assistant", "File sent: ${f.name}", attachments = pendingOutgoing.toList()))
                        pendingOutgoing.clear()
                    } else {
                        addMessage(ChatMessage(System.currentTimeMillis(), "assistant", obs))
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    addMessage(ChatMessage(System.currentTimeMillis(), "assistant", "❌ ${e.message}"))
                } finally {
                    _isProcessing.value = false
                    drainQueue()
                }
            }
            return
        }

        val attachments = uris.mapNotNull { copyToInbox(it) }

        // history text: message + attachment paths so the model knows what it has
        val historyText = buildString {
            append(userMessage)
            attachments.forEach { a ->
                append("\n[att: ${a.path} | type: ${a.mime} | name: ${a.name}]")
            }
        }

        addMessage(ChatMessage(System.currentTimeMillis(), "user", userMessage.ifBlank { "(file attached)" }, attachments = attachments))
        conversationHistory.add(mapOf("role" to "user", "content" to historyText))

        runJob = scope.launch {
            _isProcessing.value = true
            try {
                runAgentLoop(userMessage)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "chat error", e)
                addMessage(ChatMessage(System.currentTimeMillis(), "assistant", "❌ Error: ${e.message}"))
            } finally {
                _agentStatus.value = ""
                _isProcessing.value = false
                drainQueue()
            }
        }
    }

    /**
     * Main agent loop: LLM -> tool -> observation -> LLM ... -> done
     */
    private suspend fun runAgentLoop(userMessage: String) {
        val systemPrompt = buildSystemPrompt(userMessage)
        val toolsJson = toolRegistry.nativeToolsJson()
        Log.d(TAG, "tools json size: ${toolsJson.length}")
        var lastAnswer = ""
        var recoveries = 0
        var lastRepeatSig = ""
        var repeatCount = 0

        for (step in 1..MAX_TOOL_STEPS) {
            _agentStatus.value = "Thinking... #$step"
            var res = llmEngine.generateNative(systemPrompt, conversationHistory, toolsJson)

            // automatic retry on transient API errors (config errors never benefit from retrying)
            val cfgError = { e: String ->
                e.startsWith("CONFIG:") || e.contains("Expected URL scheme") || e.contains("no scheme was found")
            }
            if (!res.ok && !cfgError(res.error)) {
                Log.w(TAG, "native error, retrying: ${res.error}")
                addMessage(ChatMessage(System.currentTimeMillis(), "tool", "🔁 Retrying API..."))
                kotlinx.coroutines.delay(2500)
                res = llmEngine.generateNative(systemPrompt, conversationHistory, toolsJson)
            }
            Log.d(TAG, "step $step native: ok=${res.ok} finish=${res.finishReason} calls=${res.toolCalls.joinToString { it.name }} content=${res.content.take(200)}")

            if (!res.ok) {
                val friendly = if (cfgError(res.error))
                    "⚠️ Model server is not configured: Base URL is empty or has no https:// scheme. Open Settings, save the provider with a full address (e.g. https://host/v1), Activate it, then send again."
                else "⚠️ Connection to the model failed (${res.error.take(150)}). Please resend."
                addMessage(ChatMessage(System.currentTimeMillis(), "assistant", friendly))
                _agentStatus.value = ""
                return
            }

            // no tool call -> final text answer
            if (res.toolCalls.isEmpty()) {
                // output tokens ran out mid-job (finish=length) -> nudge the loop to continue in smaller steps
                if (res.finishReason == "length" && res.content.isBlank()) {
                    recoveries++
                    if (recoveries > 3) {
                        _agentStatus.value = ""
                        addMessage(ChatMessage(System.currentTimeMillis(), "assistant",
                            "⚙️ It kept getting truncated and I could not continue — ask for the work in smaller pieces."))
                        return
                    }
                    Log.w(TAG, "truncated with no tool_calls — recovering ($recoveries)")
                    addMessage(ChatMessage(System.currentTimeMillis(), "tool", "✂️ Response truncated — recovering..."))
                    conversationHistory.add(mapOf("role" to "user",
                        "content" to "⚠️ Your reply was truncated (token limit) and no tool ran. Rules: call a real tool immediately, no prose. Write code/HTML only via write_file, large content in ~1500-char chunks with append."))
                    continue
                }
                // fallback: maybe the model still sent textual JSON
                val legacy = parseAction(res.content)
                val legacyTool = legacy?.optString("tool")?.ifBlank { legacy.optString("action") }
                if (legacy != null && !legacyTool.isNullOrBlank() && legacyTool != "done" && legacyTool != "answer" && !legacy.has("steps")) {
                    conversationHistory.add(mapOf("role" to "assistant", "content" to res.content))
                    val args = legacy.optJSONObject("args") ?: JSONObject()
                    val answer = execAndRecord(legacyTool, args, null)
                    if (answer != null) {
                        _agentStatus.value = ""
                        lastAnswer = answer
                        addMessage(ChatMessage(System.currentTimeMillis(), "assistant", answer))
                        conversationHistory.add(mapOf("role" to "assistant", "content" to answer))
                        return
                    }
                    continue
                }
                _agentStatus.value = ""
                lastAnswer = cleanMarkdown(res.content)
                if (lastAnswer.isNotBlank()) {
                    addMessage(ChatMessage(System.currentTimeMillis(), "assistant", lastAnswer +
                        if (res.finishReason == "length") "\n\n✂️ (answer was truncated — I will keep it shorter.)" else ""))
                    conversationHistory.add(mapOf("role" to "assistant", "content" to res.content))
                }
                return
            }

            // record the assistant message with tool_calls (for native continuation)
            val tcJson = org.json.JSONArray().apply {
                res.toolCalls.forEach { tc ->
                    put(JSONObject()
                        .put("id", tc.id)
                        .put("type", "function")
                        .put("function", JSONObject().put("name", tc.name).put("arguments", tc.argsJson)))
                }
            }.toString()
            conversationHistory.add(mapOf(
                "role" to "assistant",
                "content" to res.content,
                "tool_calls" to tcJson
            ))

            for ((tcIdx, tc) in res.toolCalls.withIndex()) {
                // guard against repeat loops: same tool+args 4x in a row = deadlock
                val sig = tc.name + "|" + tc.argsJson.hashCode()
                if (sig == lastRepeatSig) {
                    repeatCount++
                } else {
                    lastRepeatSig = sig
                    repeatCount = 1
                }
                if (repeatCount >= 4) {
                    Log.w(TAG, "repeat breaker: ${tc.name} × $repeatCount")
                    addMessage(ChatMessage(System.currentTimeMillis(), "tool", "🔁 Repeat breaker — the same call repeated ${repeatCount} times"))
                    conversationHistory.add(mapOf("role" to "tool",
                        "content" to "ERROR: the same tool with the same args repeated ${repeatCount} times with no result. Try a completely different approach or explain to the user.",
                        "tool_call_id" to tc.id))
                    repeatCount = 0
                    lastRepeatSig = ""
                    continue
                }
                var argsBroken = false
                val args = try {
                    val j = JSONObject(tc.argsJson.ifBlank { "{}" })
                    // truncation mid-JSON: args without the closing `}`
                    if (tc.argsJson.isNotBlank() && !tc.argsJson.trim().endsWith("}")) {
                        argsBroken = true
                    }
                    j
                } catch (e: Exception) {
                    argsBroken = true
                    JSONObject()
                }
                if (argsBroken) {
                    val obs = "ERROR: args of tool '${tc.name}' were truncated (out of tokens). Split this task into chunks${if (tc.name == "write_file") " — max 800 chars per chunk with append:true" else ""}${if (tc.name == "skill_save") " — save a SHORT core version now (name + 5-10 bullet steps), then use skill_update to append details" else ""}"
                    conversationHistory.add(mapOf("role" to "tool", "content" to obs, "tool_call_id" to tc.id))
                    addMessage(ChatMessage(System.currentTimeMillis(), "tool", "✂️ ${tc.name}: args truncated — splitting into smaller pieces"))
                    continue
                }

                if (tc.name == "done" || tc.name == "answer") {
                    _agentStatus.value = ""
                    lastAnswer = cleanMarkdown(
                        args.optString("result").ifBlank { args.optString("text") }.ifBlank { res.content }
                    )
                    conversationHistory.add(mapOf("role" to "tool", "content" to "ok", "tool_call_id" to tc.id))
                    if (lastAnswer.isNotBlank()) addMessage(ChatMessage(System.currentTimeMillis(), "assistant", lastAnswer))
                    return
                }

                val answer = execAndRecord(tc.name, args, tc.id)
                if (answer != null) {
                    _agentStatus.value = ""
                    lastAnswer = answer
                    addMessage(ChatMessage(System.currentTimeMillis(), "assistant", answer))
                    conversationHistory.add(mapOf("role" to "assistant", "content" to answer))
                    return
                }
            }
        }

        // tool budget exhausted
        _agentStatus.value = ""
        val finalMsg = lastAnswer.ifBlank {
            "⚙️ Several tools ran but the job is not finished. Should I continue?"
        }
        addMessage(ChatMessage(System.currentTimeMillis(), "assistant", finalMsg))
    }

    /**
     * Execute one tool + live display + record the observation in history.
     * tcId != null -> native format (role=tool); null -> legacy format (OBSERVATION).
     * If the tool is done, the answer text is returned.
     */
    private fun writeLabel(args: JSONObject): String {
        val n = args.optString("content").length
        val path = args.optString("path")
        val name = path.substringAfterLast("/")
        // overwriting an existing file = editing, not creating
        val exists = try {
            java.io.File(if (path.startsWith("/")) path else java.io.File(context.filesDir, path).absolutePath).isFile
        } catch (e: Exception) { false }
        val base = if (exists && !args.optBoolean("append")) "✏️ Editing $name" else "📝 Writing $name"
        return if (args.optBoolean("append")) base + " +" + n + "ch" else base + " (" + n + "ch)"
    }

    private fun statusLabel(tool: String, args: JSONObject): String = when (tool) {
        "plan" -> "📋 Planning..."
        "write_file" -> writeLabel(args)
        "read_file" -> "📖 Reading ${args.optString("path").substringAfterLast("/")}"
        "edit_file" -> "✏️ Editing ${args.optString("path").substringAfterLast("/")} (find/replace)"
        "list_files", "list_dir_custom" -> "📂 Listing files..."
        "shell", "run_script" -> "⚙️ ${args.optString("cmd").take(55)}"
        "run_background" -> "🔄 Background: ${args.optString("cmd").take(50)}"
        "poll_process" -> "🔄 Checking job #${args.optInt("pid")}..."
        "serve" -> "🌐 Starting server :${args.optInt("port", 8080)}..."
        "stop_server" -> "🌐 Stopping server..."
        "server_status" -> "🌐 Checking server..."
        "vision_analyze" -> "👁 Analyzing image..."
        "fetch_url" -> "📖 Reading page..."
        "search" -> "🔎 Searching..."
        "open_url" -> "🌐 Opening page..."
        "open_settings" -> "📱 Opening Settings..."
        "open_app" -> "📱 Opening ${args.optString("app", args.optString("package"))}..."
        "get_screen" -> "👁 Reading screen..."
        "click" -> "👆 Tapping ${args.optString("text").take(20)}..."
        "type" -> "⌨️ Typing..."
        "image_edit" -> "🖼 Editing image (${args.optString("action")})..."
        "send_file" -> "📎 Sending file..."
        "wait" -> "⏱ Waiting..."
        "install_tool" -> "📦 Installing ${args.optString("name", "tool")}..."
        "skill_save" -> "💾 Saving skill: ${args.optString("name")}..."
        "skill_update" -> "🧠 Updating skill: ${args.optString("name")}..."
        "skill_load" -> "🧠 Loading skill: ${args.optString("name")}..."
        "skill_list" -> "🧠 Listing skills..."
        "skill_delete" -> "🗑 Deleting skill: ${args.optString("name")}..."
        "memory" -> "🧠 Writing memory..."
        "session_search" -> "🔍 Searching old sessions..."
        "python" -> "🐍 ${args.optString("args").ifBlank { args.optString("cmd") }.take(55)}"
        "pip" -> "🐍 pip ${args.optString("cmd").take(50)}"
        "run_ffmpeg" -> "🎬 ffmpeg: ${args.optString("cmd").take(40)}..."
        else -> "🔧 $tool"
    }

    private suspend fun execAndRecord(toolName: String, args: JSONObject, tcId: String?): String? {
        val label = statusLabel(toolName, args)
        _agentStatus.value = label
        val pillId = msgSeq.incrementAndGet()
        addMessage(ChatMessage(pillId, "tool", label))

        var observation = withContext(Dispatchers.IO) {
            toolRegistry.execute(toolName, args)
        }

        // vision upgrade: the image is sent to a vision model
        if (toolName == "vision_analyze" && observation.startsWith("IMAGE_READY:")) {
            val path = args.optString("path")
            val question = args.optString("question").ifBlank { "Describe this image precisely and completely" }
            _agentStatus.value = "👁 Sending image to vision model..."
            val vd = llmEngine.generateWithImage(
                "You are the agent's eyes. Look at the image and answer the question precisely. Answer in the language of the question.",
                path, question
            )
            observation = if (vd.startsWith("Error:")) {
                "Image description failed: $vd — tell the user and ask them to describe the image"
            } else vd
        }

        // did send_file stage a file?
        if (observation.startsWith("FILE_ATTACHED:")) {
            val p = observation.removePrefix("FILE_ATTACHED:").trim()
            val f = File(p)
            if (f.exists()) {
                pendingOutgoing.add(
                    Attachment(p, f.name, android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*",
                        f.extension.lowercase() in listOf("png","jpg","jpeg","webp","gif","bmp"))
                )
            }
        }

        // result preview under the label — the user sees exactly what happened
        val preview = if (observation.startsWith("FILE_ATTACHED:")) {
            "📎 " + observation.removePrefix("FILE_ATTACHED:").trim().substringAfterLast("/")
        } else observation.take(150).replace("\n", " ⏎ ")
        updateMessage(pillId, "$label\n│ $preview")

        Log.d(TAG, "tool $toolName → ${observation.take(300)}")

        if (tcId != null) {
            conversationHistory.add(mapOf("role" to "tool", "content" to observation, "tool_call_id" to tcId))
        } else {
            conversationHistory.add(
                mapOf("role" to "assistant", "content" to JSONObject().put("tool", toolName).put("args", args).toString())
            )
            conversationHistory.add(mapOf("role" to "user", "content" to "OBSERVATION: $observation"))
        }
        return null
    }
    /**
     * Detect whether the model returned tool JSON or plain text
     */
    private fun parseAction(raw: String): JSONObject? {
        // model-specific format: <longcat_tool_call>name <longcat_arg_key>k</...><longcat_arg_value>v</...>...</longcat_tool_call>
        if (raw.contains("<longcat_tool_call>")) {
            try {
                val body = raw.substringAfter("<longcat_tool_call>")
                    .substringBefore("</longcat_tool_call>")
                val toolName = body.takeWhile { it != '<' && it != '\n' }.trim()
                val args = JSONObject()
                val pairRegex = Regex(
                    "<longcat_arg_key>(.*?)</longcat_arg_key>\\s*<longcat_arg_value>(.*?)</longcat_arg_value>",
                    RegexOption.DOT_MATCHES_ALL
                )
                pairRegex.findAll(body).forEach { m ->
                    args.put(m.groupValues[1].trim(), m.groupValues[2].trim())
                }
                if (toolName.isNotBlank()) {
                    Log.d(TAG, "parsed longcat_tool_call: $toolName args=$args")
                    return JSONObject().put("tool", toolName).put("args", args)
                }
            } catch (e: Exception) {
                Log.e(TAG, "longcat parse failed", e)
            }
        }

        // strip ```json ... ``` fences
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        if (!text.startsWith("{")) return null

        // extract the first balanced JSON object
        val first = text.indexOf('{')
        var depth = 0
        var inStr = false
        var esc = false
        var last = -1
        for (i in first until text.length) {
            val ch = text[i]
            if (esc) { esc = false; continue }
            when {
                ch == '\\' && inStr -> esc = true
                ch == '"' -> inStr = !inStr
                !inStr && ch == '{' -> depth++
                !inStr && ch == '}' -> {
                    depth--
                    if (depth == 0) { last = i; break }
                }
            }
        }
        if (last < 0) return null

        return try {
            val json = JSONObject(text.substring(first, last + 1))
            if (json.has("tool") || json.has("action") || json.has("steps") || json.has("name")) json else null
        } catch (e: Exception) {
            null
        }
    }

    private fun describeStep(tool: String, args: JSONObject): String {
        return when (tool) {
            "open_settings" -> "Opening phone settings..."
            "open_app" -> "Opening app ${args.optString("package")}"
            "get_screen" -> "Reading the current screen..."
            "click" -> "Clicking \"${args.optString("text")}\""
            "type" -> "Typing: ${args.optString("text")}"
            "scroll_down" -> "Scrolling..."
            "back" -> "Back button"
            "home" -> "Going home"
            "plan" -> {
                val st = args.optJSONArray("steps")
                if (st != null) {
                    val sb = StringBuilder("📋 Plan:\n")
                    for (i in 0 until st.length()) sb.append("${i + 1}. ${st.optString(i)}\n")
                    sb.append("starting step 1")
                    sb.toString()
                } else "📋 Execution plan"
            }
            "serve" -> "🌐 Starting local server on port ${args.optInt("port", 8080)}"
            "stop_server" -> "⏹ Server stopped"
            "server_status" -> "🔍 Checking server status"
            "write_file" -> "📝 Writing: ${args.optString("path")}${if (args.optBoolean("append")) " (append)" else ""}"
            "edit_file" -> "Editing file ${args.optString("path")} (find/replace)"
            "list_files" -> "📂 Listing: ${args.optString("path", ".")}"
            "serve_dir" -> "🌐 ${args.optString("path")}"
            "vision_analyze" -> "Inspecting the image..."
            "run_background" -> "Background: ${args.optString("name", args.optString("cmd").take(40))}"
            "poll_process" -> "Checking process #${args.optInt("pid")}"
            "fetch_url" -> "Reading page: ${args.optString("url").take(60)}"
            "search" -> "Searching the web for \"${args.optString("query").take(40)}\"..."
            "open_url" -> "Opening in browser: ${args.optString("url").take(60)}"
            "shell" -> "Running shell: `${args.optString("cmd").take(60)}`"
            "wait" -> "Waiting..."
            else -> "Running $tool"
        }
    }

    private val skillStore = SkillStore(context)

    /** Block of matching skills - injected into the system prompt */
    /** Memory file injection (MEMORY.md/USER.md style) - every message */
    private fun memoryBlock(): String {
        return try {
            val store = com.agent.app.memory.MemoryStore(context)
            val user = store.read("user")
            val mem = store.read("memory")
            buildString {
                if (user.isNotEmpty()) {
                    append("\n### 👤 USER PROFILE (about this user — always authoritative)\n")
                    user.forEach { append("• $it\n") }
                }
                if (mem.isNotEmpty()) {
                    append("\n### 🧠 MEMORY (durable facts)\n")
                    mem.forEach { append("• $it\n") }
                }
            }
        } catch (e: Exception) { "" }
    }

    private fun skillsBlock(userMessage: String): String {
        return try {
            val matched = skillStore.matchKeywords(userMessage, limit = 2)
            if (matched.isEmpty()) "" else {
                Log.d(TAG, "skills injected: ${matched.joinToString { it.first }}")
                buildString {
                    append("\n### 🧠 Matching saved skills on this device — follow them:\n")
                    matched.forEach { (n, c) ->
                        append("\n--- SKILL: $n ---\n").append(c.take(2500)).append("\n")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "skill match failed: ${e.message}")
            ""
        }
    }

    private fun buildSystemPrompt(userMessage: String = ""): String {
        val recentMemories = memory.getRelevantMemories("", 5).joinToString("\n") { "- ${it.content}" }
        return """
You are Galahad-X Agent — a full AI agent running on the user's Android phone.
You act like a professional assistant: you chat, do real tasks, write scripts, and find and fixErrors yourself.

## Your runtime environment (no root, no Termux)
- Shell: toybox sh with its tools (ls, cp, grep, sed, awk, tar...)
- **Python 3.14 + pip + numpy + pillow are PREINSTALLED** (auto-installed on first launch): use the `python` / `pip` tools or plain `python3` in shell right away. NEVER run install_tool for python unless a tool explicitly says "python not installed" — it wastes a step
- ffmpeg: `install_tool name=ffmpeg`, then `run_ffmpeg`
- Any other CLI tool (nmap, curl, git...): `install_tool name=<tool> url=<static binary url for this arch>` - search the web for a static build if you do not know the url; chmod is automatic
- **NEVER say "I don't have python / it can't be installed" — install it first**; only after a real failure say so honestly
- Projects live in the app's private space; send the final output with `send_file`

## Your tools
${toolRegistry.toolSchemasJson()}
${skillsBlock(userMessage)}${memoryBlock()}

## Rules

### 1. Classify the request
- Small talk / a question (hi, how are you, what do you think) -> plain text answer, no JSON
- Any action on the phone or system -> answer with a tool JSON

### 1b. Iron rule (critical)
- NEVER answer a task request with text like "let me check / I'll look into it" — that is forbidden
- The FIRST reply to any task request = tool JSON. Unsure which tool? Call get_screen, vision_analyze or fetch_url
- If no tool can do the job, honestly say "this is not possible with my tools" and suggest an alternative — never make empty promises
- **Never give up without trying**: missing a tool? Run `install_tool` first (e.g. ffmpeg for video/image/audio) and continue. Only after a genuinely failed install may you say so
- **Skills (long-term memory):**
  - Before starting work, call `skill_list` — the routine may already be saved
  - Completed a complex task (3+ steps) likely to repeat? **Save it immediately with `skill_save`** (short English name; step-by-step content with exact commands/sample files)
  - Ran an existing skill and learned a new tip/error/fix? Update it with `skill_update`
  - Skills matching the user's request are auto-injected below this prompt — follow them
- **Memory tool:** store durable facts with it, not in skills. User preferences/personal info -> target:"user"; other facts (projects, decisions, environment) -> target:"memory". Single: action+content; batch: operations=[{action, content?, old_text?}]. Edit/remove via old_text (an exact snippet of the previous text). Keep entries short and high-signal (caps: user 1375, memory 2200 chars — rejected add = no room). Skills are only for repeatable routines
- "continue / keep going" = a work command. Immediately call a tool and resume where you left off. Forbidden: text replies like "I'm continuing, hold on" — that wastes tokens

### 2. Work loop (Perceive -> Act)
For every task:
1. First get_screen or get_info to know the current state
2. Call the right tool
3. Read the OBSERVATION
4. Done -> call done, otherwise next tool

### 3. Automatic error solving (most important rule)
When an OBSERVATION contains ERROR, a non-zero exit code, or STDERR:
- Analyze the cause
- Find the fix (e.g. command missing -> find a replacement with `which`; typo -> correct it; file missing -> write it first, then run)
- Retry
- Up to 3 retries before telling the user it failed
- Every retry must use a tool; never give up unless it is truly impossible

### 4. Scripting
- Write scripts with write_file into scripts/
- To FIX an error in a script: read_file it, then edit_file with the exact old_text/new_text - do NOT rewrite the whole file
- Run them with run_script (cwd = app files dir = the same place list_files/read_file see, so relative paths like scripts/x.py work)
- python3 IS on the shell PATH: after install_tool name=python, both `python3 script.py` in shell/run_script and the python tool work
- For one-off python: python tool with -c — multiline code is supported, quotes are handled for you
- On error: read_file, fix, run again
- Available: sh, toybox (sed, awk, grep, tar, gzip, ping, ss, netstat, df, du, find, sort, wc...)
- NEVER name your script files after python stdlib modules (inspect.py, json.py, sys.py...) - it shadows the real module and breaks imports

### 5. Working with apps
- Open: open_app by package name or app name
- Inside an app: get_screen -> click the item text -> type into the field
- Always get_screen again after each action to see the result
- Scroll: scroll_down or swipe (accessibility-based)

### 5b. Forbidden shell commands (they cause Permission Denial)
- am start / am broadcast / input tap / input text / svc -> Permission Denial in a normal app, do not use
- Use instead: open_settings, open_app, click, type, scroll_down, back, home
- Shell IS good for: pm list, getprop, df, dumpsys (read-only), ls, cat, grep, and scripts

### 5c. Running binaries & installing tools (no root)
- Binaries can only execute from the app's own storage: /data/user/0/com.agent.app/files/ (W^X restriction solved)
- Tool install recipe: download (static binary) -> shell: chmod 755 -> run directly from files/
- Do NOT run from /data/local/tmp (SELinux Permission denied, exit 126)
- Scripts run via sh and need no chmod
- If exec fails with exit 126: copy the file into files/, chmod 755, run again

### 6. Attached files (image/video/audio/document)
- When the user attaches a file, its path appears in the message as `[att: ...]`
- Inspect: list_inbox or file_info (type, dimensions, size)
- Image edits: image_edit (rotate/resize/convert/compress)
- Reading text: read_file (text/logs/scripts)
- Send output to the user: send_file with the result path -> it attaches to your reply
- Open for the user: open_file

### 6c. Images and vision
- User sent an image (`[att: ...jpg]` in the message) -> call vision_analyze with that path + the user's question
- The tool sends the image to a vision model and returns a text answer; decide afterwards
- Editing images: first vision_analyze to see what it is, then image_edit (rotate/resize/format), then send_file
- If vision errors, tell the user the vision model did not answer — no promises

### 6d. Web & internet
- Search: search with an English/Persian query -> results (title + link + snippet)
- Read a page: fetch_url with the full URL (https://...) -> page text
- Open in the user's browser: open_url
- If fetch_url/search hits a connection/filter error -> give the URL to the user and say they should open it
- Never guess URLs; if you don't know the exact URL, search first

### 6e. Long jobs & background
- Long work (big downloads, heavy processing, multi-minute jobs) -> run_background
- The tool returns a pid immediately; keep doing other things
- Check later with poll_process: RUNNING means wait and poll again; FINISHED gives output and exit code

### 6f. Big projects (website, app, multi-file) — important rule
- NEVER dump full code/HTML into chat — write files only, via write_file. Dumping code in chat is forbidden
- First call plan (3-8 short steps) — the user sees the plan
- Then step by step: each file = one write_file. For large content, chunk it with "append": true (max 1500-2000 chars each)
- Suggested layout: a site/ folder with index.html + style.css + script.js
- When files are ready: serve (dir = that folder), then open_url so the user sees the site in the browser
- If tool args get truncated (JSON error) or any error: continue in smaller chunks — never back down

### 7. Reply style
- Reply in the same language the user writes (Persian stays Persian). Friendly, short
- After doing work, briefly summarize what you did
- Do not use markdown stars (**bold**) — write plain

$recentMemories
        """.trimIndent()
    }

    fun clearChat() {
        if (_isProcessing.value) return
        _messages.value = emptyList()
        conversationHistory.clear()
        addMessage(ChatMessage(System.currentTimeMillis(), "assistant", "Chat cleared. What should I do? 🤖"))
    }

    private fun cleanMarkdown(text: String): String {
        return text.replace("**", "").replace("__", "")
    }

    private fun updateMessage(id: Long, newContent: String) {
        _messages.value = _messages.value.map {
            if (it.id == id) it.copy(content = newContent) else it
        }
        // persisted via the store too (next addMessage saves the session)
    }

    private fun addMessage(message: ChatMessage) {
        // if an output file is pending and this is an agent message, attach it
        val msg = if (message.role == "assistant" && pendingOutgoing.isNotEmpty() && message.role != "tool") {
            val merged = message.copy(attachments = message.attachments + pendingOutgoing.toList())
            pendingOutgoing.clear()
            merged
        } else message
        val current = _messages.value.toMutableList()
        current.add(msg)
        _messages.value = current
        // durable save to the session file (messages + raw LLM history)
        val histSnapshot = conversationHistory.toList()
        scope.launch(Dispatchers.IO) {
            store.saveMessages(sessionId, current, histSnapshot)
        }
    }
}

data class Attachment(
    val path: String,      // absolute path inside app storage
    val name: String,
    val mime: String,
    val isImage: Boolean
)

data class ChatMessage(
    val id: Long,
    val role: String, // user | assistant | tool
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<Attachment> = emptyList()
)
