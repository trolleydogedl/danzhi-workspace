package cn.fudan.danzhi;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.graphics.Color;
import android.view.View;
import android.webkit.*;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import org.json.JSONObject;
import cn.fudan.danzhi.core.WebFlow;

public class MainActivity extends AppCompatActivity {
    private SharedPreferences sp;
    private WebView web;
    private ActivityResultLauncher<Intent> scanLauncher;
    private volatile boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        sp=getSharedPreferences("danzhi",MODE_PRIVATE);
        migrateLegacyStorage();
        web=new WebView(this);web.setBackgroundColor(Color.parseColor("#FFF6F0"));setContentView(web);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);s.setAllowFileAccessFromFileURLs(false);s.setAllowUniversalAccessFromFileURLs(false);
        s.setBlockNetworkLoads(true);s.setSaveFormData(false);
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return !r.getUrl().toString().startsWith("file:///android_asset/www/");}
        });
        web.addJavascriptInterface(new Bridge(),"Danzhi");
        web.loadUrl("file:///android_asset/www/index.html");
        MailClient.browser=(c,email,pass)->MailBrowser.loginAndList(MainActivity.this,c,email,pass);
        getOnBackPressedDispatcher().addCallback(this,new OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){
                if(web==null)return;
                web.evaluateJavascript("(function(){try{return window.danzhiBack?!!window.danzhiBack():true;}catch(e){return true;}})()",null);
            }
        });
        web.setOnTouchListener((v,e)->{
            float edge=48f*getResources().getDisplayMetrics().density;
            if(e.getActionMasked()==android.view.MotionEvent.ACTION_DOWN && e.getX()<edge){
                v.getParent().requestDisallowInterceptTouchEvent(true);
            }
            return false;
        });
        if(sp.getBoolean("backgroundEnabled",false) && sp.getBoolean("hasSession",false)){
            try { KeepAliveService.start(this); } catch(RuntimeException ignored) {}
            try { AlarmReceiver.schedule(this); } catch(RuntimeException ignored) {}
        }
        scanLauncher=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
            IntentResult scan=IntentIntegrator.parseActivityResult(result.getResultCode(),result.getData());
            String code=scan==null?"":scan.getContents();
            if(code==null||code.isEmpty()){
                deliver("onScanResult",new JSONObject(){{try{put("status","cancelled");put("message","已返回");}catch(Exception ignored){}}}.toString());
                return;
            }
            execute("onScanResult",client->EcardClient.scan(client,code));
        });
        // Intentionally no client creation, login, polling, foreground service or network request in onCreate.
    }
    private void migrateLegacyStorage() {
        if(sp.getInt("storageVersion",0)>=2)return;
        try {
            Intent i=new Intent().setClassName(this,"cn.fudan.danzhi.AlarmReceiver").setAction("cn.fudan.danzhi.POLL");
            PendingIntent p=PendingIntent.getBroadcast(this,7,i,PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_IMMUTABLE);
            if(p!=null){AlarmManager a=getSystemService(AlarmManager.class);if(a!=null)a.cancel(p);p.cancel();}
        }catch(RuntimeException ignored){}
        // Legacy cookie serialization lost host/secure/expiry metadata: do not silently widen it during migration.
        sp.edit().remove("cookies").remove("password").remove("totpSecret").remove("lastPoll")
            .putBoolean("hasSession",false).putBoolean("backgroundEnabled",false).putInt("storageVersion",2).apply();
    }
    private void execute(String callback,SessionCoordinator.Action action) { execute(callback,action,""); }
    private void execute(String callback,SessionCoordinator.Action action,String requestId) {
        new Thread(()->{
            JSONObject result=SessionCoordinator.run(getApplicationContext(),action);
            if(!requestId.isEmpty())try{result.put("requestId",requestId);}catch(Exception ignored){}
            deliver(callback,result.toString());
        },"danzhi-action").start();
    }
    private void executeRead(String callback,SessionCoordinator.Action action) {
        new Thread(()->{
            JSONObject result=SessionCoordinator.runRead(getApplicationContext(),action);
            deliver(callback,result.toString());
        },"danzhi-read").start();
    }
    private void deliver(String callback,String json) {
        if(destroyed)return;
        runOnUiThread(()->{if(!destroyed&&web!=null)web.evaluateJavascript("window."+callback+" && window."+callback+"("+JSONObject.quote(json)+")",null);});
    }
    final class Bridge {
        @JavascriptInterface public void login(String user,String password,String unusedSecret,String code,String requestId) {
            execute("onLoginResult",client->{
                if(!client.username.equals(user==null?"":user.trim())){client.mailPassword="";client.mailAuthBlocked=false;}
                JSONObject r=client.login(user,password,"",code);
                if("ok".equals(r.optString("status"))){
                    SessionCoordinator.save(MainActivity.this,client,true);
                    sp.edit().putBoolean("backgroundEnabled",true).apply();
                    runOnUiThread(()->{
                        try {
                            NotifyHelper.ensureChannels(MainActivity.this);
                            if(Build.VERSION.SDK_INT>=33)ActivityCompat.requestPermissions(MainActivity.this,new String[]{Manifest.permission.POST_NOTIFICATIONS},1);
                            PollService.schedule(MainActivity.this);
                            AlarmReceiver.schedule(MainActivity.this);
                            KeepAliveService.start(MainActivity.this);
                            if(!sp.getBoolean("askedBattery",false) && Build.VERSION.SDK_INT>=23){
                                sp.edit().putBoolean("askedBattery",true).apply();
                                try {
                                    Intent batt=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:"+getPackageName()));
                                    startActivity(batt);
                                } catch(RuntimeException ignored) {}
                            }
                        } catch(RuntimeException ignored) {}
                    });
                }
                return r;
            },requestId);
        }
        @JavascriptInterface public void poll(String requestId) {
            execute("onPollResult",client->{
                JSONObject r=client.poll();SessionCoordinator.save(MainActivity.this,client,sp.getBoolean("hasSession",false));
                sp.edit().putString("lastPoll",r.toString()).apply();return r;
            },requestId);
        }
        @JavascriptInterface public String saved() {
            try {
                JSONObject o=new JSONObject().put("username",sp.getString("username",""))
                    .put("hasSession",sp.getBoolean("hasSession",false)).put("hasPassword",false).put("hasSecret",false)
                    .put("intervalMin",Math.max(15,sp.getInt("intervalMin",15)))
                    .put("backgroundEnabled",sp.getBoolean("backgroundEnabled",false))
                    .put("smsPhone",sp.getString("smsPhone","")).put("version",BuildConfig.VERSION_NAME);
                String last=sp.getString("lastPoll","");if(!last.isEmpty())o.put("lastPoll",new JSONObject(last));
                return o.toString();
            }catch(Exception e){return Diagnostics.error(e).toString();}
        }
        @JavascriptInterface public void logout() {
            execute("onLogout",client->{SessionCoordinator.clear(MainActivity.this,client);PollService.cancel(MainActivity.this);AlarmReceiver.cancel(MainActivity.this);KeepAliveService.stop(MainActivity.this);return new JSONObject().put("status","ok");});
        }
        @JavascriptInterface public void setMailPassword(String secret) {
            execute("onMailSettings",client->{
                client.mailPassword=secret==null?"":secret;client.mailAuthBlocked=false;
                SessionCoordinator.save(MainActivity.this,client,sp.getBoolean("hasSession",false));
                return new JSONObject().put("status","ok").put("message","邮箱凭据已安全保存，未自动发起登录");
            });
        }
        @JavascriptInterface public void setInterval(int minutes) {
            int min=Math.max(15,Math.min(180,minutes));sp.edit().putInt("intervalMin",min).apply();
            if(sp.getBoolean("backgroundEnabled",false)){
                try{PollService.schedule(MainActivity.this);}catch(RuntimeException e){deliver("onSettings",Diagnostics.error(e).toString());}
                AlarmReceiver.schedule(MainActivity.this);
            }
        }
        @JavascriptInterface public void setBackgroundEnabled(boolean enabled) {
            runOnUiThread(()->{
                try {
                    if(enabled&&!sp.getBoolean("hasSession",false))throw new IllegalStateException("请先完成登录");
                    sp.edit().putBoolean("backgroundEnabled",enabled).apply();
                    if(enabled){
                        NotifyHelper.ensureChannels(MainActivity.this);
                        if(Build.VERSION.SDK_INT>=33)ActivityCompat.requestPermissions(MainActivity.this,new String[]{Manifest.permission.POST_NOTIFICATIONS},1);
                        PollService.schedule(MainActivity.this);
                        AlarmReceiver.schedule(MainActivity.this);
                        KeepAliveService.start(MainActivity.this);
                    } else {
                        PollService.cancel(MainActivity.this);
                        AlarmReceiver.cancel(MainActivity.this);
                        KeepAliveService.stop(MainActivity.this);
                    }
                    deliver("onSettings",new JSONObject().put("status","ok").put("message",enabled?"后台巡检已启用。请允许通知并无限制电池。":"后台巡检已关闭").toString());
                }catch(Exception | LinkageError e){sp.edit().putBoolean("backgroundEnabled",false).apply();deliver("onSettings",Diagnostics.error(e).toString());}
            });
        }
        @JavascriptInterface public void setSmsPhone(String phone){
            sp.edit().putString("smsPhone",phone==null?"":phone.trim()).apply();
            if(phone!=null&&!phone.trim().isEmpty())runOnUiThread(()->ActivityCompat.requestPermissions(MainActivity.this,new String[]{Manifest.permission.SEND_SMS},2));
        }
        @JavascriptInterface public void refreshQr(){ getQr("1"); }
        @JavascriptInterface public void getQr(String forceFlag){
            boolean force="1".equals(forceFlag)||"true".equalsIgnoreCase(forceFlag)||forceFlag==null||forceFlag.isEmpty();
            executeRead("onQr",client->EcardClient.fetchQr(client,force));
        }
        @JavascriptInterface public void emptyRooms(String building,String day){executeRead("onRooms",client->RoomsClient.query(client,building,day));}
        @JavascriptInterface public String buildings(){return RoomsClient.buildings().toString();}
        @JavascriptInterface public void startScan(){runOnUiThread(()->{
            try{IntentIntegrator i=new IntentIntegrator(MainActivity.this);i.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
                i.setCaptureActivity(ScanActivity.class);
                i.setPrompt("对准二维码。点左上角返回");i.setBeepEnabled(false);i.setOrientationLocked(true);
                scanLauncher.launch(i.createScanIntent());
            }catch(RuntimeException | LinkageError e){deliver("onScanResult",Diagnostics.error(e).toString());}
        });}
        @JavascriptInterface public void readItem(String id){execute("onReadItem",client->client.readItem(id));}
        @JavascriptInterface public void trashItems(String idsJson){execute("onTrashItems",client->{
            JSONObject r=client.trashItems(idsJson);
            SessionCoordinator.save(MainActivity.this,client,sp.getBoolean("hasSession",false));
            try {
                JSONObject snap=new JSONObject();
                snap.put("status","ok");
                snap.put("items",r.optJSONArray("items"));
                snap.put("trash",r.optJSONArray("trash"));
                String last=sp.getString("lastPoll","");
                if(!last.isEmpty()){
                    JSONObject prev=new JSONObject(last);
                    prev.put("items",r.optJSONArray("items"));
                    prev.put("trash",r.optJSONArray("trash"));
                    sp.edit().putString("lastPoll",prev.toString()).apply();
                }
            } catch(Exception ignored) {}
            return r;
        });}
        @JavascriptInterface public void requestKeepAlive(){runOnUiThread(()->{
            try{
                if(Build.VERSION.SDK_INT>=23){
                    Intent batt=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:"+getPackageName()));
                    startActivity(batt);
                }
            }catch(RuntimeException ignored){}
            try{startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));}
            catch(RuntimeException e){deliver("onSettings",Diagnostics.error(e).toString());}
        });}
        @JavascriptInterface public void openUrl(String url){runOnUiThread(()->{
            try{Uri u=Uri.parse(url);if(!"https".equals(u.getScheme()))throw new IllegalArgumentException("只允许打开 HTTPS 网页");
                startActivity(new Intent(Intent.ACTION_VIEW,u));
            }catch(RuntimeException e){deliver("onSettings",Diagnostics.error(e).toString());}
        });}
    }
    @Override protected void onStart(){
        super.onStart();
        if(sp!=null && sp.getBoolean("backgroundEnabled",false) && sp.getBoolean("hasSession",false)){
            try { KeepAliveService.start(this); } catch(RuntimeException ignored) {}
            try { AlarmReceiver.schedule(this); } catch(RuntimeException ignored) {}
        }
    }
    @Override protected void onDestroy(){destroyed=true;if(web!=null){web.removeJavascriptInterface("Danzhi");web.destroy();web=null;}super.onDestroy();}
}
