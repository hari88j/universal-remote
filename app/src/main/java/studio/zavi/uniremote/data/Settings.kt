package studio.zavi.uniremote.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import studio.zavi.uniremote.core.TvApp
import studio.zavi.uniremote.core.str
import studio.zavi.uniremote.ui.AccentTheme
import studio.zavi.uniremote.ui.ThemeMode

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentTheme = AccentTheme.BLUE,
    val haptics: Boolean = true,
    val volumeKeys: Boolean = true,
    val keepScreenOn: Boolean = false,
)

/** User preferences, persisted in SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("uniremote_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_settings.value)
        _settings.value = next
        prefs.edit()
            .putString("theme", next.theme.name)
            .putString("accent", next.accent.name)
            .putBoolean("haptics", next.haptics)
            .putBoolean("volumeKeys", next.volumeKeys)
            .putBoolean("keepScreenOn", next.keepScreenOn)
            .apply()
    }

    private fun load() = AppSettings(
        theme = ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme", null) } ?: ThemeMode.SYSTEM,
        accent = AccentTheme.entries.firstOrNull { it.name == prefs.getString("accent", null) } ?: AccentTheme.BLUE,
        haptics = prefs.getBoolean("haptics", true),
        volumeKeys = prefs.getBoolean("volumeKeys", true),
        keepScreenOn = prefs.getBoolean("keepScreenOn", false),
    )
}

/** The last few apps launched on each TV, for the quick-launch row. */
class RecentApps(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("uniremote_recent_apps", Context.MODE_PRIVATE)

    fun get(deviceId: String): List<TvApp> {
        val raw = prefs.getString(deviceId, null) ?: return emptyList()
        val array = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            TvApp(id, o.str("name") ?: id)
        }
    }

    fun add(deviceId: String, app: TvApp): List<TvApp> {
        val next = (listOf(app) + get(deviceId).filterNot { it.id == app.id }).take(MAX)
        val json = buildJsonArray {
            for (a in next) add(buildJsonObject { put("id", a.id); put("name", a.name) })
        }.toString()
        prefs.edit().putString(deviceId, json).apply()
        return next
    }

    companion object {
        private const val MAX = 8
    }
}
