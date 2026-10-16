package com.linyuan.app;

import android.content.Context;
import android.util.Base64;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import com.k2fsa.sherpa.onnx.WaveData;
import com.k2fsa.sherpa.onnx.WaveReader;

import java.io.File;
import java.nio.ByteBuffer;

public class VoiceprintManager {
    private static final String MODEL_ASSET = "voiceprint.onnx";

    private final Context context;
    private SpeakerEmbeddingExtractor extractor;

    public VoiceprintManager(Context context) {
        this.context = context.getApplicationContext();
    }

    private synchronized SpeakerEmbeddingExtractor extractor() {
        if (extractor != null) return extractor;
        SpeakerEmbeddingExtractorConfig config =
                new SpeakerEmbeddingExtractorConfig(MODEL_ASSET, 2, false, "cpu");
        extractor = new SpeakerEmbeddingExtractor(context.getAssets(), config);
        return extractor;
    }

    public void warmUp() throws Exception {
        extractor();
    }

    public float[] embedding(File wavFile) throws Exception {
        WaveData data = WaveReader.Companion.readWave(wavFile.getAbsolutePath());
        SpeakerEmbeddingExtractor ex = extractor();
        OnlineStream stream = ex.createStream();
        try {
            stream.acceptWaveform(data.getSamples(), data.getSampleRate());
            stream.inputFinished();
            return ex.compute(stream);
        } finally {
            stream.release();
        }
    }

    public static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0f;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0f;
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }

    public static float[] average(java.util.List<float[]> embeddings) {
        if (embeddings == null || embeddings.isEmpty()) return null;
        if (embeddings.size() == 1) return normalize(embeddings.get(0));
        int dim = embeddings.get(0).length;
        double[] acc = new double[dim];
        for (float[] e : embeddings) {
            if (e == null || e.length != dim) continue;
            float[] n = normalize(e);
            for (int i = 0; i < dim; i++) acc[i] += n[i];
        }
        float[] mean = new float[dim];
        for (int i = 0; i < dim; i++) mean[i] = (float) acc[i];
        return normalize(mean);
    }

    public static float[] normalize(float[] v) {
        if (v == null) return null;
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        norm = Math.sqrt(norm);
        float[] out = new float[v.length];
        if (norm == 0) return out;
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    public String selfTest() {
        try {
            int sr = 16000, n = sr * 2;
            float[] samples = new float[n];
            for (int i = 0; i < n; i++) samples[i] = (float) (0.7 * Math.sin(2 * Math.PI * 150 * i / sr));
            File tmp = new File(context.getCacheDir(), "vp_selftest.wav");
            writeWav(tmp, samples, sr);
            long t0 = System.currentTimeMillis();
            float[] emb = embedding(tmp);
            long ms = System.currentTimeMillis() - t0;
            tmp.delete();
            float sim = cosine(emb, emb);
            return "OK dim=" + emb.length + " time=" + ms + "ms selfSim=" + sim;
        } catch (Throwable e) {
            return "FAIL: " + e;
        }
    }

    private static void writeWav(File file, float[] samples, int sampleRate) throws Exception {
        java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream();
        for (float v : samples) {
            short s = (short) Math.max(-32767, Math.min(32767, Math.round(v * 32767f)));
            pcm.write(s & 0xFF);
            pcm.write((s >> 8) & 0xFF);
        }
        byte[] audio = pcm.toByteArray();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
            java.io.ByteArrayOutputStream h = new java.io.ByteArrayOutputStream(44);
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            bb.put("RIFF".getBytes("US-ASCII")).putInt(36 + audio.length).put("WAVE".getBytes("US-ASCII"));
            bb.put("fmt ".getBytes("US-ASCII")).putInt(16).putShort((short) 1).putShort((short) 1);
            bb.putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
            bb.put("data".getBytes("US-ASCII")).putInt(audio.length);
            out.write(bb.array());
            out.write(audio);
        }
    }

    public static String encode(float[] v) {
        if (v == null) return "";
        byte[] buf = new byte[v.length * 4];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        for (float f : v) bb.putFloat(f);
        return Base64.encodeToString(buf, Base64.NO_WRAP);
    }

    public static float[] decode(String s) {
        if (s == null || s.isEmpty()) return null;
        byte[] buf = Base64.decode(s, Base64.NO_WRAP);
        if (buf.length < 4 || buf.length % 4 != 0) return null;
        float[] v = new float[buf.length / 4];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        for (int i = 0; i < v.length; i++) v[i] = bb.getFloat();
        return v;
    }
}
