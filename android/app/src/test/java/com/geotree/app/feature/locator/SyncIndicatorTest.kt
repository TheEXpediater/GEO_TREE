package com.geotree.app.feature.locator

import com.geotree.app.core.design.PillTone
import com.geotree.app.core.network.BackendConfig
import com.geotree.app.core.sync.SyncActivity
import com.geotree.app.core.sync.SyncOutcome
import com.geotree.app.data.repository.SyncCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncIndicatorTest {
    @Test
    fun `indicator reflects Room counts and last sync outcome`() {
        assertEquals(SyncIndicator("No trees yet", PillTone.Neutral), syncIndicator(SyncCounts(), SyncActivity()))
        assertEquals(SyncIndicator("2 pending", PillTone.Amber), syncIndicator(SyncCounts(pending = 2), SyncActivity()))
        assertEquals(
            SyncIndicator("Offline · 3 pending", PillTone.Warning),
            syncIndicator(SyncCounts(pending = 2, failed = 1), SyncActivity(lastOutcome = SyncOutcome.BackendUnavailable("x"))),
        )
        assertEquals(SyncIndicator("1 failed", PillTone.Error), syncIndicator(SyncCounts(synced = 3, failed = 1), SyncActivity()))
        assertEquals(SyncIndicator("Syncing…", PillTone.Neutral), syncIndicator(SyncCounts(pending = 1), SyncActivity(running = true)))
        assertEquals(SyncIndicator("All synced", PillTone.Good), syncIndicator(SyncCounts(synced = 4), SyncActivity()))
    }

    @Test
    fun `backend url normalization accepts LAN addresses and rejects junk`() {
        assertEquals("http://192.168.1.20:8000", BackendConfig.normalize(" 192.168.1.20:8000/ "))
        assertEquals("http://10.0.2.2:8000", BackendConfig.normalize("http://10.0.2.2:8000"))
        assertEquals("https://geotree.example.org", BackendConfig.normalize("https://geotree.example.org/"))
        assertNull(BackendConfig.normalize(""))
        assertNull(BackendConfig.normalize("ftp://host"))
        assertNull(BackendConfig.normalize("http://"))
    }
}
