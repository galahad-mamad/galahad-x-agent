package com.agent.app.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class ProviderCfg(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val models: List<String> = emptyList(),
    val model: String = ""
)

/**
 * Multi-provider API manager — providers.json in filesDir.
 * Each provider: name + baseUrl (openai-style /v1) + key + selected model list + active model.
 * activeId marks the current provider; LLMEngine reads from it.
 */
object ProviderStore {

    private const val TAG = "ProviderStore"
    private const val FILE = "providers.json"

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun load(context: Context): Pair<List<ProviderCfg>, String?> {
        return try {
            val f = file(context)
            if (!f.exists()) return emptyList<ProviderCfg>() to null
            val root = JSONObject(f.readText())
            val activeId = if (root.isNull("activeId")) null else root.optString("activeId")
            val arr = root.optJSONArray("providers") ?: JSONArray()
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val modelsArr = o.optJSONArray("models") ?: JSONArray()
                ProviderCfg(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    baseUrl = o.optString("baseUrl"),
                    apiKey = o.optString("apiKey"),
                    models = (0 until modelsArr.length()).map { modelsArr.getString(it) },
                    model = o.optString("model")
                )
            }
            list to activeId
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e); emptyList<ProviderCfg>() to null
        }
    }

    fun saveAll(context: Context, providers: List<ProviderCfg>, activeId: String?) {
        try {
            val root = JSONObject()
            root.put("activeId", activeId ?: JSONObject.NULL)
            val arr = JSONArray()
            providers.forEach { p ->
                arr.put(JSONObject().apply {
                    put("id", p.id); put("name", p.name); put("baseUrl", p.baseUrl)
                    put("apiKey", p.apiKey)
                    put("models", JSONArray(p.models))
                    put("model", p.model)
                })
            }
            root.put("providers", arr)
            file(context).writeText(root.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "save failed", e)
        }
    }

    /** First launch: seed the default provider from legacy settings */
    fun ensureSeeded(context: Context, endpoint: String, apiKey: String, model: String): Pair<List<ProviderCfg>, String?> {
        val (list, active) = load(context)
        if (list.isNotEmpty()) return list to active
        val p = ProviderCfg(
            id = "default",
            name = "Default API",
            baseUrl = endpoint,
            apiKey = apiKey,
            model = model
        )
        saveAll(context, listOf(p), p.id)
        return listOf(p) to p.id
    }

    /**
     * Fetch model list: GET {baseUrl}/models with Bearer (OpenAI style).
     * Handles both {"data":[{"id":...}]} and a plain ["id",...] array.
     */
    suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                val acc = LinkedHashSet<String>()
                var lastErr = ""
                // some gateways paginate (20 per page): try big limits first, then page through
                val queries = ArrayList<String>()
                queries.add("?limit=1000&per_page=1000&page_size=1000")
                queries.add("")
                for (pg in 1..30) queries.add("?page=$pg&limit=100&per_page=100")
                for (q in queries) {
                    val url = baseUrl.trimEnd('/') + "/models" + q
                    val req = Request.Builder().url(url)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .get().build()
                    val before = acc.size
                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: ""
                        if (resp.isSuccessful) acc.addAll(parseModels(body))
                        else lastErr = "HTTP ${resp.code}: ${body.take(120)}"
                    }
                    // if a page added nothing and we already have some -> stop
                    if (acc.isNotEmpty() && acc.size == before && q.isNotEmpty() && !q.contains("limit=1000")) break
                    if (acc.size >= 1000) break
                }
                if (acc.isEmpty()) throw Exception(if (lastErr.isNotBlank()) lastErr else "empty model list")
                acc.toList()
            }
        }

    fun parseModels(body: String): List<String> {
        return try {
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                (0 until arr.length()).mapNotNull {
                    val v = arr.get(it)
                    when (v) {
                        is String -> v
                        is JSONObject -> v.optString("id").ifBlank { null }
                        else -> null
                    }
                }
            } else {
                val root = JSONObject(trimmed)
                val data = root.optJSONArray("data") ?: JSONArray()
                (0 until data.length()).mapNotNull { i ->
                    val o = data.optJSONObject(i) ?: return@mapNotNull null
                    o.optString("id").ifBlank { null }
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun newId() = "p" + System.currentTimeMillis().toString(36)
}
