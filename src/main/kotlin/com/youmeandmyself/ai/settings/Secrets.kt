// File: src/main/kotlin/com/example/ai/settings/Secrets.kt
package com.youmeandmyself.ai.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Reads/writes the API key in the IDE's secure storage (PasswordSafe).
 */
object Secrets {
    private val attrs = CredentialAttributes("com.example.ai.apiKey")

    fun saveApiKey(value: String?) {
        PasswordSafe.instance.setPassword(attrs, value)
    }

    fun loadApiKey(): String? = PasswordSafe.instance.getPassword(attrs)
}
