# Tubo CRT

Filtro de **TV de tubo (CRT)** para qualquer jogo ou app no Android, com um **Bob dançante** que vira painel flutuante.
Feito com base nos filtros CRT LIGHT e STRONG do port do jogo *Black* para Android.

## O que tem

- **Filtro CRT por cima de tudo:** scanlines com perfil gaussiano, 4 tipos de máscara de fósforo, cantos arredondados, sombra nas bordas, curva do vidro, movimento das linhas e tremida de brilho.
- **Escurecer a tela** de 0 a 90%.
- **Painel do Bob:** o Bob dança em cima da tela (arraste pra onde quiser, ele sempre olha pro lado do centro). Toque nele e abre:
  - o **céu** no topo: um sol que anda conforme a hora do dia e uma lua de noite;
  - dois **botões de girar** (volume e brilho: girar pra direita diminui, pra esquerda aumenta) e a **hora em 12h** entre eles;
  - **FOTO** (captura de tela), **APAGAR** (bloqueia a tela), **RAM** (limpa a memória) e **MAX** (modo desempenho);
  - **player de música** da pasta que você escolher: toda música colocada na pasta é reconhecida sozinha.
- **Meus jogos:** o **+** do pilar escolhe quais apps entram na lista e o **🎮** abre ela; **JOGAR** liga o CRT, limpa a RAM (se o modo desempenho estiver ligado) e abre o jogo.
- **Fluidez do Bob:** troca leveza por suavidade (mistura os quadros quando está alta).
- **Brilho nunca automático:** o app desliga o brilho adaptável e vigia pra ele não voltar sozinho.
- Abertura em gif, música de fundo (só dentro do app) e cenário animado. A lista de ajustes rola e encaixa em cada prateleira.

## Instalar (sem computador)

1. Suba esta pasta num repositório seu no GitHub.
2. Aba **Actions → Build APK → Run workflow**.
3. Baixe o artefato `tubo-crt-apk`, extraia e instale o `app-release.apk`.

Ou abra a pasta no Android Studio e use *Build → Build APK*.

O APK é assinado com a chave `app/tubo-crt.jks`, que é **pública de propósito**: assim cada versão nova instala por cima da anterior e o Android não apaga a permissão de Acessibilidade. Não use essa chave pra mais nada.

## Permissões

| Permissão | Para quê |
|---|---|
| Exibir sobre outros apps | desenhar o filtro e o Bob |
| Acessibilidade (opcional) | cobrir a barra de botões de baixo e acionar captura de tela e bloqueio. O serviço **não lê nem grava** a tela |
| Modificar configurações do sistema (opcional) | controle de brilho do painel |
| Segundo plano / RAM / WAKE_LOCK | limpar a RAM e o modo desempenho |

## O que ele não faz

- **Não entorta a imagem de outros apps** (olho de peixe real). Um overlay só desenha por cima e não recebe a imagem do jogo. Os cantos arredondados e a sombra imitam o visual.
- **Não dá mais desempenho ao jogo.** O modo desempenho limpa processos em segundo plano, mantém tela e processador acordados e para a animação do filtro. Não faz overclock.

## Arquivos de terceiros (não inclusos neste repositório público)

O Bob (`app/src/main/assets/bob_sheet.png`) e a música (`app/src/main/res/raw/intro_music.mp3`) são opcionais: sem eles o app desenha um Bob simples e fica sem som. Coloque os seus se quiser, desde que você tenha o direito de usá-los.

## Créditos

- Valores de CRT LIGHT / STRONG: [Black-Android](https://github.com/RanggaCasper/Black-Android).
- Máscaras e perfil de linha: ideia do CRT-Lottes (domínio público). Cantos arredondados: ideia do CRT-Geom.

## Licença

MIT. Veja `LICENSE`.
