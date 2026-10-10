package com.agent.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ToolCallReq(val id: String, val name: String, val argsJson: String)
data class NativeResult(
    val ok: Boolean,
    val error: String,
    val content: String,
    val toolCalls: List<ToolCallReq>,
    val finishReason: String
)

class LLMEngine {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    enum class Mode { API, LOCAL, HYBRID }

    private var currentMode: Mode = Mode.API
    private var apiKey: String = ""
    @Volatile private var activeCall: okhttp3.Call? = null

    /** Cancel the in-flight request immediately (stop button) */
    fun cancelActive() {
        try { activeCall?.cancel() } catch (_: Exception) {}
    }

    private var apiEndpoint: String = "https://api.openai.com/v1"
    private var apiModel: String = "gpt-4"
    private var localModel: String = "llama-3.2-3b"

    fun setMode(mode: Mode) {
        currentMode = mode
    }

    fun setApiKey(key: String) {
        apiKey = key
    }

    fun setApiEndpoint(endpoint: String) {
        apiEndpoint = endpoint
    }

    fun setApiModel(model: String) {
        apiModel = model
    }

    fun setLocalModel(model: String) {
        localModel = model
    }

    suspend fun generateResponse(prompt: String, context: String = ""): String {
        return when (currentMode) {
            Mode.API -> callAPI(prompt, context)
            Mode.LOCAL -> callLocal(prompt, context)
            Mode.HYBRID -> callHybrid(prompt, context)
        }
    }

    /**
     * Chat with a system prompt and full history (for the tool-use loop)
     */
    suspend fun generateChat(
        systemPrompt: String,
        history: List<Map<String, String>>,
        temperature: Double = 0.6,
        maxTokens: Int = 2048
    ): String = withContext(Dispatchers.IO) {
        try {
            val messages = org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                history.takeLast(30).forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg["role"] ?: "user")
                        put("content", msg["content"] ?: "")
                    })
                }
            }

            val json = JSONObject().apply {
                put("model", apiModel)
                put("messages", messages)
                put("temperature", temperature)
                put("max_tokens", maxTokens)
            }

            val url = "$apiEndpoint/chat/completions"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val call = client.newCall(request)
            activeCall = call
            val response = try { call.execute() } finally { activeCall = null }
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val jsonResponse = JSONObject(body)
                jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } else {
                "Error: ${response.code} - ${body.take(500)}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /**
     * Vision: same text request but with an image attachment (data URI).
     * Tries vision-capable models in order. Returns an error if none answer.
     */
    suspend fun generateWithImage(
        systemPrompt: String,
        imagePath: String,
        userText: String
    ): String = withContext(Dispatchers.IO) {
        val file = java.io.File(imagePath)
        if (!file.exists()) return@withContext "Error: not an image file: $imagePath"
        val bytes = file.readBytes()
        if (bytes.size > 8_000_000) return@withContext "Error: image larger than 8 MB"
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val mime = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "image/jpeg"
        val dataUri = "data:$mime;base64,$b64"

        val visionModels = listOf("kimi-k3", "auto")
        var lastErr = ""
        for (model in visionModels) {
            try {
                val content = org.json.JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", userText))
                    put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", dataUri)))
                }
                val messages = org.json.JSONArray().apply {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", content))
                }
                val json = JSONObject()
                    .put("model", model)
                    .put("messages", messages)
                    .put("temperature", 0.3)
                    .put("max_tokens", 1500)
                val request = Request.Builder()
                    .url("$apiEndpoint/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val call = client.newCall(request)
            activeCall = call
            val response = try { call.execute() } finally { activeCall = null }
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val text = JSONObject(body).getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message").getString("content")
                    if (text.isNotBlank()) return@withContext "[$model] $text"
                } else {
                    lastErr = "HTTP ${response.code}"
                }
            } catch (e: Exception) {
                lastErr = e.javaClass.simpleName
            }
        }
        "Error: no vision model answered ($lastErr)"
    }

    /**
     * Native tool-calling: sends a tools array, returns structured
     * tool_calls — no JSON parsing from model prose.
     */
    suspend fun generateNative(
        systemPrompt: String,
        history: List<Map<String, String>>,
        toolsJson: String,
        temperature: Double = 0.5,
        maxTokens: Int = 6144
    ): NativeResult = withContext(Dispatchers.IO) {
        try {
            // config guard: empty or schemeless endpoint fails HERE with a clear
            // message instead of OkHttp's "Expected URL scheme 'http' or 'https'"
            val ep = apiEndpoint.trim()
            if (ep.isEmpty() || !(ep.startsWith("http://") || ep.startsWith("https://"))) {
                return@withContext NativeResult(false,
                    "CONFIG: server address is not set (Base URL is empty or missing https://) - open Settings, edit the provider, set Base URL like https://host/v1 and Activate it",
                    "", emptyList(), "")
            }
            val messages = org.json.JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                // history character budget: walk back from newest to the cap — keeps tool pairs intact
                val picked = ArrayList<Map<String, String>>()
                var used = 0
                for (h in history.asReversed()) {
                    val size = (h["content"]?.length ?: 0) + (h["tool_calls"]?.length ?: 0) + 60
                    if (used + size > 70_000 && picked.size >= 8) break
                    used += size
                    picked.add(0, h)
                }
                picked.forEach { h ->
                    val role = h["role"] ?: "user"
                    val o = JSONObject().put("role", role)
                    o.put("content", h["content"] ?: "")
                    val tc = h["tool_calls"]
                    if (tc != null) {
                        try { o.put("tool_calls", org.json.JSONArray(tc)) } catch (_: Exception) {}
                    }
                    if (role == "tool") h["tool_call_id"]?.let { o.put("tool_call_id", it) }
                    put(o)
                }
            }
            val json = JSONObject()
                .put("model", apiModel)
                .put("messages", messages)
                .put("tools", org.json.JSONArray(toolsJson))
                .put("tool_choice", "auto")
                .put("temperature", temperature)
                .put("max_tokens", maxTokens)
            val request = Request.Builder()
                .url("$ep/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val call = client.newCall(request)
            activeCall = call
            val response = try { call.execute() } finally { activeCall = null }
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext NativeResult(false, "HTTP ${response.code}: ${body.take(300)}", "", emptyList(), "")
            }
            val obj = JSONObject(body)
            val choice = obj.getJSONArray("choices").getJSONObject(0)
            val msg = choice.optJSONObject("message") ?: JSONObject()
            val calls = mutableListOf<ToolCallReq>()
            msg.optJSONArray("tool_calls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val fn = c.optJSONObject("function") ?: continue
                    calls.add(ToolCallReq(
                        c.optString("id", "call_$i"),
                        fn.optString("name"),
                        fn.optString("arguments", "{}")
                    ))
                }
            }
            NativeResult(true, "", msg.optString("content", ""), calls, choice.optString("finish_reason", ""))
        } catch (e: Exception) {
            NativeResult(false, e.message ?: e.javaClass.simpleName, "", emptyList(), "")
        }
    }

    private suspend fun callAPI(prompt: String, context: String): String = withContext(Dispatchers.IO) {
        try {
            val messages = org.json.JSONArray().apply {
                if (context.isNotEmpty()) {
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", "Context: $context")
                    })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            }

            val json = JSONObject().apply {
                put("model", apiModel)
                put("messages", messages)
                put("temperature", 0.7)
                put("max_tokens", 2048)
            }

            val url = "$apiEndpoint/chat/completions"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val call = client.newCall(request)
            activeCall = call
            val response = try { call.execute() } finally { activeCall = null }
            val body = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val jsonResponse = JSONObject(body)
                jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } else {
                "Error: ${response.code} - $body"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    private suspend fun callLocal(prompt: String, context: String): String = withContext(Dispatchers.IO) {
        // TODO: Implement local model inference (llama.cpp / ONNX)
        "Local mode not yet implemented. Model: $localModel"
    }

    private suspend fun callHybrid(prompt: String, context: String): String = withContext(Dispatchers.IO) {
        // TODO: Implement hybrid routing logic
        // Simple tasks -> Local, Complex tasks -> API
        if (prompt.length < 100) {
            callLocal(prompt, context)
        } else {
            callAPI(prompt, context)
        }
    }
}
