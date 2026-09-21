package cn.fudan.danzhi;

import cn.fudan.danzhi.core.WebFlow;
import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

final class Http {
    // Package-private instrumentation seam. Release builds cannot enable fixture transport.
    private static volatile WebFlow.Transport testTransport;
    static void useTestTransport(WebFlow.Transport transport) {
        if (!BuildConfig.DEBUG) throw new SecurityException("Fixture transport is debug-only");
        testTransport = transport;
    }
    /** Desktop Chrome. Mobile UA makes ecard/ehall 302 into WeChat. */
    static final String UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
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
        HttpURLConnection c=(HttpURLConnection)new URL(target).openConnection();
        try{
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(timeout);c.setReadTimeout(timeout);c.setRequestMethod(method);
            c.setUseCaches(false);
            String agent=ua!=null&&!ua.isEmpty()?ua:UA;
            c.setRequestProperty("User-Agent",agent);
            c.setRequestProperty("Cache-Control","no-cache, no-store");
            c.setRequestProperty("Pragma","no-cache");
            String originalHost=WebFlow.host(original);
            boolean lan="10.64.130.6".equals(originalHost);
            c.setRequestProperty("Accept",lan?"*/*":"application/json, text/html, */*");
            c.setRequestProperty("Accept-Language","zh-CN,zh;q=0.9");
            if(!lan)c.setRequestProperty("Accept-Encoding","gzip");
            String cookie=jar.headerFor(target);if(!cookie.isEmpty())c.setRequestProperty("Cookie",cookie);
            if(target.contains("/cgi-bin/login")
                    || target.contains("t=mail_list.json")
                    || target.contains("t=today.json")
                    || target.contains("qrcode") || target.contains("getQr") || target.contains("getqr")){
                c.setRequestProperty("X-Requested-With","XMLHttpRequest");
            }
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
                if("gzip".equalsIgnoreCase(c.getContentEncoding()))input=new GZIPInputStream(input);
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
            "ehall.fudan.edu.cn","mail.m.fudan.edu.cn","exmail.qq.com","ptlogin2.qq.com","ssl.ptlogin2.qq.com",
            "mail.qq.com","w.mail.qq.com","id.qq.com","webvpn.fudan.edu.cn","10.64.130.6"));
        final String agent=ua;
        WebFlow.Response r=WebFlow.follow(req->fetch(jar,req.url,req.method,req.contentType,req.body,req.timeoutMs,null,agent).flow(),
            new WebFlow.Request(url,"GET",null,null,15000),stopAtLogin,hosts);
        return new Resp(r.code,r.body,r.url,r.location,r.link);
    }
    static Resp followPost(CookieJar jar,String url,String ct,String body,String referer)throws Exception {
        Set<String> hosts=new HashSet<>(Arrays.asList(WebFlow.host(url),"id.fudan.edu.cn","uis.fudan.edu.cn",
            "my.fudan.edu.cn","elearning.fudan.edu.cn","ecard.fudan.edu.cn","fdjwgl.fudan.edu.cn","jwfw.fudan.edu.cn",
            "ehall.fudan.edu.cn","mail.m.fudan.edu.cn","exmail.qq.com","ptlogin2.qq.com","ssl.ptlogin2.qq.com",
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
