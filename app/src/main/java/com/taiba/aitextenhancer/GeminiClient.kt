package com.taiba.aitextenhancer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ChatMessage(val role: String, val text: String) // role: "user" | "assistant"

sealed class StreamEvent {
    data class Delta(val text: String) : StreamEvent()
    data class Done(val fullText: String) : StreamEvent()
    data class Error(val code: String, val message: String) : StreamEvent()
}

enum class EnhanceMode(val key: String) {
    IMPROVE("improve"),
    GRAMMAR("grammar"),
    PROFESSIONAL("professional"),
    FRIENDLY("friendly"),
    SHORTEN("shorten"),
    EXPAND("expand"),
    CUSTOM("custom")
}

object GeminiClient {

    private val MODE_PROMPTS = mapOf(
        "improve" to "You are a writing assistant. Improve the clarity, flow and correctness of the following text while keeping the author's original meaning, tone and language. Return ONLY the improved text, nothing else, no quotes, no explanation.",
        "grammar" to "You are a grammar checker. Fix only the spelling and grammar mistakes in the following text. Do not change the meaning, tone or style. Return ONLY the corrected text, nothing else, no quotes, no explanation.",
        "professional" to "Rewrite the following text to sound more professional and polished, suitable for business communication. Keep the same language and core meaning. Return ONLY the rewritten text, nothing else, no quotes, no explanation.",
        "friendly" to "Rewrite the following text to sound more friendly, warm and casual. Keep the same language and core meaning. Return ONLY the rewritten text, nothing else, no quotes, no explanation.",
        "shorten" to "Make the following text more concise without losing its key meaning. Return ONLY the shortened text, nothing else, no quotes, no explanation.",
        "expand" to "Expand the following text with a bit more detail and clarity, keeping the same tone and meaning. Return ONLY the expanded text, nothing else, no quotes, no explanation."
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    private fun buildEnhanceBody(text: String, mode: EnhanceMode, customPrompt: String?): JSONObject {
        val systemInstruction = if (mode == EnhanceMode.CUSTOM) {
            "${customPrompt?.trim().orEmpty()}\n\nApply this instruction to the text the user provides. Return ONLY the resulting text, nothing else, no quotes, no explanation, no preamble."
        } else {
            MODE_PROMPTS[mode.key] ?: MODE_PROMPTS.getValue("improve")
        }
        return JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
            put("contents", JSONArray().put(
                JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))
            ))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.5)
                put("maxOutputTokens", 4096)
                put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            })
        }
    }

    private fun buildChatBody(history: List<ChatMessage>): JSONObject {
        val contents = JSONArray()
        history.forEach { m ->
            contents.put(
                JSONObject().put("role", if (m.role == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", m.text)))
            )
        }
        return JSONObject().apply {
            put("contents", contents)
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.6)
                put("maxOutputTokens", 4096)
                put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            })
        }
    }

    /**
     * Streams Gemini response. onEvent baar baar call hota hai: Delta chunks, phir ek Done ya Error.
     * Ye function suspend hai aur khud IO dispatcher pe chalta hai.
     */
    suspend fun runStream(context: Context, body: JSONObject, onEvent: (StreamEvent) -> Unit) {
        val apiKey = Prefs.getApiKey(context)
        if (apiKey.isBlank()) {
            onEvent(StreamEvent.Error("NO_API_KEY", "API key not set"))
            return
        }
        val model = Prefs.getModel(context)
        withContext(Dispatchers.IO) {
            val full = StringBuilder()
            var lastFinishReason: String? = null
            var blockReason: String? = null
            var streamFailed = false

            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .post(body.toString().toRequestBody(JSON))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        streamFailed = true
                    } else {
                        val reader = BufferedReader(InputStreamReader(response.body?.byteStream() ?: return@use))
                        val eventBuffer = StringBuilder()
                        var line: String?
                        while (true) {
                            line = reader.readLine() ?: break
                            if (line.isEmpty()) {
                                // blank line = end of one SSE event
                                val dataLine = eventBuffer.lines().find { it.startsWith("data:") }
                                eventBuffer.clear()
                                if (dataLine != null) {
                                    val jsonStr = dataLine.removePrefix("data:").trim()
                                    if (jsonStr.isNotEmpty()) {
                                        try {
                                            val obj = JSONObject(jsonStr)
                                            val candidate = obj.optJSONArray("candidates")?.optJSONObject(0)
                                            val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
                                            val chunkText = StringBuilder()
                                            if (parts != null) {
                                                for (i in 0 until parts.length()) {
                                                    chunkText.append(parts.optJSONObject(i)?.optString("text").orEmpty())
                                                }
                                            }
                                            if (chunkText.isNotEmpty()) {
                                                full.append(chunkText)
                                                withContext(Dispatchers.Main) { onEvent(StreamEvent.Delta(chunkText.toString())) }
                                            }
                                            candidate?.optString("finishReason")?.let { if (it.isNotEmpty()) lastFinishReason = it }
                                            obj.optJSONObject("promptFeedback")?.optString("blockReason")?.let { if (it.isNotEmpty()) blockReason = it }
                                        } catch (_: Exception) {
                                            // incomplete/invalid JSON chunk — ignore
                                        }
                                    }
                                }
                            } else {
                                eventBuffer.appendLine(line)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                streamFailed = true
            }

            if (full.isNotBlank()) {
                withContext(Dispatchers.Main) { onEvent(StreamEvent.Done(full.toString().trim())) }
                return@withContext
            }

            // Streaming se kuch nahi mila — non-stream fallback try karein
            try {
                val fallbackUrl = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(fallbackUrl)
                    .post(body.toString().toRequestBody(JSON))
                    .build()
                client.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val errMsg = try { JSONObject(bodyStr).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                        withContext(Dispatchers.Main) {
                            onEvent(StreamEvent.Error("API_ERROR", errMsg ?: "HTTP ${response.code}"))
                        }
                        return@withContext
                    }
                    val obj = JSONObject(bodyStr)
                    val candidate = obj.optJSONArray("candidates")?.optJSONObject(0)
                    val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
                    val text = StringBuilder()
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            text.append(parts.optJSONObject(i)?.optString("text").orEmpty())
                        }
                    }
                    val finalText = text.toString().trim()
                    if (finalText.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            onEvent(StreamEvent.Delta(finalText))
                            onEvent(StreamEvent.Done(finalText))
                        }
                    } else {
                        val reason = obj.optJSONObject("promptFeedback")?.optString("blockReason")
                            ?: candidate?.optString("finishReason")
                            ?: blockReason ?: lastFinishReason ?: "UNKNOWN"
                        withContext(Dispatchers.Main) { onEvent(StreamEvent.Error("EMPTY_RESPONSE", reason)) }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onEvent(StreamEvent.Error("NETWORK_ERROR", e.message ?: "Unknown error")) }
            }
        }
    }

    suspend fun enhance(context: Context, text: String, mode: EnhanceMode, customPrompt: String?, onEvent: (StreamEvent) -> Unit) {
        if (text.isBlank()) {
            onEvent(StreamEvent.Error("EMPTY_TEXT", "No text found in field"))
            return
        }
        if (mode == EnhanceMode.CUSTOM && customPrompt.isNullOrBlank()) {
            onEvent(StreamEvent.Error("EMPTY_PROMPT", "Custom prompt is empty"))
            return
        }
        runStream(context, buildEnhanceBody(text, mode, customPrompt), onEvent)
    }

    suspend fun chat(context: Context, history: List<ChatMessage>, onEvent: (StreamEvent) -> Unit) {
        if (history.isEmpty()) {
            onEvent(StreamEvent.Error("EMPTY_TEXT", "No message"))
            return
        }
        runStream(context, buildChatBody(history), onEvent)
    }
}
