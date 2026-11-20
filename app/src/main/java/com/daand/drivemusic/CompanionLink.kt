package com.daand.drivemusic

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager

/**
 * Lets Drive Mode open by itself while you're using the phone. Android blocks apps in the background
 * from putting a screen in front of you - so with the phone unlocked Drive Mode only got a banner to
 * tap (17-18 Nov logs). One of Android's own exceptions: an app linked to a companion device (your
 * OBD adapter, or a Bluetooth device in the car) through the CompanionDeviceManager. Linking is a
 * one-time confirmation by you; it gives no access to anything else.
 */
object CompanionLink {
    fun supported(ctx: Context) = android.os.Build.VERSION.SDK_INT >= 33 &&
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)

    private fun cdm(ctx: Context) = ctx.getSystemService(CompanionDeviceManager::class.java)

    /** Linked to at least one device: the exception applies. */
    fun linked(ctx: Context): Boolean = android.os.Build.VERSION.SDK_INT >= 33 && supported(ctx) && names33(ctx).isNotEmpty()

    /** What it's linked to, for the settings page. */
    fun linkedNames(ctx: Context): List<String> = if (android.os.Build.VERSION.SDK_INT >= 33 && supported(ctx)) names33(ctx) else emptyList()

    @androidx.annotation.RequiresApi(33)
    private fun names33(ctx: Context): List<String> = runCatching {
        cdm(ctx).myAssociations.map { a: AssociationInfo -> a.displayName?.toString() ?: a.deviceMacAddress?.toString() ?: "a device" }
    }.getOrDefault(emptyList())

    /**
     * Ask Android to link: the OBD adapter by its address when there is one (the confirmation names it),
     * otherwise a list of nearby Bluetooth devices to pick from (e.g. the screen in the car).
     * [show] gets Android's confirmation dialog to start; [done] the outcome.
     */
    fun link(activity: Activity, obdAddress: String?, show: (IntentSender) -> Unit, done: (Boolean, String) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 33 || !supported(activity)) { done(false, "this phone doesn't offer companion devices"); return }
        link33(activity, obdAddress, show, done)
    }

    @androidx.annotation.RequiresApi(33)
    private fun link33(activity: Activity, obdAddress: String?, show: (IntentSender) -> Unit, done: (Boolean, String) -> Unit) {
        val filter = BluetoothDeviceFilter.Builder().apply {
            obdAddress?.takeIf { android.bluetooth.BluetoothAdapter.checkBluetoothAddress(it) }?.let { setAddress(it) }
        }.build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(obdAddress != null).build()
        runCatching {
            cdm(activity).associate(request, activity.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = show(intentSender)
                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    DriveLog.log("Drive Mode: linked to a companion device - it may now open by itself while you use the phone")
                    done(true, associationInfo.displayName?.toString() ?: "the device")
                }
                override fun onFailure(error: CharSequence?) { done(false, error?.toString() ?: "it didn't work") }
            })
        }.onFailure { done(false, it.message ?: it.javaClass.simpleName) }
    }
}
