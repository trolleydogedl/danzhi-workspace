package cn.fudan.danzhi;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Hidden WebView for 复旦生活码: #myText is filled by page JS after AJAX. */
final class EcardBrowser {
    static JSONObject fetch(final Activity act, final FudanClient client) throws Exception {
        if (act == null || client == null) return null;
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("ecard browser must run off the UI thread");
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<JSONObject> out = new AtomicReference<>();
        final AtomicReference<Exception> err = new AtomicReference<>();
        final AtomicReference<WebView> held = new AtomicReference<>();
        final Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            try {
                start(act, client, out, err, latch, held);
            } catch (Exception e) {
                err.set(e);
                latch.countDown();
            }
        });
        if (!latch.await(4, TimeUnit.SECONDS)) {
            main.post(() -> destroy(held.get()));
        }
        if (out.get() != null) return out.get();
        if (err.get() != null) throw err.get();
        return null;
    }

    private static void start(Activity act, FudanClient client,
                              AtomicReference<JSONObject> out, AtomicReference<Exception> err,
                              CountDownLatch latch, AtomicReference<WebView> held) {
        final WebView wv = new WebView(act);
        held.set(wv);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(Http.UA);
        wv.setAlpha(0f);
        wv.setWillNotDraw(true);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        try { cm.setAcceptThirdPartyCookies(wv, true); } catch (RuntimeException ignored) {}
        MailBrowser.seed(cm, client.jar);
        final boolean[] finished = {false};
        final int[] tries = {0};
        final Handler main = new Handler(Looper.getMainLooper());
        wv.addJavascriptInterface(new Object() {
            @JavascriptInterface public void payload(String value, String html) {
                harvest(act, wv, client, value, html, out, err, latch, finished);
            }
        }, "DanzhiQr");
        final Runnable poll = new Runnable() {
            @Override public void run() {
                if (finished[0]) return;
                tries[0]++;
                wv.evaluateJavascript(EXTRACT_JS, val -> {
                    if (finished[0]) return;
                    String payload = unquote(val);
                    if ("LOGIN".equals(payload)) {
                        err.set(new cn.fudan.danzhi.core.WebFlow.FlowException("AUTH_REQUIRED",
                                "一卡通未登录。请先巡检信匣，或退出后重新登录再开生活码"));
                        harvest(act, wv, client, "", "", out, err, latch, finished);
                    } else if (payload != null && payload.length() >= 8 && !"null".equals(payload) && !payload.isEmpty()) {
                        harvest(act, wv, client, payload, "", out, err, latch, finished);
                    } else if (tries[0] >= 12) {
                        harvest(act, wv, client, "", "", out, err, latch, finished);
                    } else {
                        main.postDelayed(this, 500);
                    }
                });
            }
        };
        wv.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                if (finished[0]) return;
                main.removeCallbacks(poll);
                main.postDelayed(poll, 250);
            }
        });
        main.postDelayed(poll, 800);
        wv.resumeTimers();
        wv.loadUrl(EcardClient.QR_URL + "?url=0");
    }

    private static final String EXTRACT_JS = "(function(){"
            + "var href=location.href||'';"
            + "if(/id\\.fudan\\.edu\\.cn|uis\\.fudan\\.edu\\.cn/.test(href) && document.querySelector('input[type=password]')) return 'LOGIN';"
            + "function val(id){var e=document.getElementById(id);return (e&&e.value)?String(e.value):'';}"
            + "var v=val('myText')||val('qrcode')||val('qrCode')||val('qrtext')||val('code_content');"
            + "if(v&&v.length>=8&&v.length<=768) return v;"
            + "var nodes=document.querySelectorAll('input[type=hidden],input[id*=qr],input[name*=qr],input[id*=code],input[name*=code]');"
            + "for(var i=0;i<nodes.length;i++){if(nodes[i].value&&nodes[i].value.length>=12) return nodes[i].value;}"
            + "var keys=['qrcode','qrCode','code_content','qrContent','lifeCode','identityCode'];"
            + "for(var k=0;k<keys.length;k++){try{var x=window[keys[k]];if(typeof x==='string'&&x.length>=12) return x;}catch(e){}}"
            + "try{for(var j=0;j<localStorage.length;j++){var key=localStorage.key(j);if(/qr|code/i.test(key||'')){var sv=localStorage.getItem(key);if(sv&&sv.length>=12&&sv.length<512) return sv;}}}catch(e){}"
            + "if(document.querySelector('input[type=password]') && !/ecard/.test(href)) return 'LOGIN';"
            + "return '';"
            + "})()";

    private static void harvest(Activity act, WebView wv, FudanClient client, String payload, String html,
                                AtomicReference<JSONObject> out, AtomicReference<Exception> err, CountDownLatch latch, boolean[] finished) {
        if (finished[0]) return;
        finished[0] = true;
        try {
            absorbEcardCookies(client.jar, CookieManager.getInstance());
            String p = payload == null ? "" : payload.trim();
            if (p.length() >= 8 && !"null".equalsIgnoreCase(p) && !"LOGIN".equals(p)) {
                JSONObject o = new JSONObject();
                o.put("status", "ok");
                o.put("payload", p);
                o.put("ts", System.currentTimeMillis());
                o.put("fresh", true);
                out.set(o);
            }
        } catch (Exception e) {
            if (err.get() == null) err.set(e);
        } finally {
            act.runOnUiThread(() -> destroy(wv));
            latch.countDown();
        }
    }

    private static void absorbEcardCookies(CookieJar jar, CookieManager cm) {
        for (String host : new String[]{
                "https://ecard.fudan.edu.cn/",
                "https://id.fudan.edu.cn/",
                "https://uis.fudan.edu.cn/"
        }) {
            try {
                String raw = cm.getCookie(host);
                if (raw == null || raw.isEmpty()) continue;
                java.util.Map<String, java.util.List<String>> headers = new java.util.HashMap<>();
                java.util.ArrayList<String> set = new java.util.ArrayList<>();
                for (String part : raw.split(";")) {
                    String t = part.trim();
                    if (!t.isEmpty()) set.add(t);
                }
                if (!set.isEmpty()) {
                    headers.put("Set-Cookie", set);
                    jar.absorb(host, headers);
                }
            } catch (RuntimeException ignored) {}
        }
    }

    private static void destroy(WebView wv) {
        if (wv == null) return;
        try {
            if (wv.getParent() instanceof ViewGroup)
                ((ViewGroup) wv.getParent()).removeView(wv);
            wv.destroy();
        } catch (RuntimeException ignored) {}
    }

    private static String unquote(String v) {
        if (v == null || "null".equals(v)) return "";
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            try { return new JSONObject("{\"x\":" + v + "}").optString("x"); }
            catch (Exception e) { return v.substring(1, v.length() - 1); }
        }
        return v;
    }
}
