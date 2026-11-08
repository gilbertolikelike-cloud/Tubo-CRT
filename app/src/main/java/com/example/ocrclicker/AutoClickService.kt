package com.example.ocrclicker

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Serviço de acessibilidade: dá permissão para (1) tocar na tela com dispatchGesture e
 * (2) tirar print da tela com takeScreenshot. Não processa nenhum evento.
 */
class AutoClickService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AutoClickService? = null
            private set
    }

    private var overlay: Overlay? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** Mostra (ou recria) a barra flutuante com as configurações atuais. */
    fun showOverlay() {
        hideOverlay()
        overlay = Overlay(this, Cfg(this)).also { it.show() }
    }

    fun hideOverlay() {
        overlay?.destroy()
        overlay = null
    }

    override fun onUnbind(intent: Intent?): Boolean {
        hideOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideOverlay()
        instance = null
        super.onDestroy()
    }
}
