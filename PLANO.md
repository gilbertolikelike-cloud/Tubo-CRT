# Plano do jogo de descanso de tela (Espada + Patos)

Guardado aqui pra não se perder se a conversa acabar. Ordem combinada: 1) ajustes do balão e da espada (feitos), 2) modelos dos patos, 3) o jogo, 4) TV de vídeo com lista de reprodução.

## Já feito
- Espada solta depois de 2 min sem tocar na tela (o painel recebe ACTION_OUTSIDE e avisa o serviço).
- Pato aparece, a espada persegue (devagar, erra 3 em 10) e golpeia com o leque verde do gif + raios azuis.
- Balão azul com letra preta e contorno branco, fonte sem serifa, tamanho ajustável em ▦ (verse_size). Versículo fica 12 a 45 s na tela.
- Alucard aparece de vez em quando; a espada voa em volta dele.

## Regras do jogo (pedidas pelo Gilberto)
- Liga/desliga o jogo por uma opção no painel.
- Espada: começa com 50 corações e 3 vidas. Perdeu as 3 vidas = game over, volta tudo do nível 1.
- Nível: sobe com kills. Ao subir, ganha 5 pontos que ELA escolhe: +5 corações, +5 de dano ou +1 vida.
- Nível 1-49 modelo A. Nível 50+ modelo B (duas lâminas laterais saindo do cabo). Nível 60: rastro de luz azulada e dano ao se mover. Nível 70+: mais agressiva. Máximo 99.
- Patos têm vida e tamanho (mais vida = maior). Vida base 70/85, crescendo com o nível da espada.
  - Azul: mais rápido. Verde: mais vida e revive uma vez (a espada ganha essa vida ao matar). Vermelho: mais corações.
- A cada 100 kills aparece o BOSS: patão com olho vermelho que ataca a espada; força baseada no nível dela.
- Vencer o boss mostra o botão Renascer: recomeça com buffs aleatórios.
- Contadores no painel: kills da partida e kills totais.
- Medalha por versículo (de Gênesis a Apocalipse). Todos desbloqueados = botão Renascer. Medalha de ouro e RGB para os patos pet.

## Patos raros
- Pato RGB (0,001% por pato novo): ao matar, vira pet atrás da espada; a cada pato morto depois disso a espada fica mais rápida. Reset do bônus de velocidade no game over e no Renascer; o pet fica.
- Pato de Ouro (0,00001%): pet que dispara laser perfurante pelos olhos, dano 1 que acelera quanto mais tempo travado no alvo. O pet fica; o multiplicador reseta no game over e no Renascer.
- Com poder: Cyborg (laser vermelho, 1-3%), Kamikaze (explode em 10 s, tira 15 corações), Bruxo (inverte o quique), Tempestade (redemoinho), Ilusionista (3 clones), Cobrador (rouba 1 de dano, devolve em dobro).
- Bíblicos (16): Turbante, Areia, Cacto, Capa de Chuva, Mergulhador, Baleia, Coroa, Armadura, Golias, Harpa, Juba de Leão, Pergaminho, Pastor, Cesta de Pães, Vinha, Trombeta.
- Buffs/debuffs do Renascer: Olho de Águia (mais dano em patos raros), Magnetismo de Encrenca (patos raros aparecem mais), sorte para achar pets etc.

## Ideias de arquivos
- Familiar.java (cena), futuras: Game.java (regras e salvamento), DuckKinds.java (tipos de pato), Medals.java (versículos desbloqueados em bits).
- Sprites: familiar_sword.png (gif novo), alucard_idle.png, duck_target.png. Modelos de pixel art da espada (A e B) vieram do 3924.png (folha SotN).

## Depois do jogo
- TV Philco flutuando com vídeo de uma pasta (lista de reprodução), redimensionável pelas quinas; olho de peixe se der.


## Atualização (arquivos novos do Gilberto)
Tudo que ele mandou está em `referencias/` (gifs, folhas de sprites, os 2 modelos de espada, o gif e o som do Puxa-Frango). Sons já estão em `res/raw/`: `level_up.mp3` (2 s, som de subir de nível da espada) e `puxa_frango.mp3` (8 s).

### Decisões novas
- O ataque da espada NÃO é raio. É a estocada do jogo: gira no lugar acelerando (WIND-UP), recua um pouco e dispara em linha reta com o rastro verde da folha `sprite ataque espada.png` (4 tons, do escuro ao brilhante, um por faixa de nível). Sem brilho azul em volta da espada.
- Modelos de espada em pixel art: `modelo 1` (nível 1 a 49) e `modelo 2` (nível 50+, com as duas lâminas laterais). Já em `familiar_sword.png` (quadro 0 e quadro 1). Só o 1 está em uso.
- Alucard: raro. Hoje só aparece depois de ~5 min da espada solta, e a cada 4 a 8 min. Quando o jogo existir: vira BOSS depois de 1000 kills com 5% de chance (ele tem muitas animações, ver `sprite do movimento.png` e `castlevaniasotnsheet1.gif`).
- Puxa-Frango: outro boss aleatório, 2,5% de chance, tem gif (500x500, 16 quadros) e som próprio.
- Painel de medalhas: um botão no painel abre um SEGUNDO painel com as medalhas, em prateleiras de madeira com casas (a imagem de exemplo do Gilberto tem 4 casas empilhadas).
- Versículo: balão azul, letra preta com contorno branco, tamanho ajustável (verse_size). Está bom de ler.

### Lógica do Sword Familiar (PDF "logica_sword_familiar_sotn") - resumo
- Variáveis: LVL 1-99, EXP 0-99 (100 = sobe de nível), STATE, TIMER, ANGULAR_VEL, CHARGE_TIMER, TARGET_VECTOR.
- Estados (60 FPS): 0 IDLE (órbita com inércia), 1 SCAN (alvo hostil mais próximo, HP>0, na tela), 2 WIND-UP (trava o alvo, calcula theta = atan2(dy,dx), gira no lugar enquanto TIMER cai), 3 STRIKE (velocidade linear na direção theta + hitbox), 4 RECOVERY (desliga a hitbox, freia e volta ao 0).
- Ataques no STRIKE: investida simples; giro contínuo (360 graus, vários ticks de dano); combo de estocadas (nível 80+, 2 a 4 investidas seguidas sem voltar ao 0).
- Giro de impulso: zera VX e VY, CHARGE_TIMER = 30-40 quadros (nível baixo) até 0-3 (nível 80+), ANGULAR_VEL cresce; nos últimos quadros faz micro-recuo (-cos, -sin); no disparo Vx = SPEED_MAX*cos(theta), Vy = SPEED_MAX*sin(theta). Estocada trava o ângulo com a ponta no alvo; corte giratório mantém o giro no máximo.
- Escala por nível (1-49 / 50-79 / 80-99): SCAN 45 / 15 / 1-3 quadros; WIND-UP 30-40 / 10 / 0-3; projétil 4 / 8 / 12+ px por quadro; padrão: golpe único e retorno lento / perseguição contínua / combos sem recuo; visual: lâmina cinza / brilho azul claro / aura azul intensa com rastro.
- XP: EXP_Ganha = floor(log2((2 * EXP_Base_Inimigo) / LVL)), mínimo 1. 100 pontos por nível (9.800 do 1 ao 99).
- Dano: só CUT. RAW_ATK = LVL + bônus do golpe (+ Mojo Mail). Dano_Final = (RAW_ATK - DEF/2) * ((256 - DEF)/256). IMMUNE/ABSORB = 0 ou 1; WEAK x2; RESIST /2. I-frames de ~15 quadros no alvo (exceto multihit).
- Nível 50: vira equipamento (ATK = nível do familiar); exclusão mútua com a espada na mão.
- Magia Sword Brothers (MP >= 30, gasta 30): 6 lâminas espectrais em órbita expandida, vários golpes CUT escalados pelo LVL.

### Pendente
- TV Philco de vídeo (pasta como lista de reprodução, redimensionar pelas quinas, olho de peixe com plano B). A moldura sai de `7_Sem_Título_20261106171530.png`.
- Respostas ainda não dadas: chance dos patos raros (RGB e ouro) e como sortear as medalhas de versículo.
- Rodada N = N patos na tela (ideia: ondas, tipos misturados, no máximo ~12 ao mesmo tempo).


## Atualização 3
### Feito nesta versão
- TV Philco de vídeo: moldura com vidro vazado, playlist por pasta (pastas salvas em ▦ > "TV de vídeo"), controles (tocar/pausar, anterior, próximo, embaralhar, barra de progresso), lista de vídeos e de pastas dentro da própria TV, mover arrastando e redimensionar pelas 4 quinas. Arquivos: TvService, TvView, TvLibrary. FALTA: olho de peixe (precisa de OpenGL; deixado de fora pra não arriscar a TV não abrir).
- Painel: nomes embaixo dos botões, texto e botões maiores, fundo barroco (parchment.jpg), céu do dia = gif do sol nascendo. Bug corrigido: a cor dos nomes sumia depois de um tempo (deslocamento do shader sem módulo).
- Espada solta: aparece em qualquer lugar da tela; rastro alinhado; ricochete nas bordas; erra de forma aleatória (40% de erro de propósito, e o pato também pode sair da frente).
- Versículos: o 1º sai no primeiro pato morto; os seguintes só depois de 5, 7, 10 e 10 min (10 min é o máximo). Tocar na tela acaba com a espada solta e tudo recomeça do zero.
- Batalha da noite (19h às 6h): 10% das vezes em que um pato ia aparecer, a espada azul de raio entra e luta com a espada dos patos (4 rodadas: giram, disparam uma na outra, choque, recuo). Intervalo mínimo de 2 min entre batalhas.

### Segundo ataque da espada: ATAQUE DE PROFUNDIDADE (só nível 70+) - PDF Logica_Ataque_Profundidade_Espada
- Ilusão 2.5D: escala, camada (z-index), brilho e hitbox mudam juntos. Sem sprites novos, só scale/alpha da mesma imagem.
- Variáveis: estado (SEGUINDO, RECUANDO_FUNDO, MERGULHANDO, IMPACTO), profundidadeZ (0 = perto, 1 = longe, 20% do tamanho), alvoX/alvoY, recuoX/recuoY, hitboxAtiva, timerImpacto.
- SEGUINDO: acha o alvo, salva alvoX/alvoY, define o ponto de recuo (alvoX +- 80, alvoY - 70), hitbox desligada.
- RECUANDO_FUNDO: profundidadeZ += 2.5*dt; vai pro ponto de recuo com lerp (5*dt); escala = 1 - Z*0.8; brilho = 1 - Z*0.55; com Z > 0.3 vai pra trás do cenário (z -1); aponta pro alvo; em Z >= 1 atualiza o alvo e vai pra MERGULHANDO.
- MERGULHANDO: Z -= 7.5*dt (3x mais rápido); lerp até o alvo (14*dt); com Z < 0.3 volta pra frente (z +10); em Z <= 0 vai pra IMPACTO.
- IMPACTO: escala 1.15 e volta a 1.0 (lerp 20*dt), hitbox ligada por 0.08 s, 2 ou 3 cópias translúcidas (50% e 25%) como pós-imagem (pool fixo de 3 posições). Acabou: se o alvo vive, volta ao RECUANDO_FUNDO sem pausa; se não, SEGUINDO.
- Evolui com o nível mexendo só nos multiplicadores (2.5 recuo, 7.5 mergulho).

### Ideia do Sol de Fogo (sun-14.gif, já em assets como fire_sun.png)
- Pato Solar (raro): invoca o Sol de Fogo, que cai devagar e persegue a espada. A espada precisa desviar ou cortar o sol ao meio (corte certo = o pato perde vida e solta brasas). Se o sol encostar, tira corações. Chefe de fogo mais forte: o Sol cresce com o tempo.

### OCR Auto Clicker (app do Gilberto, fonte em referencias/OcrAutoClicker.zip)
- 3 modos: Alvo único, Multi alvos e OCR (lê texto da tela com ML Kit e clica). Kotlin, minSdk 29, ML Kit text-recognition 16.0.1 (modelo embutido, ~50 MB), captura de tela com MediaProjection, serviço de acessibilidade próprio.
- Pedido: virar o 3º painel do Bob, sem a interface do app (MainActivity, PageActivity, Ui), com as 3 opções como botões no painel. Fazer só depois da TV.
- Pontos a resolver: o projeto do Tubo-CRT é Java (precisa do plugin Kotlin), o serviço de acessibilidade precisa ser um só (juntar com o CrtAccessService), permissão de captura de tela, e o APK passa de ~45 MB pra ~100 MB.


## Atualização 4 (depois de testar a TV)
- TV: vídeo agora PREENCHE o vidro (corta as pontas). Botão de 4 cantinhos nos controles troca entre Preencher, Esticar e Ajustar (com faixas pretas); a escolha fica salva (tv_fill). Moldura nova em alta qualidade (foto Anime4K).
- Painel: faixa dos botões agora tem só GAVETA e TV LISTA. A gaveta abre e mostra os 6 botões (APAGAR, FOTO, MAX, PARAR, GELO, RAM); a setinha no alto fecha. TV LISTA abre a TV já na aba Pastas (playlists), escolhe e toca sem abrir o app. Espaço pra futuros botões na faixa (medalhas, os 3 modos do OCR Auto Clicker).
- Fundo do painel sem quadriculado (filtro bilinear no shader).
- Ícone do app: só a TV, sem fundo preto (cor de fundo do ícone adaptativo agora é transparente).
- Pato que dança com a música: gif novo (duck-dance.gif), fundo removido, 48 quadros.
- Espada e pato a 50-60 fps (postInvalidateOnAnimation). Rastro da espada: faixa afunilada em 3 camadas verdes + 3 cópias fantasma (pós-imagem, como no PDF do ataque de profundidade) + o rastro do arquivo na estocada.

## Atualização 5
- Céu do dia com capa escura (padrão 22%, slider "Escuridão do céu do painel" em ▦, de 0 a 60%).
- Rastro da espada sai do meio da LÂMINA (a parte azul), nunca do cabo, como no gif do jogo (2026_11_13_12_59_53.gif). O rastro do Alucard que aparece no gif NÃO é usado.

## Atualização 6 (gaveta de verdade)
- O painel voltou ao desenho de antes (6 botões em duas colunas, nomes embaixo). Entre a hora e as colunas ficaram 2 botãozinhos: TV LISTA e GAVETA.
- GAVETA: o Gilberto escolhe em ▦ > "Botões da gaveta" quais dos 6 (APAGAR, FOTO, MAX, PARAR, GELO, RAM) vão pra dentro dela (preferência drawer_mask, um bit por botão). Os que ficam no painel reorganizam as casas sem buracos. A gaveta aberta cobre as duas colunas, com borda dourada e uma setinha pra fechar. Padrão: gaveta vazia (painel igual ao de antes).

## Atualização 7 (começou o jogo)
- TV: sempre inteira dentro da tela (com as perninhas): recalcula a área útil (sem a barra de navegação) ao girar o celular, a cada toque e ao redimensionar.
- Painel: mais espaço entre o botão de girar e o nome (VOL/BRI) e entre o relógio e os botõezinhos TV LISTA / GAVETA.
- Gaveta: 8 casas (4 linhas x 2). As duas primeiras são fixas: MEDALHAS (abre o 2º painel) e CLICKER (3º painel, ainda em construção). Depois vêm os botões que o Gilberto escolheu pra gaveta.
- Painel de medalhas (MedalPanel): placar (versículos, kills da rodada e total, nível e EXP), medalhas de OURO e RGB (apagadas até achar os patos pet; prefs medal_gold e medal_rgb) e lista de livros com o progresso de versículos. Arrasta pelo título, rola a lista com o dedo.
- Jogo v1 (GameState, Medals): cada pato morto soma kill e EXP (floor(log2(2*60/nível)), mín. 1; 100 EXP por nível, máx. 99). Subiu de nível: som level_up.mp3 e "LEVEL UP!". Nível 50+ usa o modelo B da espada; o tom do rastro sobe nos níveis 50, 80 e 90. O placar aparece no alto do céu do painel ("LV n  K rodada/total").
- Medalha por versículo: o versículo mostrado no balão é sorteado só entre os que ainda não deram medalha (padrão escolhido por mim, o Gilberto não respondeu); a referência ganha "(nova medalha!)". Bits salvos em medals_bits (Base64).
- Falta: vida e tamanho dos patos, corações/3 vidas/game over, boss aos 100 kills, Renascer com buffs, patos de cor (azul, verde, vermelho), RGB e Ouro com pets, patos com poder, painel do Auto Clicker, ataque de profundidade (nível 70+), zerar kills da rodada no game over.

## Atualização 8
- Bob discoteca saiu: no lugar entrou o Ned Flanders dançando (gif novo, 100 quadros; internamente a chave continua "disco").
- Partes brancas dos gifs limpas quadro a quadro: Papyrus (calombo branco entre as pernas), pato de boné (bloco branco entre as pernas) e borda branca do pato da espada. Ainda pode sobrar um pontinho aqui e ali; a fonte é pequena. Dica: o Gilberto tem o Anime4K; se aumentar os gifs originais e mandar, eu uso.
- Pato da espada e espada azul agora carregam em resolução cheia (antes era metade) e usam mipmap (menos "pixels" ao diminuir).
- Pato da espada menor (56dp; a espada tem 112dp). Boss grande e pets bem pequenos ficam para o jogo.
- Tiro de ricochete: depois de 3 a 5 erros seguidos (ou, de longe, 35% das vezes) a espada gira, mira numa parede "espelhando" o pato, bate, pega 1.7x de velocidade e passa a mirar o pato (não erra).
- Espada pesada (não lenta): acelera e gira menos rápido, a estocada arranca devagar e ganha velocidade, trava um instante no impacto (hit-stop) e demora um pouco pra parar.
- Alucard sumiu da cena (só boss no jogo, depois de 1000 kills, 5% de chance; Puxa-Frango 2,5%).
- Batalha da noite com a espada DO PAINEL (o Bob de noite sai do painel e some de lá): barras de vida, combos de 1 a 4 golpes, defesas (30%), revide, até uma morrer. Quem vence comemora; a espada do painel volta voando (ou renasce no lugar uns segundos depois, se perder); a espada dos patos renasce em outro ponto da tela.
- Sol do painel de volta (o do gif foi apagado do céu) e continua indicando a hora. Letras do painel um pouco mais negrito.
- Sons mais baixos (efeito padrão 35%, slider "Volume dos efeitos" em ▦; level up usa o mesmo volume).
- Ícone novo do app: a TV cinza de antenas (NOVA_CAPA_DO_APP).
- Painel de medalhas desliza de trás do painel principal, ligado por 2 canos de cobre (cano.png), e fecha junto com o principal. Falta: painel de cima (Clicker) e o desenho das placas de madeira dos vídeos; referências em PAINEL_DE_CONTROLE (3 vídeos + exemplo).
- Versículo fica bem mais tempo na tela: 20 a 90 s conforme o tamanho (antes 12 a 45 s). Slider "Tempo do versículo na tela" em ▦ (50 a 300%, padrão 100%).

## Atualização 9
- Painel secundário (medalhas) 22% menor e alinhado com o principal (a janela dele não usava FLAG_LAYOUT_IN_SCREEN, então saía mais baixa). 3º painel (ClickerPanel) criado EM CIMA do principal, menor, com 3 canos verticais e os 3 modos (alvo único, multi alvos, OCR) ainda sem função; abre pelo botão CLICKER da gaveta.
- Pingu (pingu_50fps.gif, 17 quadros a 20 ms) no lugar do Bob do painel de dia.
- Ícone: tirado o ícone adaptativo (o One UI pinta de preto o fundo transparente). Agora é o ícone comum (PNG da TV cinza), que o launcher enquadra como nos outros apps. Pra ficar sem moldura nenhuma: Ajustes do Samsung > Tela inicial > Molduras de ícones > "Somente ícones".
- Versículos coloridos pelo falante (VerseStyler): fala depois do travessão; Jesus = vermelho, Deus/SENHOR = azul, grandes profetas = dourado, discípulos = verde escuro; sem dono: evangelhos = vermelho, livros dos profetas = azul, cartas = verde. Livros dos profetas ficam dourados e as cartas verde escuro. Letreiro "VELHO TESTAMENTO" / "NOVO TESTAMENTO" em cima de cada versículo.

## PEDIDOS AINDA NÃO FEITOS (próxima conversa)
1. ESTÚDIO DE GIFs (opção em ▦): o Gilberto escolhe um GIF, escolhe QUAL desenho trocar (Bob de dia, os 3 dançarinos, Bob MAX, espada azul do painel, espada dos patos modelos A e B, pato alvo) e o app faz tudo sozinho: tira o fundo, mostra todos os quadros, ele toca nos pontos brancos que sobraram e eles são apagados (e "aplicar em todos os quadros"). Depois salva em filesDir/custom/<chave>.png + .json e o app passa a usar esse arquivo no lugar do original (SpriteSheet.load e o carregador do BobPanel olham primeiro a pasta custom; pref sprites_rev dispara o recarregamento). Botão "voltar ao original" por desenho.
   Ideia técnica: decodificar o GIF com android.graphics.Movie (setTime + draw), preflood do fundo pelos cantos com tolerância, tirar borda branca, recortar pela união dos quadros, montar a folha (máx. ~20 MB), borracha por toque (flood fill por semelhança de cor) e pincel; desfazer.
2. BIBLIOTECA DE EFEITOS por desenho (desenhados na hora, em Effects.java): Raio, Fogo, Gelo, Vento, Brilho, Sombra, Estrelas, Arco-íris, com intensidade; escolhidos no Estúdio (prefs fx_<chave> e fxi_<chave>).
3. LUPA no painel (botão na gaveta): um quadrado (moldura) que o Gilberto arrasta pela tela + uma janela de visualização ampliada ao vivo, com uma barra vertical (pra cima = mais zoom, pra baixo = menos zoom). Captura por MediaProjection (pede permissão uma vez; serviço em primeiro plano tipo mediaProjection; ImageReader + VirtualDisplay), a moldura mostra a região e a janela de visualização fica separada dela (senão a captura enxerga a si mesma). Ele é meio cego: botões grandes e letras grandes.

## Atualização 10
- Leitor de PDF em forma de livro (BookActivity + BookView), aberto em ▦ > "Biblioteca de livros (PDF)": estante (PDFs avulsos ou pastas, segura pra tirar), capa de couro, duas páginas com sombra no miolo, folha que vira (toque nos lados ou arraste), zoom A−/A+, 1 ou 2 páginas, cores (normal, papel creme, noturno), ir pra página, lembra a página de cada livro. Usa o PdfRenderer do Android.
- Espada dos patos parada: não passeia mais; só sai do lugar quando tem pato. Sem curvas: tudo em linha reta (perseguição e luta).
- Batalha da noite reescrita no estilo Vagabond: faixas de cinema, vinheta, jogo de pernas em passos curtos, tensão, estocada, finta, golpe duplo, defesa, desvio, contra-ataque, corte fundo em câmera lenta com respingo de tinta, riscos de corte brancos, golpe final com as duas se cruzando e uma caindo.

## Atualização 11 (versionCode 16) — Mini Bíblia, dublagem, cores seguras
- versiculos.txt = arquivo do Gilberto (versiculos_formatado_v6.txt) COPIADO SEM ALTERAR (conferido byte a byte). O app aprende o formato: "Livro, capítulo N, versículo 1. texto" e "Versículo M. texto" (30310 linhas, 1189 capítulos).
- Painel 2 virou a BÍBLIA (BiblePanel): capa de couro "BÍBLIA — Edição por Gilberto", livros, capítulos, leitura com letra A−/A+, cores das falas, progresso lido/ouvido (bible_read), ⤢ tamanho grande, medalhas OURO/RGB na capa; medalha da Bíblia completa só na capa quando 1189/1189.
- Dublagem: NÃO mexe nos arquivos. Reconhece pelo número da frente (0001..1189). ▦ > "Dublagem da Bíblia": importar ZIP(s) (copia os MP3 com o mesmo nome) ou escolher pasta (sem copiar). Toca em ordem Gn→Ap, pausa, parar, anterior/próximo, barra para pular, texto acompanha (verso destacado e rolagem).
- VerseStyler: só pinta quando o sujeito é o 1º nome antes do travessão (Yahusha, Elohim, Yahuah, profetas no AT, discípulos no NT). Pilatos/"ele"/vários travessões = sem cor. Cores configuráveis em ▦ "Cores das falas".
- Animação dos painéis 2 e 3 agora pela GPU (translation/alpha), sem updateViewLayout por quadro.
- PENDENTE: módulos do theWord (.ont/.ot/.nt: precisa de um módulo de exemplo para calibrar a versificação), capas de músicas/PDF, painel 4, opções por categorias com ⚠️, log de versões, FPS, voz para PDF (RVC), duelo (coroa/roleta/fúria), GIF Studio, lupa.

## Atualização 12 (versionCode 17)
- Painel principal NÃO fecha mais sozinho (era 10 s). Opção ▦ "Painel fecha sozinho..." (padrão desligado).
- Dublagem agora vive em BibleAudio (fora do painel): fechar o painel não para o áudio; wake lock; ■ para; no modo manual segue em ordem Gn..Ap.
- Sem versículo aleatório: balão da espada mostra versículos em ordem (verse_seq). A cada 30 min (opção ▦, 5-180 min) abre o painel + Bíblia no próximo capítulo da fila (bible_seq = último ouvido + 1) e toca a dublagem (só 1 capítulo por vez).

## Atualização 13 (versionCode 18)
- Capa nova da Bíblia (assets/bible_cover.png, esticada fina e alongada) com as medalhas OURO/RGB nos pomos redondos das espadas; medalha da Bíblia completa no centro do portão (só quando 1189/1189). As chaves medal_gold/medal_rgb ainda não são concedidas por nada (regras a definir).
- Espada do Capítulo (assets/rapier.png): aparece no leitor quando o capítulo ainda não foi ouvido, explicação curta, pega fogo em espiral (3 fios) e apaga quando o capítulo é ouvido/lido.
- Bíblia LIVRE: depois de abrir (1,3 s) solta dos canos, painel principal sai da tela, arrasta pelo título, só fecha no X dela; no 30 min/50 patos abre sozinha (showFree).
- Gatilhos: a cada 50 patos mortos (kills total % 50) e por tempo escalonado X, 2X, 3X, 4X (30 min, 1 h, 1 h 30, 2 h; fica em 2 h; bible_stage). Sempre o próximo capítulo em ordem (bible_seq).
- Cores v2 (VerseStyler): fala só recebe a cor de Deus/Jesus/profetas/discípulos quando o dono é certo; falas de outras pessoas ganham a cor "outras" (col_other); nomes pintados (col_names). Testado no texto real: 1199 falas de dono certo, 4152 de outras pessoas, ~19,8 mil nomes.
- Painel principal não fecha sozinho (v17).

## IDEIAS ANOTADAS (NÃO FAZER AINDA) — pedido do Gilberto
- Trazer mais efeitos para o filtro de tela, tipo HDR e outros ("efeitos de lá").
  Possíveis para estudar quando ele mandar fazer: HDR falso (curva de contraste/realce de brilho e cor), bloom/brilho nas luzes, realce de cor/saturação, nitidez, vinheta, curvatura da tela, desfoque de movimento, granulado de filme, aberração de cor.
  Cuidado: o filtro é uma sobreposição (overlay) e não consegue mudar os pixels de outros apps; efeitos que precisam ler a tela exigem captura (MediaProjection) e OpenGL (o mesmo caminho da lupa e do olho de peixe). Perguntar a ele quais efeitos exatos quer e se aceita a captura de tela.

## IDEIA ANOTADA (NÃO FAZER AINDA) — explicação da Bíblia versículo por versículo
- Pedido do Gilberto: explicação básica de cada versículo, "bom para não errar" (ajuda a entender o texto).
- Ideias de como ficaria: botão no leitor (por versículo ou por capítulo) que mostra a explicação embaixo do versículo; opção de ligar/desligar; letra ajustável como o resto do leitor.
- Fonte do conteúdo: NÃO inventar nem gerar explicações em massa (são ~30 mil versículos e erro doutrinário é o que ele quer evitar). Fazer como a dublagem: o app só IMPORTA um arquivo que ele fornecer (txt/zip), no formato que ele escolher, sem alterar nada. Perguntar a ele qual é a fonte e o formato (por versículo "Livro cap:ver", por capítulo, por livro).
- Se a fonte for outra tradução/comentário, combinar com o tema dos módulos do theWord (também anotados): podem usar o mesmo importador.
- ATUALIZAÇÃO da ideia das explicações: o Gilberto quer que o CLAUDE escreva, um capítulo por vez, quando ele pedir ("próximo capítulo"). Regras: explicação básica e simples (quem fala, onde, o que acontece, palavras difíceis), sem doutrina de denominação, usando os nomes da edição dele (Elohim, Yahuah, Yahusha); marcar o que for incerto; ele revisa. Escala: 1189 capítulos / ~30 mil versículos = muitas mensagens; começar por livros que ele escolher. Formato do arquivo importável: uma linha por versículo "Livro cap:ver | explicação" (o app casa pelo livro/capítulo/versículo do texto dele). Guardar o arquivo acumulado e entregar sempre completo no zip/arquivo separado.

## Atualização 14 (versionCode 19)
- Bug da espada (vídeo 25/11): ao abrir/fechar o painel a espada (Bob da noite) aparecia deslocada por 1-2 quadros (pulava para cima/esquerda). Causa: a janela muda de tamanho/lugar um instante depois do pedido e o desenho usava o lugar "pedido" (winX/winY). Correção: desenhar pelo lugar real da janela (getLocationOnScreen -> drawOX/drawOY) em onDraw/drawBob.
- Capa da Bíblia: sem o título "BÍBLIA" e sem "Edição por Gilberto"; a arte preenche o painel inteiro (sem moldura de madeira); botões ⤢ e ✕ sobem no topo; medalhas nos pomos das espadas.
