package com.example.stationops.ui.login

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.stationops.data.local.SecureStorage
import com.example.stationops.data.repository.AuthRepository
import com.example.stationops.BuildConfig
import kotlinx.coroutines.launch

class LoginViewModel : ViewModel() {
    private val repo = AuthRepository()

    var username = mutableStateOf("")
    var password = mutableStateOf("")
    var rememberMe = mutableStateOf(false)
    var isLoading = mutableStateOf(false)
    var loginError = mutableStateOf<String?>(null)

    private var secureStorage: SecureStorage? = null

    fun checkForSavedCredentials(context: Context) {
        if (secureStorage == null) {
            secureStorage = SecureStorage(context.applicationContext)
        }

        val saved = secureStorage?.getCredentials()
        if (saved != null) {
            username.value = saved.first
            password.value = saved.second
            rememberMe.value = true
        }
    }

    fun loginUser(onResult: (String) -> Unit) {
        if (username.value.isBlank() || password.value.isBlank()) {
            loginError.value = "Please fill all fields"
            return
        }

        viewModelScope.launch {
            isLoading.value = true
            loginError.value = null
            try {
                val finalEmail = "${username.value.trim()}@${BuildConfig.EMAIL_DOMAIN}"
                val user = repo.login(finalEmail, password.value)

                if (rememberMe.value) {
                    secureStorage?.saveCredentials(username.value, password.value)
                } else {
                    secureStorage?.clearCredentials()
                }
                onResult(user.role)
            } catch (e: Exception) {
                loginError.value = "Login Failed: Invalid username or password"
            } finally {
                isLoading.value = false
            }
        }
    }
}