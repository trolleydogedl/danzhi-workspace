package cn.fudan.danzhi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

final class NotifyHelper {
    static final String CH_ALERT = "danzhi-alert";
    static final String CH_FG = "danzhi-fg";

    static void ensureChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        NotificationChannel alert = new NotificationChannel(CH_ALERT, "旦知提醒", NotificationManager.IMPORTANCE_HIGH);
        alert.setDescription("作业、考试与待办");
        NotificationChannel fg = new NotificationChannel(CH_FG, "旦知巡检", NotificationManager.IMPORTANCE_LOW);
        fg.setShowBadge(false);
        nm.createNotificationChannel(alert);
        nm.createNotificationChannel(fg);
    }

    static Notification foreground(Context ctx, String text) {
        Intent i = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(ctx, CH_FG)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("旦知守护中")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    static void alert(Context ctx, String title, String body, int id) {
        Intent i = new Intent(ctx, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, id, i, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(ctx, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        ctx.getSystemService(NotificationManager.class).notify(id, n);
    }
}
