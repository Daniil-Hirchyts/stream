package tv.stream.app.addon

/** One navigation/configuration generation. Cancelling closes active sockets as well as queued work. */
class Cancellation : AutoCloseable {
    private val actions: MutableList<Runnable> = ArrayList()
    private var cancelled = false
    @Volatile private var priority = 1
    fun priority(): Int { return priority }
    fun priority(value: Int) { priority = value }

    @Synchronized fun isCancelled(): Boolean { return cancelled }
    @Throws(AddonException::class)
    fun check() {
        if (isCancelled() || Thread.currentThread().isInterrupted) {
            throw AddonException(AddonException.Kind.CANCELLED, "Request cancelled")
        }
    }
    fun add(action: Runnable) {
        synchronized(this) { if (!cancelled) { actions.add(action); return } }
        action.run()
    }
    @Synchronized fun remove(action: Runnable?) { actions.remove(action) }
    override fun close() {
        val copy: List<Runnable>
        synchronized(this) {
            if (cancelled) return
            cancelled = true
            copy = ArrayList(actions)
            actions.clear()
        }
        for (r in copy) r.run()
    }
}
