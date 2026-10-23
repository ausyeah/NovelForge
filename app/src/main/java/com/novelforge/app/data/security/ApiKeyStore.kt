package com.novelforge.app.data.security

interface ApiKeyStore {
    suspend fun read(): String?
    suspend fun write(apiKey: String)
    suspend fun clear()
}
