package cn.fudan.danzhi.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.*;
import java.util.regex.*;

/** No Android dependencies. Only HTTP redirects and narrowly recognized navigation are followed. */
public final class WebFlow {
    public interface Transport { Response request(Request request) throws Exception; }
    public static final class Request {
        public final String url, method, contentType, body;
        public final int timeoutMs;
        public Request(String url, String method, String contentType, String body, int timeoutMs) {
            this.url=url; this.method=method; this.contentType=contentType; this.body=body; this.timeoutMs=timeoutMs;
        }
    }
    public static final class Response {
        public final int code;
        public final String url, body, location, link;
        public Response(int code,String url,String body,String location) {
            this(code,url,body,location,null);
        }
        public Response(int code,String url,String body,String location,String link) {
            this.code=code;this.url=url;this.body=body==null?"":body;this.location=location;this.link=link==null?"":link;
        }
    }
    public static final class FlowException extends IOException {
        public final String category;
        public FlowException(String category,String text) {super(category+": "+text);this.category=category;}
    }
    private WebFlow() {}
    public static String encode(String value) {
        try {return URLEncoder.encode(value,"UTF-8");}
        catch(java.io.UnsupportedEncodingException e) {throw new AssertionError(e);}
    }
    public static String host(String url) {
        try {String h=URI.create(url).getHost(); return h==null?"":h.toLowerCase(Locale.ROOT);}
        catch(IllegalArgumentException e){return "";}
    }
    /** Fudan SSO hops across many subdomains (ecard, ehall, jwfw…). Block only off-campus third parties. */
    public static boolean campusHost(String host) {
        if(host==null||host.isEmpty())return false;
        String h=host.toLowerCase(Locale.ROOT);
        if(h.equals("10.64.130.6"))return true;
        if(mailHost(h))return true;
        return h.equals("fudan.edu.cn")||h.endsWith(".fudan.edu.cn");
    }
    /** Student cloud mail is Tencent Exmail; login hops across a few qq.com hosts. */
    public static boolean mailHost(String host) {
        if(host==null||host.isEmpty())return false;
        String h=host.toLowerCase(Locale.ROOT);
        if(h.equals("exmail.qq.com")||h.endsWith(".exmail.qq.com"))return true;
        if(h.equals("mail.qq.com")||h.endsWith(".mail.qq.com"))return true;
        if(h.equals("ptlogin2.qq.com")||h.endsWith(".ptlogin2.qq.com"))return true;
        if(h.equals("id.qq.com")||h.endsWith(".id.qq.com"))return true;
        return false;
    }
    private static boolean allowed(String host, Set<String> extra) {
        return extra.contains(host) || campusHost(host);
    }
    public static String safeUrl(String url) {
        try {
            URI u=URI.create(url);
            return u.getScheme()+"://"+u.getHost()+(u.getRawPath()==null?"/":u.getRawPath());
        } catch(Exception e) {return "[invalid URL]";}
    }
    public static String resolve(String base,String location) throws FlowException {
        if(location==null) throw new FlowException("INVALID_REDIRECT","invalid redirect target");
        String loc=unescape(location.trim());
        if(loc.isEmpty()) throw new FlowException("INVALID_REDIRECT","invalid redirect target");
        loc=loc.split("[\\r\\n]")[0].trim();
        if((loc.startsWith("\"")&&loc.endsWith("\"")||loc.startsWith("'")&&loc.endsWith("'"))&&loc.length()>=2)
            loc=loc.substring(1,loc.length()-1).trim();
        loc=loc.replace("\\/","/").replace("\\u0026","&");
        String lower=loc.toLowerCase(Locale.ROOT);
        if(lower.startsWith("javascript:")||lower.startsWith("data:")||lower.startsWith("vbscript:")
                ||lower.startsWith("about:")||lower.startsWith("blob:")||lower.startsWith("weixin:")
                ||lower.startsWith("wechat:")||lower.startsWith("intent:"))
            throw new FlowException("INVALID_REDIRECT","invalid redirect target");
        try {
            URI baseUri=URI.create(base);
            URI u;
            try { u=baseUri.resolve(loc); }
            catch(IllegalArgumentException first){ u=baseUri.resolve(encodeUnsafe(loc)); }
            if (!Arrays.asList("https","http").contains(u.getScheme()) || u.getHost()==null || u.getUserInfo()!=null)
                throw new IllegalArgumentException("scheme/host/userinfo");
            if ("https".equalsIgnoreCase(baseUri.getScheme()) && "http".equalsIgnoreCase(u.getScheme()))
                throw new FlowException("INSECURE_REDIRECT","HTTPS downgrade rejected");
            return u.toString();
        } catch(FlowException e) { throw e; }
        catch(IllegalArgumentException e) {throw new FlowException("INVALID_REDIRECT","invalid redirect target");}
    }
    static String encodeUnsafe(String loc) {
        StringBuilder sb=new StringBuilder();
        for(int i=0;i<loc.length();i++){
            char c=loc.charAt(i);
            boolean unsafe=c<=32||c==127||c>126||"<>\"{}|\\^`".indexOf(c)>=0;
            if(unsafe){
                byte[] bytes;
                try { bytes=String.valueOf(c).getBytes("UTF-8"); }
                catch(java.io.UnsupportedEncodingException e){ bytes=new byte[]{(byte)c}; }
                for(byte b:bytes) sb.append(String.format(Locale.US,"%%%02X",b&0xff));
            } else sb.append(c);
        }
        return sb.toString();
    }
    public static String unescape(String s) {
        if(s==null)return "";
        return s.replace("&"+"quot;","\"").replace("&#39;","'").replace("&"+"apos;","'")
                .replace("&"+"lt;","<").replace("&"+"gt;",">").replace("&"+"amp;","&");
    }
    public static boolean hasPasswordField(String body) {
        if(body==null)return false;
        return body.toLowerCase(Locale.ROOT).matches("(?s).*<input\\b[^>]*type\\s*=\\s*['\"]?password(?:['\"\\s>]).*");
    }
    public static boolean loginPage(Response r) {
        String h=host(r.url);String b=r.body.toLowerCase(Locale.ROOT);
        String path;
        try {path=URI.create(r.url).getPath();}catch(Exception e){path="";}
        return h.equals("id.fudan.edu.cn") || h.equals("uis.fudan.edu.cn")
                || hasPasswordField(r.body)
                || b.contains("未经身份验证") || b.contains("unauthenticated")
                || (path!=null && path.contains("/login") && b.contains("统一身份认证"));
    }
    public static void requireHttpSuccess(Response r) throws FlowException {
        if(r.code==401||r.code==403)throw new FlowException("AUTH_REQUIRED","HTTP "+r.code+" "+safeUrl(r.url));
        if(r.code<200||r.code>=400)throw new FlowException("HTTP_ERROR","HTTP "+r.code+" "+safeUrl(r.url));
        if(loginPage(r))throw new FlowException("AUTH_REQUIRED","登录尚未完成 "+safeUrl(r.url));
        if(r.code>=300)throw new FlowException("HTTP_ERROR","unresolved HTTP "+r.code+" "+safeUrl(r.url));
    }
    /** Detect only a direct navigation string, never evaluate arbitrary downloaded JavaScript. */
    public static String navigation(Response r) {
        String b=r.body;
        String[] ps={
            "(?:var\\s+)?locationValue\\s*=\\s*['\"]([^'\"]+)['\"]",
            "(?:(?:window|document|self|parent|top)\\.)?location(?:\\.href)?\\s*=\\s*['\"]([^'\"]+)['\"]",
            "(?:(?:window|document|self|parent|top)\\.)?location\\.(?:replace|assign)\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)"
        };
        for(String p:ps){Matcher m=Pattern.compile(p).matcher(b);if(m.find())return unescape(m.group(1));}
        Matcher meta=Pattern.compile("(?is)<meta\\b[^>]*http-equiv\\s*=\\s*['\"]?refresh['\"]?[^>]*content\\s*=\\s*['\"]\\s*\\d+\\s*;\\s*url=([^'\"]+)['\"]").matcher(b);
        if(meta.find())return unescape(meta.group(1).trim());
        Matcher target=Pattern.compile("(?:\\btarget|\\burl)\\s*=\\s*['\"]?(https?://[^\\s'\";<>]+)").matcher(b);
        if(target.find())return unescape(target.group(1));
        return null;
    }
    public static String relNext(String link) {
        if(link==null||link.isEmpty())return null;
        Matcher m=Pattern.compile("<([^>]+)>\\s*;\\s*rel\\s*=\\s*['\"]?next['\"]?",Pattern.CASE_INSENSITIVE).matcher(link);
        return m.find()?m.group(1).trim():null;
    }
    public static Response follow(Transport t,Request first, boolean stopAtLogin, Set<String> allowedHosts) throws Exception {
        return follow(t, first, stopAtLogin, allowedHosts, true);
    }
    public static Response follow(Transport t,Request first, boolean stopAtLogin, Set<String> allowedHosts, boolean followJs) throws Exception {
        long deadline=System.nanoTime()+45_000_000_000L;
        Request req=first;
        Set<String> seen=new HashSet<>();
        Response last=null;
        for(int hop=0;hop<16;hop++) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("cancelled");
            long remaining=(deadline-System.nanoTime())/1_000_000L;
            if(remaining<=0)throw new FlowException("TIMEOUT","redirect flow exceeded 45 seconds");
            if(!allowed(host(req.url),allowedHosts))throw new FlowException("UNTRUSTED_REDIRECT",safeUrl(req.url));
            String key=canonical(req.url);
            if(!seen.add(key)) return last!=null?last:new Response(200,req.url,"",null);
            Response r=t.request(new Request(req.url,req.method,req.contentType,req.body,(int)Math.min(req.timeoutMs,remaining)));
            last=r;
            String loc=null;
            boolean httpRedirect=r.code==301||r.code==302||r.code==303||r.code==307||r.code==308;
            if(httpRedirect)loc=r.location;
            else if(followJs && r.code>=200&&r.code<300){
                if(hasPasswordField(r.body)) return r;
                loc=navigation(r);
            }
            if(loc==null||loc.isEmpty())return r;
            String next;
            try { next=resolve(r.url,loc); }
            catch(FlowException e){
                if("INVALID_REDIRECT".equals(e.category)) return r;
                throw e;
            }
            if(seen.contains(canonical(next))) return r;
            if(!allowed(host(next),allowedHosts)){
                if(!httpRedirect && r.code>=200 && r.code<300) return r;
                throw new FlowException("UNTRUSTED_REDIRECT",safeUrl(next));
            }
            if(stopAtLogin && host(next).equals("id.fudan.edu.cn") && next.contains("lck="))
                return new Response(r.code,next,r.body,next,r.link);
            boolean keep=httpRedirect && (r.code==307||r.code==308);
            if(keep && req.body!=null && !host(next).equals(host(req.url)))
                throw new FlowException("UNTRUSTED_REDIRECT","cross-host request-body forwarding refused");
            req=new Request(next,keep?req.method:"GET",keep?req.contentType:null,keep?req.body:null,first.timeoutMs);
        }
        return last!=null?last:new Response(200,first.url,"",null);
    }
    public static String canonical(String url) {
        if(url==null)return "";
        int hash=url.indexOf('#');
        return hash>=0?url.substring(0,hash):url;
    }
}
