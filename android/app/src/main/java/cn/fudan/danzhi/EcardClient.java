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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class EcardClient {
    static final String QR_URL = "https://ecard.fudan.edu.cn/epay/wxpage/fudan/zfm/qrcode";
    static final String EPAY = "https://ecard.fudan.edu.cn/epay";
    static final String MY = "https://ecard.fudan.edu.cn/epay/myepay/index";
    static final String SCAN_PAGE = "https://ecard.fudan.edu.cn/epay/wxpage/fudan/zfm/scanqrcode";
    static volatile JSONObject lastQr;
    static volatile long lastQrAt;

    static JSONObject fetchQr(FudanClient client) throws Exception {
        return fetchQr(client, false);
    }

    static JSONObject fetchQr(FudanClient client, boolean force) throws Exception {
        if(!force && lastQr!=null && System.currentTimeMillis()-lastQrAt<2_000L) return lastQr;
        String previous= lastQr==null?"":lastQr.optString("payload");
        if(force){ lastQrAt=0; }
        Exception last=null;
        String stamp=String.valueOf(System.currentTimeMillis());
        if(force){
            try { Http.follow(client.jar, "https://ecard.fudan.edu.cn/", false, Http.UA); } catch(Exception ignored) {}
            try {
                Http.follow(client.jar, FudanClient.IDP+"/authserver/login?service="+WebFlow.encode(QR_URL), false, Http.UA);
            } catch(Exception ignored) {}
        }
        try {
            Http.follow(client.jar, EPAY, false, Http.UA);
        } catch(Exception ignored) {}
        JSONObject got=null;
        String[] pages={
            QR_URL+(QR_URL.contains("?")?"&":"?")+"_="+stamp+"&nocache=1&r="+stamp,
            QR_URL+"?url=0&_="+stamp,
            QR_URL
        };
        String[] agents={ Http.UA_WECHAT, Http.UA_WECHAT, Http.UA };
        int rounds=force?4:1;
        for(int round=0; round<rounds; round++){
            if(round>0){
                try { Thread.sleep(700); } catch(InterruptedException ie){ Thread.currentThread().interrupt(); }
                stamp=String.valueOf(System.currentTimeMillis());
            }
            for(int i=0;i<pages.length;i++){
                try {
                    String page=pages[i];
                    if(round>0 && page.contains("_=")) page=page.replaceAll("_=\\d+","_="+stamp).replaceAll("r=\\d+","r="+stamp);
                    Http.Resp resp=Http.follow(client.jar, page, false, agents[i]);
                    if(WebFlow.loginPage(resp.flow()))continue;
                    if(!WebFlow.host(resp.url).endsWith("fudan.edu.cn"))continue;
                    if(resp.body.contains("btn-agree-ok"))
                        throw new WebFlow.FlowException("CONSENT_REQUIRED","一卡通要求本人确认使用条款；应用不会代替你同意");
                    String payload=payloadOf(resp.body);
                    if(payload.isEmpty()){
                        for(String path:PageParser.qrAjaxPaths(resp.body)){
                            payload=fetchAjaxQr(client.jar, resp.url, path, stamp, agents[i]);
                            if(!payload.isEmpty()) break;
                        }
                    }
                    if(payload.isEmpty()) continue;
                    JSONObject o=packQr(payload, resp.body, force, previous);
                    got=o;
                    if(!force || !payload.equals(previous)) break;
                } catch(WebFlow.FlowException e){
                    if("CONSENT_REQUIRED".equals(e.category))throw e;
                    last=e;
                } catch(Exception e){ last=e; }
            }
            if(got!=null && (!force || !previous.equals(got.optString("payload")))) break;
        }
        if(got==null){
            JSONObject ajax=tryAjaxFresh(client, stamp, previous);
            if(ajax!=null) got=ajax;
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
            if(force) stampQr(stale);
            stale.put("message", last==null?"已重新请求，码尚未轮换时可再点一次":last.getMessage());
            return stale;
        }
        if(last!=null)throw last;
        throw new WebFlow.FlowException("QR_SCHEMA_UNVERIFIED",
            "一卡通页面没有已识别的官方生活码字段；这不是已验证可用的门禁码");
    }

    private static JSONObject tryAjaxFresh(FudanClient client, String stamp, String previous) {
        String[] ajax={
            EPAY+"/consume/qrcode",
            EPAY+"/wxpage/fudan/zfm/qrcode",
            EPAY+"/wxpage/fudan/zfm/getqrcode",
            EPAY+"/wxpage/fudan/zfm/refreshqrcode",
            EPAY+"/consume/qrcode/getQr?fresh=1&t="+stamp,
            EPAY+"/consume/qrcode/refresh?t="+stamp,
            EPAY+"/order/qrcode"
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
        o.put("image",toPng(payload, force));
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
            Http.Resp aj=Http.fetch(jar, ajax+sep+"_="+stamp+"&nocache=1&fresh=1", "GET", null, null, 8000, referer, ua);
            String payload=payloadOf(aj.body);
            if(!payload.isEmpty()) return payload;
            aj=Http.fetch(jar, ajax, "POST", "application/x-www-form-urlencoded",
                    "aaxmlrequest=true&fresh=1&_="+stamp+"&t="+stamp, 8000, referer, ua);
            return payloadOf(aj.body);
        } catch(Exception ignored) { return ""; }
    }

    private static String payloadOf(String body) {
        String p=PageParser.qrPayload(body);
        if(!p.isEmpty()) return p;
        return PageParser.qrPayloadJson(body);
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
        BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 480, 480, hints);
        int w = matrix.getWidth();
        int h = matrix.getHeight();
        int extra = stamp ? 44 : 0;
        Bitmap bmp = Bitmap.createBitmap(w, h + extra, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
        canvas.drawColor(Color.WHITE);
        int dark = Color.parseColor("#0B1020");
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (matrix.get(x, y)) bmp.setPixel(x, y, dark);
            }
        }
        if (stamp) {
            android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.parseColor("#3B2A24"));
            paint.setTextSize(28);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA);
            fmt.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
            canvas.drawText("已刷新 "+fmt.format(new java.util.Date()), w/2f, h + 32, paint);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
        return "data:image/png;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
    }
}
