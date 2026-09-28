package com.geotree.app.feature.login

import com.geotree.app.BuildConfig
import com.geotree.app.core.session.SessionStore
import com.geotree.app.data.repository.AuthRepository
import com.geotree.app.testing.FakeGeoTreeServer
import com.geotree.app.testing.testDataStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @get:Rule val temp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val storeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val server = FakeGeoTreeServer()
    private lateinit var sessionStore: SessionStore
    private var afterSignInCalls = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        sessionStore = SessionStore(testDataStore(temp.newFolder(), storeScope))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        storeScope.cancel()
    }

    private fun viewModel(email: String = "admin@gmail.com", password: String = "admin123") = LoginViewModel(
        authRepository = AuthRepository({ server }, sessionStore),
        serverDescription = { "http://10.0.2.2:8000" },
        afterSignIn = { afterSignInCalls++ },
        prefillEmail = email,
        prefillPassword = password,
    )

    @Test
    fun `debug build prefills development credentials`() {
        assertTrue(BuildConfig.DEBUG)
        assertEquals("admin@gmail.com", BuildConfig.DEV_EMAIL)
        assertEquals("admin123", BuildConfig.DEV_PASSWORD)
        val state = viewModel(BuildConfig.DEV_EMAIL, BuildConfig.DEV_PASSWORD).state.value
        assertEquals("admin@gmail.com", state.email)
        assertEquals("admin123", state.password)
        assertFalse(state.passwordVisible)
    }

    @Test
    fun `successful login shows loading then signs in and stores the session`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.submit()
        assertTrue(vm.state.value.isLoading)

        vm.awaitIdle()

        val state = vm.state.value
        assertFalse(state.isLoading)
        assertTrue(state.signedIn)
        assertNull(state.errorMessage)
        assertEquals("token-123", sessionStore.current()!!.accessToken)
        assertEquals(1, afterSignInCalls)
    }

    @Test
    fun `wrong password shows inline error and stays signed out`() = runTest(dispatcher) {
        val vm = viewModel(password = "wrong")
        vm.submit()
        vm.awaitIdle()

        assertFalse(vm.state.value.signedIn)
        assertEquals("Incorrect email or password.", vm.state.value.errorMessage)
        assertNull(sessionStore.current())
        assertEquals(0, afterSignInCalls)
    }

    @Test
    fun `unreachable server shows a network error naming the server`() = runTest(dispatcher) {
        server.loginFailure = IOException("Failed to connect")
        val vm = viewModel()
        vm.submit()
        vm.awaitIdle()

        val message = vm.state.value.errorMessage
        assertNotNull(message)
        assertTrue(message!!.contains("http://10.0.2.2:8000"))
        assertFalse(vm.state.value.isLoading)
    }

    @Test
    fun `invalid input is caught before any network call`() = runTest(dispatcher) {
        val vm = viewModel(email = "not-an-email", password = "")
        vm.submit()
        vm.awaitIdle()

        assertEquals("Enter a valid email address.", vm.state.value.emailError)
        assertEquals("Enter your password.", vm.state.value.passwordError)
        assertEquals(0, server.loginCalls)
    }

    @Test
    fun `duplicate submissions while loading are ignored`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.submit()
        vm.submit()
        vm.submit()
        vm.awaitIdle()
        assertEquals(1, server.loginCalls)
    }

    @Test
    fun `password visibility toggles`() {
        val vm = viewModel()
        vm.togglePasswordVisibility()
        assertTrue(vm.state.value.passwordVisible)
    }
}

/** Session persistence runs on a real IO thread, so wait on state rather than the test scheduler. */
private suspend fun LoginViewModel.awaitIdle() = state.first { !it.isLoading }
