package com.geotree.app.core.session

import com.geotree.app.data.remote.GeoTreeApi
import com.geotree.app.data.remote.LoginRequestDto
import com.geotree.app.data.remote.UserDto
import com.geotree.app.data.repository.AuthRepository
import com.geotree.app.data.repository.LoginResult
import com.geotree.app.testing.FakeGeoTreeServer
import com.geotree.app.testing.testDataStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The account role travels with the session (shown in Settings → Account; enforced by the server). */
class SessionRoleTest {
    @get:Rule val temp = TemporaryFolder()

    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After
    fun tearDown() = storeScope.cancel()

    private fun store() = SessionStore(testDataStore(temp.newFolder(), storeScope))

    /** The fake server's login/me, but reporting the given role. */
    private class RoleServer(private val role: String?, private val base: FakeGeoTreeServer = FakeGeoTreeServer()) : GeoTreeApi by base {
        override suspend fun login(body: LoginRequestDto) = base.login(body).let { it.copy(user = it.user.copy(role = role)) }
        override suspend fun me(): UserDto = UserDto("admin@gmail.com", "Development Admin", role)
    }

    @Test
    fun `roles have readable labels and unknown roles are shown as sent`() {
        assertEquals("Field Tester", Session("t", "a@b.c", 0, role = "FIELD_TESTER").roleLabel)
        assertEquals("Admin", Session("t", "a@b.c", 0, role = "ADMIN").roleLabel)
        assertEquals("Super Admin", Session("t", "a@b.c", 0, role = "SUPER_ADMIN").roleLabel)
        assertEquals("AUDITOR", Session("t", "a@b.c", 0, role = "AUDITOR").roleLabel)
        assertNull(Session("t", "a@b.c", 0).roleLabel)
    }

    @Test
    fun `login stores the role and sign-out clears it`() = runTest {
        val sessions = store()
        val result = AuthRepository({ RoleServer("ADMIN") }, sessions).login("admin@gmail.com", "admin123")
        assertTrue(result is LoginResult.Success)
        assertEquals("ADMIN", sessions.current()?.role)

        AuthRepository({ RoleServer("ADMIN") }, sessions).logout()
        assertNull(sessions.current())
    }

    @Test
    fun `a session saved before roles existed gains the role from the server`() = runTest {
        val sessions = store()
        sessions.save(Session("old-token", "admin@gmail.com", 0))
        assertNull(sessions.current()!!.role)

        AuthRepository({ RoleServer("ADMIN") }, sessions).refreshProfile()
        assertEquals("Admin", sessions.current()!!.roleLabel)
        assertEquals("old-token", sessions.current()!!.accessToken)
    }

    @Test
    fun `profile refresh offline leaves the session untouched`() = runTest {
        val sessions = store()
        sessions.save(Session("t", "admin@gmail.com", 0, role = "ADMIN"))
        AuthRepository({ throw IOException("offline") }, sessions).refreshProfile()
        AuthRepository({ throw FakeGeoTreeServer.httpError(401, "UNAUTHORIZED", "Invalid or expired token.") }, sessions).refreshProfile()
        assertEquals("ADMIN", sessions.current()?.role)
    }
}
