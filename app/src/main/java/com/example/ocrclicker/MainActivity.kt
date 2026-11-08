package com.example.ocrclicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var cfg: Cfg
    private lateinit var statusTv: TextView
    private lateinit var modeG: RadioGroup
    private lateinit var secSimple: LinearLayout
    private lateinit var secOcr: LinearLayout
    private lateinit var simpleInt: IntervalField
    private lateinit var ocrInt: IntervalField
    private lateinit var wordEt: EditText
    private lateinit var caseCb: CheckBox
    private lateinit var whereG: RadioGroup
    private lateinit var stopCb: CheckBox

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Cfg(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        // ---- status do serviço ----
        statusTv = TextView(this).apply { textSize = 16f }
        root.addView(statusTv)
        root.addView(button("Abrir configurações de acessibilidade") { openAccessibility() })

        // ---- modo ----
        root.addView(title("MODO"))
        modeG = radioGroup("Clique simples (a cada X tempo)", "OCR: clicar quando achar um texto na caixa")
        modeG.setOnCheckedChangeListener { _, _ -> updateVisibility() }
        root.addView(modeG)

        // ---- clique simples ----
        secSimple = section()
        secSimple.addView(label("Intervalo entre cliques (mínimo 20 ms)"))
        simpleInt = IntervalField(this)
        secSimple.addView(simpleInt)
        root.addView(secSimple)

        // ---- OCR ----
        secOcr = section()
        secOcr.addView(label("Texto que o app procura dentro da caixa"))
        wordEt = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "Ex.: YES"
            setSingleLine()
        }
        secOcr.addView(wordEt)
        caseCb = CheckBox(this).apply { text = "Ignorar maiúsculas/minúsculas" }
        secOcr.addView(caseCb)

        secOcr.addView(label("Tempo para ler de novo se NÃO achar (mínimo 0,5 s)"))
        ocrInt = IntervalField(this)
        secOcr.addView(ocrInt)

        secOcr.addView(label("Onde clicar quando achar"))
        whereG = radioGroup("No texto encontrado", "No centro da caixa", "No alvo (ponto fixo)")
        secOcr.addView(whereG)

        stopCb = CheckBox(this).apply { text = "Parar depois do primeiro clique" }
        secOcr.addView(stopCb)
        root.addView(secOcr)

        // ---- botões ----
        root.addView(button("SALVAR E HABILITAR BARRA FLUTUANTE") { enable() }.apply {
            setBackgroundColor(0xFFE91E63.toInt()); setTextColor(Color.WHITE)
        })
        root.addView(button("DESABILITAR BARRA") { AutoClickService.instance?.hideOverlay(); CaptureService.stop(this) })

        root.addView(TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(16), 0, 0)
            text = "Como usar:\n" +
                    "1) Ative o serviço “OCR Auto Clicker” em Acessibilidade.\n" +
                    "2) Escolha o modo e as opções acima e toque em HABILITAR (no modo OCR o Android pede permissão para gravar a tela: aceite).\n" +
                    "3) Abra o app/jogo. Arraste a caixa verde para cima do lugar onde o texto aparece " +
                    "(arraste a bolinha do canto para mudar o tamanho).\n" +
                    "4) Toque em ▶. O texto abaixo da barra mostra o que o OCR leu."
        })

        load()
        updateVisibility()
    }

    override fun onResume() {
        super.onResume()
        val on = AutoClickService.instance != null
        statusTv.text = if (on) "✔ Serviço de acessibilidade ATIVO" else "✖ Serviço de acessibilidade DESATIVADO"
        statusTv.setTextColor(if (on) 0xFF66BB6A.toInt() else 0xFFFF5252.toInt())
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    // ------------------------------------------------------------------ ações
    private fun enable() {
        save()
        val s = AutoClickService.instance
        if (s == null) {
            toast("Ative “OCR Auto Clicker” em Acessibilidade primeiro")
            openAccessibility()
            return
        }
        if (cfg.modeOcr && cfg.word.isEmpty()) {
            toast("Digite o texto que deve ser procurado")
            return
        }
        if (cfg.modeOcr && !ScreenCapture.active) {
            // Android pede permissão para "gravar a tela" (é assim que o OCR enxerga a tela)
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), 100)
            return
        }
        s.showOverlay()
        moveTaskToBack(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100) return
        if (resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, CaptureService::class.java).putExtra("code", resultCode).putExtra("data", data)
            )
            moveTaskToBack(true)
        } else {
            toast("Sem a permissão de captura o OCR não consegue ler a tela")
        }
    }

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    // ------------------------------------------------------------------ salvar / carregar
    private fun load() {
        modeG.select(if (cfg.modeOcr) 1 else 0)
        simpleInt.set(cfg.clickIntervalMs)
        ocrInt.set(cfg.ocrIntervalMs)
        wordEt.setText(cfg.word)
        caseCb.isChecked = cfg.ignoreCase
        whereG.select(cfg.clickWhere.coerceIn(0, 2))
        stopCb.isChecked = cfg.stopAfterClick
    }

    private fun save() {
        cfg.modeOcr = modeG.index() == 1
        cfg.clickIntervalMs = simpleInt.ms().coerceAtLeast(20L)
        cfg.ocrIntervalMs = ocrInt.ms().coerceAtLeast(500L)
        cfg.word = wordEt.text.toString().trim()
        cfg.ignoreCase = caseCb.isChecked
        cfg.clickWhere = whereG.index().coerceAtLeast(0)
        cfg.stopAfterClick = stopCb.isChecked
    }

    private fun updateVisibility() {
        val ocr = modeG.index() == 1
        secOcr.visibility = if (ocr) View.VISIBLE else View.GONE
        secSimple.visibility = if (ocr) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ helpers de UI
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 13f
        setTextColor(0xFF4DB6AC.toInt())
        setPadding(0, dp(20), 0, dp(4))
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setPadding(0, dp(10), 0, 0)
    }

    private fun section() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    private fun radioGroup(vararg labels: String): RadioGroup {
        val g = RadioGroup(this)
        for (l in labels) {
            g.addView(RadioButton(this).apply { text = l; id = View.generateViewId() })
        }
        return g
    }

    private fun RadioGroup.index(): Int = indexOfChild(findViewById(checkedRadioButtonId))
    private fun RadioGroup.select(i: Int) = check(getChildAt(i).id)
}

/** Campo "número + unidade (ms / s / min)". */
class IntervalField(ctx: Context) : LinearLayout(ctx) {
    private val mult = longArrayOf(1L, 1000L, 60000L)
    private val et = EditText(ctx).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSelectAllOnFocus(true)
    }
    private val unit = Spinner(ctx).apply {
        adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, arrayOf("ms", "s", "min"))
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(et, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(unit)
    }

    fun set(ms: Long) {
        val idx = when {
            ms > 0 && ms % 60000L == 0L -> 2
            ms % 1000L == 0L -> 1
            else -> 0
        }
        unit.setSelection(idx)
        et.setText((ms / mult[idx]).toString())
    }

    fun ms(): Long {
        val v = et.text.toString().replace(',', '.').toDoubleOrNull() ?: 0.0
        return (v * mult[unit.selectedItemPosition]).toLong()
    }
}
