package cn.fudan.danzhi;

import cn.fudan.danzhi.core.CookieStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

final class CookieJar {
    private final CookieStore store=new CookieStore();
    boolean viaVpn;
    synchronized void clear(){store.clear();viaVpn=false;}
    synchronized String headerFor(String url){return store.header(url,System.currentTimeMillis());}
    synchronized void absorb(String url,Map<String,List<String>> headers){store.absorb(url,headers,System.currentTimeMillis());}
    synchronized String dump(){
        JSONArray a=new JSONArray();
        for(CookieStore.Entry e:store.snapshot())try{
            a.put(new JSONObject().put("name",e.name).put("value",e.value).put("domain",e.domain).put("path",e.path)
                .put("hostOnly",e.hostOnly).put("secure",e.secure).put("expiresAt",e.expiresAt));
        }catch(Exception ignored){}
        return a.toString();
    }
    synchronized String cookie(String name) {
        if (name == null || name.isEmpty()) return "";
        for (CookieStore.Entry e : store.snapshot()) {
            if (name.equalsIgnoreCase(e.name) && e.value != null && !e.value.isEmpty()) return e.value;
        }
        return "";
    }
    synchronized void load(String json){
        List<CookieStore.Entry> data=new ArrayList<>();
        try{
            JSONArray a=new JSONArray(json==null||json.isEmpty()?"[]":json);
            for(int i=0;i<a.length();i++){
                JSONObject o=a.optJSONObject(i);if(o==null)continue;
                CookieStore.Entry e=new CookieStore.Entry();e.name=o.optString("name");e.value=o.optString("value");
                e.domain=o.optString("domain");e.path=o.optString("path","/");
                // Legacy dumps omitted scope and expiry. Do not broaden their scope during migration.
                e.hostOnly=o.optBoolean("hostOnly",true);e.secure=o.optBoolean("secure",true);e.expiresAt=o.optLong("expiresAt",-1);
                if(!e.name.isEmpty()&&!e.domain.isEmpty())data.add(e);
            }
        }catch(Exception ignored){}
        store.restore(data,System.currentTimeMillis());
    }
}
