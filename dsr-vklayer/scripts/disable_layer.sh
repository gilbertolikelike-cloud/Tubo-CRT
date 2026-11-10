#!/usr/bin/env sh
adb shell settings put global enable_gpu_debug_layers 0
adb shell settings delete global gpu_debug_app
adb shell settings delete global gpu_debug_layer_app
adb shell settings delete global gpu_debug_layers
echo "Camada desativada."
