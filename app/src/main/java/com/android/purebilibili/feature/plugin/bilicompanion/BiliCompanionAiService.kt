package com.android.purebilibili.feature.plugin.bilicompanion

import android.content.Context
import android.util.Base64
import com.android.purebilibili.core.plugin.PluginStore
import com.android.purebilibili.data.repository.CommentRepository
import com.android.purebilibili.data.repository.VideoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

internal enum class BiliCompanionAssistantAction {
    TRANSLATE_TITLE,
    SUMMARIZE_VIDEO,
    FIND_COMMENTS,
    AUTO_PAGE
}

@Serializable
internal enum class BiliCompanionAiProvider {
    OPENAI_COMPATIBLE,
    ANTHROPIC,
    GEMINI
}

private const val PLUGIN_ID = "bili_companion"
private const val DEFAULT_OPENAI_BASE = "https://api.openai.com/v1"
private const val DEFAULT_ANTHROPIC_BASE = "https://api.anthropic.com"
private const val DEFAULT_GEMINI_BASE = "https://generativelanguage.googleapis.com"
private const val KEYSTORE_ALIAS = "bili_companion_ai_api_key"
private const val SECRET_PREFS = "bili_companion_ai_secret"
private const val SECRET_VALUE = "encrypted_api_key"

/**
 * API Key is kept outside the plugin JSON and encrypted with an Android Keystore AES key.
 * The endpoint and model remain user-editable so OpenAI-compatible relay services work
 * without shipping a vendor-specific integration.
 */
internal object BiliCompanionAiSecretStore {
    fun read(context: Context): String = runCatching {
        val encoded = context.applicationContext
            .getSharedPreferences(SECRET_PREFS, Context.MODE_PRIVATE)
            .getString(SECRET_VALUE, null)
            ?: return ""
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = payload.copyOfRange(0, 12)
        val cipherText = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        }
        String(cipher.doFinal(cipherText), StandardCharsets.UTF_8)
    }.getOrDefault("")

    fun write(context: Context, value: String) {
        if (value.isBlank()) {
            context.applicationContext
                .getSharedPreferences(SECRET_PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(SECRET_VALUE)
                .apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        }
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        context.applicationContext
            .getSharedPreferences(SECRET_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(SECRET_VALUE, Base64.encodeToString(payload, Base64.NO_WRAP))
            .apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

internal object BiliCompanionAiService {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .callTimeout(45L, TimeUnit.SECONDS)
        .connectTimeout(12L, TimeUnit.SECONDS)
        .build()

    suspend fun execute(
        context: Context,
        bvid: String,
        action: BiliCompanionAssistantAction
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val config = loadConfig(context)
            require(config.aiEnabled) { "请先在 Bili-Companion 设置中开启 AI 助手" }
            require(bvid.isNotBlank()) { "当前没有可分析的视频" }
            require(config.aiModel.isNotBlank()) { "请先填写 AI 模型名称" }

            val apiKey = BiliCompanionAiSecretStore.read(context)
            require(apiKey.isNotBlank()) { "请先填写 AI API Key" }

            val info = VideoRepository.getVideoInfoOnly(bvid).getOrElse { throw it }
            val comments = if (action == BiliCompanionAssistantAction.TRANSLATE_TITLE) {
                emptyList()
            } else {
                CommentRepository.getComments(aid = info.aid, page = 1, ps = 20)
                    .getOrNull()
                    ?.replies
                    .orEmpty()
                    .mapNotNull { reply ->
                        reply.content.message.trim().takeIf { it.isNotEmpty() }
                    }
            }
            val prompt = buildPrompt(config, action, info.title, info.desc, comments)
            requestCompletion(config, apiKey, prompt)
        }
    }

    private suspend fun loadConfig(context: Context): BiliCompanionConfig =
        PluginStore.getConfigJson(context.applicationContext, PLUGIN_ID)
            ?.let { json.decodeFromString<BiliCompanionConfig>(it) }
            ?: BiliCompanionConfig()

    private fun buildPrompt(
        config: BiliCompanionConfig,
        action: BiliCompanionAssistantAction,
        title: String,
        description: String,
        comments: List<String>
    ): String {
        val evidence = buildString {
            appendLine("视频标题：${title.take(300)}")
            if (description.isNotBlank()) appendLine("视频简介：${description.take(3_500)}")
            if (comments.isNotEmpty()) {
                appendLine("热门评论：")
                comments.take(20).forEachIndexed { index, comment ->
                    appendLine("${index + 1}. ${comment.take(240)}")
                }
            }
        }
        return when (action) {
            BiliCompanionAssistantAction.TRANSLATE_TITLE ->
                "只翻译下面的视频标题，保留专有名词和原意，只输出译文，不要解释：\n$evidence"
            BiliCompanionAssistantAction.SUMMARIZE_VIDEO ->
                "根据标题、简介和热门评论整理一份简体中文摘要。明确区分资料中确定的内容和推测，" +
                    "不要声称看过视频画面或音频；输出三条要点和一句适合桌宠气泡的结论：\n$evidence"
            BiliCompanionAssistantAction.FIND_COMMENTS ->
                "在下面的评论中查找与“${config.aiCommentQuery.take(80)}”最相关的内容。" +
                    "输出最多五条，保留评论原意并说明选择理由；没有匹配时明确说没有找到：\n$evidence"
            BiliCompanionAssistantAction.AUTO_PAGE -> error("自动翻页不需要 AI")
        }
    }

    private fun requestCompletion(
        config: BiliCompanionConfig,
        apiKey: String,
        prompt: String
    ): String {
        val system = "你是 BiliPai 应用里的中文视频助手，回答简洁、准确，不编造事实。"
        val request = when (config.aiProvider) {
            BiliCompanionAiProvider.OPENAI_COMPATIBLE -> buildOpenAiRequest(config, apiKey, system, prompt)
            BiliCompanionAiProvider.ANTHROPIC -> buildAnthropicRequest(config, apiKey, system, prompt)
            BiliCompanionAiProvider.GEMINI -> buildGeminiRequest(config, apiKey, system, prompt)
        }
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IllegalStateException("AI 请求失败（${response.code}）：${body.take(180)}")
            }
            return extractText(config.aiProvider, json.parseToJsonElement(body).jsonObject)
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("AI 返回了空内容")
        }
    }

    private fun buildOpenAiRequest(
        config: BiliCompanionConfig,
        apiKey: String,
        system: String,
        prompt: String
    ): Request {
        val payload = buildJsonObject {
            put("model", config.aiModel)
            put("messages", messageArray(system, prompt))
            put("temperature", 0.2)
            put("max_tokens", 900)
        }
        return Request.Builder()
            .url(resolveOpenAiUrl(config))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun buildAnthropicRequest(
        config: BiliCompanionConfig,
        apiKey: String,
        system: String,
        prompt: String
    ): Request {
        val payload = buildJsonObject {
            put("model", config.aiModel)
            put("system", system)
            put("max_tokens", 900)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }
        return Request.Builder()
            .url(resolveBaseUrl(config, DEFAULT_ANTHROPIC_BASE) + "/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun buildGeminiRequest(
        config: BiliCompanionConfig,
        apiKey: String,
        system: String,
        prompt: String
    ): Request {
        val payload = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) })
                })
            })
        }
        val endpoint = "${resolveBaseUrl(config, DEFAULT_GEMINI_BASE)}/v1beta/models/" +
            "${config.aiModel}:generateContent"
        return Request.Builder()
            .url(endpoint)
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    }

    private fun messageArray(system: String, prompt: String): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("role", "system")
            put("content", system)
        })
        add(buildJsonObject {
            put("role", "user")
            put("content", prompt)
        })
    }

    private fun resolveOpenAiUrl(config: BiliCompanionConfig): String {
        val base = resolveBaseUrl(config, DEFAULT_OPENAI_BASE)
        val path = config.aiEndpointPath.trim().trimStart('/')
        val url = if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else if (base.endsWith("/chat/completions")) {
            base
        } else {
            "$base/${path.ifBlank { "chat/completions" }}"
        }
        require(url.startsWith("https://") || url.startsWith("http://")) { "AI 地址必须使用 HTTP 或 HTTPS" }
        return url
    }

    private fun resolveBaseUrl(config: BiliCompanionConfig, defaultBase: String): String {
        val configured = config.aiBaseUrl.trim().trimEnd('/')
        return if (configured.isBlank() || configured == DEFAULT_OPENAI_BASE && defaultBase != DEFAULT_OPENAI_BASE) {
            defaultBase
        } else {
            configured
        }
    }

    private fun extractText(provider: BiliCompanionAiProvider, root: JsonObject): String = when (provider) {
        BiliCompanionAiProvider.OPENAI_COMPATIBLE -> root["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")
            ?.jsonPrimitive?.contentOrNull.orEmpty()
        BiliCompanionAiProvider.ANTHROPIC -> root["content"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
        BiliCompanionAiProvider.GEMINI -> root["candidates"]?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
}
