package com.geotree.app.feature.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geotree.app.data.repository.AuthRepository
import com.geotree.app.data.repository.LoginResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val emailError: String? = null,
    val passwordError: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val signedIn: Boolean = false,
)

/**
 * @param prefillEmail / [prefillPassword] come from BuildConfig and are empty in release builds.
 */
class LoginViewModel(
    private val authRepository: AuthRepository,
    private val serverDescription: suspend () -> String,
    private val afterSignIn: () -> Unit,
    prefillEmail: String,
    prefillPassword: String,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState(email = prefillEmail, password = prefillPassword))
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, emailError = null, errorMessage = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, passwordError = null, errorMessage = null) }

    fun togglePasswordVisibility() = _state.update { it.copy(passwordVisible = !it.passwordVisible) }

    fun submit() {
        val current = _state.value
        if (current.isLoading) return // no duplicate submissions
        val emailError = validateEmail(current.email)
        val passwordError = if (current.password.isEmpty()) "Enter your password." else null
        if (emailError != null || passwordError != null) {
            _state.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = authRepository.login(current.email, current.password)
            val message = when (result) {
                is LoginResult.Success -> null
                LoginResult.InvalidCredentials -> "Incorrect email or password."
                is LoginResult.Unreachable -> "Cannot reach the GEO Tree server at ${serverDescription()}. Check that the backend is running, or change the server."
                is LoginResult.ServerError -> result.message
            }
            if (result is LoginResult.Success) afterSignIn()
            _state.update { it.copy(isLoading = false, errorMessage = message, signedIn = result is LoginResult.Success) }
        }
    }

    companion object {
        private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        fun validateEmail(email: String): String? = when {
            email.isBlank() -> "Enter your email."
            !EMAIL.matches(email.trim()) -> "Enter a valid email address."
            else -> null
        }
    }
}
