package cn.fudan.danzhi;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

public class AlarmReceiver extends BroadcastReceiver {
    static final String ACTION = "cn.fudan.danzhi.POLL";

    static void schedule(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("danzhi", Context.MODE_PRIVATE);
        if (!sp.getBoolean("backgroundEnabled", false) || !sp.getBoolean("hasSession", false)) return;
        long period = Math.max(15, sp.getInt("intervalMin", 15)) * 60_000L;
        long now = System.currentTimeMillis();
        long nextAt = sp.getLong("alarmNextAt", 0L);
        // Login/start/enqueuePoll all call schedule(). Keep the soon first fire; do not replace it with 15 min.
        if (nextAt > now + 8_000L && nextAt <= now + period + 5_000L) return;
        long lastPollAt = sp.getLong("lastBackgroundPollAt", 0L);
        boolean overdue = lastPollAt == 0L || now - lastPollAt >= Math.max(20_000L, period / 2);
        long delay = overdue ? Math.min(period, 25_000L) : period;
        long fireAt = now + delay;
        sp.edit().putLong("alarmScheduledAt", now).putLong("alarmNextAt", fireAt).apply();
        Intent i = new Intent(ctx, AlarmReceiver.class).setAction(ACTION);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am == null) return;
        long at = SystemClock.elapsedRealtime() + delay;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
        }
    }

    static void cancel(Context ctx) {
        Intent i = new Intent(ctx, AlarmReceiver.class).setAction(ACTION);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 7, i, PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi == null) return;
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        if (am != null) am.cancel(pi);
        pi.cancel();
        ctx.getSharedPreferences("danzhi", Context.MODE_PRIVATE).edit().remove("alarmNextAt").apply();
    }

    @Override public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        SharedPreferences sp = ctx.getSharedPreferences("danzhi", Context.MODE_PRIVATE);
        if (!sp.getBoolean("backgroundEnabled", false) || !sp.getBoolean("hasSession", false)) return;
        sp.edit().remove("alarmNextAt").apply();
        KeepAliveService.enqueuePoll(ctx);
        schedule(ctx);
    }
}
