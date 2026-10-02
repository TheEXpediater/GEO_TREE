package com.geotree.app.feature.splash

import com.geotree.app.core.session.Session
import com.geotree.app.core.session.SessionState
import com.geotree.app.core.session.SessionStore
import com.geotree.app.core.session.sessionState
import com.geotree.app.testing.FakeGeoTreeServer
import com.geotree.app.testing.testDataStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SessionRoutingTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val now = 1_790_000_000_000L
    private val day = 24 * 60 * 60 * 1000L
    private val validSession = Session("token-1", "admin@gmail.com", expiresAtMillis = now + 7 * day)
    private val expiredSession = Session("token-0", "admin@gmail.com", expiresAtMillis = now - 1)

    /** Stands in for the backend: any call would show up in its counters. */
    private val offlineServer = FakeGeoTreeServer().apply { healthy = false }
    private var syncScheduled = 0

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        storeScope.cancel()
    }

    private fun splash(session: Session?) = SplashViewModel(
        openLocalRecords = {},
        prepareOfflineMap = {},
        restoreSession = { session },
        prepareApiClient = {}, // builds a client only; never sends a request
        scheduleSync = { syncScheduled++ },
        clock = { now },
    )

    @Test
    fun `session state follows the stored expiry`() {
        assertEquals(SessionState.NONE, sessionState(null, now))
        assertEquals(SessionState.VALID, sessionState(validSession, now))
        assertEquals(SessionState.EXPIRED, sessionState(expiredSession, now))
        assertEquals("expiry instant itself counts as expired", SessionState.EXPIRED, sessionState(validSession.copy(expiresAtMillis = now), now))
        assertEquals("unknown expiry is left to the server", SessionState.VALID, sessionState(validSession.copy(expiresAtMillis = 0), now))
    }

    @Test
    fun `no session goes to login`() = runTest {
        val vm = splash(null)
        advanceUntilIdle()
        assertEquals(SplashDestination.Login, vm.state.value.destination)
        assertEquals(0, syncScheduled)
    }

    @Test
    fun `valid session goes to the dashboard without contacting the backend`() = runTest {
        val vm = splash(validSession)
        advanceUntilIdle()
        assertEquals(SplashDestination.Main, vm.state.value.destination)
        assertEquals(SessionState.VALID, vm.state.value.sessionState)
        assertEquals(1, syncScheduled) // queued for when the network allows
        assertEquals("startup never calls the API", 0, offlineServer.loginCalls + offlineServer.syncCalls + offlineServer.uploadCalls)
    }

    @Test
    fun `expired session still opens the local dashboard but does not schedule sync`() = runTest {
        val vm = splash(expiredSession)
        advanceUntilIdle()
        assertEquals(SplashDestination.Main, vm.state.value.destination)
        assertEquals(SessionState.EXPIRED, vm.state.value.sessionState)
        assertEquals(0, syncScheduled)
    }

    @Test
    fun `stored session survives an app restart`() = runTest {
        val dir = temp.newFolder()
        SessionStore(testDataStore(dir, storeScope)).save(validSession)

        // A restart starts from whatever was persisted on disk. (DataStore allows one instance per
        // file per process, so the "new process" reads a copy of the persisted file.)
        val restartedDir = temp.newFolder()
        File(dir, "test.preferences_pb").copyTo(File(restartedDir, "test.preferences_pb"))
        val restartedScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val restored = SessionStore(testDataStore(restartedDir, restartedScope)).current()
            assertEquals(validSession, restored)
            assertEquals(SplashDestination.Main, startDestination(sessionState(restored, now)))
        } finally {
            restartedScope.cancel()
        }
    }

    @Test
    fun `explicit logout returns to login`() = runTest {
        val store = SessionStore(testDataStore(temp.newFolder(), storeScope))
        store.save(validSession)
        assertTrue(store.current() != null)

        store.clear()

        assertEquals(SplashDestination.Login, startDestination(sessionState(store.current(), now)))
        assertFalse(store.cachedAccessToken() != null)
    }
}
