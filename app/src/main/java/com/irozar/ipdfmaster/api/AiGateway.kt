package com.irozar.ipdfmaster.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AiGateway {
    const val API_KEY_MISSING = "APIKeyMissing"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun generateText(
        provider: String,
        apiKey: String,
        model: String,
        prompt: String,
        systemInstruction: String? = null
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext API_KEY_MISSING

        try {
            when (provider.lowercase()) {
                "openai" -> requestOpenAi("https://api.openai.com/v1/chat/completions", apiKey, model, prompt, systemInstruction)
                "claude" -> requestClaude(apiKey, model, prompt, systemInstruction)
                "openrouter" -> requestOpenAi("https://openrouter.ai/api/v1/chat/completions", apiKey, model, prompt, systemInstruction)
                else -> requestGemini(apiKey, model, prompt, systemInstruction)
            }
        } catch (e: Exception) {
            "Error: ${e.message ?: "AI request failed"}"
        }
    }

    private fun requestGemini(apiKey: String, model: String, prompt: String, systemInstruction: String?): String {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            }))
            if (!systemInstruction.isNullOrBlank()) {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
            }
            put("generationConfig", JSONObject().put("temperature", 0.4))
        }

        fun callGemini(modelName: String): Pair<Int, String> {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey")
                .post(body.toString().toRequestBody(jsonMediaType))
                .build()

            return client.newCall(request).execute().use { response ->
                response.code to response.body?.string().orEmpty()
            }
        }

        val requestedModel = model.trim().ifBlank { "gemini-flash-latest" }
        val first = callGemini(requestedModel)
        val result = if (first.first == 404 && requestedModel != "gemini-flash-latest") {
            callGemini("gemini-flash-latest")
        } else {
            first
        }

        val code = result.first
        val raw = result.second
        if (code !in 200..299) {
            return if (code == 404) {
                "Error: Gemini model '$requestedModel' was not found. Open Activate AI Features and use gemini-flash-latest or gemini-3.5-flash."
            } else {
                "Error: Gemini returned HTTP $code."
            }
        }

        return JSONObject(raw)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?: "I couldn't generate a response. Please try again."
    }

    private fun requestOpenAi(url: String, apiKey: String, model: String, prompt: String, systemInstruction: String?): String {
        val messages = JSONArray()
        if (!systemInstruction.isNullOrBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemInstruction))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.4)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) return "Error: AI provider returned HTTP ${response.code}."
            JSONObject(raw)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
                ?: "I couldn't generate a response. Please try again."
        }
    }

    private fun requestClaude(apiKey: String, model: String, prompt: String, systemInstruction: String?): String {
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 2048)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
        if (!systemInstruction.isNullOrBlank()) {
            body.put("system", systemInstruction)
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()

        return client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) return "Error: Claude returned HTTP ${response.code}."
            JSONObject(raw)
                .optJSONArray("content")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?: "I couldn't generate a response. Please try again."
        }
    }
}
