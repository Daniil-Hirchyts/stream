package tv.stream.app.ui

import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import tv.stream.app.addon.AddonException
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
                if (stop) { pending.remove(job); if (running[job.key] === job) running.remove(job.key) }
            }
            if (stop) job.cancellation.close()
        }
    }

    internal inner class Job(val key: String, val work: (Cancellation) -> V, val generation: Int) {
        val tickets = ArrayList<Ticket>(2)
        val cancellation = Cancellation()
        var finished = false
    }

    private val lock = Any()
    private val pending = ArrayDeque<Job>()
    /** Every key with work queued or in progress. */
    private val running = HashMap<String, Job>()
    private var closed = false
    @Volatile private var generation = 0
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
            val job = existing ?: Job(key, work, generation)
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
        for (t in tickets) deliver(Runnable { if (!t.cancelled && job.generation == generation) t.failed() })
    }

    private fun loop() {
        while (true) {
            val job: Job = try {
                synchronized(lock) {
                    while (pending.isEmpty() && !closed) (lock as Object).wait()
                    if (closed) return
                    started++
                    pending.removeFirst()
                }
            } catch (stopping: InterruptedException) {
                // close() interrupts idle workers: they end quietly rather than crash the process.
                return
            }
            val result: V? = try {
                if (job.cancellation.isCancelled()) null else job.work(job.cancellation)
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) { null }
            catch (e: OutOfMemoryError) { null } // One image too many: a failure like any other, and the worker lives on.
            val tickets = synchronized(lock) {
                job.finished = true
                if (running[job.key] === job) running.remove(job.key)
                ArrayList(job.tickets)
            }
            if (job.cancellation.isCancelled()) continue
            for (t in tickets) {
                deliver(Runnable { if (!t.cancelled && job.generation == generation) { if (result != null) t.done(result) else t.failed() } })
            }
        }
    }

    /** Cancels every request, as when the screen stops. */
    fun cancelAll() {
        val all = synchronized(lock) { generation++; val jobs = ArrayList(running.values); pending.clear(); running.clear(); jobs }
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

/**
 * One blocking result per key among callers already on their own threads, such as the differently sized decodes of one
 * image waiting for its single download. The first caller runs the work under a [Cancellation] of its own, so it
 * outlives that caller's cancellation for as long as anyone waits, and stops when the last waiter leaves. Nothing is
 * kept once the work ends: a failure is the next caller's fresh attempt.
 */
class SharedWork<V : Any> {
    private inner class Flight {
        val cancellation = Cancellation()
        val result = CompletableFuture<V>()
        var waiters = 0
    }

    private val flights = HashMap<String, Flight>()

    /** How many times the work ran, for tests and measurements. */
    @Volatile var runs = 0; private set

    @Throws(Exception::class)
    fun get(key: String, request: Cancellation, work: (Cancellation) -> V): V {
        request.check()
        val flight: Flight
        val first: Boolean
        synchronized(flights) {
            val existing = flights[key]
            first = existing == null
            flight = existing ?: Flight().also { flights[key] = it; runs++ }
            flight.waiters++
        }
        // A caller of its own: cancelling this one wakes only this caller, whatever the others are doing.
        val mine = flight.result.thenApply { it }
        val left = AtomicBoolean()
        val leave = Runnable { if (left.compareAndSet(false, true)) { leave(key, flight); mine.cancel(false) } }
        request.add(leave)
        try {
            if (first) run(key, flight, work)
            val value = mine.get()
            request.check()
            return value
        } catch (e: CancellationException) {
            throw AddonException(AddonException.Kind.CANCELLED, "Request cancelled")
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            request.remove(leave)
            leave.run()
        }
    }

    private fun run(key: String, flight: Flight, work: (Cancellation) -> V) {
        val outcome = try { Result.success(work(flight.cancellation)) } catch (e: Throwable) { Result.failure(e) }
        synchronized(flights) { if (flights[key] === flight) flights.remove(key) }
        outcome.fold({ flight.result.complete(it) }, { flight.result.completeExceptionally(it) })
    }

    private fun leave(key: String, flight: Flight) {
        val stop = synchronized(flights) {
            flight.waiters--
            (flight.waiters == 0 && flights[key] === flight).also { if (it) flights.remove(key) }
        }
        if (stop) flight.cancellation.close()
    }
}
