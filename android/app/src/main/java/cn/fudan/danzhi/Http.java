package cn.fudan.danzhi;

import cn.fudan.danzhi.core.WebFlow;
import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

final class Http {
    static {
        try {
            System.setProperty("http.keepAlive", "false");
            System.setProperty("http.maxConnections", "4");
        } catch (SecurityException ignored) {}
    }
    // Package-private instrumentation seam. Release builds cannot enable fixture transport.
    private static volatile WebFlow.Transport testTransport;
    static void useTestTransport(WebFlow.Transport transport) {
        if (!BuildConfig.DEBUG) throw new SecurityException("Fixture transport is debug-only");
        testTransport = transport;
    }
    /** Desktop Chrome. Mobile UA makes ecard/ehall 302 into WeChat. */
    static final String UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    /** Official 生活码 H5 is an iPhone Safari page; WeChat UA often returns a mini-program shell. */
    static final String UA_IPHONE="Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    static final String UA_WECHAT="Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/116.0.0.0 Mobile Safari/537.36 MMWEBID/4478 MicroMessenger/8.0.44.2502(0x28002C51) WeChat/arm64 NetType/WIFI Language/zh_CN miniProgram/wx9f1c2e7e5a7b3c4d";
    static class Resp {
        final int code;final String body,url,location,link;
        Resp(int code,String body,String url,String location){this(code,body,url,location,"");}
        Resp(int code,String body,String url,String location,String link){
            this.code=code;this.body=body;this.url=url;this.location=location;this.link=link==null?"":link;
        }
        WebFlow.Response flow(){return new WebFlow.Response(code,url,body,location,link);}
    }
    static Resp fetch(CookieJar jar,String url,String method,String ct,String body,int timeout) throws Exception {
        return fetch(jar,url,method,ct,body,timeout,null,null);
    }
    static Resp fetch(CookieJar jar,String url,String method,String ct,String body,int timeout,String referer) throws Exception {
        return fetch(jar,url,method,ct,body,timeout,referer,null);
    }
    static Resp fetch(CookieJar jar,String url,String method,String ct,String body,int timeout,String referer,String ua) throws Exception {
        String original=url;
        String target=jar.viaVpn&&Vpn.isCampus(url)?Vpn.wrap(url):url;
        int hash=target.indexOf('#');if(hash>=0)target=target.substring(0,hash);
        WebFlow.Transport fixture = BuildConfig.DEBUG ? testTransport : null;
        if (fixture != null) {
            WebFlow.Response response = fixture.request(new WebFlow.Request(target,method,ct,body,timeout));
            return new Resp(response.code,response.body,response.url,response.location,response.link);
        }
        IOException lastIo=null;
        int attempts="POST".equalsIgnoreCase(method)?2:4;
        String originalHost=WebFlow.host(original);
        boolean elearnHost=originalHost.contains("elearning");
        if(elearnHost) attempts=5;
        for(int attempt=0;attempt<attempts;attempt++){
            if(attempt>0){
                try { Thread.sleep(220L*attempt); } catch(InterruptedException ie){ Thread.currentThread().interrupt(); throw ie; }
            }
            try {
                String tryUrl=target;
                if(attempt>0 && elearnHost && "GET".equalsIgnoreCase(method) && tryUrl.indexOf('#')<0){
                    tryUrl += (tryUrl.contains("?")?"&":"?")+"_r="+attempt+"&_="+System.currentTimeMillis();
                }
                return fetchOnce(jar,original,tryUrl,method,ct,body,timeout,referer,ua,attempt>0 || elearnHost);
            } catch(IOException e){
                lastIo=e;
                if(!staleStream(e) && attempt+1>=attempts) throw e;
                if(!staleStream(e) && attempt==0) continue;
            }
        }
        if(lastIo!=null) throw lastIo;
        throw new IOException("empty http attempt");
    }
    static boolean staleStream(Throwable e) {
        for(int i=0;i<6 && e!=null;i++){
            if(e instanceof java.util.zip.ZipException) return true;
            String m=e.getMessage()==null?"":e.getMessage().toLowerCase(Locale.ROOT);
            if(m.contains("unexpected end of stream")||m.contains("connection reset")
                    ||m.contains("broken pipe")||m.contains("connection closed")
                    ||m.contains("software caused connection abort")||m.contains("gzip"))
                return true;
            e=e.getCause();
        }
        return false;
    }
    private static Resp fetchOnce(CookieJar jar,String original,String target,String method,String ct,String body,int timeout,String referer,String ua,boolean skipGzip) throws Exception {
        try { System.setProperty("http.keepAlive", "false"); } catch (SecurityException ignored) {}
        HttpURLConnection c=(HttpURLConnection)new URL(target).openConnection();
        try{
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(timeout);c.setReadTimeout(timeout);c.setRequestMethod(method);
            c.setUseCaches(false);
            c.setRequestProperty("Connection","close");
            String agent=ua!=null&&!ua.isEmpty()?ua:UA;
            c.setRequestProperty("User-Agent",agent);
            c.setRequestProperty("Cache-Control","no-cache, no-store");
            c.setRequestProperty("Pragma","no-cache");
            String originalHost=WebFlow.host(original);
            boolean lan="10.64.130.6".equals(originalHost);
            boolean api=original.contains("/api/")||original.contains(".json")||original.contains("/cgi-bin/");
            boolean printData=original.contains("/print-data");
            boolean getData=original.contains("/get-data");
            boolean jwgl=originalHost.contains("fdjwgl")||original.contains("course-table");
            boolean elearn=originalHost.contains("elearning");
            String accept;
            if(lan) accept="*/*";
            else if(getData) accept="application/json, text/javascript, text/html, */*;q=0.1";
            else if(printData || jwgl) accept="text/html,application/xhtml+xml,*/*;q=0.8";
            else if(api) accept="application/json, */*;q=0.8";
            else accept="text/html,application/xhtml+xml,*/*;q=0.8";
            c.setRequestProperty("Accept", accept);
            c.setRequestProperty("Accept-Language","zh-CN,zh;q=0.9");
            if(elearn || skipGzip) c.setRequestProperty("Accept-Encoding","identity");
            else if(!lan && !printData) c.setRequestProperty("Accept-Encoding","gzip");
            if(WebFlow.ajaxHeader(original) || WebFlow.ajaxHeader(target))
                c.setRequestProperty("X-Requested-With","XMLHttpRequest");
            String cookie=jar.headerFor(target);if(!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);
            if(target.contains("/cgi-bin/login")){
                c.setRequestProperty("Accept","application/json, text/javascript, */*; q=0.01");
            }
            if(WebFlow.host(target).equals("id.fudan.edu.cn")){
                c.setRequestProperty("Origin",FudanClient.IDP);
                String ref = referer != null && WebFlow.host(referer).equals("id.fudan.edu.cn") ? referer : FudanClient.IDP+"/ac-h5/";
                int fragment = ref.indexOf('#'); if(fragment >= 0) ref=ref.substring(0,fragment);
                c.setRequestProperty("Referer",ref);
            } else if(referer!=null && !referer.isEmpty()) {
                int fragment = referer.indexOf('#');
                c.setRequestProperty("Referer", fragment>=0?referer.substring(0,fragment):referer);
            }
            if(body!=null){
                byte[] bytes=body.getBytes(StandardCharsets.UTF_8);c.setDoOutput(true);c.setFixedLengthStreamingMode(bytes.length);
                c.setRequestProperty("Content-Type",ct==null?"application/json":ct);
                String h=WebFlow.host(target);
                if(WebFlow.mailHost(h) || "mail.m.fudan.edu.cn".equals(h))
                    c.setRequestProperty("Origin", "https://"+h);
                try(OutputStream os=c.getOutputStream()){os.write(bytes);}
            }
            int code=c.getResponseCode();jar.absorb(target,c.getHeaderFields());
            String text="";InputStream input=code>=400?c.getErrorStream():c.getInputStream();
            if(input!=null){
                if("gzip".equalsIgnoreCase(c.getContentEncoding())){
                    try { input=new GZIPInputStream(input); }
                    catch(java.util.zip.ZipException e){ throw new IOException("gzip", e); }
                }
                try(InputStream in=input){
                    byte[] raw=readBytes(in);
                    text=new String(raw, charsetOf(c.getContentType(), originalHost, raw));
                }
            }
            return new Resp(code,text,target,c.getHeaderField("Location"),c.getHeaderField("Link"));
        }finally{c.disconnect();}
    }
    static Resp follow(CookieJar jar,String url)throws Exception {return follow(jar,url,false,null);}
    static Resp follow(CookieJar jar,String url,boolean stopAtLogin)throws Exception {return follow(jar,url,stopAtLogin,null);}
    static Resp follow(CookieJar jar,String url,boolean stopAtLogin,String ua)throws Exception {
        Set<String> hosts=new HashSet<>(Arrays.asList(WebFlow.host(url),"id.fudan.edu.cn","uis.fudan.edu.cn",
            "my.fudan.edu.cn","elearning.fudan.edu.cn","ecard.fudan.edu.cn","fdjwgl.fudan.edu.cn","jwfw.fudan.edu.cn",
            "ehall.fudan.edu.cn","workflow1.fudan.edu.cn","mail.m.fudan.edu.cn","exmail.qq.com","ptlogin2.qq.com","ssl.ptlogin2.qq.com",
            "mail.qq.com","w.mail.qq.com","id.qq.com","webvpn.fudan.edu.cn","10.64.130.6"));
        final String agent=ua;
        WebFlow.Response r=WebFlow.follow(req->fetch(jar,req.url,req.method,req.contentType,req.body,req.timeoutMs,null,agent).flow(),
            new WebFlow.Request(url,"GET",null,null,15000),stopAtLogin,hosts);
        return new Resp(r.code,r.body,r.url,r.location,r.link);
    }
    static Resp followPost(CookieJar jar,String url,String ct,String body,String referer)throws Exception {
        Set<String> hosts=new HashSet<>(Arrays.asList(WebFlow.host(url),"id.fudan.edu.cn","uis.fudan.edu.cn",
            "my.fudan.edu.cn","elearning.fudan.edu.cn","ecard.fudan.edu.cn","fdjwgl.fudan.edu.cn","jwfw.fudan.edu.cn",
            "ehall.fudan.edu.cn","workflow1.fudan.edu.cn","mail.m.fudan.edu.cn","exmail.qq.com","ptlogin2.qq.com","ssl.ptlogin2.qq.com",
            "mail.qq.com","w.mail.qq.com","id.qq.com","webvpn.fudan.edu.cn","10.64.130.6"));
        WebFlow.Response r=WebFlow.follow(req->fetch(jar,req.url,req.method,req.contentType,req.body,req.timeoutMs,referer).flow(),
            new WebFlow.Request(url,"POST",ct,body,18000),false,hosts,false);
        return new Resp(r.code,r.body,r.url,r.location,r.link);
    }
    static String readAll(InputStream in)throws Exception{
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }
    static byte[] readBytes(InputStream in)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n,total=0;
        while((n=in.read(buf))!=-1){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("cancelled");
            total+=n;if(total>4*1024*1024)throw new IOException("响应超过 4 MiB，停止读取");out.write(buf,0,n);
        }
        return out.toByteArray();
    }
    static Charset charsetOf(String contentType,String host,byte[] raw) {
        if(contentType!=null){
            String ct=contentType.toLowerCase(Locale.ROOT);
            if(ct.contains("gb2312")||ct.contains("gbk")||ct.contains("gb18030"))return Charset.forName("GB18030");
            int i=ct.indexOf("charset=");
            if(i>=0){
                String name=ct.substring(i+8).replace("\"","").replace("'","");
                int cut=name.indexOf(';');if(cut>=0)name=name.substring(0,cut);
                name=name.trim();
                try { if(!name.isEmpty()) return Charset.forName(name); } catch(Exception ignored) {}
            }
        }
        if("10.64.130.6".equals(host)) {
            try {
                String utf=new String(raw, StandardCharsets.UTF_8);
                if(utf.contains("\"status\"")) return StandardCharsets.UTF_8;
            } catch(Exception ignored) {}
            return Charset.forName("GB18030");
        }
        if(host!=null && (host.endsWith("exmail.qq.com") || host.endsWith("mail.qq.com") || "mail.m.fudan.edu.cn".equals(host))) {
            try {
                String head=new String(raw, 0, Math.min(raw.length, 1200), StandardCharsets.ISO_8859_1).trim();
                String low=head.toLowerCase(Locale.ROOT);
                if(head.startsWith("{")||head.startsWith("[")||low.startsWith("while(1);")) return StandardCharsets.UTF_8;
                if(low.contains("gb2312")||low.contains("gbk")||low.contains("gb18030")) return Charset.forName("GB18030");
                if(low.contains("<!doctype")||low.contains("<html")||low.contains("<script")) return Charset.forName("GB18030");
            } catch(Exception ignored) {}
            return Charset.forName("GB18030");
        }
        return StandardCharsets.UTF_8;
    }
}
