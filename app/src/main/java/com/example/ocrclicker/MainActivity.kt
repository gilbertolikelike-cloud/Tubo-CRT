package com.example.ocrclicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(10), dp(10), dp(10), dp(24))
        val sv = ScrollView(this)
        sv.addView(content)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    // ------------------------------------------------------------------ tela
    private fun build() {
        content.removeAllViews()
        val svc = AutoClickService.instance
        val active = if (svc == null) Mode.NONE else AutoClickService.mode

        if (svc == null) {
            val c = Ui.card(this)
            c.addView(Ui.text(this, "✖ Serviço de acessibilidade desativado", 15f, 0xFFFF5252.toInt(), true))
            c.addView(Ui.text(this, "É ele que permite tocar na tela. Ative “OCR Auto Clicker” na lista.", 13f, Ui.TEXT2))
            c.addView(Ui.bigButton(this, "ABRIR ACESSIBILIDADE", Ui.PINK, true) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            })
            content.addView(c)
        }

        // ---- modo de alvo único ----
        content.addView(section(
            "MODO DE ALVO ÚNICO", active, Mode.SINGLE,
            listOf(item(IconView.GEAR, "Configurações") { PageActivity.open(this, PageActivity.SINGLE) })
        ))

        // ---- modo de multi alvos ----
        content.addView(section(
            "MODO DE MULTI ALVOS", active, Mode.MULTI,
            listOf(
                item(IconView.GEAR, "Configurações") { PageActivity.open(this, PageActivity.MULTI) },
                item(IconView.INFO, "Instruções") { PageActivity.open(this, PageActivity.INSTR) },
                item(IconView.WRENCH, "Gerenciador de configurações") { openManager() }
            )
        ))

        // ---- modo OCR (novo) ----
        content.addView(section(
            "MODO OCR (LER TEXTO E CLICAR)", active, Mode.OCR,
            listOf(item(IconView.GEAR, "Configurações") { PageActivity.open(this, PageActivity.OCR) })
        ))

        // ---- outros ----
        val others = Ui.card(this)
        others.addView(Ui.text(this, "OUTROS", 13f, Ui.TEXT2))
        others.addView(Ui.row(this, IconView.GEAR, "Configurações padrão", true) {
            PageActivity.open(this, PageActivity.DEFAULTS)
        })
        others.addView(Ui.row(this, IconView.HELP, "Solução de problemas", true) {
            PageActivity.open(this, PageActivity.TROUBLE)
        })
        content.addView(others)

        val ver = Ui.text(this, "Ver 1.0", 12f, Ui.TEXT2)
        ver.gravity = Gravity.CENTER
        content.addView(ver, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
    }

    /** Um bloco da tela inicial: título, linhas e o botão HABILITAR/DESABILITAR. */
    private fun section(
        title: String, active: Int, mode: Int,
        rows: List<Triple<Int, String, () -> Unit>>
    ): LinearLayout {
        val c = Ui.card(this)
        c.addView(Ui.text(this, title, 13f, Ui.TEXT2))
        val locked = active != Mode.NONE            // com um modo ligado, o resto fica cinza
        for ((icon, label, action) in rows) c.addView(Ui.row(this, icon, label, !locked, action))
        if (active == mode) {
            c.addView(Ui.bigButton(this, "DESABILITAR", 0xFF757575.toInt(), true) {
                AutoClickService.instance?.disable()
                build()
            })
        } else {
            c.addView(Ui.bigButton(this, "HABILITAR", Ui.PINK, !locked) { enableMode(mode) })
        }
        return c
    }

    private fun item(icon: Int, label: String, action: () -> Unit) = Triple(icon, label, action)

    private fun openManager() {
        Ui.configList(this, false, store,
            onPick = { idx ->
                store.activeConfig = idx
                enableMode(Mode.MULTI)
            },
            onNew = {
                val list = store.configs()
                list.add(Config("Config ${list.size + 1}"))
                store.saveConfigs(list)
                store.activeConfig = list.size - 1
                enableMode(Mode.MULTI)
            })
    }

    // ------------------------------------------------------------------ ligar um modo
    private fun enableMode(m: Int) {
        val svc = AutoClickService.instance
        if (svc == null) {
            toast("Ative “OCR Auto Clicker” em Acessibilidade primeiro")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (m == Mode.OCR) {
            if (store.word.isEmpty()) {
                toast("Digite o texto a procurar em Configurações do modo OCR")
                return
            }
            if (!ScreenCapture.active) {
                // O Android pergunta se pode "gravar a tela": é assim que o OCR enxerga a tela.
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), 100)
                return
            }
        }
        svc.enable(m)
        moveTaskToBack(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 100) return
        if (resultCode == RESULT_OK && data != null) {
            // O CaptureService liga a captura e, em seguida, mostra a barra do modo OCR.
            startForegroundService(
                Intent(this, CaptureService::class.java).putExtra("code", resultCode).putExtra("data", data)
            )
            moveTaskToBack(true)
        } else {
            toast("Sem a permissão de captura o OCR não consegue ler a tela")
        }
    }

    // ------------------------------------------------------------------ menu ⋮
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Instruções")
        menu.add(0, 2, 1, "Sobre")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> PageActivity.open(this, PageActivity.INSTR)
            2 -> {
                val t: TextView = Ui.text(
                    this,
                    "Auto clicker com modo único, multi alvos (toques e deslizes), configurações " +
                            "salvas e modo OCR, que lê o texto de uma caixa na tela e clica quando o encontra.",
                    14f, Ui.TEXT
                )
                t.setPadding(dp(20), dp(12), dp(20), 0)
                Ui.alert(this, false, "OCR Auto Clicker 1.0", t, "OK", null)
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
