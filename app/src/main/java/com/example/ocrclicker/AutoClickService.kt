package com.example.ocrclicker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Serviço de acessibilidade: dá a permissão de tocar na tela (dispatchGesture) e hospeda a
 * barra flutuante do modo ativo. Não processa nenhum evento.
 */
class AutoClickService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoClickService? = null
            private set

        /** Modo ativo agora (Mode.NONE se nenhum). */
        @Volatile
        var mode: Int = Mode.NONE
            private set
    }

    private var overlay: Overlay? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** Liga um modo: mostra a barra com as configurações atuais. */
    fun enable(m: Int) {
        overlay?.destroy()
        overlay = null
        mode = m
        overlay = Overlay(this, Store(this), m).also { it.show() }
    }

    /** Desliga a barra (e a captura de tela, se era o modo OCR). */
    fun disable() {
        overlay?.destroy()
        overlay = null
        val was = mode
        mode = Mode.NONE
        if (was == Mode.OCR) CaptureService.stop(this)
    }

    /** Girou a tela: ajusta a captura e recria a barra nas novas dimensões. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val m = mode
        if (m == Mode.NONE) return
        main.postDelayed({
            ScreenCapture.resize(this)
            if (mode == m) enable(m)
        }, 400)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        overlay?.destroy()
        overlay = null
        mode = Mode.NONE
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        overlay?.destroy()
        overlay = null
        mode = Mode.NONE
        instance = null
        super.onDestroy()
    }
}
