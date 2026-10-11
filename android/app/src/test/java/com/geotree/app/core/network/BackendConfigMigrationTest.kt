package com.geotree.app.core.network

import com.geotree.app.BuildConfig
import com.geotree.app.core.session.Session
import com.geotree.app.core.session.SessionStore
import com.geotree.app.core.session.SessionState
import com.geotree.app.core.session.sessionState
import com.geotree.app.feature.splash.SplashDestination
import com.geotree.app.feature.splash.startDestination
import com.geotree.app.testing.testDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackendConfigMigrationTest {
    @get:Rule val temp = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val hosted = "https://geo-tree.onrender.com"

    @After
    fun tearDown() = storeScope.cancel()

    private fun store() = testDataStore(temp.newFolder(), storeScope)

    @Test
    fun `local Docker backend is the build default for the AI milestone`() {
        // Hosted builds remain possible with -Pgeotree.baseUrl=https://geo-tree.onrender.com.
        assertEquals("http://10.0.2.2:8000", BuildConfig.DEFAULT_BASE_URL)
        assertTrue(BackendConfig.isLocalDevelopmentUrl(BuildConfig.DEFAULT_BASE_URL))
    }

    @Test
    fun `a local default build never runs the hosted migration`() = runTest {
        val config = BackendConfig(store(), BuildConfig.DEFAULT_BASE_URL)
        config.save("http://192.168.1.20:8000")

        assertFalse(config.migrateLegacyLocalOverride())
        assertEquals("http://192.168.1.20:8000", config.current())
    }

    @Test
    fun `local and private addresses are recognised, hosted ones are not`() {
        listOf(
            "http://10.0.2.2:8000", "http://localhost:8000", "http://127.0.0.1:8000",
            "http://192.168.1.20:8000", "http://10.20.30.40:8000",
            "http://172.16.0.5:8000", "http://172.31.255.1:8000",
        ).forEach { assertTrue(it, BackendConfig.isLocalDevelopmentUrl(it)) }
        listOf(
            hosted, "https://geo-tree-staging.example.org", "http://172.15.0.1:8000",
            "http://172.32.0.1:8000", "http://8.8.8.8", "https://10.example.com", "not a url",
        ).forEach { assertFalse(it, BackendConfig.isLocalDevelopmentUrl(it)) }
    }

    @Test
    fun `saved emulator server is migrated to the hosted default`() = runTest {
        val config = BackendConfig(store(), hosted)
        config.save("http://10.0.2.2:8000")

        assertTrue(config.migrateLegacyLocalOverride())
        assertEquals(hosted, config.current())
    }

    @Test
    fun `saved LAN server is migrated to the hosted default`() = runTest {
        val config = BackendConfig(store(), hosted)
        config.save("http://192.168.1.20:8000")

        assertTrue(config.migrateLegacyLocalOverride())
        assertEquals(hosted, config.current())
    }

    @Test
    fun `intentional HTTPS hosted override is preserved`() = runTest {
        val config = BackendConfig(store(), hosted)
        config.save("https://geo-tree-staging.example.org")

        assertFalse(config.migrateLegacyLocalOverride())
        assertEquals("https://geo-tree-staging.example.org", config.current())
    }

    @Test
    fun `migration runs once so a LAN server can be chosen again afterwards`() = runTest {
        val config = BackendConfig(store(), hosted)
        config.save("http://10.0.2.2:8000")
        assertTrue(config.migrateLegacyLocalOverride())

        config.save("http://192.168.1.20:8000") // deliberate choice made in Settings → Change Server
        assertFalse(config.migrateLegacyLocalOverride())
        assertEquals("http://192.168.1.20:8000", config.current())
    }

    @Test
    fun `local development builds keep their local server`() = runTest {
        val config = BackendConfig(store(), "http://10.0.2.2:8000")
        config.save("http://192.168.1.20:8000")

        assertFalse(config.migrateLegacyLocalOverride())
        assertEquals("http://192.168.1.20:8000", config.current())
    }

    @Test
    fun `migration keeps the session and a valid session still skips Login`() = runTest {
        // Production shares one preferences file between the session and the server address.
        val prefs = store()
        val sessionStore = SessionStore(prefs)
        val now = 1_790_000_000_000
        val session = Session("token", "admin@gmail.com", now + 86_400_000)
        sessionStore.save(session)
        val config = BackendConfig(prefs, hosted)
        config.save("http://10.0.2.2:8000")

        assertTrue(config.migrateLegacyLocalOverride())

        val restored = sessionStore.current()
        assertEquals(session, restored)
        assertEquals(SessionState.VALID, sessionState(restored, now))
        assertEquals(SplashDestination.Main, startDestination(sessionState(restored, now)))
    }
}
