package com.strike

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class DiLink5NetworkClient(context: Context) : AutoCloseable {
    enum class Result { CONFIRMED, UNCONFIRMED, UNAVAILABLE, REJECTED }

    private val context = context.applicationContext
    private val gate = Any()
    private var closed = false
    private var requesting = false
    private var connection: Connection? = null
    private var pending: Pair<Connection, Callback>? = null

    fun request(start: Boolean): Result {
        synchronized(gate) {
            if (closed || requesting || Process.myUid() < 10_000) return Result.UNAVAILABLE
            requesting = true
        }
        try {
            if (!available()) {
                synchronized(gate) { connection }?.let(::retire)
                return Result.UNAVAILABLE
            }
            val connected = connect() ?: return Result.UNAVAILABLE
            val service = synchronized(gate) { connected.service } ?: return Result.UNAVAILABLE
            try {
                if (service.interfaceDescriptor != DESCRIPTOR) {
                    retire(connected)
                    return Result.UNAVAILABLE
                }
                val callback = Callback()
                synchronized(gate) {
                    if (!current(connected)) return Result.UNAVAILABLE
                    pending = connected to callback
                }
                val request = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    request.writeInterfaceToken(DESCRIPTOR)
                    request.writeInt(0)
                    request.writeInt(if (start) 0 else 1)
                    request.writeStrongBinder(callback)
                    if (!synchronized(gate) { current(connected) } || Thread.currentThread().isInterrupted) {
                        return Result.UNAVAILABLE
                    }
                    // This vendor call is synchronous; the owner supplies a bounded worker.
                    if (!service.transact(135, request, reply, 0)) {
                        retire(connected)
                        return Result.REJECTED
                    }
                    reply.readException()
                    if (reply.readInt() == 0) return Result.REJECTED
                } finally {
                    reply.recycle()
                    request.recycle()
                }
                val result = callback.await()
                return if (synchronized(gate) { current(connected) }) result else Result.UNAVAILABLE
            } catch (e: RemoteException) {
                retire(connected)
                return Result.UNAVAILABLE
            } catch (e: RuntimeException) {
                retire(connected)
                return Result.UNAVAILABLE
            }
        } finally {
            synchronized(gate) {
                pending?.second?.cancel()
                pending = null
                requesting = false
            }
        }
    }

    override fun close() {
        val held = synchronized(gate) {
            closed = true
            pending?.second?.cancel()
            connection
        }
        if (held != null) retire(held)
    }

    private fun available(): Boolean = try {
        val service = context.packageManager.getServiceInfo(COMPONENT, 0)
        service.enabled && service.exported && service.applicationInfo.enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: RuntimeException) {
        false
    }

    private fun connect(): Connection? {
        val existing = synchronized(gate) { if (closed) return null else connection }
        if (existing != null) {
            if (synchronized(gate) { current(existing) && existing.service?.isBinderAlive == true }) return existing
            retire(existing)
        }
        val fresh = synchronized(gate) {
            if (closed) return null
            Connection().also { connection = it }
        }
        var accepted = false
        try {
            if (!synchronized(gate) { current(fresh) }) return null
            accepted = context.bindService(Intent(ACTION).setComponent(COMPONENT), fresh, Context.BIND_AUTO_CREATE)
        } catch (e: RuntimeException) {
            return null
        } finally {
            val release = synchronized(gate) {
                fresh.registered = true
                !accepted || !current(fresh)
            }
            if (release) retire(fresh)
        }
        if (!accepted) return null
        val ready = try {
            fresh.ready.await(3_000L, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (ready && synchronized(gate) { current(fresh) && fresh.service?.isBinderAlive == true }) return fresh
        retire(fresh)
        return null
    }

    private fun current(held: Connection): Boolean = !closed && connection === held && !held.retired

    private fun retire(held: Connection) {
        val unbind = synchronized(gate) {
            if (connection === held) connection = null
            held.retired = true
            held.service = null
            held.ready.countDown()
            if (pending?.first === held) pending?.second?.cancel()
            held.registered.also { held.registered = false }
        }
        if (unbind) {
            try {
                context.unbindService(held)
            } catch (e: IllegalArgumentException) {
                // A rejected bind may not have registered its connection.
            }
        }
    }

    private inner class Connection : ServiceConnection {
        val ready = CountDownLatch(1)
        var registered = false
        var retired = false
        var service: IBinder? = null

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            synchronized(gate) {
                if (!current(this)) return
                this.service = service
                ready.countDown()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = retire(this)
        override fun onBindingDied(name: ComponentName) = retire(this)
        override fun onNullBinding(name: ComponentName) = retire(this)
    }

    private class Callback : Binder(), IInterface {
        private val ready = CountDownLatch(1)
        private val result = AtomicReference<Result?>()

        init { attachInterface(this, CALLBACK_DESCRIPTOR) }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                reply?.writeString(CALLBACK_DESCRIPTOR)
                return true
            }
            if (code != 11) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CALLBACK_DESCRIPTOR)
            val received = if (data.readInt() == 1) Result.CONFIRMED else Result.REJECTED
            result.compareAndSet(null, received)
            ready.countDown()
            reply?.writeNoException()
            return true
        }

        fun cancel() {
            result.compareAndSet(null, Result.UNAVAILABLE)
            ready.countDown()
        }

        fun await(): Result = try {
            if (ready.await(4_000L, TimeUnit.MILLISECONDS)) result.get() ?: Result.UNCONFIRMED
            else Result.UNCONFIRMED
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.UNAVAILABLE
        }
    }

    companion object {
        private const val ACTION = "android.Otasdk.OtaTsUpdateService"
        private const val DESCRIPTOR = "com.ts.ota.otasdkmgr.IOtaSdkManager"
        private const val CALLBACK_DESCRIPTOR = "com.ts.ota.otasdkmgr.IAbsMessageCallback"
        private val COMPONENT = ComponentName("com.ts.ota.otasdkmgr", "com.ts.ota.otasdkmgr.OtaTsUpdateService")
    }
}
