package ai.zoya.assistant.live

import ai.zoya.assistant.audio.Base64Codec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests. These are the highest-value tests in the Android module:
 * a wrong key here is a silent failure at runtime with no compile error.
 */
class LiveProtocolTest {

    private fun obj(json: String): JsonObject =
        LiveProtocol.json.parseToJsonElement(json).jsonObject

    @Test
    fun `setup frame asks for audio only`() {
        val frame = obj(
            LiveProtocol.setupFrame(
                apiKey = "test-key",
                systemInstruction = "You are Zoya.",
                voiceName = "Aoede",
                tools = LiveProtocol.json.parseToJsonElement("[]").let {
                    it as kotlinx.serialization.json.JsonArray
                },
            ),
        )
        val setup = frame["setup"]!!.jsonObject
        assertEquals("models/gemini-3.1-flash-live-preview", setup["model"]!!.jsonPrimitive.content)

        val gen = setup["generationConfig"]!!.jsonObject
        assertEquals(
            "AUDIO",
            gen["responseModalities"]!!.jsonArray0()[0].jsonPrimitive.content,
        )

        val voice = gen["speechConfig"]!!.jsonObject["voiceConfig"]!!.jsonObject
            ["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content
        assertEquals("Aoede", voice)

        val instruction = setup["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray0()[0]
            .jsonObject["text"]!!.jsonPrimitive.content
        assertEquals("You are Zoya.", instruction)
    }

    @Test
    fun `setup frame requests the model path not the bare id`() {
        val frame = LiveProtocol.setupFrame("k", "s", "Kore", LiveProtocol.json.parseToJsonElement("[]") as kotlinx.serialization.json.JsonArray)
        assertTrue(frame.contains("\"models/gemini-3.1-flash-live-preview\""))
    }

    @Test
    fun `audio frame carries pcm16 as base64 at 16k`() {
        val pcm = ByteArray(640) { (it % 128).toByte() }
        val frame = obj(LiveProtocol.audioFrame(pcm))
        val audio = frame["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject
        assertEquals("audio/pcm;rate=16000", audio["mimeType"]!!.jsonPrimitive.content)
        val decoded = Base64Codec.decode(audio["data"]!!.jsonPrimitive.content)
        assertTrue(decoded.contentEquals(pcm))
    }

    @Test
    fun `tool response frame includes ids and payloads`() {
        val payload = buildJsonObject { put("ok", true) }
        val frame = obj(
            LiveProtocol.toolResponseFrame(
                listOf(Triple("call-1", "openApp", payload)),
            ),
        )
        val responses = frame["toolResponse"]!!.jsonObject["functionResponses"]!!.jsonArray0()
        val first = responses[0].jsonObject
        assertEquals("call-1", first["id"]!!.jsonPrimitive.content)
        assertEquals("openApp", first["name"]!!.jsonPrimitive.content)
        assertTrue(first["response"]!!.jsonObject["ok"]!!.jsonPrimitive.content == "true")
    }

    @Test
    fun `incoming audio parts are decoded to raw bytes`() {
        val pcm = ByteArray(96) { it.toByte() }
        val b64 = Base64Codec.encode(pcm)
        val frame = """
            {"serverContent":{"modelTurn":{"parts":[
              {"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}
            ]},"turnComplete":true}}
        """.trimIndent()

        val events = LiveProtocol.parse(frame)
        val audio = events.filterIsInstance<LiveProtocol.ServerEvent.Audio>().single()
        assertTrue(audio.pcm24k.contentEquals(pcm))
        assertTrue(events.any { it is LiveProtocol.ServerEvent.TurnComplete })
    }

    @Test
    fun `text parts are ignored, never played`() {
        val frame = """{"serverContent":{"modelTurn":{"parts":[{"text":"hello"}]}}}"""
        val events = LiveProtocol.parse(frame)
        assertTrue(events.none { it is LiveProtocol.ServerEvent.Audio })
    }

    @Test
    fun `interrupted is surfaced for barge-in`() {
        val events = LiveProtocol.parse("""{"serverContent":{"interrupted":true}}""")
        assertTrue(events.contains(LiveProtocol.ServerEvent.Interrupted))
    }

    @Test
    fun `tool calls are parsed with args`() {
        val frame = """
            {"toolCall":{"functionCalls":[
              {"id":"c1","name":"openApp","args":{"packageName":"com.whatsapp"}},
              {"id":"c2","name":"getCurrentTime"}
            ]}}
        """.trimIndent()
        val calls = LiveProtocol.parse(frame)
            .filterIsInstance<LiveProtocol.ServerEvent.ToolCalls>().single().calls
        assertEquals(2, calls.size)
        assertEquals("openApp", calls[0].name)
        assertEquals(
            "com.whatsapp",
            calls[0].args!!["packageName"]!!.jsonPrimitive.content,
        )
        assertEquals(null, calls[1].args)
    }

    @Test
    fun `resumption handle is captured`() {
        val frame = """{"sessionResumptionUpdate":{"newHandle":"abc123","resumable":true}}"""
        val resumption = LiveProtocol.parse(frame)
            .filterIsInstance<LiveProtocol.ServerEvent.Resumption>().single()
        assertEquals("abc123", resumption.handle)
        assertTrue(resumption.resumable)
    }

    @Test
    fun `goAway carries the remaining time`() {
        val events = LiveProtocol.parse("""{"goAway":{"timeLeft":"12s"}}""")
        val goAway = events.filterIsInstance<LiveProtocol.ServerEvent.GoAway>().single()
        assertEquals("12s", goAway.timeLeft)
    }

    @Test
    fun `setupComplete is recognised`() {
        assertTrue(
            LiveProtocol.parse("""{"setupComplete":{"sessionId":"s1"}}""")
                .contains(LiveProtocol.ServerEvent.SetupComplete),
        )
    }

    @Test
    fun `garbage frames never throw`() {
        assertEquals(emptyList<Any>(), LiveProtocol.parse(""))
        assertEquals(emptyList<Any>(), LiveProtocol.parse("not json"))
        assertEquals(emptyList<Any>(), LiveProtocol.parse("[]"))
        assertEquals(emptyList<Any>(), LiveProtocol.parse("""{"unknown":1}"""))
    }

    @Test
    fun `text nudges produce a valid client content turn`() {
        val frame = obj(LiveProtocol.textFrame("say hi"))
        val turn = frame["clientContent"]!!.jsonObject["turns"]!!.jsonArray0()[0].jsonObject
        assertEquals("user", turn["role"]!!.jsonPrimitive.content)
        assertEquals(
            "say hi",
            turn["parts"]!!.jsonArray0()[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
        assertNotNull(frame["clientContent"])
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonArray0() =
    this as kotlinx.serialization.json.JsonArray
