package cn.fudan.danzhi;

import cn.fudan.danzhi.core.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Base64;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EcardClient {
    static final String QR_URL = "https://ecard.fudan.edu.cn/epay/wxpage/fudan/zfm/qrcode";
    static final String EPAY = "https://ecard.fudan.edu.cn/epay";
    static final String MY = "https://ecard.fudan.edu.cn/epay/myepay/index";
    static final String SCAN_PAGE = "https://ecard.fudan.edu.cn/epay/wxpage/fudan/zfm/scanqrcode";
    static volatile JSONObject lastQr;
    static volatile long lastQrAt;
    private static final AtomicBoolean inflight=new AtomicBoolean();
    private static volatile long inflightSince;
    interface BrowserHook { JSONObject fetch(FudanClient client) throws Exception; }
    static volatile BrowserHook browser;

    static JSONObject fetchQr(FudanClient client) throws Exception {
        return fetchQr(client, false);
    }

    static JSONObject fetchQr(FudanClient client, boolean force) throws Exception {
        long now=System.currentTimeMillis();
        if(!force && lastQr!=null && now-lastQrAt<8_000L) return lastQr;
        if(!inflight.compareAndSet(false,true)){
            if(now-inflightSince>15_000L){
                inflight.set(true);
            } else if(lastQr!=null){
                JSONObject busy=new JSONObject(lastQr.toString());
                busy.put("stale", true);
                busy.put("fresh", false);
                busy.put("message", "正在取码，沿用上一张");
                return busy;
            } else {
                throw new WebFlow.FlowException("BUSY","正在取生活码");
            }
        }
        inflightSince=now;
        try {
            return fetchQrLocked(client, force);
        } finally {
            inflight.set(false);
        }
    }

    private static JSONObject fetchQrLocked(FudanClient client, boolean force) throws Exception {
        String previous= lastQr==null?"":lastQr.optString("payload");
        Exception last=null;
        boolean sawLogin=false;
        String stamp=String.valueOf(System.currentTimeMillis());
        JSONObject got=null;
        String page=QR_URL+"?url=0&_="+stamp;
        try {
            Http.Resp resp=Http.follow(client.jar, page, false, Http.UA_WECHAT);
            if(WebFlow.loginPage(resp.flow()) || hostLooksLikeIdp(resp.url)){
                sawLogin=true;
                enterEcard(client);
                resp=Http.follow(client.jar, page, false, Http.UA_WECHAT);
            }
            if(WebFlow.loginPage(resp.flow()) || hostLooksLikeIdp(resp.url)){
                sawLogin=true;
                last=new WebFlow.FlowException("AUTH_REQUIRED","一卡通登录未完成，请先巡检或重新登录");
            } else if(resp.body.contains("btn-agree-ok")){
                throw new WebFlow.FlowException("CONSENT_REQUIRED","一卡通要求本人确认使用条款；应用不会代替你同意");
            } else if(WebFlow.host(resp.url).endsWith("fudan.edu.cn")){
                String payload=payloadOf(resp.body);
                if(payload.isEmpty()){
                    for(String path:PageParser.qrAjaxPaths(resp.body)){
                        payload=fetchAjaxQr(client.jar, resp.url, path, stamp, Http.UA);
                        if(!payload.isEmpty()) break;
                    }
                }
                if(!payload.isEmpty() && force && payload.equals(previous)){
                    try { Thread.sleep(1100); } catch(InterruptedException ie){
                        Thread.currentThread().interrupt();
                    }
                    String again=payloadOf(resp.body);
                    for(String path:PageParser.qrAjaxPaths(resp.body)){
                        String fresh=fetchAjaxQr(client.jar, resp.url, path, String.valueOf(System.currentTimeMillis()), Http.UA);
                        if(!fresh.isEmpty() && !fresh.equals(previous)){ payload=fresh; break; }
                    }
                    if(again.length()>=8) payload=payload.isEmpty()?again:payload;
                }
                if(!payload.isEmpty()) got=packQr(payload, resp.body, force, previous);
            }
        } catch(WebFlow.FlowException e){
            if("CONSENT_REQUIRED".equals(e.category)) throw e;
            last=e;
        } catch(Exception e){ last=e; }
        if(got==null){
            JSONObject ajax=tryAjaxFresh(client, stamp, previous);
            if(ajax!=null) got=ajax;
        }
        // Hidden WebView used to freeze / crash the app. HTTP + WeChat UA is enough; never spin a WebView.
        if(got==null && force){
            try {
                Http.Resp again=Http.follow(client.jar, QR_URL+"?url=0&fresh=1&_="+System.currentTimeMillis(), false, Http.UA_WECHAT);
                String payload=payloadOf(again.body);
                if(payload.isEmpty()){
                    for(String path:PageParser.qrAjaxPaths(again.body)){
                        payload=fetchAjaxQr(client.jar, again.url, path, String.valueOf(System.currentTimeMillis()), Http.UA_WECHAT);
                        if(!payload.isEmpty()) break;
                    }
                }
                if(!payload.isEmpty()) got=packQr(payload, again.body, force, previous);
            } catch(Exception e){ if(last==null) last=e; }
        }
        if(got!=null){
            if(force) stampQr(got);
            lastQr=got;
            lastQrAt=System.currentTimeMillis();
            try { got.put("ts", lastQrAt); got.put("fresh", true); } catch(Exception ignored) {}
            return got;
        }
        if(lastQr!=null){
            JSONObject stale=new JSONObject(lastQr.toString());
            stale.put("stale", true);
            stale.put("fresh", false);
            stale.put("ts", System.currentTimeMillis());
            stale.put("message", last==null?"已重新请求，码尚未轮换时可再点一次":last.getMessage());
            return stale;
        }
        if(sawLogin) throw new WebFlow.FlowException("AUTH_REQUIRED","一卡通未登录。请先巡检信匣，或退出后重新登录再开生活码");
        if(last!=null)throw last;
        throw new WebFlow.FlowException("QR_SCHEMA_UNVERIFIED",
            "一卡通页面没有已识别的官方生活码字段。请确认已登录后再点刷新。");
    }

    private static boolean hostLooksLikeIdp(String url) {
        String h=WebFlow.host(url);
        return "id.fudan.edu.cn".equals(h) || "uis.fudan.edu.cn".equals(h);
    }

    private static void enterEcard(FudanClient client) {
        String qr=QR_URL;
        try {
            Http.Resp hop=client.enterPublic(qr+"?url=0");
            if(hop!=null && WebFlow.host(hop.url).contains("ecard") && !WebFlow.loginPage(hop.flow()))
                return;
        } catch(Exception ignored) {}
        String[] gates={
            FudanClient.IDP+"/idp/authCenter/authenticate?service="+WebFlow.encode(qr+"?url=0"),
            FudanClient.IDP+"/authserver/login?service="+WebFlow.encode(qr+"?url=0"),
            "https://uis.fudan.edu.cn/authserver/login?service="+WebFlow.encode(qr+"?url=0"),
            qr+"?url=0",
            EPAY,
            MY,
            "https://ecard.fudan.edu.cn/"
        };
        for(String g:gates){
            try {
                Http.Resp r=Http.follow(client.jar, g, false, Http.UA);
                if(r!=null && WebFlow.host(r.url).contains("ecard") && !WebFlow.loginPage(r.flow()))
                    return;
            } catch(Exception ignored) {}
        }
    }

    private static JSONObject tryAjaxFresh(FudanClient client, String stamp, String previous) {
        String[] ajax={
            EPAY+"/wxpage/fudan/zfm/qrcode/getQr",
            EPAY+"/wxpage/fudan/zfm/getqrcode",
            EPAY+"/wxpage/fudan/zfm/qrcode?ajax=1",
            EPAY+"/consume/qrcode",
            EPAY+"/consume/qrcode/getQr?fresh=1&t="+stamp
        };
        for(String path:ajax){
            String payload=fetchAjaxQr(client.jar, QR_URL, path, stamp, Http.UA_WECHAT);
            if(payload.isEmpty()) payload=fetchAjaxQr(client.jar, QR_URL, path, stamp, Http.UA);
            if(payload.isEmpty()) continue;
            try { return packQr(payload, "", true, previous); } catch(Exception ignored) {}
        }
        return null;
    }

    private static JSONObject packQr(String payload, String html, boolean force, String previous) throws Exception {
        JSONObject o=new JSONObject();
        o.put("status","ok");
        o.put("payload",payload);
        try { o.put("image",toPng(payload, force)); }
        catch(Throwable t){ o.put("image",""); o.put("message","码已取到，绘制失败时可再点刷新"); }
        o.put("balance",extractBalanceFrom(html));
        o.put("name",extractName(html));
        o.put("ts",System.currentTimeMillis());
        o.put("fresh", true);
        return o;
    }

    private static void stampQr(JSONObject o) {
        try { o.put("ts", System.currentTimeMillis()); o.put("fresh", true); } catch(Exception ignored) {}
    }

    private static String fetchAjaxQr(CookieJar jar, String referer, String path, String stamp, String ua) {
        try {
            String ajax=path.startsWith("http")?path:WebFlow.resolve(referer, path);
            if(!WebFlow.host(ajax).endsWith("fudan.edu.cn")) return "";
            String sep=ajax.contains("?")?"&":"?";
            Http.Resp aj=Http.fetch(jar, ajax+sep+"_="+stamp+"&nocache=1&fresh=1", "GET", null, null, 3500, referer, ua);
            String payload=payloadOf(aj.body);
            if(!payload.isEmpty()) return payload;
            aj=Http.fetch(jar, ajax, "POST", "application/x-www-form-urlencoded",
                    "aaxmlrequest=true&fresh=1&_="+stamp+"&t="+stamp, 3500, referer, ua);
            return payloadOf(aj.body);
        } catch(Exception ignored) { return ""; }
    }

    private static String payloadOf(String body) {
        String p=PageParser.qrPayload(body);
        if(p.isEmpty()) p=PageParser.qrPayloadJson(body);
        if(p.length()<8 || p.length()>768) return "";
        return p;
    }

    static JSONObject scan(FudanClient client, String qr) throws Exception {
        if(qr==null||qr.trim().isEmpty())throw new Exception("空码");
        String code=qr.trim();
        return new JSONObject().put("status","confirmation_required")
            .put("message","已识别二维码。扫码缴费或设备控制需在官方一卡通中确认，未自动提交交易。")
            .put("url",SCAN_PAGE)
            .put("code",code);
    }

    private static String extractBalanceFrom(String html) {
        if (html == null) return "";
        Matcher m = Pattern.compile("(?:余额|balance)[^\\d]{0,12}(\\d+(?:\\.\\d{1,2})?)").matcher(html);
        if (m.find()) return m.group(1);
        m = Pattern.compile("payway-box-bottom-item[\\s\\S]{0,120}?<p[^>]*>\\s*([0-9.]+)").matcher(html);
        return m.find() ? m.group(1) : "";
    }

    private static String extractBalance(FudanClient client) {
        try {
            Http.Resp r = Http.follow(client.jar, MY);
            return extractBalanceFrom(r.body);
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String extractName(String html) {
        if (html == null) return "";
        Matcher m = Pattern.compile("(?:姓名|用户)[:：]\\s*([\\u4e00-\\u9fff]{2,8})").matcher(html);
        if (m.find()) return m.group(1);
        m = Pattern.compile("您好，\\s*([\\u4e00-\\u9fff]{2,8})").matcher(html);
        return m.find() ? m.group(1) : "";
    }

    static String toPng(String payload) throws Exception {
        return toPng(payload, false);
    }

    static String toPng(String payload, boolean stamp) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 200, 200, hints);
        int w = matrix.getWidth();
        int h = matrix.getHeight();
        int extra = stamp ? 36 : 0;
        Bitmap bmp = Bitmap.createBitmap(w, h + extra, Bitmap.Config.RGB_565);
        int dark = Color.parseColor("#0B1020");
        int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) pixels[row + x] = matrix.get(x, y) ? dark : Color.WHITE;
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        if (extra > 0) {
            android.graphics.Paint bg = new android.graphics.Paint();
            bg.setColor(Color.WHITE);
            canvas.drawRect(0, h, w, h + extra, bg);
        }
        if (stamp) {
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.parseColor("#3B2A24"));
            paint.setTextSize(22);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            canvas.drawText("已刷新 "+fmt.format(new java.util.Date()), w/2f, h + 26, paint);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 90, baos);
        bmp.recycle();
        return "data:image/png;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
    }
}
