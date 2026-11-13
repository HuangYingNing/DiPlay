package com.shilapi.xcertplay.transport

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 只读试探吉利 OneOS 蓝牙服务；不记录设备名称、地址，也不改变配对状态。 */
internal object GeelyOneOsBluetoothProbe {
    private const val PACKAGE = "com.geely.service.oneosapi"
    private const val SERVICE = "com.geely.service.oneosapi.OneOSApiService"
    private const val MANAGER_DESCRIPTOR = "com.geely.lib.oneosapi.IServiceManager"
    private const val PHONE_DESCRIPTOR = "com.geely.lib.oneosapi.phone.inter.IBluetoothService"
    private val binderQueries = Executors.newSingleThreadExecutor { task ->
        Thread(task, "diplay-oneos-read-probe").apply { isDaemon = true }
    }

    fun capture(context: Context): String {
        val component = ComponentName(PACKAGE, SERVICE)
        val service = runCatching { context.packageManager.getServiceInfo(component, 0) }.getOrNull()
            ?: return "oneOsService=not_visible"
        if (!service.exported) return "oneOsService=not_exported"
        val permission = service.permission
        if (permission != null && context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            return "oneOsService=permission_denied"
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return "oneOsService=main_thread_skipped"

        val ready = CountDownLatch(1)
        val binder = AtomicReference<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, serviceBinder: IBinder) {
                binder.set(serviceBinder)
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                binder.set(null)
            }
        }
        val bound = try {
            context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE)
        } catch (failure: Exception) {
            return "oneOsService=bind_${failure.javaClass.simpleName}"
        }
        if (!bound) return "oneOsService=bind_rejected"
        return try {
            if (!ready.await(1500, TimeUnit.MILLISECONDS)) return "oneOsService=bind_timeout"
            val manager = binder.get() ?: return "oneOsService=binder_missing"
            val query = binderQueries.submit<String> { readBluetooth(manager) }
            try {
                query.get(2000, TimeUnit.MILLISECONDS)
            } finally {
                query.cancel(true)
            }
        } catch (failure: Exception) {
            "oneOsService=query_${failure.javaClass.simpleName}"
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    private fun readBluetooth(manager: IBinder): String {
        val phone = query(manager, MANAGER_DESCRIPTOR, 2, { it.writeInt(14) }) { reply ->
            reply.readStrongBinder()
        } ?: return "oneOsService=phone_binder_missing"
        val enabled = query(phone, PHONE_DESCRIPTOR, 28) { it.readInt() != 0 }
            ?: return "oneOsService=bt_state_unavailable"
        val current = query(phone, PHONE_DESCRIPTOR, 33) { reply ->
            if (reply.readInt() == 0) {
                "absent"
            } else {
                val name = reply.readString().orEmpty()
                val address = reply.readString().orEmpty()
                val type = reply.readInt()
                val bondState = reply.readInt()
                "present iphoneName=${name.contains("iPhone", ignoreCase = true)} " +
                    "addressAvailable=${address.isNotBlank()} type=$type bondState=$bondState"
            }
        } ?: "unavailable"
        return "oneOsService=connected btEnabled=$enabled currentDevice=$current"
    }

    private fun <T> query(
        binder: IBinder,
        descriptor: String,
        code: Int,
        write: (Parcel) -> Unit = {},
        read: (Parcel) -> T,
    ): T? {
        val reply = transact(binder, descriptor, code, write) ?: return null
        return try {
            read(reply)
        } finally {
            reply.recycle()
        }
    }

    private fun transact(
        binder: IBinder,
        descriptor: String,
        code: Int,
        write: (Parcel) -> Unit = {},
    ): Parcel? {
        val request = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken(descriptor)
            write(request)
            if (!binder.transact(code, request, reply, 0)) {
                reply.recycle()
                null
            } else {
                reply.readException()
                reply
            }
        } catch (failure: Exception) {
            reply.recycle()
            throw failure
        } finally {
            request.recycle()
        }
    }
}
