package com.example.stationops.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

@Suppress("DEPRECATION")
class SecureStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences = EncryptedSharedPreferences.create(
        context,
        "secure_app_creds",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveCredentials(username: String, pin: String) {
        sharedPreferences.edit().apply {
            putString("USER_ID", username)
            putString("USER_PIN", pin)
            putBoolean("REMEMBER_ME", true)
            apply()
        }
    }

    fun getCredentials(): Pair<String, String>? {
        val remember = sharedPreferences.getBoolean("REMEMBER_ME", false)
        if (!remember) return null

        val user = sharedPreferences.getString("USER_ID", "") ?: ""
        val pin = sharedPreferences.getString("USER_PIN", "") ?: ""

        if (user.isNotEmpty() && pin.isNotEmpty()) {
            return Pair(user, pin)
        }
        return null
    }

    fun clearCredentials() {
        sharedPreferences.edit().apply {
            clear()
            apply()
        }
    }
}