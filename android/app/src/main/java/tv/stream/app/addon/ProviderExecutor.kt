package tv.stream.app.addon

import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Four sockets at most, with bounded admission and foreground work ahead of preparation. */
class ProviderExecutor : ThreadPoolExecutor(PARALLELISM, PARALLELISM, 0, TimeUnit.MILLISECONDS, PriorityBlockingQueue<Runnable>()) {
    private val sequence = AtomicLong()
    @Synchronized fun execute(command: Runnable, scope: Cancellation) {
        if (queue.size >= 64) throw RejectedExecutionException("Provider queue full")
        val work = Work(command, scope, sequence.getAndIncrement())
        work.abort = Runnable { remove(work) }; scope.add(work.abort!!)
        try { super.execute(work) } catch (failure: RejectedExecutionException) { scope.remove(work.abort); throw failure }
    }
    @Synchronized fun promote() {
        val queued = queue.toTypedArray()
        for (item in queued) if (queue.remove(item)) queue.offer(item as Runnable)
    }
    private class Work(val command: Runnable, val scope: Cancellation, val sequence: Long) : Runnable, Comparable<Work> {
        var abort: Runnable? = null
        override fun run() { try { command.run() } finally { scope.remove(abort) } }
        override fun compareTo(other: Work): Int {
            val order = Integer.compare(scope.priority(), other.scope.priority())
            return if (order == 0) java.lang.Long.compare(sequence, other.sequence) else order
        }
    }
    companion object {
        const val PARALLELISM = 4
    }
}
