# DSR – camada Vulkan "dynamic rendering" (teste)

Objetivo: fazer o app `com.dsr.volyre.vision` desenhar na **Adreno 512** (Galaxy A9 2018),
cujo driver Vulkan 1.1.87 **não tem `VK_KHR_dynamic_rendering`**. Hoje o app pula todos os
desenhos (`draw skipped: no dynamic rendering`) e só limpa a tela → tela preta.

## Como funciona (para aprender)
Uma *Vulkan layer* é uma biblioteca `.so` que o loader do Android coloca **entre o app e o driver**.
Ela vê (e pode alterar) cada chamada Vulkan. Esta camada faz 3 coisas:

1. **Mente sobre as capacidades**: em `vkGetPhysicalDeviceFeatures2` e
   `vkEnumerateDeviceExtensionProperties` diz que `dynamicRendering` existe.
   Ao criar o device, remove a extensão/feature antes de passar ao driver (ele não a conhece).
2. **Traduz o desenho**: `vkCmdBeginRendering` vira `vkCmdBeginRenderPass`.
   A camada cria sozinha o `VkRenderPass` e o `VkFramebuffer` (com cache) a partir das
   views, load/store ops, clears e layouts que o jogo informou.
3. **Ajusta os pipelines**: pipelines criados "sem render pass" (só com
   `VkPipelineRenderingCreateInfo`) recebem um `VkRenderPass` compatível.

Ela só age se o driver **não** tiver dynamic rendering; em GPUs novas fica inativa.

## Como compilar (GitHub)
1. Crie um repositório e envie estes arquivos (mantenha a pasta `.github`).
2. Aba **Actions → build-layer → Run workflow** (ou faça um push).
3. Baixe o artefato **dsr-vklayer** (dá para baixar pelo navegador do celular).
   Dentro: `dsr-vklayer.apk` (a camada empacotada) e `enable_layer.sh` / `disable_layer.sh`.
4. Se a compilação falhar, abra o log do passo "Compilar a camada" e me mande o erro.

## Como ativar
1. Instale `dsr-vklayer.apk` (é só um contêiner da camada, não tem ícone).
   Se reinstalar uma versão nova, desinstale a antiga antes (a assinatura muda a cada build).
2. Ative as opções de desenvolvedor e a depuração USB.
3. Com o celular no PC (`adb` instalado), rode `sh enable_layer.sh`.
   No Android 10 não existe depuração sem fio; só pelo Termux não dá para gravar essas
   configurações, a menos que o aparelho tenha root ou o Termux receba a permissão
   `WRITE_SECURE_SETTINGS` por adb uma vez:
   `adb shell pm grant com.termux android.permission.WRITE_SECURE_SETTINGS`
   (depois os 4 comandos `settings put global ...` do script funcionam dentro do Termux, sem o `adb shell`).
4. Force-feche o jogo e abra de novo.
5. Para desligar: `sh disable_layer.sh`.

Isso só funciona se o app for **debuggable** (o manifest do `dsr-native-debug` tem o atributo,
mas não confirmei o valor).

## Como saber se funcionou
- Arquivo `Download/DSRNative/logs/layer_log.txt` (a camada escreve nele) com linhas como
  `dispositivo criado: dynamic rendering EMULADO via render pass`.
  Se esse arquivo não aparecer, a camada nem foi carregada.
- No log do próprio app: `features: dynamic rendering 1` e `draws: N drawn` com N > 0.

## Limitações conhecidas
- **Não foi compilado nem testado** por quem escreveu: sem NDK nem o aparelho.
  Pode haver erros de compilação e de execução; vamos iterar com os logs.
- Sem suporte a: command buffers secundários dentro de rendering, MSAA com resolve,
  multiview, suspend/resume.
- O log mostrou também `BC textures 0` (a GPU não tem texturas BC). Se o app não converter,
  pode surgir outro bloqueio depois deste.
- Mesmo funcionando, a Adreno 512 é fraca para o jogo.
