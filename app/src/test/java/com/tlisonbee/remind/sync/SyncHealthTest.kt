package com.tlisonbee.remind.sync

import com.tlisonbee.remind.sync.SyncHealth.Action
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class SyncHealthTest {
    private val hour = TimeUnit.HOURS.toMillis(1)
    private val now = 1_000 * hour

    @Test
    fun failureUnderTwelveHoursStaysQuiet() {
        assertEquals(Action.NONE, SyncHealth.afterSync(false, now - 11 * hour, now, alreadyShown = false))
    }

    @Test
    fun failureAfterTwelveHoursNotifiesOnce() {
        assertEquals(Action.SHOW, SyncHealth.afterSync(false, now - 12 * hour, now, alreadyShown = false))
        assertEquals(Action.NONE, SyncHealth.afterSync(false, now - 13 * hour, now, alreadyShown = true))
    }

    @Test
    fun successClearsTheNotification() {
        assertEquals(Action.CANCEL, SyncHealth.afterSync(true, now - 20 * hour, now, alreadyShown = true))
        assertEquals(Action.NONE, SyncHealth.afterSync(true, now - 20 * hour, now, alreadyShown = false))
    }

    @Test
    fun notConfiguredOrUnknownStartNeverNotifies() {
        assertEquals(Action.NONE, SyncHealth.afterSync(false, null, now, alreadyShown = false))
    }
}
