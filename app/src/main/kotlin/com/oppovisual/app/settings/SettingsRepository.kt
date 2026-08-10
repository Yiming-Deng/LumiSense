package com.oppovisual.app.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "oppovisual_settings")

data class AppSettings(
    val onboardingAccepted: Boolean = false,
    val showLandmarks: Boolean = false,
    val soundEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val diagnosticsOverlayEnabled: Boolean = false,
    val selectedHeadgear: String = "off",
    val bestChallengeScore: Int = 0,
)

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.dataStore.data.map { values ->
        AppSettings(
            onboardingAccepted = values[ONBOARDING] ?: false,
            showLandmarks = values[LANDMARKS] ?: false,
            soundEnabled = values[SOUND] ?: true,
            hapticsEnabled = values[HAPTICS] ?: true,
            diagnosticsOverlayEnabled = values[DIAGNOSTICS] ?: false,
            selectedHeadgear = values[HEADGEAR] ?: "off",
            bestChallengeScore = values[BEST_CHALLENGE_SCORE] ?: 0,
        )
    }

    suspend fun acceptOnboarding() = update(ONBOARDING, true)
    suspend fun setShowLandmarks(value: Boolean) = update(LANDMARKS, value)
    suspend fun setSoundEnabled(value: Boolean) = update(SOUND, value)
    suspend fun setHapticsEnabled(value: Boolean) = update(HAPTICS, value)
    suspend fun setDiagnosticsOverlayEnabled(value: Boolean) = update(DIAGNOSTICS, value)
    suspend fun setSelectedHeadgear(value: String) {
        context.dataStore.edit { it[HEADGEAR] = value }
    }

    suspend fun setBestChallengeScore(value: Int) {
        context.dataStore.edit { it[BEST_CHALLENGE_SCORE] = value.coerceAtLeast(0) }
    }

    private suspend fun update(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }

    private companion object {
        val ONBOARDING = booleanPreferencesKey("onboarding_accepted")
        val LANDMARKS = booleanPreferencesKey("show_landmarks")
        val SOUND = booleanPreferencesKey("sound_enabled")
        val HAPTICS = booleanPreferencesKey("haptics_enabled")
        val DIAGNOSTICS = booleanPreferencesKey("diagnostics_overlay_enabled")
        val HEADGEAR = stringPreferencesKey("selected_headgear")
        val BEST_CHALLENGE_SCORE = intPreferencesKey("best_challenge_score")
    }
}
