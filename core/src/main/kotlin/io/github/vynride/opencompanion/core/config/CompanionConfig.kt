// Copyright (C) 2026 Vivian Richard Demello (vynride)
// SPDX-License-Identifier: AGPL-3.0-or-later
package io.github.vynride.opencompanion.core.config

import io.github.vynride.opencompanion.core.state.StateConfig

enum class AuthHeader { AUTHORIZATION, API_KEY }

enum class ServiceKind { CHAT, TRANSCRIBE, TTS }

enum class ChatApi { CHAT, RESPONSES }

/** One OpenAI-compatible endpoint: where to send requests, how to authenticate, which model. */
data class Service(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val authHeader: AuthHeader = AuthHeader.AUTHORIZATION,
) {
    fun url(path: String): String {
        val (base, query) = baseUrl.split("?", limit = 2).let { it[0] to it.getOrNull(1) }
        val url = base.trimEnd('/') + "/" + path.trimStart('/')
        return if (query != null) "$url?$query" else url
    }

    fun headers(): Map<String, String> = when (authHeader) {
        AuthHeader.API_KEY -> mapOf("api-key" to apiKey)
        AuthHeader.AUTHORIZATION -> mapOf("Authorization" to "Bearer $apiKey")
    }
}

data class ServiceOverride(
    val baseUrl: String? = null,
    val apiKey: String? = null,
    val authHeader: AuthHeader? = null,
)

data class ApiConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val authHeader: AuthHeader = AuthHeader.AUTHORIZATION,
    val chatModel: String = "",
    val transcribeModel: String = "",
    val ttsModel: String = "",
    val chat: ServiceOverride = ServiceOverride(),
    val transcribe: ServiceOverride = ServiceOverride(),
    val tts: ServiceOverride = ServiceOverride(),
)

data class Location(
    val name: String = "Somewhere",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val timezone: String = "UTC",
)

data class WakeWordConfig(
    val modelFile: String = "",
    val threshold: Float = 0.4f,
    val refractoryS: Double = 1.0,
    val vadGate: Boolean = true,
    val gateHoldS: Double = 1.5,
    // Long enough for a post-reset replay to complete the pipeline warmup in one burst.
    val prerollMs: Int = 2080,
    val maxQueue: Int = 100,
)

data class SttConfig(
    val language: String = "en",
    val vadThreshold: Float = 0.5f,
    val silenceMs: Int = 700,
    val maxMs: Int = 8000,
    val minMs: Int = 300,
    // A lone VAD blip must not send a noise-only segment to the STT API. Kept low:
    // the model VAD marks only a fraction of genuinely voiced frames on some mics.
    val minSpeechMs: Int = 100,
)

data class BrainConfig(
    val api: ChatApi = ChatApi.CHAT,
    val reasoningEffort: String = "",
    val vision: Boolean = true,
    val maxTurns: Int = 20,
    val sessionResetMin: Int = 30,
    val maxToolCalls: Int = 4,
    val fillerAfterS: Double = 2.0,
    val fillers: List<String> = listOf("One moment.", "Let me check.", "Looking that up."),
)

data class TtsConfig(
    val voice: String = "",
    val speed: Double = 1.0,
    val instructions: String = "",
)

data class SensesConfig(
    val tapThreshold: Float = 4.0f,
    val darkLux: Float = 5f,
    val proximityNearCm: Float = 5.0f,
    val dimBrightness: Float = 10f / 255f,
    val normalBrightness: Float = 80f / 255f,
    val sleepBrightness: Float = 1f / 255f,
)

data class MemoryConfig(
    val maxFactsLines: Int = 150,
)

data class LookConfig(
    val maxPx: Int = 1024,
)

data class SearchConfig(
    val maxResults: Int = 3,
    val maxChars: Int = 1500,
    val exaApiKey: String = "",
)

data class HostsConfig(
    val hosts: Map<String, String> = emptyMap(),
    val timeoutS: Double = 3.0,
)

data class CompanionConfig(
    val name: String = "Soc",
    val location: Location = Location(),
    val api: ApiConfig = ApiConfig(),
    val wakeWord: WakeWordConfig = WakeWordConfig(),
    val stt: SttConfig = SttConfig(),
    val brain: BrainConfig = BrainConfig(),
    val tts: TtsConfig = TtsConfig(),
    val state: StateConfig = StateConfig(),
    val senses: SensesConfig = SensesConfig(),
    val memory: MemoryConfig = MemoryConfig(),
    val look: LookConfig = LookConfig(),
    val search: SearchConfig = SearchConfig(),
    val hosts: HostsConfig = HostsConfig(),
    val cameraEnabled: Boolean = true,
)

/** The endpoint for `kind`, or null when it has no key or no model. */
fun CompanionConfig.service(kind: ServiceKind): Service? {
    val (override, model) =
        when (kind) {
            ServiceKind.CHAT -> api.chat to api.chatModel
            ServiceKind.TRANSCRIBE -> api.transcribe to api.transcribeModel
            ServiceKind.TTS -> api.tts to api.ttsModel
        }
    val key = override.apiKey?.takeIf { it.isNotBlank() } ?: api.apiKey
    if (key.isBlank() || model.isBlank()) return null
    return Service(
        baseUrl = override.baseUrl?.takeIf { it.isNotBlank() } ?: api.baseUrl,
        apiKey = key,
        model = model,
        authHeader = override.authHeader ?: api.authHeader,
    )
}
