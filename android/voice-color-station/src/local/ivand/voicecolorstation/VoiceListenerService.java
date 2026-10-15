package local.ivand.voicecolorstation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class VoiceListenerService extends Service implements RecognitionListener {
    public static final String ACTION_COLOR_CHANGED =
            "local.ivand.voicecolorstation.COLOR_CHANGED";
    public static final String EXTRA_COLOR = "color";
    public static final String EXTRA_COLOR_NAME = "color_name";
    public static final String EXTRA_PHRASE = "phrase";

    private static final String ACTION_STOP =
            "local.ivand.voicecolorstation.STOP_LISTENING";
    private static final String CHANNEL_ID = "voice-listener-silent-v2";
    private static final int NOTIFICATION_ID = 71;
    private static final String WAKE_WORD = "помогатор";

    private final Map<String, ColorChoice> colors = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable restartListening = new Runnable() {
        @Override public void run() { startListening(); }
    };

    private SpeechRecognizer recognizer;
    private TextToSpeech textToSpeech;
    private boolean listening;
    private boolean waitingForCommand;
    private boolean speaking;
    private boolean stopping;

    @Override
    public void onCreate() {
        super.onCreate();
        registerColors();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Жду «Помогатор»"));

        textToSpeech = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override public void onInit(int initStatus) {
                if (initStatus == TextToSpeech.SUCCESS && textToSpeech != null) {
                    textToSpeech.setLanguage(new Locale("ru", "RU"));
                    textToSpeech.setOnUtteranceProgressListener(
                            new UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) { }
                        @Override public void onError(String utteranceId) {
                            resumeAfterSpeech();
                        }
                        @Override public void onDone(String utteranceId) {
                            resumeAfterSpeech();
                        }
                    });
                }
            }
        });

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
            scheduleListening(300);
        } else {
            updateNotification("Распознаватель речи недоступен");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopping = true;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!stopping) scheduleListening(250);
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Фоновое распознавание", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Показывает, что Помогатор использует микрофон");
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
        Intent stopIntent = new Intent(this, VoiceListenerService.class)
                .setAction(ACTION_STOP);
        PendingIntent stopService = PendingIntent.getService(this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Помогатор слушает")
                .setContentText(message)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_delete, "Остановить", stopService).build())
                .build();
    }

    private void updateNotification(String message) {
        getSystemService(NotificationManager.class)
                .notify(NOTIFICATION_ID, buildNotification(message));
    }

    private void registerColors() {
        addColor("красн", "красный", Color.rgb(220, 35, 45));
        addColor("зелён", "зелёный", Color.rgb(25, 180, 85));
        addColor("зелен", "зелёный", Color.rgb(25, 180, 85));
        addColor("голуб", "голубой", Color.rgb(30, 180, 230));
        addColor("син", "синий", Color.rgb(25, 75, 210));
        addColor("жёлт", "жёлтый", Color.rgb(250, 215, 45));
        addColor("желт", "жёлтый", Color.rgb(250, 215, 45));
        addColor("оранж", "оранжевый", Color.rgb(245, 125, 25));
        addColor("фиолет", "фиолетовый", Color.rgb(125, 55, 190));
        addColor("розов", "розовый", Color.rgb(245, 90, 165));
        addColor("бел", "белый", Color.WHITE);
        addColor("сер", "серый", Color.rgb(110, 115, 125));
        addColor("чёрн", "чёрный", Color.BLACK);
        addColor("черн", "чёрный", Color.BLACK);
    }

    private void addColor(String stem, String name, int value) {
        colors.put(stem, new ColorChoice(name, value));
    }

    private void startListening() {
        if (stopping || speaking || listening || recognizer == null) return;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false);
        recognizer.startListening(intent);
    }

    private void handleResults(ArrayList<String> phrases) {
        if (phrases == null || phrases.isEmpty()) {
            waitForWakeWord(700);
            return;
        }
        for (String phrase : phrases) {
            String normalized = phrase.toLowerCase(new Locale("ru", "RU"));
            if (!waitingForCommand && !normalized.contains(WAKE_WORD)) continue;
            ColorChoice choice = findColor(normalized);
            if (choice != null) {
                applyColor(choice, phrase);
                return;
            }
            if (normalized.contains(WAKE_WORD)) {
                waitingForCommand = true;
                updateNotification("Слушаю команду: назовите цвет");
                scheduleListening(350);
                return;
            }
        }
        if (waitingForCommand) {
            updateNotification("Цвет не найден — повторите");
            scheduleListening(500);
        } else {
            waitForWakeWord(400);
        }
    }

    private ColorChoice findColor(String normalized) {
        for (Map.Entry<String, ColorChoice> entry : colors.entrySet()) {
            if (normalized.contains(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private void applyColor(ColorChoice choice, String phrase) {
        waitingForCommand = false;
        getSharedPreferences("voice_state", MODE_PRIVATE).edit()
                .putInt("color_value", choice.value)
                .putString("color_name", choice.name)
                .apply();
        sendBroadcast(new Intent(ACTION_COLOR_CHANGED)
                .setPackage(getPackageName())
                .putExtra(EXTRA_COLOR, choice.value)
                .putExtra(EXTRA_COLOR_NAME, choice.name)
                .putExtra(EXTRA_PHRASE, phrase));
        updateNotification("Выполнено: экран " + choice.name);

        if (textToSpeech != null) {
            speaking = true;
            int result = textToSpeech.speak("Экран теперь " + choice.name,
                    TextToSpeech.QUEUE_FLUSH, null, "color-confirmation");
            if (result == TextToSpeech.ERROR) {
                speaking = false;
                waitForWakeWord(900);
            }
        } else {
            waitForWakeWord(900);
        }
    }

    private void scheduleListening(long delayMillis) {
        handler.removeCallbacks(restartListening);
        if (!stopping && !speaking) handler.postDelayed(restartListening, delayMillis);
    }

    private void waitForWakeWord(long delayMillis) {
        waitingForCommand = false;
        updateNotification("Жду «Помогатор»");
        scheduleListening(delayMillis);
    }

    private void resumeAfterSpeech() {
        handler.post(new Runnable() {
            @Override public void run() {
                speaking = false;
                waitForWakeWord(500);
            }
        });
    }

    @Override public void onReadyForSpeech(Bundle params) { listening = true; }
    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onError(int error) {
        listening = false;
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            updateNotification("Нет разрешения на микрофон");
            return;
        }
        if (error == SpeechRecognizer.ERROR_NETWORK
                || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT) {
            updateNotification("Ошибка сети — повторяю");
            scheduleListening(2500);
            return;
        }
        waitForWakeWord(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1500 : 800);
    }
    @Override public void onResults(Bundle results) {
        listening = false;
        handleResults(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION));
    }
    @Override public void onPartialResults(Bundle partialResults) { }
    @Override public void onEvent(int eventType, Bundle params) { }

    @Override
    public void onDestroy() {
        stopping = true;
        handler.removeCallbacks(restartListening);
        if (recognizer != null) {
            recognizer.cancel();
            recognizer.destroy();
        }
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
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
