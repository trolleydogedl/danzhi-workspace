package cn.fudan.danzhi;

import cn.fudan.danzhi.core.PageParser;
import cn.fudan.danzhi.core.WebFlow;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RoomsClient {
    static volatile JSONObject lastOk;
    static volatile long lastAt;
    static volatile String lastKey = "";
    static final String[][] BUILDINGS = {
            {"HGD", "光华楼东辅楼"},
            {"HGX", "光华楼西辅楼"},
            {"H2", "第二教学楼"},
            {"H3", "第三教学楼"},
            {"H4", "第四教学楼"},
            {"H5", "第五教学楼"},
            {"H6", "第六教学楼"},
            {"JA", "江湾教学 A 楼"},
            {"JB", "江湾教学 B 楼"},
            {"F1", "枫林 1 号楼"},
            {"F2", "枫林 2 号楼"},
            {"Z2", "张江教学楼"},
    };

    static JSONObject query(FudanClient client, String building, String day) throws Exception {
        if (building == null || building.isEmpty()) building = "H2";
        if (day == null || day.isEmpty()) {
            day = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date());
        }
        String key = building + "|" + day;
        if(lastOk!=null && key.equals(lastKey) && System.currentTimeMillis()-lastAt<90_000L){
            return lastOk;
        }
        String statusUrl="http://10.64.130.6/daystatus.asp?b="+enc(building)+"&day="+enc(day);
        String detailUrl="http://10.64.130.6/?b="+enc(building)+"&c=&p=&day="+enc(day);
        Exception direct=null, vpn=null;
        try {
            JSONArray rooms=load(new CookieJar(), statusUrl, detailUrl, 5000);
            if(rooms.length()>0){
                JSONObject r=ok(building,day,rooms);
                lastOk=r; lastAt=System.currentTimeMillis(); lastKey=key;
                return r;
            }
        } catch(Exception e){ direct=e; }
        CookieJar roomJar=new CookieJar();
        roomJar.load(client.jar.dump());
        roomJar.viaVpn=true;
        try {
            JSONArray rooms=load(roomJar, statusUrl, detailUrl, 8000);
            if(rooms.length()>0){
                client.jar.load(roomJar.dump());
                client.jar.viaVpn=true;
                JSONObject r=ok(building,day,rooms);
                lastOk=r; lastAt=System.currentTimeMillis(); lastKey=key;
                return r;
            }
        } catch(Exception e){ vpn=e; }
        try {
            Http.Resp login=Http.follow(roomJar,Vpn.LOGIN,false);
            if(WebFlow.loginPage(login.flow()))
                throw new WebFlow.FlowException("AUTH_REQUIRED","WebVPN 未完成统一认证，校外请先保证已登录");
            JSONArray rooms=load(roomJar, statusUrl, detailUrl, 8000);
            if(rooms.length()>0){
                client.jar.load(roomJar.dump());
                client.jar.viaVpn=true;
                JSONObject r=ok(building,day,rooms);
                lastOk=r; lastAt=System.currentTimeMillis(); lastKey=key;
                return r;
            }
        } catch(Exception e){ vpn=e; }
        if(lastOk!=null && key.equals(lastKey) && System.currentTimeMillis()-lastAt<15*60*1000L){
            JSONObject stale=new JSONObject(lastOk.toString());
            stale.put("stale", true);
            return stale;
        }
        StringBuilder msg=new StringBuilder("空教室未取到。");
        if(direct!=null) msg.append("校园网直连：").append(shortErr(direct)).append("。");
        if(vpn!=null) msg.append("WebVPN：").append(shortErr(vpn)).append("。");
        msg.append("请确认已连校园网，或与旦夕一样开启 WebVPN。");
        throw new WebFlow.FlowException("CLASSROOM_UNAVAILABLE", msg.toString());
    }

    private static String shortErr(Exception e) {
        String m=e.getMessage();
        if(m==null||m.isEmpty())return e.getClass().getSimpleName();
        m=m.replaceAll("https?://[^\\s<>\"']+","[URL]");
        return m.length()>120?m.substring(0,120):m;
    }

    private static JSONObject ok(String building,String day,JSONArray rooms)throws Exception {
        return new JSONObject().put("status","ok").put("building",building).put("day",day).put("rooms",rooms);
    }

    private static JSONArray load(CookieJar jar,String statusUrl,String detailUrl,int timeout)throws Exception {
        Http.Resp status, detail;
        if (jar.viaVpn) {
            status=Http.follow(jar,statusUrl);
            detail=Http.follow(jar,detailUrl);
        } else {
            status=direct(jar,statusUrl,timeout);
            detail=direct(jar,detailUrl,timeout);
        }
        if(status.code<200||status.code>=400)throw new WebFlow.FlowException("HTTP_ERROR","空教室状态 HTTP "+status.code);
        JSONArray rooms=parse(status.body, detail.body);
        if(rooms.length()==0)throw new WebFlow.FlowException("SCHEMA_UNVERIFIED","空教室接口无数据");
        return rooms;
    }

    private static Http.Resp direct(CookieJar jar,String url,int timeout)throws Exception {
        String cur=url;
        Http.Resp r=null;
        Exception last=null;
        for(int hop=0;hop<5;hop++){
            int tries=0;
            while(tries<4){
                tries++;
                try {
                    r=Http.fetch(jar,cur,"GET",null,null,Math.max(timeout, tries*2500));
                    last=null;
                    break;
                } catch(java.io.IOException e){
                    last=e;
                    String m=e.getMessage()==null?"":e.getMessage().toLowerCase(java.util.Locale.ROOT);
                    boolean stale=m.contains("unexpected end of stream")||m.contains("connection reset")
                            ||m.contains("broken pipe")||m.contains("connection closed");
                    if(!stale && tries>=2) throw e;
                    try { Thread.sleep(120L*tries); } catch(InterruptedException ie){
                        Thread.currentThread().interrupt(); throw ie;
                    }
                }
            }
            if(r==null && last!=null) throw last;
            if(r.code<300||r.code>=400||r.location==null||r.location.isEmpty())return r;
            String next=WebFlow.resolve(r.url,r.location);
            if(!WebFlow.campusHost(WebFlow.host(next)))
                throw new WebFlow.FlowException("UNTRUSTED_REDIRECT",WebFlow.safeUrl(next));
            cur=next;
        }
        if(r==null && last!=null) throw last;
        return r;
    }

    static JSONArray buildings() {
        JSONArray arr = new JSONArray();
        for (String[] b : BUILDINGS) {
            try {
                arr.put(new JSONObject().put("code", b[0]).put("name", b[1]));
            } catch (Exception ignored) {
            }
        }
        return arr;
    }

    static JSONArray parse(String statusBody, String detailBody) {
        JSONArray out = new JSONArray();
        String array = PageParser.classroomStatusArray(statusBody);
        if (array.isEmpty()) return out;
        try {
            JSONArray ids = new JSONArray(array);
            for (int i = 0; i < ids.length(); i++) {
                JSONObject rec = ids.optJSONObject(i);
                if (rec == null) continue;
                String room = rec.optString("room");
                String id = rec.optString("id");
                if (room.isEmpty()) continue;
                JSONObject o = new JSONObject();
                o.put("name", room);
                o.put("seats", rec.optString("ec"));
                JSONArray busy = new JSONArray();
                if (detailBody != null && !id.isEmpty()) {
                    int start = detailBody.indexOf("\"c" + id + "\"");
                    JSONObject next = i + 1 < ids.length() ? ids.optJSONObject(i + 1) : null;
                    int end = (next != null)
                            ? detailBody.indexOf("\"r" + next.optString("id") + "\"")
                            : detailBody.indexOf("innerHTML");
                    if (end < 0) end = detailBody.length();
                    if (start >= 0 && end > start) {
                        String html = detailBody.substring(start, end);
                        Matcher cells = Pattern.compile("<td style=\"background-color.*?>(.*?)</td>").matcher(html);
                        while (cells.find()) {
                            String content = cells.group(1).replaceAll("<[^>]+>", "").trim();
                            busy.put(!content.isEmpty());
                        }
                        if (busy.length() > 13) {
                            JSONArray trim = new JSONArray();
                            int keep = Math.min(13, busy.length() - 1);
                            for (int k = 0; k < keep; k++) trim.put(busy.optBoolean(k));
                            busy = trim;
                        }
                        Matcher seats = Pattern.compile(">(\\d{1,3})<").matcher(html);
                        if (seats.find()) o.put("seats", seats.group(1));
                    }
                }
                o.put("busy", busy);
                boolean anyFree = busy.length()==0;
                for (int k = 0; k < busy.length(); k++) {
                    if (!busy.optBoolean(k)) {
                        anyFree = true;
                        break;
                    }
                }
                o.put("freeNow", anyFree);
                out.put(o);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
