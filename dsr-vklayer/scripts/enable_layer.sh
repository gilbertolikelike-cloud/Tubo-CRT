#!/usr/bin/env sh
# Rode com o celular ligado ao PC por USB (depuracao USB ativada):  sh enable_layer.sh
adb shell settings put global enable_gpu_debug_layers 1
adb shell settings put global gpu_debug_app com.dsr.volyre.vision
adb shell settings put global gpu_debug_layer_app com.dsr.vklayer
adb shell settings put global gpu_debug_layers VK_LAYER_dsr_dynrender
echo "Camada ativada. Force-feche o jogo e abra de novo."
