package com.daand.drivemusic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/** Gets the result of an update install from Android's package installer. Not exported. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DriveLog.init(context)
        val prefs = Prefs(context)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Show Android's own "Do you want to update this app?" screen.
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        prefs.updateStatus = "Could not show the install screen: ${e.message}"
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                prefs.updateStatus = "Updated successfully"
                DriveLog.log("UPDATE: installed successfully")
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                prefs.updateStatus = "Update cancelled"
                DriveLog.log("UPDATE: cancelled by user")
            }
            else -> {
                prefs.updateStatus = "Update failed: ${message ?: "status $status"}"
                DriveLog.log("UPDATE: failed (status $status: $message)")
            }
        }
    }
}
