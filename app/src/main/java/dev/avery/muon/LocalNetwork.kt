package dev.avery.muon

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/**
 * Android 17's local network permission. Muon only ever talks to a trusted-LAN Tauon, so from API 37,
 * which it targets, discovery, the library, artwork and streaming all need it; without it a connection
 * does not fail, it times out. Muon therefore asks before connecting, and never tries without it.
 * Earlier Android versions have no such permission, and grant access as they always have.
 */
internal const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** The first API level that enforces it. */
internal const val LOCAL_NETWORK_API = 37

internal fun localNetworkNeedsAsking(sdk: Int): Boolean = sdk >= LOCAL_NETWORK_API

/** Whether Muon may reach the LAN now: always before Android 17, and from it only once granted. */
internal fun mayUseLocalNetwork(sdk: Int, granted: Boolean): Boolean = !localNetworkNeedsAsking(sdk) || granted

/** What the Connect screen offers while access is missing: ask again, or, once Android stops asking, Settings. */
internal enum class LocalNetworkAsk { Ask, OpenSettings }

internal fun localNetworkAsk(denied: Boolean, rationale: Boolean): LocalNetworkAsk =
    if (denied && !rationale) LocalNetworkAsk.OpenSettings else LocalNetworkAsk.Ask

/** Whether access is granted right now, read afresh, since it can be revoked in Settings at any time. */
internal fun localNetworkGranted(context: Context): Boolean = mayUseLocalNetwork(Build.VERSION.SDK_INT,
    ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED)

/** The last known answer, for Compose; refreshed on resume and after the permission prompt. */
internal object LocalNetworkState {
    var granted by mutableStateOf(true)
    /** Set once the user has declined in this sitting, to tell a first ask from a refusal. */
    var denied by mutableStateOf(false)
}
