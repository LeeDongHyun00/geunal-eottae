package app.geunal.bridge

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse
import com.android.installreferrer.api.InstallReferrerStateListener

/**
 * Picks up an invite that sent someone to the store before the app was installed (docs/parts/android.md §4): the invite
 * page passes `invite=<code>` as the Play referrer, and the Play Store hands it back once here. Only Google Play can
 * fill it, so another app cannot slip a code in this way.
 */
object InstallReferrer {
    /** Reads the referrer once per install into [pending]; a Play Store that is busy now is asked again next launch. */
    fun readOnce(
        context: Context,
        pending: PendingInvite,
    ) {
        if (pending.referrerRead) return
        val client = InstallReferrerClient.newBuilder(context).build()
        client.startConnection(
            object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    when (responseCode) {
                        InstallReferrerResponse.OK -> {
                            val referrer = runCatching { client.installReferrer.installReferrer }.getOrNull()
                            if (referrer != null) {
                                // An invite tapped after install already sits there and is newer than the store's.
                                InviteLink.fromReferrer(referrer)?.let { if (pending.code.value == null) pending.offer(it) }
                                pending.markReferrerRead()
                            }
                        }
                        // No Play Store on this device (or not one that keeps referrers): there will never be one.
                        InstallReferrerResponse.FEATURE_NOT_SUPPORTED,
                        InstallReferrerResponse.DEVELOPER_ERROR,
                        -> pending.markReferrerRead()
                        else -> Unit
                    }
                    client.endConnection()
                }

                override fun onInstallReferrerServiceDisconnected() = Unit
            },
        )
    }
}
