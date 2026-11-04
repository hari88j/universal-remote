package studio.zavi.uniremote.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import studio.zavi.uniremote.core.Brand
import studio.zavi.uniremote.core.KeyValueStore
import studio.zavi.uniremote.core.TvDevice
import studio.zavi.uniremote.core.str

/** App-private SharedPreferences as a [KeyValueStore]. */
class PrefsStore(context: Context, name: String = "uniremote_keys") : KeyValueStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String?) {
        val edit = prefs.edit()
        if (value == null) edit.remove(key) else edit.putString(key, value)
        edit.apply()
    }
}

/** Saved TVs, most recently used first. */
class DeviceRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("uniremote_devices", Context.MODE_PRIVATE)
    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<List<TvDevice>> = _devices.asStateFlow()

    @Synchronized
    fun upsert(device: TvDevice) = update { list ->
        val existing = list.firstOrNull { it.id == device.id }
        // Keep a name the person chose; take everything else from the new info.
        val merged = if (existing != null) device.copy(name = existing.name) else device
        list.filterNot { it.id == device.id } + merged
    }

    @Synchronized
    fun rename(id: String, name: String) = update { list ->
        list.map { if (it.id == id) it.copy(name = name) else it }
    }

    @Synchronized
    fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    private fun update(change: (List<TvDevice>) -> List<TvDevice>) {
        val next = change(_devices.value).sortedByDescending { it.lastUsed }
        _devices.value = next
        prefs.edit().putString(KEY, encode(next)).apply()
    }

    private fun load(): List<TvDevice> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val array = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val brand = Brand.fromName(o.str("brand")) ?: return@mapNotNull null
            TvDevice(
                name = o.str("name") ?: brand.short,
                ip = o.str("ip") ?: return@mapNotNull null,
                brand = brand,
                mac = o.str("mac"),
                token = o.str("token"),
                model = o.str("model"),
                lastUsed = (o["lastUsed"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }.sortedByDescending { it.lastUsed }
    }

    private fun encode(list: List<TvDevice>): String = buildJsonArray {
        for (d in list) {
            add(buildJsonObject {
                put("name", d.name)
                put("ip", d.ip)
                put("brand", d.brand.name)
                d.mac?.let { put("mac", it) }
                d.token?.let { put("token", it) }
                d.model?.let { put("model", it) }
                put("lastUsed", d.lastUsed)
            })
        }
    }.toString()

    companion object {
        private const val KEY = "devices"
    }
}
