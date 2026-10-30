package com.geotree.app.feature.settings

import com.geotree.app.core.sync.SyncActivity
import com.geotree.app.core.sync.SyncOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTextTest {
    private fun idle(outcome: SyncOutcome?) = SyncActivity(running = false, lastOutcome = outcome)

    @Test
    fun `a running sync says so before anything else`() {
        assertEquals("Syncing now…", syncDetailText(SyncActivity(running = true), deviceOnline = false, authRequired = true))
    }

    @Test
    fun `an expired sign-in asks for a new sign-in and keeps field work going`() {
        val text = syncDetailText(idle(SyncOutcome.AuthExpired), deviceOnline = true, authRequired = true)
        assertTrue(text, text.contains("sign-in has expired"))
        assertTrue(text, text.contains("Field work continues"))
    }

    @Test
    fun `offline explains that changes stay on the phone and sync later`() {
        val text = syncDetailText(idle(SyncOutcome.BackendUnavailable("timeout")), deviceOnline = false, authRequired = false)
        assertTrue(text, text.contains("offline"))
        assertTrue(text, text.contains("saved on the device"))
    }

    @Test
    fun `an unreachable server mentions a cold start and the automatic retry`() {
        val text = syncDetailText(idle(SyncOutcome.BackendUnavailable("Server took too long to respond")), deviceOnline = true, authRequired = false)
        assertTrue(text, text.contains("Server took too long to respond"))
        assertTrue(text, text.contains("retries automatically"))
    }

    @Test
    fun `an interrupted sync reassures that nothing local was lost`() {
        val text = syncDetailText(idle(SyncOutcome.Interrupted("connection reset", pushed = 2)), deviceOnline = true, authRequired = false)
        assertTrue(text, text.contains("interrupted (connection reset)"))
        assertTrue(text, text.contains("nothing on this phone was lost"))
    }

    @Test
    fun `a completed sync summarises trees, leaf scans and updates`() {
        val text = syncDetailText(
            idle(SyncOutcome.Completed(pushed = 1, rejected = 0, pulled = 2, blockedTreeCode = null, assessmentsPushed = 3, assessmentsPulled = 1)),
            deviceOnline = true,
            authRequired = false,
        )
        assertEquals("Last sync sent 1 tree and 3 leaf scans, and received 3 updates.", text)
    }

    @Test
    fun `a completed sync reports rejected and blocked trees`() {
        val text = syncDetailText(
            idle(SyncOutcome.Completed(pushed = 0, rejected = 2, pulled = 0, blockedTreeCode = "T-001")),
            deviceOnline = true,
            authRequired = false,
        )
        assertTrue(text, text.contains("2 trees were not accepted"))
        assertTrue(text, text.contains("Tree T-001 needs a different code"))
    }

    @Test
    fun `before any sync it explains automatic sync and Sync Now`() {
        val text = syncDetailText(idle(null), deviceOnline = null, authRequired = false)
        assertTrue(text, text.contains("sync automatically"))
        assertTrue(text, text.contains("Sync Now"))
    }
}
