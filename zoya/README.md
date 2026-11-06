# Zoya — a voice-to-voice assistant

Zoya is a realtime voice assistant with a personality: young, quick, sassy,
flirty in a tasteful way, and genuinely useful. Two clients, one character,
one model.

| Client | Stack | What it is |
| --- | --- | --- |
| [`web/`](web) | React 19 · TypeScript · Tailwind v4 · Vite | The browser app. Fullscreen orb, no chat UI at all. |
| [`android/`](android) | Kotlin · Jetpack Compose · foreground service | A native Android assistant that keeps working with the screen off, wakes on the word "Zoya", and can control the phone. |

Both talk to **`gemini-3.1-flash-live-preview`** over the Gemini Live API,
**audio in / audio out only**. There is no text chat anywhere in either client:
no message list, no transcript pane, no keyboard. The interface is a voice and
an orb.

---

## Quick start (web)

```bash
cd zoya/web
npm install
cp .env.example .env.local        # then put your Gemini key in it
npm run dev                       # http://localhost:5173
```

Get a key from <https://aistudio.google.com/apikey>. The key is only ever read
on the server: the browser receives a **single-use ephemeral token** locked to
`AUDIO` output and Zoya's chosen voice, so it cannot request text, change the
model, or talk to anything else.

Without a key the UI still runs and tells you, in the app, that it is not
configured. Nothing pretends to work.

```bash
npm run build && npm start        # production: node server/serve.mjs
npm run verify                    # typecheck + render + audio-algorithm checks
```

Deploy anywhere Node runs (Render, Fly, a VPS, Docker). `PORT` is respected.
Vercel works too — the two API routes are the only server code.

---

## Architecture (web)

```
web/src
├── App.tsx                  one stateful component: owns the LiveSession
├── lib/
│   ├── persona.ts           who Zoya is (single source of truth)
│   ├── LiveSession.ts        websocket, barge-in, tools, reconnect
│   ├── AudioStreamer.ts      mic in / speaker out, nothing else
│   ├── AudioLevelMonitor.ts  30fps envelope + spectrum for the orb
│   ├── orbPainter.ts         pure drawing function (renderable offline)
│   └── tools.ts              tool declarations + browser executors
├── components/
│   ├── ZoyaOrb.tsx           canvas shell around orbPainter
│   ├── ActivityFeed.tsx      transient "what she did" strip (not a chat log)
│   └── FirstRun.tsx          pre-flight: voice, name, honesty about the mic
└── server/  (../../web/server)
    ├── handlers.mjs          POST /api/token, POST /api/tool, GET /api/health
    └── serve.mjs             static + API for production
```

**Audio path.** `getUserMedia` → AudioWorklet (`public/pcm-worklet.js`) →
PCM16 mono @16 kHz, 40 ms frames → base64 → `sendRealtimeInput`. Model audio
comes back as base64 PCM16 @24 kHz and is scheduled on an absolute play head in
a 24 kHz `AudioContext`, so playback is gapless.

**Interruptions.** Three layers, cheapest first:

1. local RMS barge-in — the moment the mic hears 3 loud frames while she is
   talking, playback is flushed (this is what makes her feel alive);
2. the server's `interrupted` flag, which also flushes;
3. `turnComplete` + a drain watcher that returns the orb to *listening* only
   when the last scheduled buffer has actually played.

**Tool calls.** `toolCall` → executed in the tab (or via `/api/tool` for URL
policy) → `sendToolResponse` immediately, because the model's turn is blocked
until every call has an answer.

---

## Architecture (Android)

```
android/app/src/main/java/ai/zoya/assistant
├── audio/     Pcm.kt (ring buffer, base64, resampler) · AudioEngine.kt · WakeWordDetector.kt
├── live/      LiveProtocol.kt (wire format) · LiveSessionManager.kt (session owner)
├── tools/     ToolExecutionEngine.kt · ToolDeclarations.kt · DeviceIntents.kt · Contacts.kt
├── service/   ZoyaService.kt (foreground, wake word) · BootReceiver · WakeReceiver
├── data/      ZoyaPrefs.kt (settings, memory, timers)
├── domain/    ZoyaState.kt · ZoyaPersona.kt
└── ui/        MainActivity · ZoyaViewModel · components/ZoyaOrb · components/PermissionsOnboarding
```

Clean Architecture / MVVM, in practice:

- `ZoyaService` owns the microphone, the speaker and the single
  `LiveSessionManager`. It is the only thing that can start audio.
- `ZoyaViewModel` never touches audio; it binds to `ZoyaService.session` and
  exposes one immutable `UiState`. Rotating the phone, or the activity dying,
  changes nothing about the conversation.
- `LiveSessionManager` knows nothing about Compose. It pulls 20 ms frames from
  a bounded `SharedFlow`, pushes them through a `Mutex` onto the socket (OkHttp
  throws on concurrent sends), and hands model audio to a queue that a dedicated
  writer thread drains into `AudioTrack`. A slow network can never block the
  recorder.
- No SDK dependency for the protocol: `LiveProtocol.kt` speaks
  BidiGenerateContent directly, so a broken SDK release cannot silently stop
  her from connecting.

### Wake word

`WakeWordDetector` is **local, offline, and honest about what it is**: an
energy/fricative envelope matcher for the two-syllable shape of "Zoya", not a
neural keyword spotter. It runs on 20 ms frames with an adaptive noise floor, a
zero-crossing voiced test and a high-band ratio for the "Z", then looks for
`loud → dip → softer` inside a 700 ms window. ~0.2 ms per frame. There is a
`cooldownMs` so it cannot self-trigger, and unit tests pin both the true
positive and four kinds of false positive.

If you have a trained `.tflite` spotter, replace `feed()`; the rest of the
pipeline does not change.

### Device tools

| Tool | What actually happens | Honest limitation |
| --- | --- | --- |
| `openApp(packageName)` | curated alias → launcher label match → `getLaunchIntentForPackage` | Android 10+ may block a background activity start; we post a tap-to-continue notification and tell the model so |
| `searchAndCallContact(contactName)` | Contacts Provider query (exact → prefix → first name → contains), then `ACTION_CALL` | needs `READ_CONTACTS` + `CALL_PHONE`; ambiguous names are returned as several matches so she asks which |
| `sendWhatsAppMessage(contactName, message)` | `https://wa.me/<digits>?text=…` deep link | WhatsApp has no public send API — the chat opens with the text typed, the user taps send. Zoya says so. |
| `sendGmail(recipientEmail, subject, body)` | `ACTION_SENDTO mailto:` then `ACTION_SEND` fallback | opens a draft; the user presses send |
| `openWebsite(url)` | `ACTION_VIEW` in the in-app browser | the model cannot read the page |
| `setTimer`, `rememberFact`, `recallFacts`, `getCurrentTime` | in-process / prefs | none |

Every tool checks `PermissionState` first and returns
`{error: "missing_permission", permission: …}` so Zoya can sass about it and
stop, instead of crashing into a `SecurityException`.

### Build it

```bash
cd zoya/android
echo "GEMINI_API_KEY=your_key" > zoya.properties      # git-ignored
./gradlew :app:assembleDebug                          # needs JDK 17 + SDK 35
./gradlew :app:testDebugUnitTest                      # pure-JVM tests, no device
```

`TOKEN_SERVER_URL=https://your-web-app` in `zoya.properties` switches the app to
the same ephemeral-token flow as the web client, so the phone never holds a
long-lived key.

The first launch shows a permissions screen that explains *why* each permission
exists and routes to system settings when the OS has stopped showing the dialog.
Mic and notifications gate entry; contacts and calling are optional and the UI
says which abilities are therefore off.

---

## What is real, and what is not

Nothing in this folder claims a capability it does not have.

- **Real:** the Live API session, the audio pipeline in both clients, the tool
  calls, the wake-word matcher, the foreground service, the unit tests.
- **Requires your key:** every conversation. Without `GEMINI_API_KEY` the apps
  build, run, and say they are not configured.
- **Requires a device:** the Android module needs an emulator or phone with a
  mic. There is no JDK in the environment this was written in, so the Kotlin
  has not been compiled here — the pure algorithms (`Base64Codec`, `Pcm16`,
  `Downsampler`, the protocol frames, the wake-word heuristic) were verified by
  transcription against reference implementations
  (`web/scripts/verify-kotlin-logic.mjs`) and by the JUnit tests in
  `android/app/src/test`.
- **Not implemented on purpose:** reading the model's transcripts on screen
  (the brief says no text UI), and auto-sending WhatsApp or email (no API
  exists; faking it would be a lie).

## Continuous integration

`ci/zoya-web.github-workflow.yml` is a ready-made GitHub Actions job (typecheck,
render checks, audio-algorithm checks, production build — no API key needed).
Copy it to `.github/workflows/zoya-web.yml` to switch it on. It is kept outside
`.github/` here because the repository's automation token cannot create
workflow files.

## Privacy

- Audio goes browser/phone → Gemini and nowhere else. Nothing is recorded.
- The web server stores nothing; it mints tokens and forgets them.
- On Android, memory (`rememberFact`) and timers live in app-private prefs and
  are excluded from cloud backup.
- The wake word never leaves the device; while asleep, no audio is streamed.
