package local.ivand.voicecolorstation;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
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
    public static volatile boolean resumed;
    private static final int MICROPHONE_PERMISSION_REQUEST = 1001;

    private LinearLayout root;
    private TextView status;
    private IdleCountdownView countdown;
    private Button pinScreen;
    private final StationScreenIdle screenIdle = new StationScreenIdle(6000L);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable idleCheck;
    private boolean settingsOpen;
    private String serviceStatus = "Загружаю локальную модель…";
    private String lastHeard;
    private boolean lastHeardIsFinal;
    private boolean receiverRegistered;
    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())
                    || Intent.ACTION_POWER_CONNECTED.equals(intent.getAction())
                    || Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {
                refreshForScreenEvent(intent.getAction());
                return;
            }
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
                String phrase = intent.getStringExtra(VoiceListenerService.EXTRA_PHRASE);
                boolean isFinal = intent.getBooleanExtra(VoiceListenerService.EXTRA_IS_FINAL, false);
                if (phrase != null) {
                    screenIdle.speech(phrase, isFinal, SystemClock.elapsedRealtime());
                    if (!phrase.isEmpty()) { lastHeard = phrase; lastHeardIsFinal = isFinal; }
                }
                updateCountdown();
                renderStatus();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setShowWhenLocked(true);
        setTurnScreenOn(ACTION_WAKE_SCREEN.equals(getIntent().getAction()));
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
        countdown = new IdleCountdownView(this);
        int ringSize = Math.round(144f * getResources().getDisplayMetrics().density);
        LinearLayout.LayoutParams ringLayout = new LinearLayout.LayoutParams(ringSize, ringSize);
        ringLayout.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(countdown, ringLayout);
        pinScreen = new Button(this);
        pinScreen.setText("Не гасить экран");
        pinScreen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (!screenIdle.active()) startScreenTimer("pin-button");
                screenIdle.togglePin(SystemClock.elapsedRealtime());
                updateCountdown();
                restartIdleCheck();
                Log.i("Pomogator", "SCREEN_PIN enabled=" + screenIdle.pinned());
            }
        });
        root.addView(pinScreen);
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
        countdown.setForeground(status.getCurrentTextColor());
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
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(serviceReceiver, filter);
        receiverRegistered = true;
    }

    @Override
    protected void onStop() {
        visible = false;
        suspendScreenTimer();
        if (receiverRegistered) {
            unregisterReceiver(serviceReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        restoreLastColor();
        String current = VoiceListenerService.currentStatus;
        if (current != null) { serviceStatus = current; renderStatus(); }
        startScreenTimer("resume");
        enterImmersiveMode();
        if (!ACTION_WAKE_SCREEN.equals(getIntent().getAction())
                && !getSharedPreferences("voice_state", MODE_PRIVATE).getBoolean("background_setup_shown", false)) {
            getSharedPreferences("voice_state", MODE_PRIVATE).edit().putBoolean("background_setup_shown", true).apply();
            status.post(new Runnable() {
                @Override public void run() { if (!isFinishing()) showBackgroundSettings(); }
            });
        }
    }

    @Override protected void onPause() {
        resumed = false;
        suspendScreenTimer();
        setTurnScreenOn(false);
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        boolean wake = ACTION_WAKE_SCREEN.equals(intent.getAction());
        setTurnScreenOn(wake);
        if (wake) {
            lastHeard = null;
        }
        if (screenIdle.active()) screenIdle.interact(SystemClock.elapsedRealtime());
        startScreenTimer("intent");
    }

    private void updateCountdown() {
        countdown.setDeadline(screenIdle.deadline(), 6000L);
        pinScreen.setText(screenIdle.pinned() ? "Вернуть таймер (6 с)" : "Не гасить экран");
        if (screenIdle.pinned()) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private boolean ownsScreen() {
        return visible && resumed && hasWindowFocus() && !settingsOpen && !isFinishing()
                && getSystemService(PowerManager.class).isInteractive();
    }

    private void startScreenTimer(String reason) {
        if (!ownsScreen()) return;
        if (!screenIdle.active()) {
            screenIdle.resume(SystemClock.elapsedRealtime());
            Log.i("Pomogator", "SCREEN_TIMER armed; reason=" + reason + "; timeout=6000ms");
        }
        updateCountdown();
        restartIdleCheck();
    }

    private void refreshForScreenEvent(String reason) {
        if (!ownsScreen()) return;
        if (!screenIdle.active()) startScreenTimer(reason);
        else screenIdle.interact(SystemClock.elapsedRealtime());
        updateCountdown();
        Log.i("Pomogator", "SCREEN_TIMER power/screen event=" + reason + "; pinned=" + screenIdle.pinned());
    }

    @Override public void onUserInteraction() {
        super.onUserInteraction();
        if (!ownsScreen()) return;
        if (!screenIdle.active()) startScreenTimer("interaction");
        screenIdle.interact(SystemClock.elapsedRealtime());
        updateCountdown();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (countdown == null) return;
        if (hasFocus) startScreenTimer("focus"); else suspendScreenTimer();
    }

    private void suspendScreenTimer() {
        if (idleCheck != null) uiHandler.removeCallbacks(idleCheck);
        screenIdle.pause();
        if (countdown != null) updateCountdown();
    }

    private void restartIdleCheck() {
        if (idleCheck != null) uiHandler.removeCallbacks(idleCheck);
        if (!screenIdle.active() || screenIdle.pinned()) return;
        final long session = screenIdle.session();
        idleCheck = new Runnable() {
            @Override public void run() {
                if (!ownsScreen()) { suspendScreenTimer(); return; }
                if (screenIdle.shouldLock(SystemClock.elapsedRealtime(), session, ownsScreen())) {
                    suspendScreenTimer();
                    DevicePolicyManager policy = getSystemService(DevicePolicyManager.class);
                    ComponentName admin = new ComponentName(MainActivity.this, ScreenLockAdminReceiver.class);
                    if (!policy.isAdminActive(admin)) {
                        serviceStatus = "Разрешите блокировку экрана в настройках фоновой работы";
                        renderStatus();
                        Log.w("Pomogator", "SCREEN_OFF blocked: force-lock admin not enabled");
                        return;
                    }
                    try {
                        setTurnScreenOn(false);
                        policy.lockNow();
                        Log.i("Pomogator", "SCREEN_OFF requested by visible UI idle timeout=6000ms; listener retained");
                    } catch (SecurityException error) { Log.w("Pomogator", "SCREEN_OFF denied", error); }
                    return;
                }
                if (screenIdle.session() == session && !screenIdle.pinned()) uiHandler.postDelayed(this, 100L);
            }
        };
        uiHandler.postDelayed(idleCheck, 100L);
    }

    private void showBackgroundSettings() {
        settingsOpen = true;
        suspendScreenTimer();
        PowerManager power = getSystemService(PowerManager.class);
        final ComponentName lockAdmin = new ComponentName(this, ScreenLockAdminReceiver.class);
        DevicePolicyManager policies = getSystemService(DevicePolicyManager.class);
        String[] choices = {
                "Поверх других приложений: " + (Settings.canDrawOverlays(this) ? "разрешено" : "нужно разрешить"),
                "Без экономии батареи: " + (power.isIgnoringBatteryOptimizations(getPackageName()) ? "да" : "нужно разрешить"),
                "Команда «отбой»: " + (policies.isAdminActive(lockAdmin) ? "разрешена" : "разрешить блокировку экрана")};
        new AlertDialog.Builder(this).setTitle("Постоянная голосовая станция")
                .setItems(choices, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                    Intent settings;
                    if (which == 0) settings = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    else if (which == 1) settings = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    else settings = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, lockAdmin)
                            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    "Только погасить и заблокировать экран по команде «отбой». Микрофон продолжает слушать. Для разблокировки может понадобиться PIN/пароль.");
                    try { startActivity(settings); }
                    catch (RuntimeException error) { serviceStatus = "Откройте разрешения вручную в настройках Android"; renderStatus(); }
                    }
                })
                .setNegativeButton("Позже", null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override public void onDismiss(DialogInterface dialog) {
                        settingsOpen = false;
                        startScreenTimer("settings-dismissed");
                    }
                }).show();
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
