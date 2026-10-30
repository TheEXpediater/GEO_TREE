package com.geotree.app.feature.settings

import com.geotree.app.core.sync.SyncActivity
import com.geotree.app.core.sync.SyncOutcome

/**
 * One plain sentence under the sync status: what happened last and what happens next.
 * Local work is never lost by a failed sync, and retries happen on their own when online.
 */
fun syncDetailText(activity: SyncActivity, deviceOnline: Boolean?, authRequired: Boolean): String = when {
    activity.running -> "Syncing now…"
    authRequired -> "Your sign-in has expired. Field work continues on this phone; sign in again to resume syncing."
    deviceOnline == false -> "This phone is offline. Changes are saved on the device and sync automatically when a connection returns."
    else -> when (val outcome = activity.lastOutcome) {
        null -> "Changes sync automatically whenever the phone is online. Tap Sync Now to sync immediately."
        SyncOutcome.NotSignedIn -> "Sign in to sync."
        SyncOutcome.AuthExpired -> "The server asked for a new sign-in. Sign in again to resume syncing."
        is SyncOutcome.BackendUnavailable ->
            "The server could not be reached (${outcome.reason}). It may be starting up; sync retries automatically, or tap Sync Now."
        is SyncOutcome.Interrupted -> "The last sync was interrupted (${outcome.reason}). It retries automatically; nothing on this phone was lost."
        is SyncOutcome.Completed -> buildString {
            append("Last sync sent ${outcome.pushed} tree${if (outcome.pushed == 1) "" else "s"}")
            append(" and ${outcome.assessmentsPushed} leaf scan${if (outcome.assessmentsPushed == 1) "" else "s"}, ")
            val received = outcome.pulled + outcome.assessmentsPulled
            append("and received $received update${if (received == 1) "" else "s"}.")
            if (outcome.rejected > 0) append(" ${outcome.rejected} tree${if (outcome.rejected == 1) " was" else "s were"} not accepted; see the Dashboard.")
            outcome.blockedTreeCode?.let { append(" Tree $it needs a different code before it can sync.") }
        }
    }
}
