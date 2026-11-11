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
