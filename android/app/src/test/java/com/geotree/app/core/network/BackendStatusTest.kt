package com.geotree.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class BackendStatusTest {
    @Test
    fun `health check results map to reachability`() {
        assertEquals(BackendReachability.CONNECTED, BackendCheck.Verified("http://10.0.2.2:8000", "0.2.0").toStatus(1).reachability)
        assertEquals(BackendReachability.OFFLINE, BackendCheck.Unreachable("http://10.0.2.2:8000", "timeout").toStatus(1).reachability)
        assertEquals(BackendReachability.OFFLINE, BackendCheck.DatabaseUnavailable("http://x:8000").toStatus(1).reachability)
        assertEquals(BackendReachability.OFFLINE, BackendCheck.NotGeoTree("http://x:8000").toStatus(1).reachability)
        assertEquals(BackendReachability.OFFLINE, BackendCheck.InvalidUrl.toStatus(1).reachability)
        val connected = BackendCheck.Verified("http://192.168.1.20:8000", "0.2.0").toStatus(99)
        assertEquals("http://192.168.1.20:8000", connected.baseUrl)
        assertEquals(99L, connected.checkedAt)
    }
}
