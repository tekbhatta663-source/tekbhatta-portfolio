package ai.zoya.assistant.live

import ai.zoya.assistant.audio.Base64Codec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * LiveProtocol
 * ------------
 * The BidiGenerateContent wire format, hand-rolled.
 *
 * Why not the (very new) Android `google-genai` SDK? Because the Live API's
 * setup frame changes shape faster than any released SDK tracks it, and a
 * voice assistant that silently stops connecting after an SDK bump is worse
 * than one that speaks the wire protocol directly — which is stable, small,
 * and fully visible in this file. Swapping in an SDK later means implementing
 * [LiveTransport] and nothing else.
 */
object LiveProtocol {

    const val MODEL = "gemini-3.1-flash-live-preview"

    const val WS_BASE = "wss://generativelanguage.googleapis.com/ws/" +
        "google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    // ------------------------------------------------------------- outbound --

    /** First frame after the socket opens. Everything else is a response. */
    fun setupFrame(
        apiKey: String,
        systemInstruction: String,
        voiceName: String,
        tools: JsonArray,
        resumeHandle: String? = null,
    ): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put(
                "setup",
                buildJsonObject {
                    put("model", "models/$MODEL")
                    put(
                        "generationConfig",
                        buildJsonObject {
                            put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                            put("temperature", 0.9)
                            put(
                                "speechConfig",
                                buildJsonObject {
                                    put(
                                        "voiceConfig",
                                        buildJsonObject {
                                            put(
                                                "prebuiltVoiceConfig",
                                                buildJsonObject { put("voiceName", voiceName) },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                    put(
                        "systemInstruction",
                        buildJsonObject {
                            put(
                                "parts",
                                buildJsonArray { add(buildJsonObject { put("text", systemInstruction) }) },
                            )
                        },
                    )
                    put("tools", tools)
                    put(
                        "contextWindowCompression",
                        buildJsonObject {
                            put("slidingWindow", buildJsonObject { put("targetTokens", "20480") })
                        },
                    )
                    if (!apiKey.startsWith("auth_tokens/")) {
                        put(
                            "sessionResumption",
                            if (resumeHandle != null) {
                                buildJsonObject { put("handle", resumeHandle) }
                            } else {
                                buildJsonObject { put("transparent", true) }
                            },
                        )
                    }
                },
            )
        },
    )

    /** One 20 ms mic frame. */
    fun audioFrame(pcm16: ByteArray): String {
        val b64 = Base64Codec.encode(pcm16)
        return buildJsonObject {
            put(
                "realtimeInput",
                buildJsonObject {
                    put(
                        "audio",
                        buildJsonObject {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", b64)
                        },
                    )
                },
            )
        }.toString()
    }

    /** Tells the server the mic is paused (VAD should not wait on us). */
    fun audioStreamEndFrame(): String =
        buildJsonObject { put("realtimeInput", buildJsonObject { put("audioStreamEnd", true) }) }
            .toString()

    /** A user turn sent as text (timers, wake-word nudges, host events). */
    fun textFrame(text: String, turnComplete: Boolean = true): String = buildJsonObject {
        put(
            "clientContent",
            buildJsonObject {
                put(
                    "turns",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "parts",
                                    buildJsonArray {
                                        add(buildJsonObject { put("text", text) })
                                    },
                                )
                            },
                        )
                    },
                )
                put("turnComplete", turnComplete)
            },
        )
    }.toString()

    /** Answers a toolCall. Must be sent promptly or the model stalls. */
    fun toolResponseFrame(
        responses: List<Triple<String?, String, JsonObject>>,
    ): String = buildJsonObject {
        put(
            "toolResponse",
            buildJsonObject {
                put(
                    "functionResponses",
                    buildJsonArray {
                        responses.forEach { (id, name, payload) ->
                            add(
                                buildJsonObject {
                                    if (!id.isNullOrEmpty()) put("id", id)
                                    put("name", name)
                                    put("response", payload)
                                },
                            )
                        }
                    },
                )
            },
        ).toString()
    }

    // -------------------------------------------------------------- inbound --

    sealed interface ServerEvent {
        data object SetupComplete : ServerEvent
        data class Audio(val bytes: ByteArray) : ServerEvent
        data object Interrupted : ServerEvent
        data object TurnComplete : ServerEvent
        data class ToolCalls(val calls: List<Call>) : ServerEvent
        data class ToolCancelled(val ids: List<String>) : ServerEvent
        data class Resumption(val handle: String, val resumable: Boolean) : ServerEvent
        data class GoAway(val timeLeft: String?) : ServerEvent
        data class Failure(val message: String, val code: Int? = null) : ServerEvent
    }

    data class Call(
        val id: String? = null,
        val name: String? = null,
        val args: JsonObject? = null,
    )

    /** Parse one socket frame into zero or more semantic events. */
    fun parse(text: String): List<ServerEvent> {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<ServerEvent>()

        root["setupComplete"]?.let { out += ServerEvent.SetupComplete }

        root["goAway"]?.let { go ->
            val left = go.jsonObject["timeLeft"]?.jsonPrimitive?.contentOrNull
            out += ServerEvent.GoAway(left)
        }

        root["sessionResumptionUpdate"]?.let { upd ->
            val obj = upd.jsonObject
            val handle = obj["newHandle"]?.jsonPrimitive?.contentOrNull
            val resumable = obj["resumable"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            if (!handle.isNullOrEmpty()) out += ServerEvent.Resumption(handle, resumable)
        }

        root["toolCall"]?.let { tc ->
            val calls = (tc.jsonObject["functionCalls"] as? JsonArray)?.mapNotNull { element ->
                runCatching {
                    val obj = element.jsonObject
                    Call(
                        id = obj["id"]?.jsonPrimitive?.contentOrNull,
                        name = obj["name"]?.jsonPrimitive?.contentOrNull,
                        args = obj["args"]?.jsonObject,
                    )
                }.getOrNull()
            }.orEmpty()
            if (calls.isNotEmpty()) out += ServerEvent.ToolCalls(calls)
        }

        root["toolCallCancellation"]?.let { tcc ->
            val ids = (tcc.jsonObject["ids"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
            out += ServerEvent.ToolCancelled(ids)
        }

        root["serverContent"]?.let { sc ->
            val obj = sc.jsonObject

            if (obj["interrupted"]?.jsonPrimitive?.contentOrNull == "true") {
                out += ServerEvent.Interrupted
            }

            val parts = (obj["modelTurn"]?.jsonObject?.get("parts") as? JsonArray).orEmpty()
            for (part in parts) {
                val inline = part.jsonObject["inlineData"]?.jsonObject ?: continue
                val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull ?: ""
                if (!mime.startsWith("audio/")) continue
                val data = inline["data"]?.jsonPrimitive?.contentOrNull ?: continue
                runCatching { Base64Codec.decode(data) }
                    .getOrNull()
                    ?.let { out += ServerEvent.Audio(it) }
            }

            val complete = obj["turnComplete"]?.jsonPrimitive?.contentOrNull == "true"
            if (complete) out += ServerEvent.TurnComplete
        }

        return out
    }

    /** Wrapper for a hand-built JSON payload sent from the tool runner. */
    fun encodeObject(obj: JsonObject): String = obj.toString()
}
