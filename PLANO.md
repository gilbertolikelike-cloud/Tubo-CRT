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
