package local.ivand.voicecolorstation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.StorageService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class VoiceListenerService extends Service implements RecognitionListener {
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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable commandTimeout = new Runnable() {
        @Override public void run() {
            if (!stopping && System.currentTimeMillis() > commandWindowUntil) {
                waitingForWakeWord();
            }
        }
    };
    private Model model;
    private Recognizer recognizer;
    private SpeechService speechService;
    private boolean stopping;
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
        try {
            recognizer = new Recognizer(model, 16000.0f);
            recognizer.setWords(true);
            speechService = new SpeechService(recognizer, 16000.0f);
            if (!speechService.startListening(this)) {
                fail("Микрофон уже занят другим процессом");
                return;
            }
            waitingForWakeWord();
        } catch (IOException error) {
            fail("Не удалось запустить Vosk: " + error.getMessage());
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
        handler.removeCallbacks(commandTimeout);
        commandWindowUntil = 0L;
        publishStatus("Жду: «Помогатор»");
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
            boolean hasWakeWord = isWakePhrase(phrase);
            long now = System.currentTimeMillis();
            if (hasWakeWord) {
                commandWindowUntil = now + COMMAND_WINDOW_MILLIS;
                handler.removeCallbacks(commandTimeout);
                handler.postDelayed(commandTimeout, COMMAND_WINDOW_MILLIS + 100L);
                publishStatus("Слушаю команду: назовите цвет");
            }
            ColorChoice choice = findColor(words);
            if (choice != null && (hasWakeWord || now <= commandWindowUntil)) {
                if (!phrase.equals(lastAppliedPhrase) || now - lastAppliedAt > 1500L) {
                    applyColor(choice, phrase);
                    lastAppliedPhrase = phrase;
                    lastAppliedAt = now;
                }
            } else if (commandWindowUntil != 0L && now > commandWindowUntil) {
                waitingForWakeWord();
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

    private boolean isWakePhrase(String phrase) {
        String compact = phrase.replace(" ", "");
        String firstWord = phrase.split("\\s+", 2)[0];
        return firstWord.equals("помогать")
                || compact.contains("помогатор")
                || compact.contains("помогато")
                || compact.contains("помогамотор");
    }

    private ColorChoice findColor(String[] words) {
        for (String word : words) {
            ColorChoice choice = colors.get(word);
            if (choice != null) return choice;
        }
        return null;
    }

    private void applyColor(ColorChoice choice, String phrase) {
        handler.removeCallbacks(commandTimeout);
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

    @Override public void onPartialResult(String hypothesis) { handleJson(hypothesis, true); }
    @Override public void onResult(String hypothesis) { handleJson(hypothesis, false); }
    @Override public void onFinalResult(String hypothesis) { handleJson(hypothesis, false); }
    @Override public void onError(Exception error) {
        fail("Ошибка микрофона Vosk: " + error.getMessage());
    }
    @Override public void onTimeout() { waitingForWakeWord(); }

    @Override
    public void onDestroy() {
        stopping = true;
        handler.removeCallbacks(commandTimeout);
        if (speechService != null) speechService.shutdown();
        if (recognizer != null) recognizer.close();
        if (model != null) model.close();
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
