package com.example.drowsinessdetectorapp.data
//manda a whatsapp o sms
import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("nexa_prefs")

object Keys{
    val PHONE = stringPreferencesKey("alert_phone")
    val SENSITIVITY = floatPreferencesKey("sensitivity") //0.0-1.0
}

class PreferencesManager(private val context: Context) {
    val phone: Flow<String?> = context.dataStore.data.map { it[Keys.PHONE] }
    val sensitivity: Flow<Float> = context.dataStore.data.map { it[Keys.SENSITIVITY] ?: 0.5f }

    suspend fun savePhone(number: String){
        context.dataStore.edit { prefs -> prefs[Keys.PHONE] = number }
    }

    suspend fun saveSensitivity(value: Float){
        context.dataStore.edit { prefs -> prefs[Keys.SENSITIVITY] = value }
    }
}

