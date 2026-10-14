package com.tlisonbee.remind.sync

import java.util.concurrent.TimeUnit

/** Decides when to tell the user that syncing has quietly stopped working. */
object SyncHealth {
    val STALE_AFTER_MILLIS = TimeUnit.HOURS.toMillis(12)

    enum class Action { SHOW, CANCEL, NONE }

    /**
     * @param lastOkAt last successful sync, or when sync was set up if it never succeeded
     * @param alreadyShown whether the notification was already posted for this stretch of failures,
     *   so a dismissed notification isn't re-posted every 15 minutes
     */
    fun afterSync(success: Boolean, lastOkAt: Long?, now: Long, alreadyShown: Boolean): Action = when {
        success -> if (alreadyShown) Action.CANCEL else Action.NONE
        alreadyShown || lastOkAt == null -> Action.NONE
        now - lastOkAt >= STALE_AFTER_MILLIS -> Action.SHOW
        else -> Action.NONE
    }
}
