package local.ivand.voicecolorstation;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.json.JSONObject;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;

/** Streaming PCM -> mel -> embedding -> Pomogator. Single audio-worker owner.
 * Feature layout follows dscripka/openWakeWord utils.AudioFeatures (Apache-2.0).
 */
public final class WakeWordDetector implements Closeable {
    public static final int FRAME_SAMPLES = 1280;
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession melSession;
    private final OrtSession embeddingSession;
    private final OrtSession classifierSession;
    private final float[] context = new float[480];
    private final float[] melHistory = new float[76 * 32];
    private final float[] features = new float[16 * 96];
    private int frames;
    private final float threshold;
    private final float referenceScore;
    private final float referenceFeature;
    private final String phrase;
    private final String profile;
    private final String modelHash;

    public WakeWordDetector(File directory) throws OrtException, IOException {
        try {
            JSONObject config = new JSONObject(new String(Files.readAllBytes(
                    new File(directory, "config.json").toPath()), StandardCharsets.UTF_8));
            threshold = (float) config.getDouble("threshold");
            referenceScore = (float) config.getDouble("self_test_score");
            referenceFeature = (float) config.getDouble("self_test_feature");
            phrase = config.getString("phrase");
            profile = config.getString("profile");
            modelHash = config.getString("model_sha256");
            if (!(threshold > 0f && threshold < 1f) || !Float.isFinite(referenceScore)
                    || !Float.isFinite(referenceFeature)) throw new IOException("Invalid wake config");
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(new File(directory, "pomogator.onnx"))) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) sha.update(buffer, 0, count);
            }
            StringBuilder actual = new StringBuilder();
            for (byte value : sha.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02X", value & 255));
            if (!actual.toString().equalsIgnoreCase(modelHash)) throw new IOException("Wake model hash mismatch");
        } catch (Exception error) {
            throw new IOException("Cannot verify wake configuration", error);
        }
        OrtSession mel = null;
        OrtSession embedding = null;
        OrtSession classifier = null;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            mel = environment.createSession(new File(directory, "melspectrogram.onnx").getPath(), options);
            embedding = environment.createSession(new File(directory, "embedding_model.onnx").getPath(), options);
            classifier = environment.createSession(new File(directory, "pomogator.onnx").getPath(), options);
        } catch (OrtException error) {
            if (classifier != null) classifier.close();
            if (embedding != null) embedding.close();
            if (mel != null) mel.close();
            throw error;
        }
        melSession = mel;
        embeddingSession = embedding;
        classifierSession = classifier;
        reset();
    }

    public float threshold() { return threshold; }
    public String phrase() { return phrase; }
    public String identity() { return "profile=" + profile + "; model=" + modelHash + "; threshold=" + threshold; }

    public void reset() {
        Arrays.fill(context, 0f);
        Arrays.fill(melHistory, 1f);
        Arrays.fill(features, 0f);
        frames = 0;
    }

    /** Golden synthetic PCM reference from verify_streaming_reference.py. */
    public float selfTest() throws OrtException {
        reset();
        short[] frame = new short[FRAME_SAMPLES];
        for (int i = 0; i < frame.length; i++) frame[i] = (short) (i % 80 < 40 ? 3000 : -3000);
        float score = 0f;
        for (int i = 0; i < 40; i++) score = process(frame);
        float feature = features[features.length - 96];
        if (Math.abs(score - referenceScore) > 0.0001f
                || Math.abs(feature - referenceFeature) > 0.01f) {
            throw new IllegalStateException("Wake pipeline self-test mismatch: " + score + ", " + feature);
        }
        reset();
        return score;
    }

    private float[] infer(OrtSession session, float[] input, long[] shape) throws OrtException {
        String name = session.getInputNames().iterator().next();
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), shape);
             OrtSession.Result result = session.run(Collections.singletonMap(name, tensor))) {
            FloatBuffer buffer = ((OnnxTensor) result.get(0)).getFloatBuffer();
            float[] values = new float[buffer.remaining()];
            buffer.get(values);
            return values;
        }
    }

    /** Input stays in signed PCM16 amplitude units, not normalized to [-1,1]. */
    public float process(short[] frame) throws OrtException {
        if (frame.length != FRAME_SAMPLES) throw new IllegalArgumentException("Expected 1280 PCM samples");
        float[] pcm = new float[1760];
        System.arraycopy(context, 0, pcm, 0, 480);
        for (int i = 0; i < FRAME_SAMPLES; i++) pcm[i + 480] = frame[i];
        System.arraycopy(pcm, 1280, context, 0, 480);
        float[] mel = infer(melSession, pcm, new long[]{1, pcm.length});
        if (mel.length != 8 * 32) throw new IllegalStateException("Unexpected mel length " + mel.length);
        System.arraycopy(melHistory, mel.length, melHistory, 0, melHistory.length - mel.length);
        for (int i = 0; i < mel.length; i++) melHistory[melHistory.length - mel.length + i] = mel[i] / 10f + 2f;
        float[] embedding = infer(embeddingSession, melHistory, new long[]{1, 76, 32, 1});
        if (embedding.length != 96) throw new IllegalStateException("Unexpected embedding length");
        System.arraycopy(features, 96, features, 0, features.length - 96);
        System.arraycopy(embedding, 0, features, features.length - 96, 96);
        // Fill real acoustic context before allowing detections after a reset.
        if (++frames < 32) return 0f;
        float score = infer(classifierSession, features, new long[]{1, 16, 96})[0];
        if (Float.isNaN(score) || Float.isInfinite(score)) throw new IllegalStateException("Invalid wake score");
        return score;
    }

    @Override public void close() {
        try { classifierSession.close(); } catch (OrtException ignored) { }
        try { embeddingSession.close(); } catch (OrtException ignored) { }
        try { melSession.close(); } catch (OrtException ignored) { }
    }
}
