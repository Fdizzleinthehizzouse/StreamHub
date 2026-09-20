package com.felix.streamhub

import android.content.SharedPreferences

/** SharedPreferences held in a map, so Store runs off-device unchanged. */
internal class MemoryPrefs : SharedPreferences {
    private val map = HashMap<String, Any?>()

    override fun getAll(): Map<String, *> = HashMap(map)
    override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) = map[key] as Set<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = map[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as Boolean? ?: defValue
    override fun contains(key: String) = map.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) map.clear()
            removed.forEach { map.remove(it) }
            map.putAll(pending)
        }
    }
}
