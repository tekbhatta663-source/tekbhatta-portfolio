package ai.zoya.assistant.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The schemas Gemini sees. Written as JSON rather than generated from a
 * library so the exact wire shape is reviewable in one screen.
 *
 * Every description tells the model *when* to call it, not just what it does —
 * that is the difference between a tool that fires at the right moment and one
 * that fires on every sentence.
 */
object ToolDeclarations {

    val ALL: JsonArray = buildJsonArray {
        add(function(
            name = "openApp",
            description = "Open an installed app by name or package. Use for \"open Instagram\", \"launch YouTube\", \"start the calculator\".",
            properties = buildJsonObject {
                put("packageName", string("[package or app name, e.g. com.google.android.youtube or 'YouTube']"))
            },
            required = listOf("packageName"),
        ))

        add(function(
            name = "searchAndCallContact",
            description = "Look a person up in the phone's contacts and place a call. Use for \"call mom\", \"ring Riya\".",
            properties = buildJsonObject {
                put("contactName", string("[the name as the user said it]"))
            },
            required = listOf("contactName"),
        ))

        add(function(
            name = "sendWhatsAppMessage",
            description = "Open a WhatsApp chat with a contact and pre-fill a message. Use for \"whatsapp Riya I'm running late\". You cannot press send — say so briefly.",
            properties = buildJsonObject {
                put("contactName", string("[the person]"))
                put("message", string("[exactly what they want to say, in their voice]"))
            },
            required = listOf("contactName", "message"),
        ))

        add(function(
            name = "sendGmail",
            description = "Compose an email in the phone's mail app with recipient, subject and body pre-filled.",
            properties = buildJsonObject {
                put("recipientEmail", string("[a valid email address]"))
                put("subject", string("[short subject line]"))
                put("body", string("[the email body]"))
            },
            required = listOf("recipientEmail", "subject", "body"),
        ))

        add(function(
            name = "openWebsite",
            description = "Open a web page on the phone. Accepts a domain or a full URL.",
            properties = buildJsonObject {
                put("url", string("[full URL or bare domain]"))
            },
            required = listOf("url"),
        ))

        add(function(
            name = "setTimer",
            description = "Start a countdown on the device. When it fires you are told, and you announce it in character.",
            properties = buildJsonObject {
                put("seconds", number("[duration in seconds, max 86400]"))
                put("label", string("[what it is for, e.g. 'pasta']"))
            },
            required = listOf("seconds"),
        ))

        add(function(
            name = "rememberFact",
            description = "Quietly store one durable fact about the user (name, city, preference, deadline). Do not announce it.",
            properties = buildJsonObject {
                put("key", string("[short snake_case key]"))
                put("value", string("[the fact, one short sentence]"))
            },
            required = listOf("key", "value"),
        ))

        add(function(
            name = "recallFacts",
            description = "Read back everything you remember about the user.",
            properties = buildJsonObject { },
            required = emptyList(),
        ))

        add(function(
            name = "getCurrentTime",
            description = "Get the phone's current local date, time and timezone. Check this instead of guessing.",
            properties = buildJsonObject { },
            required = emptyList(),
        ))
    }

    // -------------------------------------------------------------- builders --

    private fun function(
        name: String,
        description: String,
        properties: kotlinx.serialization.json.JsonObject,
        required: List<String>,
    ) = buildJsonObject {
        put("name", name)
        put("description", description)
        put(
            "parameters",
            buildJsonObject {
                put("type", "OBJECT")
                put("properties", properties)
                if (required.isNotEmpty()) {
                    put(
                        "required",
                        buildJsonArray { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } },
                    )
                }
            },
        )
    }

    private fun string(description: String) = buildJsonObject {
        put("type", "STRING")
        put("description", description)
    }

    private fun number(description: String) = buildJsonObject {
        put("type", "NUMBER")
        put("description", description)
    }
}
