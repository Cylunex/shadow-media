package top.cylunex.shadowmedia.model

/** Process-wide audible ownership. Participants call on their application/main looper. */
class PlaybackCoordinator {
    private var active: Participant? = null
    inner class Participant internal constructor(private val pause: () -> Unit) : AutoCloseable {
        private var closed = false
        fun claim() {
            if (closed || active === this) return
            val previous = active
            active = this
            previous?.pause?.invoke()
        }
        fun abandon() { if (active === this) active = null }
        override fun close() { closed = true; abandon() }
    }
    fun participant(pause: () -> Unit) = Participant(pause)
    companion object { val process = PlaybackCoordinator() }
}
