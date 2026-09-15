package com.strike.camera

import android.graphics.SurfaceTexture
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameBusLifecycleTest {
    @Test
    fun detachingWaitsForTheGraphicsOwnerToRetireTheConsumer() {
        val bus = frameBusForTest()
        bus.add(Consumer("recorder", Surface(null as SurfaceTexture?), CameraView.ALL, Frame(64, 64)))
        val releaseOwner = CountDownLatch(1)
        val owner = Thread {
            releaseOwner.await()
            call(bus, "retire")
        }
        set(bus, "thread", owner)
        owner.start()
        val detached = AtomicReference<Boolean?>()
        val waiter = Thread { detached.set(bus.detach("recorder")) }
        waiter.start()
        try {
            until {
                (field(bus, "retired") as Collection<*>).any {
                    it != null && field(it, "done") != null
                }
            }
            assertNull(detached.get())
        } finally {
            releaseOwner.countDown()
            waiter.join(3_000)
            owner.join(3_000)
        }
        assertFalse(waiter.isAlive)
        assertTrue(detached.get() == true)
    }

    @Test
    fun anIdleBusRetiresAConsumerWithoutWaitingForAnotherCameraFrame() {
        val bus = frameBusForTest()
        val owner = Thread { call(bus, "spin") }
        set(bus, "thread", owner)
        owner.start()
        try {
            until { field(bus, "running") == true }
            bus.add(Consumer("recorder", Surface(null as SurfaceTexture?), CameraView.ALL, Frame(64, 64)))
            bus.add(Consumer("detector", Surface(null as SurfaceTexture?), CameraView.ALL, Frame(64, 64)))
            assertTrue(bus.detach("recorder"))
            val consumers = field(bus, "consumers") as Collection<*>
            assertTrue(consumers.single() is Consumer)
            assertTrue((consumers.single() as Consumer).name == "detector")
            assertTrue(bus.frameCount == 0L)
        } finally {
            assertTrue(bus.stop())
        }
    }

    @Test
    fun stoppingAKeptBusyBusDoesNotForgetItsLiveWorker() {
        val bus = frameBusForTest()
        val entered = CountDownLatch(1)
        val releaseOwner = CountDownLatch(1)
        val owner = Thread { entered.countDown(); releaseOwner.await() }
        set(bus, "thread", owner)
        owner.start()
        assertTrue(entered.await(1, TimeUnit.SECONDS))
        try {
            assertFalse(bus.stop())
            assertSame(owner, field(bus, "thread"))
        } finally {
            releaseOwner.countDown()
            owner.join(2_000)
        }
        assertTrue(bus.stop())
    }

    private fun field(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(target)

    private fun set(target: Any, name: String, value: Any) =
        target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(target, value)

    private fun call(target: Any, name: String) =
        target.javaClass.getDeclaredMethod(name).also { it.isAccessible = true }.invoke(target)

    private fun until(ready: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (ready()) return
            Thread.yield()
        }
        throw AssertionError("graphics owner did not reach the expected state")
    }
}
