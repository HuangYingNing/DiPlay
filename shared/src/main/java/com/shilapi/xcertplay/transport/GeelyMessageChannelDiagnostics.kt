package com.shilapi.xcertplay.transport

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** 只读检查车机消息通道服务是否对 DiPlay 开放，不绑定服务或发送账户消息。 */
internal object GeelyMessageChannelDiagnostics {
    private const val PACKAGE = "com.reachauto.messagechannel"
    private const val ACTION = "com.reachauto.messagechannel.MessageChannelService"

    fun capture(context: Context): String {
        val manager = context.packageManager
        val visible = runCatching { manager.getPackageInfo(PACKAGE, 0) }.isSuccess
        val service = runCatching {
            manager.resolveService(Intent(ACTION).setPackage(PACKAGE), 0)?.serviceInfo
        }.getOrNull()
        val permission = service?.permission
        val access = when {
            service == null -> "unresolved"
            !service.exported -> "not_exported"
            permission == null -> "no_declared_permission"
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED -> "permission_granted"
            else -> "permission_denied"
        }
        return "messageChannelPackageVisible=$visible serviceResolved=${service != null} " +
            "serviceExported=${service?.exported ?: "unknown"} serviceAccess=$access"
    }
}
