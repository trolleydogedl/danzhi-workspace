package cn.fudan.danzhi;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.telephony.SmsManager;
import org.json.JSONArray;
import org.json.JSONObject;

/** Sticky foreground keeper so OEM task killers do not silently drop JobScheduler. */
public class KeepAliveService extends Service {
    static final String ACTION_POLL = "cn.fudan.danzhi.KEEP_POLL";
    static final int NOTE_ID = 17;
    private volatile Thread worker;
    private volatile Thread loop;
    private volatile boolean running;

    static void start(Context ctx) {
        Intent i = new Intent(ctx, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    static void enqueuePoll(Context ctx) {
        Intent i = new Intent(ctx, KeepAliveService.class).setAction(ACTION_POLL);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }

    static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, KeepAliveService.class));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotifyHelper.ensureChannels(this);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTE_ID, NotifyHelper.foreground(this, "后台巡检已开启"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTE_ID, NotifyHelper.foreground(this, "后台巡检已开启"));
            }
        } catch (RuntimeException ignored) {}
        SharedPreferences sp = getSharedPreferences("danzhi", MODE_PRIVATE);
        if (!sp.getBoolean("backgroundEnabled", false) || !sp.getBoolean("hasSession", false)) {
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        AlarmReceiver.schedule(this);
        startLoop();
        if (intent != null && ACTION_POLL.equals(intent.getAction())) runPoll();
        return START_STICKY;
    }

    private void startLoop() {
        if (loop != null && loop.isAlive()) return;
        loop = new Thread(() -> {
            while (running && !Thread.currentThread().isInterrupted()) {
                SharedPreferences sp = getSharedPreferences("danzhi", MODE_PRIVATE);
                long period = Math.max(15, sp.getInt("intervalMin", 15)) * 60_000L;
                try { Thread.sleep(period); }
                catch (InterruptedException e) { return; }
                if (!running) return;
                if (!sp.getBoolean("backgroundEnabled", false) || !sp.getBoolean("hasSession", false)) {
                    stopSelf();
                    return;
                }
                runPoll();
            }
        }, "danzhi-loop");
        loop.start();
    }

    private void runPoll() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(() -> {
            SharedPreferences sp = getSharedPreferences("danzhi", MODE_PRIVATE);
            try {
                JSONObject r = SessionCoordinator.run(this, client -> {
                    JSONObject result = client.poll();
                    SessionCoordinator.save(this, client, true);
                    return result;
                });
                if ("error".equals(r.optString("status"))) {
                    int n = sp.getInt("backgroundFailures", 0) + 1;
                    sp.edit().putInt("backgroundFailures", n).putString("backgroundError", r.optString("message")).apply();
                } else {
                    sp.edit().putInt("backgroundFailures", 0).remove("backgroundError").putString("lastPoll", r.toString()).apply();
                    notifyFresh(this, sp, r);
                }
            } catch (Exception | LinkageError e) {
                sp.edit().putString("backgroundError", Diagnostics.message(e)).apply();
            } finally {
                AlarmReceiver.schedule(this);
            }
        }, "danzhi-keep");
        worker.start();
    }

    static void notifyFresh(Context ctx, SharedPreferences sp, JSONObject result) {
        JSONArray items = result.optJSONArray("items");
        if (items == null) return;
        boolean mailBase = sp.getBoolean("mailBaseline", false);
        boolean ddlBase = sp.getBoolean("ddlBaseline", false);
        java.util.Set<String> mailSeen = new java.util.HashSet<>(sp.getStringSet("mailSeen", java.util.Collections.emptySet()));
        java.util.Set<String> ddlSeen = new java.util.HashSet<>(sp.getStringSet("ddlSeen", java.util.Collections.emptySet()));
        java.util.Set<String> mailNow = new java.util.HashSet<>();
        java.util.Set<String> ddlNow = new java.util.HashSet<>();
        int fresh = 0;
        NotifyHelper.ensureChannels(ctx);
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id");
            if (id.isEmpty()) continue;
            String kind = item.optString("kind");
            if ("mail".equals(kind) || "mail".equals(item.optString("source"))) {
                mailNow.add(id);
                if (mailBase && !mailSeen.contains(id)) {
                    NotifyHelper.alert(ctx, "新邮件 · 旦知", item.optString("title"), 200 + fresh++);
                    String phone = sp.getString("smsPhone", "");
                    if (!phone.isEmpty() && ctx.checkSelfPermission(android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        String text = "旦知新邮件：" + item.optString("title");
                        if (text.length() > 70) text = text.substring(0, 70);
                        try { SmsManager.getDefault().sendTextMessage(phone, null, text, null, null); }
                        catch (RuntimeException e) { sp.edit().putString("smsError", e.getClass().getSimpleName()).apply(); }
                    }
                }
            } else if ("ddl".equals(kind) || "exam".equals(kind)) {
                ddlNow.add(id);
                if (ddlBase && !ddlSeen.contains(id)) {
                    String due = item.optString("dueLabel");
                    String body = due.isEmpty() ? item.optString("title") : due + " · " + item.optString("title");
                    NotifyHelper.alert(ctx, "作业 · 旦知", body, 400 + fresh++);
                }
            }
        }
        mailSeen.addAll(mailNow); if (mailSeen.size() > 2000) mailSeen = mailNow;
        ddlSeen.addAll(ddlNow); if (ddlSeen.size() > 2000) ddlSeen = ddlNow;
        sp.edit().putStringSet("mailSeen", mailSeen).putBoolean("mailBaseline", true)
                .putStringSet("ddlSeen", ddlSeen).putBoolean("ddlBaseline", true).apply();
    }

    @Override public void onDestroy() {
        running = false;
        if (loop != null) loop.interrupt();
        if (worker != null) worker.interrupt();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
