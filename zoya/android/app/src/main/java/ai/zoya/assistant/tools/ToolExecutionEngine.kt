package ai.zoya.assistant.tools

import ai.zoya.assistant.data.ZoyaPrefs
import android.content.Context
import android.util.Log
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * ToolExecutionEngine
 * -------------------
 * Maps a Gemini function call to a real Android action.
 *
 * Design rules, in order of importance:
 *
 *  1. **Never throw into the socket path.** Anything that fails becomes a
 *     structured `{error}` payload the model can speak about in character.
 *  2. **Never assume a permission.** Every device tool asks
 *     [PermissionState] first and returns a `missing_permission` payload that
 *     the persona knows how to sass about.
 *  3. **Never background-launch silently.** Starting an Activity from a
 *     service requires FLAG_ACTIVITY_NEW_TASK, and on Android 10+ background
 *     activity starts are restricted — so we post a notification fallback and
 *     tell the model what actually happened.
 */
class ToolExecutionEngine(
    private val context: Context,
    private val prefs: ZoyaPrefs,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        private const val TAG = "Zoya/Tools"
    }

    data class Result(
        val ok: Boolean,
        /** One short line for the in-app activity feed. */
        val summary: String,
        /** Exactly what goes back to the model as `functionResponse.response`. */
        val payload: JsonObject,
    )

    private val intents = DeviceIntents(context, prefs)

    /** Declarations sent in the setup frame. */
    val declarations: JsonArray = ToolDeclarations.ALL

    suspend fun execute(name: String, args: JsonObject): Result {
        Log.i(TAG, "execute $name")
        return try {
            when (name) {
                "openApp" -> openApp(args)
                "searchAndCallContact" -> callContact(args)
                "sendWhatsAppMessage" -> whatsapp(args)
                "sendGmail" -> gmail(args)
                "openWebsite" -> website(args)
                "setTimer" -> timer(args)
                "rememberFact" -> remember(args)
                "recallFacts" -> recall()
                "getCurrentTime" -> now()
                else -> fail("unknown_tool", "There is no tool called $name.")
            }
        } catch (e: SecurityException) {
            fail("permission_denied", e.message ?: "The OS refused that action.")
        } catch (e: Exception) {
            Log.e(TAG, "$name failed", e)
            fail("exception", e.message ?: "That action failed.")
        }
    }

    // ------------------------------------------------------------------ tools --

    private fun openApp(args: JsonObject): Result {
        val raw = args.str("packageName") ?: args.str("appName")
        if (raw.isNullOrBlank()) return fail("bad_args", "I need an app name.")

        val target = intents.resolveApp(raw)
            ?: return Result(
                ok = false,
                summary = "No app called “$raw”",
                payload = buildJsonObject {
                    put("error", "app_not_found")
                    put("message", "Nothing installed matches “$raw”. Say so and offer to search the Play Store.")
                    put("searched", raw)
                },
            )

        val launched = intents.launchApp(target.packageName)
        return if (launched) {
            Result(
                ok = true,
                summary = "Opened ${target.label}",
                payload = buildJsonObject {
                    put("ok", true)
                    put("app", target.label)
                    put("package", target.packageName)
                },
            )
        } else {
            Result(
                ok = false,
                summary = "${target.label} refused to open",
                payload = buildJsonObject {
                    put("error", "launch_blocked")
                    put("message", "Android blocked the background launch. Tell them to tap it themselves once; after that it works.")
                    put("app", target.label)
                },
            )
        }
    }

    private suspend fun callContact(args: JsonObject): Result {
        val who = args.str("contactName")
        if (who.isNullOrBlank()) return fail("bad_args", "Who should I call?")

        if (!PermissionState.has(context, PermissionState.READ_CONTACTS)) {
            return permissionResult(
                which = "READ_CONTACTS",
                label = "contacts",
                sassy = "I'm not allowed to look in your contacts. Bit rude of you, honestly.",
            )
        }
        if (!PermissionState.has(context, PermissionState.CALL_PHONE)) {
            return permissionResult(
                which = "CALL_PHONE",
                label = "phone",
                sassy = "You haven't let me place calls yet. I can dial, you press the green button.",
            )
        }

        val matches = Contacts.lookup(context, who)
        val best = matches.firstOrNull()
            ?: return Result(
                ok = false,
                summary = "No contact matching “$who”",
                payload = buildJsonObject {
                    put("error", "contact_not_found")
                    put("message", "No saved contact matches “$who”. Ask them who exactly, or offer to dial the number if they say it.")
                },
            )

        val number = best.phones.firstOrNull()
            ?: return Result(
                ok = false,
                summary = "${best.name} has no number saved",
                payload = buildJsonObject {
                    put("error", "no_number")
                    put("message", "${best.name} exists but has no phone number saved.")
                },
            )

        val placed = intents.dial(number, directCall = true)
        return Result(
            ok = placed,
            summary = if (placed) "Calling ${best.name}" else "Couldn't place the call",
            payload = buildJsonObject {
                put("ok", placed)
                put("contact", best.name)
                put("matchedBy", best.matchedBy)
                put("otherMatches", matches.drop(1).take(3).joinToString { it.name })
                if (!placed) put("error", "call_failed")
            },
        )
    }

    private suspend fun whatsapp(args: JsonObject): Result {
        val who = args.str("contactName")
        val message = args.str("message") ?: ""
        if (who.isNullOrBlank()) return fail("bad_args", "Who am I messaging?")

        if (!PermissionState.has(context, PermissionState.READ_CONTACTS)) {
            return permissionResult(
                which = "READ_CONTACTS",
                label = "contacts",
                sassy = "Contacts are locked. Can't find them if I can't look.",
            )
        }
        if (!intents.hasWhatsApp()) {
            return Result(
                ok = false,
                summary = "WhatsApp isn't installed",
                payload = buildJsonObject {
                    put("error", "app_missing")
                    put("message", "WhatsApp isn't on this phone. Offer a plain SMS instead.")
                },
            )
        }

        val matches = Contacts.lookup(context, who)
        val best = matches.firstOrNull()
            ?: return Result(
                ok = false,
                summary = "No contact matching “$who”",
                payload = buildJsonObject {
                    put("error", "contact_not_found")
                    put("message", "No saved contact matches “$who”.")
                },
            )

        // WhatsApp deep link opens the chat with the text pre-filled but never
        // sends by itself — Android has no API for that, so we are honest.
        val opened = intents.openWhatsApp(best.phones, message)
        return Result(
            ok = opened,
            summary = if (opened) "WhatsApp ready for ${best.name}" else "Couldn't open WhatsApp",
            payload = buildJsonObject {
                put("ok", opened)
                put("contact", best.name)
                put("prefilled", message.isNotBlank())
                put(
                    "note",
                    "The chat is open with the text typed in. You cannot press send for them — say “one tap and it's gone”.",
                )
                if (!opened) put("error", "whatsapp_failed")
            },
        )
    }

    private suspend fun gmail(args: JsonObject): Result {
        val to = args.str("recipientEmail")
        val subject = args.str("subject") ?: ""
        val body = args.str("body") ?: ""
        if (to.isNullOrBlank() || !to.contains("@")) {
            return Result(
                ok = false,
                summary = "That email address looks off",
                payload = buildJsonObject {
                    put("error", "bad_email")
                    put("message", "“$to” is not a valid email address. Ask them to repeat it.")
                },
            )
        }

        val opened = intents.composeEmail(to, subject, body)
        return Result(
            ok = opened,
            summary = if (opened) "Draft ready · to $to" else "No mail app answered · $to",
            payload = buildJsonObject {
                put("ok", opened)
                put("to", to)
                put("draft", true)
                put(
                    "note",
                    if (opened) {
                        "The draft is open in their mail app. They still have to hit send."
                    } else {
                        "No mail app answered. Offer to open Gmail in the browser instead."
                    },
                )
            },
        )
    }

    private fun website(args: JsonObject): Result {
        val raw = args.str("url")
        if (raw.isNullOrBlank()) return fail("bad_args", "Which site?")

        val url = UrlTools.normalise(raw)
            ?: return fail("bad_url", "I couldn't make a web address out of “$raw”.")

        // In-app browser keeps Zoya in the foreground, so she can keep talking
        // about the page the user is looking at — a real difference on a phone.
        val opened = intents.openUrl(url)
        return Result(
            ok = opened,
            summary = "Opened ${UrlTools.prettyHost(url)}",
            payload = buildJsonObject {
                put("ok", opened)
                put("url", url)
                put("note", "Opened on their phone. You cannot read the page contents.")
                if (!opened) put("error", "no_browser")
            },
        )
    }

    private fun timer(args: JsonObject): Result {
        val seconds = args.num("seconds")?.toLong()?.coerceIn(1L, 86_400L) ?: 0L
        if (seconds <= 0) return fail("bad_args", "How long?")
        val label = args.str("label") ?: "timer"

        prefs.addTimer(label, clock() + seconds * 1000, seconds)
        val human = when {
            seconds < 60 -> "$seconds seconds"
            seconds % 60 == 0L -> "${seconds / 60} minutes"
            else -> "%.1f minutes".format(seconds / 60.0)
        }
        return Result(
            ok = true,
            summary = "Timer · $human ($label)",
            payload = buildJsonObject {
                put("ok", true)
                put("seconds", seconds)
                put("label", label)
                put("firesAt", clock() + seconds * 1000)
            },
        )
    }

    private fun remember(args: JsonObject): Result {
        val key = args.str("key") ?: "fact_${clock()}"
        val value = args.str("value")
        if (value.isNullOrBlank()) return fail("bad_args", "Nothing to remember.")
        prefs.rememberFact(key, value)
        return Result(
            ok = true,
            summary = "Noted: $value",
            payload = buildJsonObject {
                put("ok", true)
                put("key", key)
            },
        )
    }

    private fun recall(): Result {
        val facts = prefs.facts
        return Result(
            ok = true,
            summary = if (facts.isEmpty()) "Nothing remembered yet" else "Recalled ${facts.size} thing(s)",
            payload = buildJsonObject {
                put("count", facts.size)
                put(
                    "facts",
                    buildJsonArray {
                        facts.forEach { (k, v) ->
                            add(buildJsonObject { put("key", k); put("value", v) })
                        }
                    },
                )
            },
        )
    }

    private fun now(): Result {
        val date = java.util.Date(clock())
        val fmt = java.text.SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", java.util.Locale.getDefault())
        return Result(
            ok = true,
            summary = "Checked the clock",
            payload = buildJsonObject {
                put("local", fmt.format(date))
                put("timeZone", java.util.TimeZone.getDefault().id)
                put("epochMs", clock())
            },
        )
    }

    // ---------------------------------------------------------------- helpers --

    private fun fail(code: String, message: String) = Result(
        ok = false,
        summary = message,
        payload = buildJsonObject {
            put("error", code)
            put("message", message)
        },
    )

    private fun permissionResult(which: String, label: String, sassy: String) = Result(
        ok = false,
        summary = "Permission needed · $label",
        payload = buildJsonObject {
            put("error", "missing_permission")
            put("permission", which)
            put("message", sassy)
            put(
                "hint",
                "Tell the user in one sassy line to turn on $label access for Zoya, then stop trying.",
            )
        },
    )
}

/* ------------------------------------------------------------------ utils -- */

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }

internal fun JsonObject.num(key: String): Double? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

object UrlTools {
    private val domain = Regex("""^[\w-]+(\.[\w-]+)+(\/\S*)?$""", RegexOption.IGNORE_CASE)

    /** Returns null when the input cannot be made safe to open. */
    fun normalise(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            return trimmed
        }
        // Reject everything that is not a plain web address.
        if (trimmed.contains("://") || trimmed.startsWith("javascript:", true)) return null
        return if (domain.matches(trimmed)) "https://$trimmed" else null
    }

    fun prettyHost(url: String): String =
        runCatching { java.net.URI(url).host?.removePrefix("www.") ?: url }.getOrDefault(url)
}
