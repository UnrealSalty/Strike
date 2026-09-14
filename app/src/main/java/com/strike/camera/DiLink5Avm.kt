package com.strike.camera

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import com.strike.daemon.DaemonLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val AVM_DESCRIPTOR = "com.ts.avm.IAvmServiceInterface"
private const val AVM_WAIT_MS = 2_000L

internal class DiLink5Avm {
    private var binding: Binding? = null
    private val requesting = AtomicBoolean(false)

    fun open(context: Context) {
        close()
        val fresh = Binding(context)
        binding = fresh
        if (!fresh.bind()) return
        try {
            if (!fresh.connected.await(AVM_WAIT_MS, TimeUnit.MILLISECONDS)) {
                DaemonLog.w("Camera", "the car's AVM service did not connect")
                return
            }
            val service = fresh.service ?: return
            if (!requesting.compareAndSet(false, true)) return
            val finished = CountDownLatch(1)
            val awakened = AtomicBoolean(false)
            Thread({
                try {
                    if (!fresh.closed) awakened.set(wake(service))
                } finally {
                    requesting.set(false)
                    finished.countDown()
                }
            }, "ais-wake").also { it.isDaemon = true }.start()
            if (!finished.await(AVM_WAIT_MS, TimeUnit.MILLISECONDS) || !awakened.get()) {
                DaemonLog.w("Camera", "the car's AVM service did not confirm camera wake")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    fun close() {
        binding?.close()
        binding = null
    }

    private fun wake(service: IBinder): Boolean {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            if (service.interfaceDescriptor != AVM_DESCRIPTOR) return false
            request.writeInterfaceToken(AVM_DESCRIPTOR)
            // startAvm is transaction 8 in Overdrive's com.ts.avm AIDL.
            if (!service.transact(IBinder.FIRST_CALL_TRANSACTION + 7, request, reply, 0)) return false
            reply.readException()
            true
        } catch (e: RemoteException) {
            false
        } catch (e: RuntimeException) {
            false
        } finally {
            request.recycle()
            reply.recycle()
        }
    }

    private class Binding(private val context: Context) : ServiceConnection {
        val connected = CountDownLatch(1)
        @Volatile var service: IBinder? = null
        @Volatile var closed = false
        private var bound = false

        fun bind(): Boolean = try {
            val intent = Intent().setComponent(ComponentName("com.ts.avm", "com.ts.avm.AvmAndroidService"))
            bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE)
            if (!bound) DaemonLog.w("Camera", "the car's AVM service is unavailable")
            bound
        } catch (e: RuntimeException) {
            DaemonLog.w("Camera", "the car's AVM service could not be reached")
            false
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!closed) service = binder
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }

        override fun onNullBinding(name: ComponentName) {
            connected.countDown()
        }

        override fun onBindingDied(name: ComponentName) {
            service = null
            connected.countDown()
        }

        fun close() {
            closed = true
            service = null
            if (!bound) return
            bound = false
            try {
                context.unbindService(this)
            } catch (e: RuntimeException) {
                DaemonLog.w("Camera", "the car's AVM service could not be released")
            }
        }
    }
}
