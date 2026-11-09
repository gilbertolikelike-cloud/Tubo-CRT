package com.example.ocrclicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/** Subtelas com seta de voltar: configurações de cada modo, instruções, padrões e ajuda. */
class PageActivity : Activity() {

    companion object {
        const val SINGLE = 1
        const val MULTI = 2
        const val OCR = 3
        const val INSTR = 4
        const val DEFAULTS = 5
        const val TROUBLE = 6

        fun open(ctx: Context, page: Int) {
            ctx.startActivity(Intent(ctx, PageActivity::class.java).putExtra("page", page))
        }
    }

    private var saver: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        val store = Store(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(12), dp(16), dp(24))
        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)

        when (intent.getIntExtra("page", 0)) {
            SINGLE -> {
                title = "Modo de alvo único"
                val p = SinglePanel(this, false)
                p.load(store)
                col.addView(p)
                saver = { p.save(store) }
            }
            MULTI -> {
                title = "Modo de multi alvos"
                val p = MultiDefaultsPanel(this)
                p.load(store)
                col.addView(p)
                saver = { p.save(store) }
            }
            OCR -> {
                title = "Modo OCR"
                val p = OcrPanel(this, false)
                p.load(store)
                col.addView(p)
                saver = { p.save(store) }
            }
            INSTR -> {
                title = "Instruções"
                instructions(col)
            }
            DEFAULTS -> {
                title = "Configurações padrão"
                defaults(col, store)
            }
            else -> {
                title = "Solução de problemas"
                trouble(col)
            }
        }
    }

    override fun onPause() {
        saver?.invoke()
        super.onPause()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------ Instruções
    private fun instructions(col: LinearLayout) {
        fun line(icon: Int, text: String) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, dp(12), 0, dp(12))
            val ic = IconView(this, icon)
            ic.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(14) }
            r.addView(ic)
            r.addView(Ui.text(this, text, 14f, Ui.TEXT2))
            col.addView(r)
        }
        line(IconView.PLAY, "Executar os cliques (▶ vira ■ enquanto roda; toque para parar)")
        line(IconView.PLUS, "Toque para adicionar um alvo de clique")
        line(IconView.TARGET, "- Arraste o alvo para qualquer lugar que você deseja executar os cliques\n- Toque para configurar este alvo")
        line(IconView.SWIPE, "Adicionar um deslize: arraste o anel laranja para onde o dedo deve terminar")
        line(IconView.MINUS, "Remover o último alvo")
        line(IconView.GEAR, "Configurar este modo (tempo, parar após, Anti-Detecção, configurações salvas)")
        line(IconView.CLOSE, "Fechar a barra")
        line(IconView.MOVE, "Arraste para mover o painel")
        line(IconView.BOX, "Modo OCR: arraste a caixa verde para cima do texto; a bolinha do canto muda o tamanho. " +
                "O texto pequeno na barra mostra o que o OCR leu")
    }

    // ------------------------------------------------------------------ Configurações padrão
    private fun defaults(col: LinearLayout, store: Store) {
        val teal = ColorStateList.valueOf(Ui.PINK)

        // --- tamanho do alvo ---
        col.addView(Ui.text(this, "Tamanho alvo", 14f, Ui.TEXT2))
        val tBox = LinearLayout(this)
        tBox.gravity = Gravity.CENTER
        tBox.minimumHeight = dp(110)
        val tPrev = TargetView(this, 0, false)
        tBox.addView(tPrev)
        col.addView(tBox, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        val tSeek = SeekBar(this)
        tSeek.progress = store.targetPct
        tSeek.progressTintList = teal
        tSeek.thumbTintList = teal
        col.addView(tSeek)
        col.addView(Ui.divider(this))

        // --- tamanho da barra ---
        col.addView(Ui.text(this, "Tamanho da barra controladora", 14f, Ui.TEXT2))
        val bBox = LinearLayout(this)
        bBox.gravity = Gravity.CENTER
        bBox.minimumHeight = dp(90)
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.background = GradientDrawable().apply {
            setColor(0xD9000000.toInt())
            cornerRadius = dp(8).toFloat()
        }
        val types = listOf(
            IconView.PLAY, IconView.PLUS, IconView.SWIPE, IconView.MINUS,
            IconView.GEAR, IconView.CLOSE, IconView.MOVE
        )
        val icons = types.map { IconView(this, it) }
        for (i in icons) bar.addView(i)
        bBox.addView(bar)
        col.addView(bBox, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        val bSeek = SeekBar(this)
        bSeek.progress = store.barPct
        bSeek.progressTintList = teal
        bSeek.thumbTintList = teal
        col.addView(bSeek)
        val labels = LinearLayout(this)
        labels.addView(Ui.text(this, "Pequeno", 12f, Ui.TEXT2), LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val mid = Ui.text(this, "Médio", 12f, Ui.TEXT2)
        mid.gravity = Gravity.CENTER
        labels.addView(mid, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        val big = Ui.text(this, "Grande", 12f, Ui.TEXT2)
        big.gravity = Gravity.END
        labels.addView(big, LinearLayout.LayoutParams(0, Ui.WRAP, 1f))
        col.addView(labels)

        fun refresh() {
            val tp = dp(30 + tSeek.progress * 0.6f)
            tPrev.layoutParams = LinearLayout.LayoutParams(tp, tp)
            val sc = 0.6f + bSeek.progress / 100f * 0.8f
            val b = dp(46 * sc)
            for (i in icons) {
                i.layoutParams = LinearLayout.LayoutParams(b, b)
                i.setPadding(b / 6, b / 6, b / 6, b / 6)
            }
        }
        val l = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = refresh()
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        tSeek.setOnSeekBarChangeListener(l)
        bSeek.setOnSeekBarChangeListener(l)
        refresh()
        saver = {
            store.targetPct = tSeek.progress
            store.barPct = bSeek.progress
        }
    }

    // ------------------------------------------------------------------ Solução de problemas
    private fun trouble(col: LinearLayout) {
        val items = listOf(
            "Os cliques não acontecem" to
                    "Confira em Acessibilidade se “OCR Auto Clicker” está ATIVO. Se o Android desligar o serviço, ative de novo.",
            "O serviço some depois de um tempo" to
                    "Tire o app da otimização de bateria (Configurações > Apps > OCR Auto Clicker > Bateria > Sem restrições).",
            "O OCR não acha o texto" to
                    "Olhe o texto pequeno na barra (✗ li: ...) para ver o que foi lido. Aumente a caixa, deixe só a palavra dentro dela e escolha “Ignorar maiúsculas”.",
            "O OCR vê tudo preto" to
                    "Alguns apps bloqueiam captura de tela (bancos, certos jogos). Nesses o OCR não funciona.",
            "A captura parou" to
                    "O Android encerra a gravação se você tocar em “Parar” na notificação ou na barra de status. Desabilite e habilite o modo OCR de novo.",
            "O celular trava" to
                    "Use intervalos acima de 40 ms. Intervalos muito curtos sobrecarregam o aparelho.",
            "Girei a tela e a barra ficou fora do lugar" to
                    "A barra se recria sozinha ao girar. Se não acontecer, desabilite e habilite o modo de novo."
        )
        for ((q, a) in items) {
            val c = Ui.card(this)
            c.addView(Ui.text(this, q, 15f, Ui.TEXT, true))
            c.addView(Ui.text(this, a, 13f, Ui.TEXT2))
            col.addView(c)
        }
    }
}
