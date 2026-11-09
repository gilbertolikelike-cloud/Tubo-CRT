package com.example.ocrclicker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Mode {
    const val NONE = 0
    const val SINGLE = 1
    const val MULTI = 2
    const val OCR = 3
}

/** Um alvo do modo multi alvos: toque (type 0) ou deslize (type 1). Coordenadas = centro do alvo. */
class Target(
    var type: Int = 0,
    var x: Int = 0, var y: Int = 0,
    var x2: Int = 0, var y2: Int = 0,
    var delayMs: Long = 300L,
    var swipeMs: Long = 500L
) {
    fun copy() = Target(type, x, y, x2, y2, delayMs, swipeMs)

    fun toJson(): JSONObject = JSONObject()
        .put("t", type).put("x", x).put("y", y).put("x2", x2).put("y2", y2)
        .put("d", delayMs).put("s", swipeMs)

    companion object {
        fun from(o: JSONObject) = Target(
            o.optInt("t"), o.optInt("x"), o.optInt("y"), o.optInt("x2"), o.optInt("y2"),
            o.optLong("d", 300L), o.optLong("s", 500L)
        )
    }
}

/** Configuração salva do modo multi alvos ("Config 1", "Config 2"...). */
class Config(var name: String) {
    var stopMode = 0          // 0 indefinido, 1 tempo, 2 ciclos
    var stopSeconds = 300
    var stopCount = 10
    var anti = false
    val targets = mutableListOf<Target>()

    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (t in targets) arr.put(t.toJson())
        return JSONObject().put("name", name).put("sm", stopMode).put("ss", stopSeconds)
            .put("sc", stopCount).put("anti", anti).put("targets", arr)
    }

    companion object {
        fun from(o: JSONObject): Config {
            val c = Config(o.optString("name", "Config"))
            c.stopMode = o.optInt("sm")
            c.stopSeconds = o.optInt("ss", 300)
            c.stopCount = o.optInt("sc", 10)
            c.anti = o.optBoolean("anti")
            val arr = o.optJSONArray("targets")
            if (arr != null) for (i in 0 until arr.length()) c.targets.add(Target.from(arr.getJSONObject(i)))
            return c
        }
    }
}

/** Tudo que fica salvo no aparelho. */
class Store(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("cfg2", Context.MODE_PRIVATE)

    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun l(k: String, d: Long) = sp.getLong(k, d)
    private fun b(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: Any) {
        val e = sp.edit()
        when (v) {
            is Int -> e.putInt(k, v)
            is Long -> e.putLong(k, v)
            is Boolean -> e.putBoolean(k, v)
            is String -> e.putString(k, v)
        }
        e.apply()
    }

    // ---------------- modo alvo único ----------------
    var sIntervalMs: Long
        get() = l("sInt", 1000L)
        set(v) = put("sInt", v)
    var sX: Int
        get() = i("sX", -1)
        set(v) = put("sX", v)
    var sY: Int
        get() = i("sY", -1)
        set(v) = put("sY", v)
    var sStopMode: Int
        get() = i("sSm", 0)
        set(v) = put("sSm", v)
    var sStopSeconds: Int
        get() = i("sSs", 300)
        set(v) = put("sSs", v)
    var sStopCount: Int
        get() = i("sSc", 1)
        set(v) = put("sSc", v)
    var sAnti: Boolean
        get() = b("sAnti", false)
        set(v) = put("sAnti", v)

    // ---------------- modo multi alvos ----------------
    var mDelayMs: Long
        get() = l("mDelay", 300L)
        set(v) = put("mDelay", v)
    var mSwipeMs: Long
        get() = l("mSwipe", 500L)
        set(v) = put("mSwipe", v)
    var activeConfig: Int
        get() = i("active", 0)
        set(v) = put("active", v)

    fun configs(): MutableList<Config> {
        val out = mutableListOf<Config>()
        try {
            val arr = JSONArray(s("configs", "[]"))
            for (n in 0 until arr.length()) out.add(Config.from(arr.getJSONObject(n)))
        } catch (e: Exception) {
        }
        if (out.isEmpty()) out.add(Config("Config 1"))
        return out
    }

    fun saveConfigs(list: List<Config>) {
        val arr = JSONArray()
        for (c in list) arr.put(c.toJson())
        put("configs", arr.toString())
    }

    // ---------------- modo OCR ----------------
    var word: String
        get() = s("word", "YES")
        set(v) = put("word", v)
    var ignoreCase: Boolean
        get() = b("ignoreCase", true)
        set(v) = put("ignoreCase", v)
    var ocrIntervalMs: Long
        get() = l("ocrInt", 2000L)
        set(v) = put("ocrInt", v)
    /** 0 = no texto achado, 1 = centro da caixa, 2 = alvo (ponto fixo). */
    var where: Int
        get() = i("where", 0)
        set(v) = put("where", v)
    var oStopMode: Int
        get() = i("oSm", 0)
        set(v) = put("oSm", v)
    var oStopSeconds: Int
        get() = i("oSs", 300)
        set(v) = put("oSs", v)
    var oStopCount: Int
        get() = i("oSc", 1)
        set(v) = put("oSc", v)
    var oAnti: Boolean
        get() = b("oAnti", false)
        set(v) = put("oAnti", v)
    var oTx: Int
        get() = i("oTx", -1)
        set(v) = put("oTx", v)
    var oTy: Int
        get() = i("oTy", -1)
        set(v) = put("oTy", v)
    var boxX: Int
        get() = i("boxX", -1)
        set(v) = put("boxX", v)
    var boxY: Int
        get() = i("boxY", -1)
        set(v) = put("boxY", v)
    var boxW: Int
        get() = i("boxW", -1)
        set(v) = put("boxW", v)
    var boxH: Int
        get() = i("boxH", -1)
        set(v) = put("boxH", v)

    /** Usar o OCR junto com o modo único / multi alvos. */
    var ocrWith: Boolean
        get() = b("ocrWith", false)
        set(v) = put("ocrWith", v)

    // ---------------- aparência ----------------
    var barCollapsed: Boolean
        get() = b("barCollapsed", false)
        set(v) = put("barCollapsed", v)
    /** 0..100 -> tamanho do alvo. */
    var targetPct: Int
        get() = i("targetPct", 25)
        set(v) = put("targetPct", v)
    /** 0..100 -> tamanho da barra (Pequeno ... Grande). */
    var barPct: Int
        get() = i("barPct", 50)
        set(v) = put("barPct", v)
    var barX: Int
        get() = i("barX", -1)
        set(v) = put("barX", v)
    var barY: Int
        get() = i("barY", -1)
        set(v) = put("barY", v)
}
