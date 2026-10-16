package local.ivand.voicecolorstation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class VoiceListenerService extends Service {
    public static final String ACTION_COLOR_CHANGED =
            "local.ivand.voicecolorstation.COLOR_CHANGED";
    public static final String ACTION_STATUS_CHANGED =
            "local.ivand.voicecolorstation.STATUS_CHANGED";
    public static final String ACTION_PHRASE_HEARD =
            "local.ivand.voicecolorstation.PHRASE_HEARD";
    public static final String EXTRA_COLOR = "color";
    public static final String EXTRA_COLOR_NAME = "color_name";
    public static final String EXTRA_PHRASE = "phrase";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_IS_FINAL = "is_final";

    private static final String ACTION_STOP =
            "local.ivand.voicecolorstation.STOP_LISTENING";
    private static final String CHANNEL_ID = "local-vosk-listener-v1";
    private static final int NOTIFICATION_ID = 71;
    private static final long COMMAND_WINDOW_MILLIS = 6000L;
    private static final String MODEL_ASSET = "vosk-model-small-ru-0.22";
    private static final String MODEL_TARGET = "vosk-model-small-ru";

    private final Map<String, ColorChoice> colors = new LinkedHashMap<>();
    private static final String TAG = "Pomogator";
    private String wakePhrase = "Эй, Помогатор";
    private Model model;
    private Recognizer recognizer;
    private Thread audioThread;
    private volatile AudioRecord audioRecord;
    private volatile boolean stopping;
    private long commandWindowUntil;
    private String lastAppliedPhrase = "";
    private long lastAppliedAt;

    @Override
    public void onCreate() {
        super.onCreate();
        registerColors();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Загружаю локальную модель…"));
        publishStatus("Загружаю локальную модель Vosk…");
        loadModel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopping = true;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void loadModel() {
        StorageService.unpack(this, MODEL_ASSET, MODEL_TARGET,
                new StorageService.Callback<Model>() {
                    @Override public void onComplete(Model unpackedModel) {
                        if (stopping) {
                            unpackedModel.close();
                            return;
                        }
                        model = unpackedModel;
                        startRecognizer();
                    }
                }, new StorageService.Callback<IOException>() {
                    @Override public void onComplete(IOException error) {
                        fail("Не удалось загрузить модель: " + error.getMessage());
                    }
                });
    }

    private void startRecognizer() {
        audioThread = new Thread(new Runnable() {
            @Override public void run() { audioLoop(); }
        }, "Pomogator-audio");
        audioThread.start();
    }

    private File prepareWakeModels() throws IOException {
        File directory = new File(getFilesDir(), "wakeword-v1");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create wakeword directory");
        for (String name : new String[]{"pomogator.onnx", "melspectrogram.onnx", "embedding_model.onnx", "config.json"}) {
            try (InputStream input = getAssets().open("wakeword/" + name);
                 FileOutputStream output = new FileOutputStream(new File(directory, name))) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            }
        }
        return directory;
    }

    private void audioLoop() {
        AudioRecord recorder = null;
        try (WakeWordDetector detector = new WakeWordDetector(prepareWakeModels())) {
            wakePhrase = detector.phrase();
            final float wakeThreshold = detector.threshold();
            Log.i(TAG, "SELF_TEST passed; score=" + detector.selfTest() + "; " + detector.identity());
            recognizer = new Recognizer(model, 16000f);
            recognizer.setWords(true);
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IOException("Invalid microphone buffer: " + minimum);
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum, WakeWordDetector.FRAME_SAMPLES * 8));
            audioRecord = recorder;
            if (stopping) return;
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("Microphone unavailable");
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IOException("Microphone did not start");
            short[] frame = new short[WakeWordDetector.FRAME_SAMPLES];
            short[] preRoll = new short[6400];
            int preRollWrite = 0;
            long lastScoreAt = 0L;
            float peak = 0f;
            waitingForWakeWord();
            Log.i(TAG, "READY: PCM16 16000Hz, single AudioRecord; " + detector.identity());
            while (!stopping) {
                int offset = 0;
                while (offset < frame.length && !stopping) {
                    int count = recorder.read(frame, offset, frame.length - offset, AudioRecord.READ_BLOCKING);
                    if (count <= 0) {
                        if (stopping) return;
                        throw new IOException("AudioRecord read error " + count);
                    }
                    offset += count;
                }
                if (stopping) break;
                for (short value : frame) {
                    preRoll[preRollWrite] = value;
                    preRollWrite = (preRollWrite + 1) % preRoll.length;
                }
                long now = SystemClock.elapsedRealtime();
                if (commandWindowUntil != 0L) {
                    boolean complete = recognizer.acceptWaveForm(frame, frame.length);
                    handleJson(complete ? recognizer.getResult() : recognizer.getPartialResult(), !complete);
                    if (commandWindowUntil == 0L) {
                        recognizer.reset();
                        detector.reset();
                    } else if (now >= commandWindowUntil) {
                        handleJson(recognizer.getFinalResult(), false);
                        recognizer.reset();
                        detector.reset();
                        if (commandWindowUntil != 0L) waitingForWakeWord();
                    }
                } else {
                    float score = detector.process(frame);
                    peak = Math.max(peak, score);
                    if (score >= wakeThreshold) {
                        recognizer.reset();
                        short[] lead = new short[preRoll.length];
                        for (int i = 0; i < lead.length; i++) lead[i] = preRoll[(preRollWrite + i) % lead.length];
                        recognizer.acceptWaveForm(lead, lead.length);
                        commandWindowUntil = now + COMMAND_WINDOW_MILLIS;
                        publishHeard(wakePhrase + " — сработал детектор", true);
                        publishStatus(wakePhrase + " услышан. Слушаю команду…");
                        Log.i(TAG, "WAKE score=" + score + "; command window=6000ms");
                    } else if (now - lastScoreAt >= 1000L) {
                        // Scores are UI telemetry; avoid updating Android's notification every second.
                        sendBroadcast(new Intent(ACTION_STATUS_CHANGED).setPackage(getPackageName())
                                .putExtra(EXTRA_STATUS, String.format(Locale.ROOT,
                                        "Жду: «%s» · %.2f / %.2f", wakePhrase, peak, wakeThreshold)));
                        Log.d(TAG, "WAIT peak=" + peak);
                        peak = 0f;
                        lastScoreAt = now;
                    }
                }
            }
        } catch (Exception error) {
            if (!stopping) {
                Log.e(TAG, "Audio pipeline failed", error);
                fail("Ошибка голосовой станции: " + error.getMessage());
            }
        } finally {
            if (recorder != null) {
                try { recorder.stop(); } catch (IllegalStateException ignored) { }
                recorder.release();
            }
            audioRecord = null;
            if (recognizer != null) recognizer.close();
            if (model != null) model.close();
            Log.i(TAG, "Audio pipeline closed");
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Локальное распознавание Vosk", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Показывает, что голосовая станция использует микрофон");
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification(String message) {
        PendingIntent openApp = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stopService = PendingIntent.getService(this, 2,
                new Intent(this, VoiceListenerService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Локальный помощник слушает")
                .setContentText(message)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_delete, "Остановить", stopService).build())
                .build();
    }

    private void publishStatus(String message) {
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, buildNotification(message));
        sendBroadcast(new Intent(ACTION_STATUS_CHANGED)
                .setPackage(getPackageName())
                .putExtra(EXTRA_STATUS, message));
    }

    private void fail(String message) {
        publishStatus(message);
        stopSelf();
    }

    private void waitingForWakeWord() {
        commandWindowUntil = 0L;
        publishStatus("Жду: «" + wakePhrase + "»");
    }

    private void registerColors() {
        addColor("красный", Color.rgb(220, 35, 45), "красный", "красным");
        addColor("зелёный", Color.rgb(25, 180, 85),
                "зелёный", "зеленый", "зелёным", "зеленым");
        addColor("голубой", Color.rgb(30, 180, 230), "голубой", "голубым");
        addColor("синий", Color.rgb(25, 75, 210), "синий", "синим");
        addColor("жёлтый", Color.rgb(250, 215, 45),
                "жёлтый", "желтый", "жёлтым", "желтым");
        addColor("оранжевый", Color.rgb(245, 125, 25),
                "оранжевый", "оранжевым");
        addColor("фиолетовый", Color.rgb(125, 55, 190),
                "фиолетовый", "фиолетовым");
        addColor("розовый", Color.rgb(245, 90, 165), "розовый", "розовым");
        addColor("белый", Color.WHITE, "белый", "белым");
        addColor("серый", Color.rgb(110, 115, 125), "серый", "серым");
        addColor("чёрный", Color.BLACK,
                "чёрный", "черный", "чёрным", "черным");
    }

    private void addColor(String name, int value, String... recognizedWords) {
        ColorChoice choice = new ColorChoice(name, value);
        for (String word : recognizedWords) colors.put(word, choice);
    }

    private void handleJson(String json, boolean partial) {
        if (stopping) return;
        try {
            JSONObject object = new JSONObject(json);
            String phrase = object.optString(partial ? "partial" : "text", "")
                    .trim().toLowerCase(new Locale("ru", "RU"));
            if (phrase.isEmpty()) return;
            publishHeard(phrase, !partial);
            if (partial) return;
            String[] words = phrase.split("\\s+");
            long now = SystemClock.elapsedRealtime();
            ColorChoice choice = findColor(words);
            if (choice != null && commandWindowUntil != 0L) {
                if (!phrase.equals(lastAppliedPhrase) || now - lastAppliedAt > 1500L) {
                    applyColor(choice, phrase);
                    lastAppliedPhrase = phrase;
                    lastAppliedAt = now;
                }
            }
        } catch (JSONException error) {
            publishStatus("Ошибка ответа Vosk: " + error.getMessage());
        }
    }

    private void publishHeard(String phrase, boolean isFinal) {
        sendBroadcast(new Intent(ACTION_PHRASE_HEARD)
                .setPackage(getPackageName())
                .putExtra(EXTRA_PHRASE, phrase)
                .putExtra(EXTRA_IS_FINAL, isFinal));
    }

    private ColorChoice findColor(String[] words) {
        for (String word : words) {
            ColorChoice choice = colors.get(word);
            if (choice != null) return choice;
        }
        return null;
    }

    private void applyColor(ColorChoice choice, String phrase) {
        commandWindowUntil = 0L;
        getSharedPreferences("voice_state", MODE_PRIVATE).edit()
                .putInt("color_value", choice.value)
                .putString("color_name", choice.name)
                .apply();
        sendBroadcast(new Intent(ACTION_COLOR_CHANGED)
                .setPackage(getPackageName())
                .putExtra(EXTRA_COLOR, choice.value)
                .putExtra(EXTRA_COLOR_NAME, choice.name)
                .putExtra(EXTRA_PHRASE, phrase));
        publishStatus("Выполнено: экран " + choice.name);
    }

    @Override
    public void onDestroy() {
        stopping = true;
        AudioRecord recorder = audioRecord;
        if (recorder != null) {
            try { recorder.stop(); } catch (IllegalStateException ignored) { }
        }
        // The worker owns model/session handles and closes them after its read loop exits.
        super.onDestroy();
    }

    private static final class ColorChoice {
        final String name;
        final int value;
        ColorChoice(String name, int value) {
            this.name = name;
            this.value = value;
        }
    }
}
