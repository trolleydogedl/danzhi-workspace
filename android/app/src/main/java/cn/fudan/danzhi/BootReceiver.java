package cn.fudan.danzhi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)) return;
        SharedPreferences sp = ctx.getSharedPreferences("danzhi", Context.MODE_PRIVATE);
        if (!sp.getBoolean("backgroundEnabled", false) || !sp.getBoolean("hasSession", false)) return;
        try { PollService.schedule(ctx); } catch (RuntimeException ignored) {}
        try { AlarmReceiver.schedule(ctx); } catch (RuntimeException ignored) {}
        try { KeepAliveService.start(ctx); } catch (RuntimeException ignored) {}
    }
}
