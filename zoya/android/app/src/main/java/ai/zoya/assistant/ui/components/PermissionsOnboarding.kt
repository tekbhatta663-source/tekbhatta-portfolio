package ai.zoya.assistant.ui.components

import ai.zoya.assistant.tools.PermissionState
import ai.zoya.assistant.ui.theme.Cyan
import ai.zoya.assistant.ui.theme.Ink
import ai.zoya.assistant.ui.theme.Rose
import ai.zoya.assistant.ui.theme.RoseSoft
import ai.zoya.assistant.ui.theme.Violet
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PermissionsOnboarding
 * ---------------------
 * The first-run screen. Three jobs:
 *   1. explain *why* each permission exists, in her voice, before asking
 *   2. request every permission the OS still reports as denied
 *   3. hand the user a route to Settings when the OS has permanently
 *      suppressed the dialog ("Don't ask again") — because a button that does
 *      nothing is the worst possible UX
 *
 * Essentials (mic, notifications) gate entry; contacts and phone can be skipped
 * and enabled later from Settings, which the UI makes obvious.
 */
@Composable
fun PermissionsOnboarding(
    userName: String,
    voice: String,
    wakeWord: Boolean,
    autostart: Boolean,
    configured: Boolean,
    onUserNameChange: (String) -> Unit,
    onVoiceChange: (String) -> Unit,
    onWakeWordChange: (Boolean) -> Unit,
    onAutostartChange: (Boolean) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf(PermissionState.pending(context)) }
    // True once the OS dialog has been shown at least once; after that a dead
    // "Allow" tap means the permission is permanently denied and the only route
    // left is system settings.
    var askedOnce by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Re-read from the OS rather than trusting the returned map: some OEMs
        // report "granted" for permissions they then silently drop.
        pending = PermissionState.pending(context)
        askedOnce = true
    }

    // Always request what is still missing *at tap time*; asking for the stale
    // snapshot would re-prompt for permissions the user just granted.
    val requestAll: () -> Unit = {
        val missing = PermissionState.pending(context)
        if (missing.isNotEmpty()) {
            launcher.launch(PermissionState.launcherPermissions(missing))
        } else {
            pending = emptyList()
            onDone()
        }
    }

    val essentialMissing = pending.filter { it.essential }
    val optionalMissing = pending.filterNot { it.essential }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color(0xFF06070C),
                    0.55f to Color(0xFF0A0713),
                    1f to Color(0xFF05060A),
                ),
            )
            .systemBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "BEFORE WE START",
                    color = RoseSoft.copy(alpha = 0.7f),
                    fontSize = 10.5.sp,
                    letterSpacing = 4.2.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "Zoya",
                    color = Ink,
                    fontSize = 54.sp,
                    lineHeight = 56.sp,
                    letterSpacing = (-1.8).sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "I talk, you talk. That's the entire interface — no keyboard, no chat log, nothing to scroll.",
                    color = Ink.copy(alpha = 0.55f),
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                )
            }

            if (!configured) {
                WarningCard(
                    title = "No API key yet",
                    body = "This build has no GEMINI_API_KEY. Add it to zoya.properties (or set " +
                        "TOKEN_SERVER_URL to point at the web app) and rebuild. Everything else " +
                        "works — she just cannot connect.",
                )
            }

            // ------------------------------------------------------ permissions --
            if (pending.isNotEmpty()) {
                SectionLabel("WHAT I NEED")
                pending.forEach { item ->
                    PermissionRow(
                        item = item,
                        granted = false,
                        onRequest = requestAll,
                        onOpenSettings = { openAppSettings(context) },
                        showSettings = askedOnce,
                    )
                }
            } else {
                SectionLabel("ALL SET")
                Text(
                    text = "Permissions are sorted. I can hear you, call people, and survive the " +
                        "battery optimiser.",
                    color = Ink.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }

            // ---------------------------------------------------------- identity --
            SectionLabel("A FEW THINGS")

            OutlinedTextField(
                value = userName,
                onValueChange = { onUserNameChange(it.take(32)) },
                singleLine = true,
                label = { Text("What should I call you?", fontSize = 12.sp) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Ink,
                    unfocusedTextColor = Ink.copy(alpha = 0.85f),
                    focusedBorderColor = Rose.copy(alpha = 0.6f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                    focusedLabelColor = RoseSoft,
                    unfocusedLabelColor = Ink.copy(alpha = 0.4f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "VOICE",
                color = Ink.copy(alpha = 0.35f),
                fontSize = 9.5.sp,
                letterSpacing = 3.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ai.zoya.assistant.domain.ZoyaPersona.VOICES.forEach { option ->
                    val selected = option == voice
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (selected) Rose.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.03f),
                            )
                            .border(
                                1.dp,
                                if (selected) Rose.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.09f),
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { onVoiceChange(option) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = option,
                            color = if (selected) Ink else Ink.copy(alpha = 0.5f),
                            fontSize = 11.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }

            ToggleRow(
                title = "Wake word",
                subtitle = "Say “Zoya” to summon her from anywhere. Keeps a mic open in the background.",
                checked = wakeWord,
                onChange = onWakeWordChange,
            )
            ToggleRow(
                title = "Start on boot",
                subtitle = "Bring her back automatically after a restart. Off by default, for good reason.",
                checked = autostart,
                onChange = onAutostartChange,
            )

            // ------------------------------------------------------------ button --
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(
                        Brush.horizontalGradient(listOf(Rose, Color(0xFFD977FF), Violet)),
                    )
                    .clickable {
                        if (essentialMissing.isNotEmpty()) requestAll() else onDone()
                    }
                    .padding(vertical = 17.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when {
                        essentialMissing.isNotEmpty() -> "Allow what I actually need"
                        else -> "Wake her up"
                    },
                    color = Color(0xFF0B0614),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (optionalMissing.isNotEmpty() && askedOnce) {
                TextButton(
                    onClick = { openAppSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Contacts and calling stay off — open system settings",
                        color = Ink.copy(alpha = 0.4f),
                        fontSize = 11.5.sp,
                    )
                }
            }

            AnimatedVisibility(
                visible = pending.isEmpty(),
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 3 },
            ) {
                Text(
                    text = "She's ready. Tap the orb and say something — she'll handle the rest.",
                    color = Cyan.copy(alpha = 0.55f),
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                )
            }

            Text(
                text = "Audio streams directly to Google's Gemini Live API. Nothing is recorded or " +
                    "stored on a server, and no transcript is kept on this device.",
                color = Ink.copy(alpha = 0.25f),
                fontSize = 10.5.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/* ------------------------------------------------------------------ pieces -- */

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Ink.copy(alpha = 0.32f),
        fontSize = 9.5.sp,
        letterSpacing = 3.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun PermissionRow(
    item: PermissionState.Item,
    granted: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    showSettings: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.035f))
            .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    if (granted) Cyan.copy(alpha = 0.2f) else Rose.copy(alpha = 0.14f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (granted) "✓" else item.title.take(1),
                color = if (granted) Cyan else Rose,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, color = Ink, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(
                text = item.why,
                color = Ink.copy(alpha = 0.45f),
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
            )
            Text(
                text = item.unlocks,
                color = RoseSoft.copy(alpha = 0.5f),
                fontSize = 10.sp,
            )
        }
        if (!granted) {
            TextButton(onClick = if (showSettings) onOpenSettings else onRequest) {
                Text(
                    text = if (showSettings) "Settings" else "Allow",
                    color = Rose,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Ink, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                color = Ink.copy(alpha = 0.42f),
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF1A0B16),
                checkedTrackColor = Rose,
                uncheckedThumbColor = Ink.copy(alpha = 0.5f),
                uncheckedTrackColor = Color.White.copy(alpha = 0.1f),
            ),
        )
    }
}

@Composable
private fun WarningCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Rose.copy(alpha = 0.1f))
            .border(1.dp, Rose.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(title, color = RoseSoft, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        Text(body, color = RoseSoft.copy(alpha = 0.75f), fontSize = 11.5.sp, lineHeight = 16.sp)
    }
}

private fun openAppSettings(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
