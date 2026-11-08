package com.example.ocrclicker

import android.content.Context

/** Configurações salvas no aparelho (SharedPreferences). */
class Cfg(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    private fun gi(k: String, d: Int) = sp.getInt(k, d)
    private fun si(k: String, v: Int) = sp.edit().putInt(k, v).apply()

    // ---- modo ----
    var modeOcr: Boolean
        get() = sp.getBoolean("modeOcr", true)
        set(v) = sp.edit().putBoolean("modeOcr", v).apply()

    // ---- clique simples ----
    var clickIntervalMs: Long
        get() = sp.getLong("clickIntervalMs", 1000L)
        set(v) = sp.edit().putLong("clickIntervalMs", v).apply()

    // ---- OCR ----
    var word: String
        get() = sp.getString("word", "YES") ?: "YES"
        set(v) = sp.edit().putString("word", v).apply()

    var ignoreCase: Boolean
        get() = sp.getBoolean("ignoreCase", true)
        set(v) = sp.edit().putBoolean("ignoreCase", v).apply()

    /** Tempo entre uma leitura e a próxima. */
    var ocrIntervalMs: Long
        get() = sp.getLong("ocrIntervalMs", 2000L)
        set(v) = sp.edit().putLong("ocrIntervalMs", v).apply()

    /** 0 = no texto achado, 1 = centro da caixa, 2 = ponto fixo (alvo). */
    var clickWhere: Int
        get() = gi("clickWhere", 0)
        set(v) = si("clickWhere", v)

    var stopAfterClick: Boolean
        get() = sp.getBoolean("stopAfterClick", false)
        set(v) = sp.edit().putBoolean("stopAfterClick", v).apply()

    // ---- posições na tela (-1 = ainda não definido) ----
    var barX: Int
        get() = gi("barX", -1)
        set(v) = si("barX", v)
    var barY: Int
        get() = gi("barY", -1)
        set(v) = si("barY", v)
    var targetX: Int
        get() = gi("targetX", -1)
        set(v) = si("targetX", v)
    var targetY: Int
        get() = gi("targetY", -1)
        set(v) = si("targetY", v)
    var boxX: Int
        get() = gi("boxX", -1)
        set(v) = si("boxX", v)
    var boxY: Int
        get() = gi("boxY", -1)
        set(v) = si("boxY", v)
    var boxW: Int
        get() = gi("boxW", -1)
        set(v) = si("boxW", v)
    var boxH: Int
        get() = gi("boxH", -1)
        set(v) = si("boxH", v)
}
