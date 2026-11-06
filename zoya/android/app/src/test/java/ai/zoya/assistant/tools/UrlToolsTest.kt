package ai.zoya.assistant.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * URL and argument handling — the boundary between model output and an Intent.
 * Everything here is a security property: a language model must never be able
 * to make us launch an arbitrary scheme.
 */
class UrlToolsTest {

    @Test
    fun `bare domains become https`() {
        assertEquals("https://youtube.com", UrlTools.normalise("youtube.com"))
        assertEquals("https://x.y/z?a=1", UrlTools.normalise("x.y/z?a=1"))
    }

    @Test
    fun `explicit http and https are preserved`() {
        assertEquals("https://a.b", UrlTools.normalise("https://a.b"))
        assertEquals("http://localhost:3000", UrlTools.normalise("http://localhost:3000"))
    }

    @Test
    fun `dangerous schemes are rejected`() {
        assertNull(UrlTools.normalise("javascript:alert(1)"))
        assertNull(UrlTools.normalise("intent://scan#Intent;scheme=zxing;end"))
        assertNull(UrlTools.normalise("file:///etc/passwd"))
        assertNull(UrlTools.normalise("data:text/html,<script>"))
        assertNull(UrlTools.normalise("content://contacts/people"))
    }

    @Test
    fun `plain words are rejected rather than guessed`() {
        // The Android client refuses to guess a search engine; the model is
        // told to use openWebsite with a real URL instead.
        assertNull(UrlTools.normalise("the weather tomorrow"))
        assertNull(UrlTools.normalise(""))
        assertNull(UrlTools.normalise("   "))
    }

    @Test
    fun `pretty host strips www`() {
        assertEquals("youtube.com", UrlTools.prettyHost("https://www.youtube.com/watch?v=1"))
        assertEquals("example.org", UrlTools.prettyHost("https://example.org"))
    }

    @Test
    fun `args reader handles strings, numbers and blanks`() {
        val args = buildJsonObject {
            put("contactName", "Riya")
            put("seconds", 90)
            put("empty", "")
        }
        assertEquals("Riya", args.str("contactName"))
        assertEquals(90.0, args.num("seconds")!!, 0.001)
        assertNull(args.str("empty"))
        assertNull(args.str("missing"))
        assertNull(args.num("missing"))
    }

    @Test
    fun `declarations are well formed`() {
        val declarations = ToolDeclarations.ALL
        assertTrue("expect the full device tool set", declarations.size >= 9)

        val names = declarations.mapNotNull { element ->
            (element as JsonObject)["name"]?.toString()?.trim('"')
        }
        listOf(
            "openApp",
            "searchAndCallContact",
            "sendWhatsAppMessage",
            "sendGmail",
            "openWebsite",
            "setTimer",
            "rememberFact",
            "recallFacts",
            "getCurrentTime",
        ).forEach { required ->
            assertTrue("missing tool: $required", names.contains(required))
        }

        // Every declaration must be describable to the model.
        declarations.forEach { element ->
            val obj = element as JsonObject
            assertTrue(obj["description"] != null)
            assertTrue(obj["parameters"] != null)
        }
    }
}
