package local.ivand.voicecolorstation;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.PowerManager;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Button;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    public static final String ACTION_WAKE_SCREEN = "local.ivand.voicecolorstation.WAKE_SCREEN";
    public static volatile boolean visible;
    private static final int MICROPHONE_PERMISSION_REQUEST = 1001;

    private LinearLayout root;
    private TextView status;
    private String serviceStatus = "Загружаю локальную модель…";
    private String lastHeard;
    private boolean lastHeardIsFinal;
    private boolean receiverRegistered;
    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (VoiceListenerService.ACTION_COLOR_CHANGED.equals(intent.getAction())) {
                applyColor(intent.getIntExtra(VoiceListenerService.EXTRA_COLOR,
                                Color.rgb(20, 28, 42)),
                        intent.getStringExtra(VoiceListenerService.EXTRA_COLOR_NAME),
                        intent.getStringExtra(VoiceListenerService.EXTRA_PHRASE));
            } else if (VoiceListenerService.ACTION_STATUS_CHANGED.equals(intent.getAction())) {
                String message = intent.getStringExtra(VoiceListenerService.EXTRA_STATUS);
                if (message != null) {
                    serviceStatus = message;
                    renderStatus();
                }
            } else if (VoiceListenerService.ACTION_PHRASE_HEARD.equals(intent.getAction())) {
                lastHeard = intent.getStringExtra(VoiceListenerService.EXTRA_PHRASE);
                lastHeardIsFinal = intent.getBooleanExtra(
                        VoiceListenerService.EXTRA_IS_FINAL, false);
                renderStatus();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(ACTION_WAKE_SCREEN.equals(getIntent().getAction()));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        enterImmersiveMode();
        createInterface();
        restoreLastColor();
        ensureMicrophonePermission();
    }

    private void createInterface() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(42, 72, 42, 72);
        root.setBackgroundColor(Color.rgb(20, 28, 42));

        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(27f);
        status.setTextColor(Color.WHITE);
        status.setText("Загружаю локальную модель…\n"
                + "Скажите «Эй, Помогатор», дождитесь «Слушаю команду», затем назовите цвет");
        root.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f));
        Button backgroundSettings = new Button(this);
        backgroundSettings.setText("Настройки фоновой работы");
        backgroundSettings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showBackgroundSettings(); }
        });
        root.addView(backgroundSettings);
        setContentView(root);
    }

    private void ensureMicrophonePermission() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startVoiceService();
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    MICROPHONE_PERMISSION_REQUEST);
        }
    }

    private void startVoiceService() {
        getSystemService(android.app.NotificationManager.class).cancel(72);
        getSharedPreferences("voice_state", MODE_PRIVATE).edit().putBoolean("listening_enabled", true).apply();
        serviceStatus = "Запускаю детектор обращения и Vosk…";
        renderStatus();
        startForegroundService(new Intent(this, VoiceListenerService.class));
    }

    private void restoreLastColor() {
        int value = getSharedPreferences("voice_state", MODE_PRIVATE)
                .getInt("color_value",
                Color.rgb(20, 28, 42));
        String name = getSharedPreferences("voice_state", MODE_PRIVATE)
                .getString("color_name", null);
        if (name != null) applyColor(value, name, null);
    }

    private void applyColor(int value, String name, String phrase) {
        root.setBackgroundColor(value);
        double luminance = (0.299 * Color.red(value)
                + 0.587 * Color.green(value)
                + 0.114 * Color.blue(value)) / 255.0;
        status.setTextColor(luminance > 0.62 ? Color.BLACK : Color.WHITE);
        if (phrase != null) {
            lastHeard = phrase;
            lastHeardIsFinal = true;
        }
        serviceStatus = "Экран теперь " + name + ".\nФоновый режим продолжает слушать.";
        renderStatus();
        getSharedPreferences("voice_state", MODE_PRIVATE).edit()
                .putInt("color_value", value)
                .putString("color_name", name)
                .apply();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MICROPHONE_PERMISSION_REQUEST) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) startVoiceService();
            else status.setText("Без разрешения на микрофон фоновый режим не работает.");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(VoiceListenerService.ACTION_COLOR_CHANGED);
        filter.addAction(VoiceListenerService.ACTION_STATUS_CHANGED);
        filter.addAction(VoiceListenerService.ACTION_PHRASE_HEARD);
        registerReceiver(serviceReceiver, filter);
        receiverRegistered = true;
    }

    @Override
    protected void onStop() {
        visible = false;
        if (receiverRegistered) {
            unregisterReceiver(serviceReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreLastColor();
        String current = VoiceListenerService.currentStatus;
        if (current != null) { serviceStatus = current; renderStatus(); }
        enterImmersiveMode();
        if (!ACTION_WAKE_SCREEN.equals(getIntent().getAction())
                && !getSharedPreferences("voice_state", MODE_PRIVATE).getBoolean("background_setup_shown", false)) {
            getSharedPreferences("voice_state", MODE_PRIVATE).edit().putBoolean("background_setup_shown", true).apply();
            status.post(new Runnable() {
                @Override public void run() { if (!isFinishing()) showBackgroundSettings(); }
            });
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_WAKE_SCREEN.equals(intent.getAction())) {
            setTurnScreenOn(true);
            lastHeard = null;
        }
    }

    private void showBackgroundSettings() {
        PowerManager power = getSystemService(PowerManager.class);
        String[] choices = {
                "Поверх других приложений: " + (Settings.canDrawOverlays(this) ? "разрешено" : "нужно разрешить"),
                "Без экономии батареи: " + (power.isIgnoringBatteryOptimizations(getPackageName()) ? "да" : "нужно разрешить")};
        new AlertDialog.Builder(this).setTitle("Постоянная голосовая станция")
                .setItems(choices, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                    Intent settings;
                    if (which == 0) settings = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    else settings = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    try { startActivity(settings); }
                    catch (RuntimeException error) { serviceStatus = "Откройте разрешения вручную в настройках Android"; renderStatus(); }
                    }
                })
                .setNegativeButton("Позже", null).show();
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void renderStatus() {
        String heard = "";
        if (lastHeard != null && !lastHeard.isEmpty()) {
            heard = (lastHeardIsFinal ? "Услышал: «" : "Слышу: «")
                    + lastHeard + "»\n\n";
        }
        status.setText(heard + serviceStatus);
    }
}
