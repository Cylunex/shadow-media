package top.cylunex.shadowmedia

import top.cylunex.shadowmedia.model.EmbySession
import top.cylunex.shadowmedia.model.PlaybackEvent
import top.cylunex.shadowmedia.model.PlaybackPlan

/** Late external-player callbacks belong to the account that resolved the plan, never the UI account. */
internal class PlaybackOwnership {
    private val sessions = linkedMapOf<PlaybackPlan, EmbySession>()
    fun remember(plan: PlaybackPlan, session: EmbySession) {
        sessions[plan] = session
        while (sessions.size > 16) sessions.remove(sessions.keys.first())
    }
    fun session(plan: PlaybackPlan, event: PlaybackEvent): EmbySession? =
        if (event == PlaybackEvent.STOPPED) sessions.remove(plan) else sessions[plan]
}
