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
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Hidden WebView login for Tencent Exmail. Matches the path that works in a real browser. */
final class MailBrowser {
    static JSONArray loginAndList(final Activity act, final FudanClient client, final String email, final String password) throws Exception {
        if (act == null || email == null || password == null) return null;
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("mail browser must run off the UI thread");
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<JSONArray> out = new AtomicReference<>();
        final AtomicReference<Exception> err = new AtomicReference<>();
        final Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            try {
                start(act, client, email, password, out, err, latch);
            } catch (Exception e) {
                err.set(e);
                latch.countDown();
            }
        });
        if (!latch.await(36, TimeUnit.SECONDS)) {
            main.post(() -> { /* timeout; WebView cleaned in start */ });
        }
        if (out.get() != null) return out.get();
        if (err.get() != null) throw err.get();
        return null;
    }

    private static void start(Activity act, FudanClient client, String email, String password,
                              AtomicReference<JSONArray> out, AtomicReference<Exception> err, CountDownLatch latch) {
        final WebView wv = new WebView(act);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(Http.UA);
        wv.setAlpha(0f);
        try {
            ViewGroup root = (ViewGroup) act.findViewById(android.R.id.content);
            if (root != null) {
                ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(2, 2);
                root.addView(wv, lp);
            }
        } catch (RuntimeException ignored) {}
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        try { cm.setAcceptThirdPartyCookies(wv, true); } catch (RuntimeException ignored) {}
        seed(cm, client.jar);
        final boolean[] submitted = {false};
        final boolean[] finished = {false};
        wv.addJavascriptInterface(new Object() {
            @JavascriptInterface public void sid(String sid, String href, String html) {
                if (finished[0]) return;
                harvest(act, wv, client, sid, href, html, out, err, latch, finished);
            }
        }, "DanzhiMail");
        wv.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                if (finished[0]) return;
                String u = url == null ? "" : url;
                boolean login = u.contains("loginpage") || u.contains("/login") || u.contains("exmail.qq.com/login");
                if (login && !submitted[0]) {
                    String js = "(function(){"
                            + "function fill(sel,val){document.querySelectorAll(sel).forEach(function(n){try{n.focus();n.value=val;n.dispatchEvent(new Event('input',{bubbles:true}));n.dispatchEvent(new Event('change',{bubbles:true}));}catch(e){}});}"
                            + "var email="+jsonStr(email)+";"
                            + "var local="+jsonStr(email.contains("@")?email.substring(0,email.indexOf('@')):email)+";"
                            + "var pass="+jsonStr(password)+";"
                            + "fill('#uin,input[name=uin],#inputuin,input[name=inputuin],input[name=account],#account,input[name=qquin]', email);"
                            + "fill('#uin,input[name=uin]', local);"
                            + "fill('input[type=password],#pp,input[name=pp],input[name=password],#password', pass);"
                            + "try{if(document.form1){document.form1.pp.value=pass; if(document.form1.uin) document.form1.uin.value=local;}}catch(e){}"
                            + "if(typeof window.loginCheck==='function'){try{window.loginCheck();return 'loginCheck';}catch(e){}}"
                            + "var btn=document.querySelector('#loginbtn,#btlogin,.login_button,.loginbtn,input[type=submit],button[type=submit],a.btn_login');"
                            + "if(btn){btn.click();return 'click';}"
                            + "if(typeof window.doLogin==='function'){try{window.doLogin();return 'doLogin';}catch(e){}}"
                            + "try{if(document.form1){document.form1.submit();return 'submit';}}catch(e){}"
                            + "return 'nofind';"
                            + "})();";
                    v.evaluateJavascript(js, val -> {
                        String r = unquote(val);
                        if ("click".equals(r) || "loginCheck".equals(r) || "doLogin".equals(r) || "submit".equals(r)) submitted[0] = true;
                    });
                    return;
                }
                v.evaluateJavascript("(function(){return (document.cookie||'')+'\\n'+(location.href||'')+'\\n'+(document.body?document.body.innerHTML.slice(0,12000):'');})()",
                        val -> harvest(act, wv, client, "", url, unquote(val), out, err, latch, finished));
            }
        });
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (finished[0]) return;
            wv.evaluateJavascript("(function(){return (document.cookie||'')+'\\n'+(location.href||'')+'\\n'+(document.body?document.body.innerHTML.slice(0,12000):'');})()",
                    val -> harvest(act, wv, client, "", "", unquote(val), out, err, latch, finished));
        }, 12000);
        wv.loadUrl("https://exmail.qq.com/cgi-bin/loginpage?s=ssl&from=&vt=separate&d=m.fudan.edu.cn");
    }

    private static void harvest(Activity act, WebView wv, FudanClient client, String sidHint, String href, String html,
                                AtomicReference<JSONArray> out, AtomicReference<Exception> err, CountDownLatch latch, boolean[] finished) {
        if (finished[0]) return;
        try {
            CookieManager cm = CookieManager.getInstance();
            String blob = (sidHint == null ? "" : sidHint) + " " + (href == null ? "" : href) + " " + (html == null ? "" : html)
                    + " " + safeCookie(cm, "https://exmail.qq.com/") + " " + safeCookie(cm, "https://mail.m.fudan.edu.cn/");
            absorbCookies(client.jar, cm);
            String sid = cn.fudan.danzhi.core.PageParser.exmailSid(blob);
            if (sid.isEmpty()) sid = client.jar.cookie("qm_sid");
            if (sid.isEmpty()) return;
            final String foundSid = sid;
            client.mailSid = foundSid;
            finished[0] = true;
            new Thread(() -> {
                try {
                    JSONArray listed = MailClient.listBySidPublic(client, client.jar, foundSid, html == null ? "" : html, 1);
                    out.set(listed == null ? new JSONArray() : listed);
                } catch (Exception e) {
                    err.set(e);
                } finally {
                    act.runOnUiThread(() -> {
                        try {
                            if (wv.getParent() instanceof ViewGroup)
                                ((ViewGroup) wv.getParent()).removeView(wv);
                            wv.destroy();
                        } catch (RuntimeException ignored) {}
                    });
                    latch.countDown();
                }
            }, "danzhi-mail-list").start();
        } catch (Exception e) {
            err.set(e);
            finished[0] = true;
            try {
                if (wv.getParent() instanceof ViewGroup)
                    ((ViewGroup) wv.getParent()).removeView(wv);
                wv.destroy();
            } catch (RuntimeException ignored) {}
            latch.countDown();
        }
    }

    static void seed(CookieManager cm, CookieJar jar) {
        try {
            JSONArray a = new JSONArray(jar.dump());
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String domain = o.optString("domain");
                String name = o.optString("name");
                String value = o.optString("value");
                if (domain.isEmpty() || name.isEmpty()) continue;
                String host = domain.startsWith(".") ? domain.substring(1) : domain;
                String url = (o.optBoolean("secure", true) ? "https://" : "http://") + host + o.optString("path", "/");
                cm.setCookie(url, name + "=" + value + "; Domain=" + domain + "; Path=" + o.optString("path", "/"));
            }
            cm.flush();
        } catch (Exception ignored) {}
    }

    static void absorbCookies(CookieJar jar, CookieManager cm) {
        for (String host : new String[]{"https://exmail.qq.com/", "https://mail.m.fudan.edu.cn/", "https://mail.qq.com/"}) {
            String raw = safeCookie(cm, host);
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
        }
    }

    private static String safeCookie(CookieManager cm, String url) {
        try { return cm.getCookie(url); } catch (RuntimeException e) { return ""; }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String jsonStr(String s) {
        if (s == null) return "''";
        return JSONObject.quote(s);
    }

    private static String unquote(String v) {
        if (v == null || "null".equals(v)) return "";
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            try { return new JSONObject("{\"x\":" + v + "}").optString("x"); } catch (Exception e) { return v.substring(1, v.length() - 1); }
        }
        return v;
    }
}
