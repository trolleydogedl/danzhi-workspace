package cn.fudan.danzhi;

import android.content.Context;
import android.content.SharedPreferences;
import cn.fudan.danzhi.core.TaskGate;
import cn.fudan.danzhi.core.WebFlow;
import org.json.JSONObject;

/** A single process-wide mutable session, guarded for UI and background work alike. No startup network work. */
final class SessionCoordinator {
    interface Action {JSONObject run(FudanClient client)throws Exception;}
    private static final TaskGate GATE=new TaskGate();
    private static final Object CLIENT_LOCK=new Object();
    private static final long WAIT_MS=180_000L;
    private static FudanClient client;
    private SessionCoordinator() {}
    static void resetForTests() {
        if (!BuildConfig.DEBUG) throw new SecurityException("Test reset is debug-only");
        TaskGate.Result<Void> r=GATE.run(()->{synchronized(CLIENT_LOCK){client=null;}return null;});
        if(r.busy||r.error!=null)throw new IllegalStateException("Cannot reset an active session");
    }
    private static FudanClient load(Context context)throws Exception {
        synchronized(CLIENT_LOCK){
            if(client==null){
                FudanClient fresh=new FudanClient();
                SharedPreferences sp=context.getSharedPreferences("danzhi",Context.MODE_PRIVATE);
                CredentialStore vault=new CredentialStore(context);
                fresh.username=sp.getString("username","");fresh.jar.load(vault.get("cookies"));
                fresh.password=vault.get("uisPassword");
                fresh.mailPassword=vault.get("mailPassword");fresh.mailAuthBlocked=sp.getBoolean("mailAuthBlocked",false);
                fresh.mailSid=vault.get("mailSid");
                String last=sp.getString("lastPoll","");
                if(!last.isEmpty()){
                    try {
                        JSONObject poll=new JSONObject(last);
                        org.json.JSONArray items=poll.optJSONArray("items");
                        if(items!=null) fresh.lastItems=items;
                        org.json.JSONArray trash=poll.optJSONArray("trash");
                        if(trash!=null) fresh.lastTrash=trash;
                        org.json.JSONArray courses=poll.optJSONArray("courses");
                        if(courses!=null) fresh.lastCourses=courses;
                    } catch(Exception ignored) {}
                }
                try {
                    org.json.JSONArray hid=new org.json.JSONArray(sp.getString("hiddenIds","[]"));
                    for(int i=0;i<hid.length();i++) fresh.hiddenIds.add(hid.optString(i));
                } catch(Exception ignored) {}
                client=fresh;
            }
            return client;
        }
    }
    static JSONObject run(Context context,Action action) {
        TaskGate.Result<JSONObject> r=GATE.run(()->action.run(load(context.getApplicationContext())), WAIT_MS);
        if(r.busy)return Diagnostics.error(new WebFlow.FlowException("BUSY","上一件事还在进行，请稍后再点"));
        if(r.error!=null)return Diagnostics.error(r.error);
        return r.value;
    }
    /** Snapshot cookies and run without waiting for poll/login. Used for 生活码 / 空教室. */
    static JSONObject runRead(Context context,Action action) {
        try {
            FudanClient live=load(context.getApplicationContext());
            FudanClient snap=new FudanClient();
            synchronized(CLIENT_LOCK){
                snap.username=live.username;
                snap.password=live.password;
                snap.mailPassword=live.mailPassword;
                snap.mailSid=live.mailSid==null?"":live.mailSid;
                snap.jar.load(live.jar.dump());
                snap.jar.viaVpn=live.jar.viaVpn;
            }
            JSONObject result=action.run(snap);
            synchronized(CLIENT_LOCK){
                live.jar.load(snap.jar.dump());
                live.jar.viaVpn=snap.jar.viaVpn;
            }
            return result;
        } catch(Throwable e){
            return Diagnostics.error(e);
        }
    }
    static void save(Context context,FudanClient client,boolean established)throws Exception {
        CredentialStore vault=new CredentialStore(context);vault.put("cookies",client.jar.dump());
        vault.put("mailPassword",client.mailPassword);vault.put("uisPassword",client.password);
        vault.put("mailSid",client.mailSid==null?"":client.mailSid);
        org.json.JSONArray hid=new org.json.JSONArray();
        for(String id:client.hiddenIds) hid.put(id);
        context.getSharedPreferences("danzhi",Context.MODE_PRIVATE).edit().putString("username",client.username)
            .putBoolean("hasSession",established).putBoolean("mailAuthBlocked",client.mailAuthBlocked)
            .putString("hiddenIds",hid.toString())
            .remove("password").remove("totpSecret").remove("cookies").apply();
    }
    static void clear(Context context,FudanClient client) {
        client.jar.clear();client.mfa.clear();client.username="";client.password="";client.mailPassword="";client.mailAuthBlocked=false;
        client.mailSid="";client.mailListConfirmed=false;client.lastItems=new org.json.JSONArray();client.lastTrash=new org.json.JSONArray();client.lastCourses=new org.json.JSONArray();client.hiddenIds.clear();
        new CredentialStore(context).clear();context.getSharedPreferences("danzhi",Context.MODE_PRIVATE).edit().clear().apply();
    }
}