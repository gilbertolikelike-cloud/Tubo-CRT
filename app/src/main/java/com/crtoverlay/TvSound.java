package com.crtoverlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.audiofx.Equalizer;
import java.util.Random;

/**
 * Som de caixinha de TV: corta graves e agudos da música e mistura um chiado (estática, zumbido de 60 Hz e estalos).
 * O filtro é ligado só na sessão de áudio da música do app, e o chiado só toca enquanto a música toca.
 * O som dos jogos e dos outros apps não é tocado nem alterado. Intensidade: preferência "tv_noise", de 0 a 100.
 */
final class TvSound {
    private static final int RATE = 22050;

    private final SharedPreferences sp;
    private Equalizer equalizer;
    private AudioTrack noise;
    private int session = -1;
    private boolean playing;

    TvSound(Context ctx) {
        sp = ctx.getSharedPreferences("crt", Context.MODE_PRIVATE);
    }

    /** Chame quando uma música começa, com o id da sessão de áudio do player. */
    synchronized void attach(int audioSessionId) {
        releaseEffects();
        session = audioSessionId;
        apply();
    }

    synchronized void setPlaying(boolean on) {
        playing = on;
        apply();
    }

    /** Liga, desliga ou muda a intensidade conforme o ajuste. Pode ser chamado a qualquer hora. */
    synchronized void apply() {
        int level = sp.getInt("tv_noise", 0);
        if (level <= 0 || session < 0) {
            releaseEffects();
            return;
        }
        if (equalizer == null) equalizer = createEqualizer();
        if (noise == null) noise = createNoise();
        if (noise != null) {
            noise.setVolume(Math.min(1f, level / 100f * 0.55f));
            if (playing && noise.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) noise.play();
            else if (!playing && noise.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) noise.pause();
        }
    }

    synchronized void release() {
        releaseEffects();
        session = -1;
        playing = false;
    }

    private void releaseEffects() {
        if (equalizer != null) {
            try { equalizer.release(); } catch (RuntimeException ignored) { /* já liberado */ }
            equalizer = null;
        }
        if (noise != null) {
            try { noise.stop(); noise.release(); } catch (RuntimeException ignored) { /* já liberado */ }
            noise = null;
        }
    }

    /** Equalizador que imita um alto-falante pequeno: sem graves, sem agudos e com o médio "nasal". */
    private Equalizer createEqualizer() {
        try {
            Equalizer e = new Equalizer(0, session);
            short min = e.getBandLevelRange()[0], max = e.getBandLevelRange()[1];
            for (short band = 0; band < e.getNumberOfBands(); band++) {
                int hz = e.getCenterFreq(band) / 1000;
                short level;
                if (hz < 150) level = min;
                else if (hz < 400) level = (short) (min * 0.55f);
                else if (hz < 3000) level = (short) (max * 0.6f);
                else if (hz < 6000) level = (short) (min * 0.3f);
                else level = min;
                e.setBandLevel(band, level);
            }
            e.setEnabled(true);
            return e;
        } catch (RuntimeException ex) {
            return null; // aparelho sem equalizador: fica só o chiado
        }
    }

    /** Dois segundos de estática com zumbido de 60 Hz e estalos, tocados em loop (60 Hz x 2 s = ciclos inteiros, sem "pulo"). */
    private AudioTrack createNoise() {
        try {
            int frames = RATE * 2;
            short[] pcm = new short[frames];
            Random rnd = new Random(7);
            float crackle = 0f;
            for (int i = 0; i < frames; i++) {
                float v = (rnd.nextFloat() * 2f - 1f) * 0.5f + (float) Math.sin(2 * Math.PI * 60 * i / RATE) * 0.12f;
                if (rnd.nextInt(2500) == 0) crackle = 0.9f;
                v += crackle * (rnd.nextFloat() * 2f - 1f);
                crackle *= 0.85f;
                pcm[i] = (short) (Math.max(-1f, Math.min(1f, v)) * 18000);
            }
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            t.write(pcm, 0, pcm.length);
            t.setLoopPoints(0, frames, -1);
            return t;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
