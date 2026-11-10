# OCR Auto Clicker

Auto clicker no estilo do app original, com três modos:

- **Alvo único** – um alvo, intervalo (ms / s / min), parar após (indefinido / tempo / ciclos), Anti-Detecção.
- **Multi alvos** – vários alvos numerados (toques e deslizes, 12 por página), atraso por alvo,
  configurações salvas ("Config 1", "Config 2"...), Gerenciador de configurações.
- **OCR (novo)** – uma caixa verde lê o texto da tela (ex.: `YES`); se achar, clica; se não, espera o
  tempo escolhido no app e lê de novo. Pode ligar **sozinho** ("HABILITAR SÓ O OCR") ou **junto** com o
  alvo único / multi alvos (marque "Usar o OCR junto com os modos acima"): o ▶ roda os dois ao mesmo tempo. Quando o OCR vê o texto, os outros cliques PARAM até ele clicar nele; depois todos voltam, na ordem.

Telas do app: principal (3 blocos + Outros), Configurações de cada modo, Instruções,
Configurações padrão (tamanho do alvo e da barra) e Solução de problemas.

## Gerar o APK
GitHub Actions (aba **Actions > Build APK**) ou Android Studio (*Build APK*).

## Usar
1. Ative o serviço **OCR Auto Clicker** em Acessibilidade.
2. Escolha o modo e toque em **HABILITAR**. No modo OCR o Android pede permissão para gravar a tela: aceite.
3. Posicione alvos / caixa, toque em ▶. Toque no ✥ (sem arrastar) para encolher a barra (▶ ✕ ✥) e de novo para expandir. O texto pequeno na barra mostra o status (no OCR: o que foi lido).

## Arquivos
- `MainActivity.kt`, `PageActivity.kt` – telas do app
- `Ui.kt` – ícones desenhados, painéis de configuração, diálogos
- `Overlay.kt` – barra flutuante, alvos, deslizes, paginador, caixa OCR
- `ClickEngine.kt` – cliques, multi alvos, OCR (ML Kit), parar após, Anti-Detecção
- `ScreenCapture.kt` – captura de tela (MediaProjection) + serviço de notificação
- `AutoClickService.kt` – serviço de acessibilidade
- `Model.kt` – configurações e dados salvos

## Limitações
- Android 10+.
- Apps que bloqueiam captura de tela (FLAG_SECURE) aparecem pretos no OCR.
- Tempo mínimo de leitura do OCR: 0,5 s.

## Otimização
- A captura de tela fica desligada e só liga no instante de cada leitura (custo de GPU/bateria zero entre leituras).
- Só os pixels da caixa são copiados e lidos.
- Todos os toques passam por um único funil: nunca dois gestos ao mesmo tempo, a ordem 1, 2, 3... é sempre respeitada.
- Adicionar/remover bolinha mexe só naquela bolinha; arrastar usa no máximo ~70 atualizações por segundo.
- A barra, as bolinhas e a caixa nunca saem da tela.
