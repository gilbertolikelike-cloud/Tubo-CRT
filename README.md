# OCR Auto Clicker

Auto clicker com modo **OCR**: você coloca uma caixa na tela, digita um texto (ex.: `YES`) e o app
lê só aquela caixa; se o texto estiver lá, ele clica. Se não estiver, espera o tempo que você
escolheu (ex.: 2 s) e lê de novo.

## Como gerar o APK

**Opção A — Android Studio (PC):** abra a pasta do projeto, espere o Gradle sincronizar,
`Build > Build APK(s)`. O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.

**Opção B — sem PC (GitHub Actions):** crie um repositório no GitHub, envie todos os arquivos
(incluindo a pasta `.github`), vá na aba **Actions > Build APK > Run workflow** e baixe o artefato
`OcrAutoClicker-debug-apk` quando terminar.

## Como usar

1. Instale o APK. No Android 13+: *Configurações > Apps > OCR Auto Clicker > ⋮ >
   **Permitir configurações restritas***.
2. *Acessibilidade > Apps instalados > OCR Auto Clicker > Ativar*.
3. Abra o app, escolha o modo, o texto e o tempo de leitura, e toque em **HABILITAR** (no modo OCR, aceite o pedido de gravar a tela).
4. Arraste a caixa verde para cima do lugar onde o texto aparece (bolinha do canto = tamanho).
5. Toque em ▶. O texto pequeno na barra mostra o que o OCR leu (`✗ li: ...`) ou `✓ achou!`.

## Arquivos

- `AutoClickService.kt` – serviço de acessibilidade (toque na tela)
- `ScreenCapture.kt` – captura da tela (MediaProjection) + serviço de notificação
- `ClickEngine.kt` – loop de cliques, captura da tela, OCR (ML Kit), decisão de clicar
- `Overlay.kt` – barra flutuante, alvo e caixa OCR
- `MainActivity.kt` – tela de configurações
- `Cfg.kt` – configurações salvas

## Limitações

- Android 10+. A tela é lida via MediaProjection: o Android pede permissão de gravar a tela cada vez que você liga o modo OCR e mostra uma notificação enquanto ativo.
- Apps que bloqueiam print (FLAG_SECURE: bancos, alguns DRM) aparecem pretos → OCR não lê.
- Tempo mínimo de leitura: 0,5 s.
- Se girar a tela, desligue e ligue a barra para reposicionar.
