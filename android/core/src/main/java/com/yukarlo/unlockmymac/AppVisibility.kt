package com.yukarlo.unlockmymac

/**
 * Whether this app has UI in front of the user right now.
 *
 * Exists so an approval request can tell the difference between "the user is holding this app" and
 * "the user is somewhere else". When the app is on screen it shows the request as an in-app card, and a
 * notification or a floating banner on top of that is the same question asked twice.
 *
 * Deliberately a dumb flag that something else sets, rather than reading `ProcessLifecycleOwner` here.
 * `core` is shared with the watch, and on the watch the approval notification is what *launches* the
 * full-screen prompt — so a process-lifecycle check made in here would suppress the notification
 * whenever the watch's own app happened to be open and take the prompt with it. Only the phone reports
 * in; the watch never does, so the watch keeps its previous behaviour by construction rather than by
 * remembering to special-case it.
 */
object AppVisibility {
    @Volatile
    var isForeground: Boolean = false
        private set

    fun report(foreground: Boolean) {
        isForeground = foreground
    }
}
