package com.aliothmoon.maafw.privileged

import android.content.Context
import android.os.IBinder
import com.aliothmoon.maafw.domain.RemoteBackend
import com.aliothmoon.maafw.root.BootstrapRegistry
import io.mockk.CapturingSlot
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 兜底等待预算：只用来吸收调度延迟，不属于任何断言语义本身。
 *
 * 必须显著大于被测产品延迟（本文件最长为 spawnTimeoutMs=5s，其余 300ms），
 * 否则用例会在**正确**实现上偶发失败——这正是之前 flaky 的教训。
 * 注意这不是"把某条断言放宽"：目标条件与断言值一个字都没动。
 */
private const val AWAIT_BUDGET_MS = 15_000L

/** 条件轮询间隔：只影响发现条件成立的最坏延迟，不参与任何断言 */
private const val POLL_INTERVAL_MS = 10L

/**
 * 进程连接器基座行为：IPC 状态机高变更区，失败 / 竞态路径必须有真行为测试兜底
 */
class ProcessServiceConnectorBehaviorTest {

    private val logFile = File.createTempFile("connector", ".log").apply { delete() }

    @After
    fun tearDown() {
        logFile.delete()
    }

    /**
     * 轮询到 [condition] 成立或超时。
     *
     * 直接等目标状态本身，而不是"睡 N 毫秒再赌它已经发生"；超时预算
     * [AWAIT_BUDGET_MS] 只兜底调度延迟，远大于被测延迟，失败时报出仍在等的条件。
     */
    private fun awaitUntil(description: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_BUDGET_MS)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        if (!condition()) throw AssertionError("timed out after ${AWAIT_BUDGET_MS}ms waiting for: $description")
    }

    private class FakeRegistry : BootstrapRegistry {
        val pending = ConcurrentHashMap<String, CompletableDeferred<IBinder>>()

        override fun register(token: String): CompletableDeferred<IBinder> =
            CompletableDeferred<IBinder>().also { pending[token] = it }

        override fun unregister(token: String) {
            pending.remove(token)?.cancel()
        }

        /** 模拟 provider 回投；token 已注销返回 false */
        fun attach(token: String, binder: IBinder): Boolean =
            pending.remove(token)?.complete(binder) == true
    }

    private class FakeHandle(private val alive: Boolean, private val code: Int?) : SpawnHandle {
        override fun isAlive(): Boolean = alive
        override fun exitCode(): Int? = code
    }

    private class FakeSpawner : ProcessSpawner {
        var handle: SpawnHandle? = null
        var spawnError: Throwable? = null
        var onSpawn: () -> Unit = {}
        val spawnCalls = AtomicInteger()
        private val kills = LinkedBlockingQueue<String>()

        override fun wrapCommand(launcherPath: String, invocation: String): String = invocation

        override fun spawn(command: String): SpawnHandle? {
            spawnCalls.incrementAndGet()
            onSpawn()
            spawnError?.let { throw it }
            return handle
        }

        override fun killResidual(processName: String) {
            kills.add(processName)
        }

        fun awaitKill(): Boolean = kills.poll(AWAIT_BUDGET_MS, TimeUnit.MILLISECONDS) != null
        fun killCount(): Int = kills.size
    }

    private class FakeConnector(
        spawner: ProcessSpawner,
        registry: BootstrapRegistry,
        private val logFile: File,
        timeoutMs: Long,
    ) : ProcessServiceConnectorBackend(spawner, registry) {
        override val backend = RemoteBackend.ROOT
        override val eventPrefix = "FAKE"
        override val processNameSuffix = "fake"
        override val serviceClass: Class<*> = Any::class.java
        override val logFileName = "fake.log"
        override val spawnTimeoutMs = timeoutMs

        private val tokens = LinkedBlockingQueue<String>()

        override fun buildStartCommand(token: String, logFile: File): String {
            tokens.add(token)
            return "fake-cmd"
        }

        override fun debugLogFile(): File = logFile

        override fun destroyRemote(binder: IBinder) = Unit

        fun awaitToken(): String =
            tokens.poll(AWAIT_BUDGET_MS, TimeUnit.MILLISECONDS) ?: error("token never assigned")
    }

    private class RecordingCallbacks : RemoteServiceConnectorBackend.Callbacks {
        val connected = mutableListOf<IBinder>()
        val errors = mutableListOf<Throwable>()
        val disconnected = AtomicInteger()
        private val connectedLatch = CountDownLatch(1)
        private val errorLatch = CountDownLatch(1)

        override fun onConnected(backend: RemoteBackend, binder: IBinder) {
            connected += binder
            connectedLatch.countDown()
        }

        override fun onDisconnected(backend: RemoteBackend) {
            disconnected.incrementAndGet()
        }

        override fun onError(backend: RemoteBackend, throwable: Throwable) {
            errors += throwable
            errorLatch.countDown()
        }

        fun awaitConnected() = connectedLatch.await(AWAIT_BUDGET_MS, TimeUnit.MILLISECONDS)
        fun awaitError() = errorLatch.await(AWAIT_BUDGET_MS, TimeUnit.MILLISECONDS)
    }

    private inner class Harness(timeoutMs: Long = 300L) {
        val spawner = FakeSpawner()
        val registry = FakeRegistry()
        val callbacks = RecordingCallbacks()
        val connector = FakeConnector(spawner, registry, logFile, timeoutMs).apply {
            initialize(mockk<Context>(relaxed = true))
        }

        /** 连接并回投一个 binder，返回其死亡回调 */
        fun connectAndAttach(): CapturingSlot<IBinder.DeathRecipient> {
            val binder = mockk<IBinder>(relaxed = true)
            val recipient = slot<IBinder.DeathRecipient>()
            justRun { binder.linkToDeath(capture(recipient), any()) }
            connector.connect(callbacks)
            assertTrue(registry.attach(connector.awaitToken(), binder))
            assertTrue(callbacks.awaitConnected())
            // onConnected 是从 connect 协程体内部回调的：countDown 那一刻 job 还没 return，
            // activeLaunch?.job?.isActive 仍为 true。必须等协程真正收尾，之后读
            // isConnecting / errors / killCount 才是不会再变的终态。
            // 漏了这一步，assertFalse(isConnecting) 就会在 countDown→job 完成之间的窗口里
            // 偶发失败（processAliveUntilBinder_connects 曾偶发的原因）。
            awaitUntil("connect 协程收尾（isConnecting=false）") { !connector.isConnecting }
            return recipient
        }
    }

    @Test
    fun spawnFailure_reportsOnceWithoutRetry() {
        val h = Harness()
        h.spawner.spawnError = IllegalStateException("launcher missing")

        h.connector.connect(h.callbacks)
        assertTrue(h.callbacks.awaitError())

        // 失败终态屏障：onFailure 先置 activeLaunch=null 再 reportFailure，
        // 所以 awaitError 返回时状态已定；这里再等协程收尾，杜绝与并发写竞争。
        awaitUntil("spawn 失败协程收尾") { !h.connector.isConnecting }

        assertEquals(1, h.spawner.spawnCalls.get())
        assertEquals("launcher missing", h.callbacks.errors.single().message)
        assertTrue("失败后 token 必须注销", h.registry.pending.isEmpty())
        assertEquals("没拉起来就没有残留可清", 0, h.spawner.killCount())
    }

    @Test
    fun binderNeverArrives_timesOutAsTimeoutException() {
        val h = Harness(timeoutMs = 300L)

        h.connector.connect(h.callbacks)
        // awaitError 等的是 onError 这个目标事件；预算 15s 是被测超时 300ms 的 50 倍，
        // 只吸收调度延迟，不与产品超时赛跑。
        assertTrue(h.callbacks.awaitError())

        val error = h.callbacks.errors.single()
        assertTrue(
            "超时必须转成 TimeoutException 而非泄漏 CancellationException",
            error is TimeoutException,
        )
        assertTrue(error.message!!.contains("300ms"))
        assertTrue("超时必须清残留进程", h.spawner.awaitKill())
    }

    @Test
    fun launcherLogTail_isAppendedToFailure() {
        val h = Harness(timeoutMs = 300L)
        h.spawner.onSpawn = { logFile.writeText("[I] launcher start\n[E] execv failed: EACCES\n") }

        h.connector.connect(h.callbacks)
        // 同上：等 onError 事件本身，预算远大于 300ms 超时。
        assertTrue(h.callbacks.awaitError())

        val message = h.callbacks.errors.single().message!!
        assertTrue(
            message,
            message.contains("launcher log tail: [I] launcher start | [E] execv failed: EACCES"),
        )
    }

    @Test
    fun processExitsBeforeBinder_failsFastWithExitCode() {
        val h = Harness(timeoutMs = 10_000L)
        h.spawner.handle = FakeHandle(alive = false, code = 126)

        val start = System.currentTimeMillis()
        h.connector.connect(h.callbacks)
        assertTrue(h.callbacks.awaitError())
        val elapsed = System.currentTimeMillis() - start

        val error = h.callbacks.errors.single()
        // 进程已死时 watcher 首轮探活即抛，正常远小于 100ms；3s 给调度留足余量，
        // 且仍远小于 10s 的 spawnTimeoutMs，足以证明"秒级失败而非等满超时"。
        assertTrue("进程先退出必须秒级失败而非等满超时 (elapsed=${elapsed}ms)", elapsed < 3_000)
        assertTrue(error is ProcessExitedException)
        assertEquals(126, (error as ProcessExitedException).exitCode)
        assertTrue(h.spawner.awaitKill())
    }

    @Test
    fun processAliveUntilBinder_connects() {
        val h = Harness(timeoutMs = 5_000L)
        h.spawner.handle = FakeHandle(alive = true, code = null)

        h.connectAndAttach()

        // connectAndAttach 已等到 connect 协程收尾，以下都是终态：
        // isConnecting 不会再回 true，errors / connected / killCount 也不会再被写。
        assertEquals(1, h.callbacks.connected.size)
        assertTrue(h.callbacks.errors.isEmpty())
        assertFalse(h.connector.isConnecting)
        assertEquals("成功路径不得清进程", 0, h.spawner.killCount())
    }

    @Test
    fun disconnectThenLateBinder_isDropped() {
        val h = Harness(timeoutMs = 5_000L)
        h.connector.connect(h.callbacks)
        val token = h.connector.awaitToken()

        h.connector.disconnect(null)

        // disconnect 会 cancel 连接协程并同步注销 token；等协程真正收尾后再读，
        // 避免与取消收尾路径并发写 registry / connected。
        awaitUntil("disconnect 后 connect 协程收尾") { !h.connector.isConnecting }

        assertFalse("disconnect 后 token 应已注销", h.registry.attach(token, mockk(relaxed = true)))
        assertTrue("迟到的 binder 不得触发 onConnected", h.callbacks.connected.isEmpty())
        assertTrue("未连上就断开必须清残留进程", h.spawner.awaitKill())
    }

    @Test
    fun deathOfOldToken_isIgnored() {
        val h = Harness(timeoutMs = 5_000L)
        val recipient = h.connectAndAttach()

        // 第二次连接发起后，旧 binder 的死讯必须被丢弃。
        // awaitToken 返回即新 connect 已把 activeLaunch 指向 token2（登记先于 job.start），
        // 所以此处读到的 activeLaunch 确定是新 token，死讯判定不依赖调度快慢。
        h.connector.connect(h.callbacks)
        h.connector.awaitToken()
        recipient.captured.binderDied()

        assertEquals("旧 token 死讯不得触发 onDisconnected", 0, h.callbacks.disconnected.get())
        h.connector.disconnect(null)
    }

    @Test
    fun deathOfCurrentToken_reportsDisconnected() {
        val h = Harness(timeoutMs = 5_000L)
        val recipient = h.connectAndAttach()

        recipient.captured.binderDied()

        // 成功路径不清理 activeLaunch（linkToDeath 回调要靠它核验 token 仍是当前连接），
        // 所以 connectAndAttach 收尾后 activeLaunch 仍是 token1，死讯确定上抛。
        assertEquals("当前连接死讯必须上抛", 1, h.callbacks.disconnected.get())
    }

    @Test
    fun binderFromSupersededConnect_isDropped() {
        val h = Harness(timeoutMs = 5_000L)
        h.connector.connect(h.callbacks)
        val token1 = h.connector.awaitToken()

        h.connector.connect(h.callbacks)
        val token2 = h.connector.awaitToken()

        assertFalse("被取代的 token 应已注销", h.registry.attach(token1, mockk(relaxed = true)))
        // token2 在 connect() 内同步登记，job2 尚未收到 binder（handle=null，超时 5s 远未到），
        // 因此这里是确定态而非竞速：被取代只注销旧 token，新 token 必须留着。
        assertNotNull("新 token 仍在等待", h.registry.pending[token2])
        assertTrue(h.callbacks.connected.isEmpty())
        h.connector.disconnect(null)
    }
}
