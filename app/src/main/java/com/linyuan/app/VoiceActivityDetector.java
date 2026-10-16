package com.linyuan.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

public class VoiceActivityDetector {
    public interface Listener {
        void onSpeechStart();
        void onSpeechEnd(File wavFile, long durationMs);
        void onRejected(String reason);
        void onError(String message);
        void onLevel(double level);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_MS = 40;
    private static final int FRAME_BYTES = SAMPLE_RATE * 2 * FRAME_MS / 1000;
    private static final double MIN_THRESHOLD = 1200.0;

    private static final double NOISE_FACTOR = 2.0;
    private static final double NOISE_ADAPT_RATE = 0.002;
    private static final double PLAYING_THRESHOLD_FACTOR = 1.4;

    private static final double VOICED_NORM_THRESHOLD = 0.22;
    private static final int MIN_VOICED_MS = 200;
    private static final int MIN_VOICED_PERCENT = 10;
    private static final int ABANDON_AFTER_MS = 2000;
    private static final int MIN_SPEECH_MS = 500;
    private static final int MAX_SPEECH_MS = 60000;

    private static final int TAIL_MS = 1400;

    private static final double CONTINUE_FACTOR = 0.62;

    private static final int PRE_ROLL_FRAMES = 8;

    private final Context context;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioRecord record;
    private Thread thread;
    private volatile boolean running = false;

    private final java.util.concurrent.atomic.AtomicLong generation =
            new java.util.concurrent.atomic.AtomicLong(0);

    private ByteArrayOutputStream current;

    private final java.util.ArrayDeque<byte[]> preRoll = new java.util.ArrayDeque<>();
    private int speechBytes;
    private int silentFrames;
    private int totalMs;
    private int voicedMs;
    private boolean inSpeech;
    private boolean sawSpeech;
    private double noiseFloor = 200.0;
    private boolean aecAvailable;
    private volatile boolean paused;
    private volatile boolean playing;
    private boolean playbackPaused;

    public VoiceActivityDetector(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void start() {

        if (running && record != null) return;
        if (running) running = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onError("缺少麦克风权限");
            return;
        }
        if (record == null) {
            initRecord();
        }
        if (record == null) return;
        try { record.startRecording(); } catch (Exception e) { listener.onError("录音启动失败：" + e.getMessage()); return; }
        running = true;
        final long gen = generation.incrementAndGet();
        thread = new Thread(() -> loop(gen), "vad-thread");
        thread.start();
    }

    private void initRecord() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(FRAME_BYTES * 8, minBuf * 2);
        boolean usedVoiceComm = false;
        try {

            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release(); record = null;
                record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            } else {
                usedVoiceComm = true;
            }
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                record.release(); record = null;
                listener.onError("无法初始化麦克风");
                return;
            }
            attachEffects(usedVoiceComm);
        } catch (Exception e) {
            record = null;
            listener.onError("麦克风初始化失败：" + e.getMessage());
        }
    }

    private void attachEffects(boolean usedVoiceComm) {
        aecAvailable = usedVoiceComm;
        try {
            AcousticEchoCanceler aec = AcousticEchoCanceler.create(record.getAudioSessionId());
            if (aec != null) {
                aec.setEnabled(true);
                aecAvailable = true;
            }
        } catch (Exception ignored) {
        }
        try {
            NoiseSuppressor ns = NoiseSuppressor.create(record.getAudioSessionId());
            if (ns != null) ns.setEnabled(true);
        } catch (Exception ignored) {
        }
    }

    public void stop() {
        running = false;
        generation.incrementAndGet();
        if (thread != null) { thread.interrupt(); thread = null; }
        if (record != null) { try { record.stop(); } catch (Exception ignored) {} try { record.release(); } catch (Exception ignored) {} record = null; }
        current = null; inSpeech = false; sawSpeech = false;

        paused = false;
        playbackPaused = false;
    }

    public boolean isRunning() {
        return running;
    }

    public void pause() {
        running = false;
        generation.incrementAndGet();
        if (thread != null) { thread.interrupt(); thread = null; }
        if (record != null) { try { record.stop(); } catch (Exception ignored) {} }
        paused = true;
    }

    public void resume() {
        if (!paused || running) return;
        paused = false;
        if (record == null) {

            initRecord();
            if (record == null) return;
        }
        try { record.startRecording(); } catch (Exception ignored) {}
        running = true;
        final long gen = generation.incrementAndGet();
        thread = new Thread(() -> loop(gen), "vad-thread");
        thread.start();
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        if (playing) {
            if (!aecAvailable && !paused && !playbackPaused) {
                playbackPaused = true;
                stopLoopKeepRecord();
            }
        } else if (playbackPaused) {
            playbackPaused = false;
            startLoopKeepRecord();
        }
    }

    private void stopLoopKeepRecord() {
        running = false;
        generation.incrementAndGet();
        if (thread != null) { thread.interrupt(); thread = null; }
        if (record != null) { try { record.stop(); } catch (Exception ignored) {} }
        current = null; inSpeech = false; sawSpeech = false; voicedMs = 0; silentFrames = 0; speechBytes = 0;
    }

    private void startLoopKeepRecord() {
        if (running || record == null) return;
        try { record.startRecording(); } catch (Exception ignored) {}
        running = true;
        final long gen = generation.incrementAndGet();
        thread = new Thread(() -> loop(gen), "vad-thread");
        thread.start();
    }

    private void loop(long gen) {
        byte[] buf = new byte[FRAME_BYTES];
        while (running && gen == generation.get()) {

            AudioRecord rec = record;
            if (rec == null) break;
            int n;
            try {
                n = rec.read(buf, 0, buf.length);
            } catch (Exception e) {
                break;
            }
            if (gen != generation.get()) break;
            if (n < 0) {

                running = false;
                final int errCode = n;
                handler.post(() -> listener.onError("麦克风读取失败（错误码 " + errCode + "），已停止采集"));
                break;
            }
            if (n == 0) continue;
            double rms = rms(buf, n);
            final double lvl = Math.min(1.0, rms / 3000.0);
            handler.post(() -> listener.onLevel(lvl));
            if (!inSpeech) {

                if (rms < noiseFloor) noiseFloor = rms;
                else noiseFloor += (rms - noiseFloor) * NOISE_ADAPT_RATE;
                if (noiseFloor < 30) noiseFloor = 30;
            }
            double threshold = Math.max(MIN_THRESHOLD, noiseFloor * NOISE_FACTOR);
            if (playing && aecAvailable) threshold *= PLAYING_THRESHOLD_FACTOR;
            boolean voiced = isVoicedFrame(buf, n);
            boolean loud = rms >= threshold;

            boolean stillLoud = rms >= threshold * CONTINUE_FACTOR;
            if (!loud) {

                if (!stillLoud) silentFrames = silentFrames + 1;
                else if (inSpeech) silentFrames = 0;
            }
            if (loud && !inSpeech) {
                inSpeech = true; sawSpeech = true;
                current = new ByteArrayOutputStream();
                speechBytes = 0; silentFrames = 0; totalMs = 0; voicedMs = 0;

                synchronized (preRoll) {
                    for (byte[] f : preRoll) {
                        current.write(f, 0, f.length);
                        speechBytes += f.length;
                        totalMs += FRAME_MS;
                    }
                    preRoll.clear();
                }
                handler.post(() -> listener.onSpeechStart());
            }

            if (!inSpeech) {
                byte[] copy = new byte[n];
                System.arraycopy(buf, 0, copy, 0, n);
                synchronized (preRoll) {
                    preRoll.addLast(copy);
                    while (preRoll.size() > PRE_ROLL_FRAMES) preRoll.pollFirst();
                }
            }
            if (inSpeech) {
                ByteArrayOutputStream cur = current;
                if (cur == null) { inSpeech = false; continue; }
                cur.write(buf, 0, n);
                speechBytes += n; totalMs += FRAME_MS;
                if (voiced) voicedMs += FRAME_MS;

                if (totalMs >= ABANDON_AFTER_MS && voicedMs == 0) {

                    abandonUtterance("环境声，已忽略");
                } else if (silentFrames >= TAIL_MS / FRAME_MS) {
                    finalizeUtterance();
                } else if (totalMs >= MAX_SPEECH_MS) {
                    finalizeUtterance();
                }
            }
        }
    }

    private void abandonUtterance(String reason) {
        if (!inSpeech) return;
        inSpeech = false;
        current = null;
        silentFrames = 0;
        handler.post(() -> listener.onRejected(reason));
    }

    private void finalizeUtterance() {
        if (!inSpeech) return;
        inSpeech = false;
        int ms = totalMs;
        byte[] audio = current == null ? new byte[0] : current.toByteArray();
        current = null;
        if (ms < MIN_SPEECH_MS || audio.length < FRAME_BYTES * (MIN_SPEECH_MS / FRAME_MS)) return;

        if (voicedMs < MIN_VOICED_MS || voicedMs * 100 < ms * MIN_VOICED_PERCENT) {
            handler.post(() -> listener.onRejected("不是人声，已忽略"));
            return;
        }
        try {
            File dir = context.getCacheDir();
            final File wav = new File(dir, "utt_" + System.currentTimeMillis() + ".wav");
            writeWav(wav, audio, SAMPLE_RATE);
            final long durMs = ms;
            handler.post(() -> listener.onSpeechEnd(wav, durMs));
        } catch (Exception e) {
            handler.post(() -> listener.onError("保存录音失败：" + e.getMessage()));
        }
    }

    static boolean isVoicedFrame(byte[] pcm, int n) {
        int samples = n / 2;
        if (samples < 160) return false;
        double[] x = new double[samples];
        long sum = 0;
        for (int i = 0; i < samples; i++) {
            short s = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
            x[i] = s;
            sum += s;
        }
        double mean = (double) sum / samples;
        double r0 = 0;
        for (int i = 0; i < samples; i++) {
            double d = x[i] - mean;
            x[i] = d;
            r0 += d * d;
        }
        if (r0 < samples * 300.0 * 300.0) return false;
        double best = 0;
        for (int lag = 45; lag <= 200; lag++) {
            double corr = 0;
            for (int i = 0; i + lag < samples; i++) corr += x[i] * x[i + lag];
            double norm = corr / r0;
            if (norm > best) best = norm;
        }
        return best > VOICED_NORM_THRESHOLD;
    }

    private static double rms(byte[] pcm, int len) {
        long sum = 0;
        int n = len / 2;
        for (int i = 0; i < n; i++) {
            short s = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
            sum += (long) s * s;
        }
        return Math.sqrt((double) sum / n);
    }

    private static void writeWav(File file, byte[] pcm, int rate) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            byte[] h = new byte[44];
            writeStr(h, 0, "RIFF");
            writeInt(h, 4, 36 + pcm.length);
            writeStr(h, 8, "WAVE");
            writeStr(h, 12, "fmt ");
            writeInt(h, 16, 16);
            writeShort(h, 20, 1);
            writeShort(h, 22, 1);
            writeInt(h, 24, rate);
            writeInt(h, 28, rate * 2);
            writeShort(h, 32, 2);
            writeShort(h, 34, 16);
            writeStr(h, 36, "data");
            writeInt(h, 40, pcm.length);
            out.write(h);
            out.write(pcm);
        }
    }

    private static void writeStr(byte[] b, int o, String s) { for (int i = 0; i < s.length(); i++) b[o + i] = (byte) s.charAt(i); }
    private static void writeInt(byte[] b, int o, int v) { b[o] = (byte) v; b[o+1] = (byte) (v >> 8); b[o+2] = (byte) (v >> 16); b[o+3] = (byte) (v >> 24); }
    private static void writeShort(byte[] b, int o, int v) { b[o] = (byte) v; b[o+1] = (byte) (v >> 8); }
}
