package cn.fudan.danzhi.core;

import java.net.HttpCookie;
import java.net.URI;
import java.util.*;

/** RFC-style host-only, domain, secure, path and expiry checks. Persist absolute expiry, not reset max-age. */
public final class CookieStore {
    public static final class Entry {
        public String name,value,domain,path;
        public boolean hostOnly,secure;
        public long expiresAt; // -1: session; milliseconds since epoch otherwise
        public Entry copy(){Entry c=new Entry();c.name=name;c.value=value;c.domain=domain;c.path=path;c.hostOnly=hostOnly;c.secure=secure;c.expiresAt=expiresAt;return c;}
    }
    private final List<Entry> entries=new ArrayList<>();
    public synchronized void clear(){entries.clear();}
    public synchronized List<Entry> snapshot(){List<Entry> out=new ArrayList<>();for(Entry e:entries)out.add(e.copy());return out;}
    public synchronized void restore(List<Entry> data,long now){
        entries.clear();for(Entry e:data)if(e.name!=null&&e.value!=null&&e.domain!=null&&e.path!=null&&(e.expiresAt<0||e.expiresAt>now))entries.add(e.copy());
    }
    public synchronized void absorb(String url,Map<String,List<String>> headers,long now){
        if(headers==null)return;
        URI u=URI.create(url);String host=u.getHost().toLowerCase(Locale.ROOT);
        for(Map.Entry<String,List<String>> field:headers.entrySet()) {
            if(field.getKey()==null||!field.getKey().equalsIgnoreCase("Set-Cookie")||field.getValue()==null)continue;
            for(String line:field.getValue()) {
                try {
                    for(HttpCookie c:HttpCookie.parse(line)) {
                        Entry e=new Entry();e.name=c.getName();e.value=c.getValue();e.hostOnly=c.getDomain()==null;
                        e.domain=e.hostOnly?host:c.getDomain().replaceFirst("^\\.","").toLowerCase(Locale.ROOT);
                        // Reject foreign domains and public top-level scope; campus SSO cookies stay scoped to their issuer.
                        if(!host.equals(e.domain)&&!host.endsWith("."+e.domain))continue;
                        if(!e.hostOnly && (!e.domain.contains(".")||Arrays.asList("edu.cn","com.cn","co.uk").contains(e.domain)))continue;
                        String path=u.getPath();int cut=path==null?-1:path.lastIndexOf('/');
                        e.path=c.getPath()==null||!c.getPath().startsWith("/")?(cut<=0?"/":path.substring(0,cut)):c.getPath();
                        e.secure=c.getSecure();long age=c.getMaxAge();
                        e.expiresAt=age<0?-1:(age> (Long.MAX_VALUE-now)/1000?Long.MAX_VALUE:now+age*1000);
                        if(e.name.startsWith("__Secure-")&&(!e.secure||!"https".equals(u.getScheme())))continue;
                        if(e.name.startsWith("__Host-")&&(!e.secure||!e.hostOnly||!e.path.equals("/")||!"https".equals(u.getScheme())))continue;
                        entries.removeIf(x->x.name.equals(e.name)&&x.domain.equals(e.domain)&&x.path.equals(e.path));
                        if(e.expiresAt<0||e.expiresAt>now)entries.add(e);
                    }
                }catch(IllegalArgumentException ignored){/* malformed Set-Cookie is not a login success */}
            }
        }
    }
    public synchronized String header(String url,long now){
        URI u=URI.create(url);String host=u.getHost().toLowerCase(Locale.ROOT);
        String path=u.getPath()==null||u.getPath().isEmpty()?"/":u.getPath();
        entries.removeIf(e->e.expiresAt>=0&&e.expiresAt<=now);
        List<Entry> matches=new ArrayList<>();
        for(Entry e:entries){
            if(e.secure&&!"https".equalsIgnoreCase(u.getScheme()))continue;
            if(!(host.equals(e.domain)||(!e.hostOnly&&host.endsWith("."+e.domain))))continue;
            if(!(path.equals(e.path)||(path.startsWith(e.path)&&(e.path.endsWith("/")||path.charAt(e.path.length())=='/'))))continue;
            matches.add(e);
        }
        matches.sort((a,b)->Integer.compare(b.path.length(),a.path.length()));
        StringBuilder out=new StringBuilder();for(Entry e:matches){if(out.length()>0)out.append("; ");out.append(e.name).append('=').append(e.value);}
        return out.toString();
    }
}
