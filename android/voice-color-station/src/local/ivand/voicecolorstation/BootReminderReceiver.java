package local.ivand.voicecolorstation;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Boot only prompts the user. Never starts a microphone service from background. */
public final class BootReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || !context.getSharedPreferences("voice_state", Context.MODE_PRIVATE)
                        .getBoolean("listening_enabled", false)) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel("voice-boot-reminder", "Запуск голосовой станции", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(context, 19, new Intent(context, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        manager.notify(72, new Notification.Builder(context, "voice-boot-reminder")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Запустить помощника")
                .setContentText("Нажмите для включения постоянного микрофона после перезагрузки")
                .setContentIntent(open).setAutoCancel(true).build());
    }
}
