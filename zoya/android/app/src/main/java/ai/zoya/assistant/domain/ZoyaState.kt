package ai.zoya.assistant.domain

/**
 * The only states the UI knows about. Everything visual derives from this
 * enum, so there is exactly one place to look when the orb misbehaves.
 */
enum class ZoyaState {
    /** Service off, no mic, nothing running. */
    Asleep,

    /** Foreground service alive, mic open, only the wake word is being matched. */
    WaitingForWake,

    /** Websocket opening / resuming. */
    Connecting,

    /** Live session up, user can talk. */
    Listening,

    /** A tool call is executing (or the model is reasoning). */
    Thinking,

    /** Model audio is playing out of the speaker. */
    Speaking,

    /** Something broke; [ZoyaSession.lastError] says what. */
    Error,
    ;

    val isLive: Boolean get() = this == Listening || this == Thinking || this == Speaking
    val isBusy: Boolean get() = this == Connecting
}
