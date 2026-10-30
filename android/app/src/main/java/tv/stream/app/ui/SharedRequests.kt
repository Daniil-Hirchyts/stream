package tv.stream.app.ui

import java.util.ArrayDeque
import tv.stream.app.addon.Cancellation

/**
 * Bounded background work shared by key: concurrent requests for the same key run once and every requester gets the
 * result. Each requester holds a [Ticket]; cancelling it detaches only that requester, and the work itself stops once
 * no ticket still wants it. At most [capacity] distinct keys wait; the newest run first (what is on screen now), and
 * the oldest are dropped with their requesters told they failed. Results and failures are delivered through
 * [deliver], on the main thread in the app.
 */
class SharedRequests<V : Any>(
    private val capacity: Int,
    threads: Int,
    private val deliver: (Runnable) -> Unit,
) : AutoCloseable {
    inner class Ticket internal constructor(internal val job: Job, internal val done: (V) -> Unit, internal val failed: () -> Unit) {
        @Volatile internal var cancelled = false
        /** Detaches this requester; the shared work stops when nobody else waits for it. */
        fun cancel() {
            if (cancelled) return
            cancelled = true
            val stop: Boolean
            synchronized(lock) {
                job.tickets.remove(this)
                stop = job.tickets.isEmpty() && !job.finished
                if (stop) { pending.remove(job); running.remove(job.key) }
            }
            if (stop) job.cancellation.close()
        }
    }

    internal inner class Job(val key: String, val work: (Cancellation) -> V) {
        val tickets = ArrayList<Ticket>(2)
        val cancellation = Cancellation()
        var finished = false
    }

    private val lock = Any()
    private val pending = ArrayDeque<Job>()
    /** Every key with work queued or in progress. */
    private val running = HashMap<String, Job>()
    private var closed = false
    private val workers: List<Thread>

    /** How many keys ran their work, and how many requests joined work already queued or running. */
    @Volatile var started = 0; private set
    @Volatile var joined = 0; private set

    init {
        workers = (0 until threads).map { i ->
            Thread({ loop() }, "shared-requests-$i").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
        }
    }

    fun request(key: String, work: (Cancellation) -> V, done: (V) -> Unit, failed: () -> Unit): Ticket {
        val dropped = ArrayList<Job>()
        val ticket: Ticket
        synchronized(lock) {
            val existing = running[key]
            val job = existing ?: Job(key, work)
            ticket = Ticket(job, done, failed)
            job.tickets.add(ticket)
            if (existing != null) {
                joined++
                // Wanted again: it moves to the front of the queue if it hasn't started.
                if (pending.remove(job)) pending.addFirst(job)
            } else if (closed) {
                dropped.add(job)
            } else {
                running[key] = job
                pending.addFirst(job)
                while (pending.size > capacity) {
                    val old = pending.removeLast()
                    running.remove(old.key)
                    dropped.add(old)
                }
                (lock as Object).notify()
            }
        }
        for (job in dropped) fail(job)
        return ticket
    }

    private fun fail(job: Job) {
        job.cancellation.close()
        val tickets = synchronized(lock) { job.finished = true; ArrayList(job.tickets) }
        for (t in tickets) deliver(Runnable { if (!t.cancelled) t.failed() })
    }

    private fun loop() {
        while (true) {
            val job: Job = try {
                synchronized(lock) {
                    while (pending.isEmpty() && !closed) (lock as Object).wait()
                    if (closed) return
                    pending.removeFirst()
                }
            } catch (stopping: InterruptedException) {
                // close() interrupts idle workers: they end quietly rather than crash the process.
                return
            }
            started++
            val result: V? = try {
                if (job.cancellation.isCancelled()) null else job.work(job.cancellation)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) { null }
            val tickets = synchronized(lock) {
                job.finished = true
                if (running[job.key] === job) running.remove(job.key)
                ArrayList(job.tickets)
            }
            if (job.cancellation.isCancelled()) continue
            for (t in tickets) {
                deliver(Runnable { if (!t.cancelled) { if (result != null) t.done(result) else t.failed() } })
            }
        }
    }

    /** Cancels every request, as when the screen stops. */
    fun cancelAll() {
        val all = synchronized(lock) { val jobs = ArrayList(running.values); pending.clear(); running.clear(); jobs }
        for (job in all) {
            synchronized(lock) { job.tickets.forEach { it.cancelled = true } }
            job.cancellation.close()
        }
    }

    override fun close() {
        cancelAll()
        synchronized(lock) { closed = true; (lock as Object).notifyAll() }
        workers.forEach { it.interrupt() }
    }
}
