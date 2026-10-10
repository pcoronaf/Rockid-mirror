package com.rokidmirror.sender.privilege

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.rokidmirror.sender.telemetry.MirrorLog

/**
 * Receives the Binder the helper publishes, and nothing else.
 *
 * The helper (running as `shell`) calls into this provider through `getContentProviderExternal`
 * and hands over its Binder. The call is accepted only when it genuinely comes from the shell
 * user, so no other app can impersonate the helper. Adapted from Dextop's
 * `EmbeddedPrivilegeProvider` (GPL-3.0).
 */
class ShellPrivilegeProvider : ContentProvider() {
    private val lifecycleBinder = Binder()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val fromShell = Binder.getCallingUid() == Process.SHELL_UID
        if (!fromShell || method != ShellProtocol.PROVIDER_METHOD || extras == null) return null
        val binder = extras.getBinder(ShellProtocol.PROVIDER_BINDER_KEY) ?: return null
        if (binder.isBinderAlive) {
            ShellBridge.accept(binder)
            MirrorLog.i("ShellProvider", "runtime_published")
        }
        return Bundle().apply {
            putBoolean("accepted", binder.isBinderAlive)
            putBinder(ShellProtocol.PROVIDER_LIFECYCLE_KEY, lifecycleBinder)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
