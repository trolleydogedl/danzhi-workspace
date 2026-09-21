package cn.fudan.danzhi;

import cn.fudan.danzhi.core.PageParser;
import cn.fudan.danzhi.core.RsaPkcs1;
import cn.fudan.danzhi.core.WebFlow;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.mail.*;
import javax.mail.internet.InternetAddress;

/**
 * Student cloud mail is Tencent Exmail: 学号@m.fudan.edu.cn + UIS password at exmail.qq.com/login.
 * CAS webmail is preferred; password CGI and IMAP are fallbacks.
 */
final class MailClient {
    static final String EXMAIL="https://mail.m.fudan.edu.cn";
    static final String EXMAIL_LOGIN="https://exmail.qq.com/login";
    static final String EXMAIL_LOGINPAGE="https://exmail.qq.com/cgi-bin/loginpage";
    static final String FUDAN_LOGINPAGE="https://mail.m.fudan.edu.cn/cgi-bin/loginpage";
    static final String IMAP_HOST="imap.exmail.qq.com";
    static final String DEFAULT_MODULUS="CF87D7B4C864F4842F1D337491A48FFF54B73A17300E8E42FA365420393AC0346AE55D8AFAD975DFA175FAF0106CBA81AF1DDE4ACEC284DAC6ED9A0D8FEB1CC070733C58213EFFED46529C54CEA06D774E3CC7E073346AEBD6C66FC973F299EB74738E400B22B1E7CDC54E71AED059D228DFEB5B29C530FF341502AE56DDCFE9";

    /** Optional WebView hook set by MainActivity. Runs on a background thread. */
    interface BrowserHook { JSONArray loginAndList(FudanClient client, String email, String password) throws Exception; }
    static volatile BrowserHook browser;

    static JSONArray fetch(FudanClient client,String username,String credential)throws Exception {
        Exception last=null;
        String uis=client.password;
        String extra=credential!=null&&!credential.isEmpty()?credential:"";
        String[] secrets=extra.isEmpty()?new String[]{uis}:new String[]{uis,extra};
        client.mailListConfirmed=false;
        // 0. Reuse a live sid — listing is cheap; re-login is the slow path.
        if(client.mailSid!=null && !client.mailSid.isEmpty()){
            try {
                JSONArray cached=listBySid(client, client.jar, client.mailSid, "", 1);
                if(cached!=null && (cached.length()>0 || client.mailListConfirmed)){
                    client.mailAuthBlocked=false;
                    return cached;
                }
            } catch(Exception e){ last=e; }
        }
        // 1. Classic CGI (fast). Only trust a real list payload, never "got a sid and 0 rows".
        for(String secret:secrets){
            if(secret==null||secret.isEmpty())continue;
            try {
                JSONArray web=viaExmailPassword(client,username,secret);
                if(web!=null && (web.length()>0 || client.mailListConfirmed)){
                    client.mailAuthBlocked=false;
                    return web;
                }
                last=new WebFlow.FlowException("MAIL_SID_MISSING","企业邮 CGI 未列出收件箱");
            } catch(WebFlow.FlowException e){
                last=e;
                if("MAIL_CAPTCHA".equals(e.category)) throw e;
            } catch(Exception e){ last=e; }
        }
        // 2. CAS hop — UIS session is already live after login.
        try {
            JSONArray web=viaCasHttp(client);
            if(web!=null && (web.length()>0 || client.mailListConfirmed)){
                client.mailAuthBlocked=false;
                return web;
            }
        } catch(Exception e){ last=e; }
        // 3. Hidden WebView — same path as a real browser, including JS RSA.
        if(browser!=null){
            for(String secret:secrets){
                if(secret==null||secret.isEmpty())continue;
                try {
                    JSONArray web=browser.loginAndList(client, emailOf(username), secret);
                    if(web!=null && (web.length()>0 || client.mailListConfirmed)){
                        client.mailAuthBlocked=false;
                        return web;
                    }
                    if(web!=null) last=new WebFlow.FlowException("MAIL_LIST_FAILED","网页邮箱已打开但未解析到信件");
                } catch(Exception e){ last=e; }
            }
        }
        // 4. IMAP only with a client-specific password. UIS password is rejected on imap.exmail.qq.com
        //    ("service is not open") and must not cover up a successful web login.
        if(!extra.isEmpty()){
            try {
                JSONArray imap=viaImap(client,username,extra);
                if(imap!=null){
                    client.mailAuthBlocked=false;
                    client.mailListConfirmed=true;
                    return imap;
                }
            } catch(AuthenticationFailedException e){ last=e; }
            catch(Exception e){ last=e; }
        }
        throw rewriteMailError(last, false);
    }

    private static Exception rewriteMailError(Exception last) {
        return rewriteMailError(last, false);
    }

    private static Exception rewriteMailError(Exception last, boolean imapTried) {
        String detail = last==null ? "" : Diagnostics.message(last);
        if(last instanceof AuthenticationFailedException)
            return new WebFlow.FlowException("MAIL_AUTH_REJECTED","云邮箱拒绝了账密。请确认 学号@m.fudan.edu.cn 与 UIS 密码能打开 exmail.qq.com/login");
        if(last instanceof WebFlow.FlowException){
            WebFlow.FlowException fe=(WebFlow.FlowException)last;
            if("MAIL_AUTH_REJECTED".equals(fe.category)||"MAIL_CAPTCHA".equals(fe.category)||"MAIL_ACCOUNT_INVALID".equals(fe.category))
                return fe;
        }
        String extra=imapTried?" 网页与 IMAP 都未能列出。":" ";
        if(last!=null) return new WebFlow.FlowException("MAIL_LIST_FAILED",
                "未能列出信件。已用 学号@m.fudan.edu.cn + UIS 密码直登 exmail。"+extra+"详情："+detail);
        return new WebFlow.FlowException("MAIL_LIST_FAILED","未能列出信件。请用 学号@m.fudan.edu.cn 与 UIS 密码打开 exmail.qq.com/login 确认");
    }

    static String emailOf(String username) throws WebFlow.FlowException {
        if(username==null||username.trim().isEmpty())throw new WebFlow.FlowException("MAIL_ACCOUNT_INVALID","缺少学号");
        String u=username.trim();
        if(u.contains("@")){
            if(!u.toLowerCase(Locale.ROOT).endsWith("@m.fudan.edu.cn"))
                throw new WebFlow.FlowException("MAIL_ACCOUNT_INVALID","当前适配学生云邮箱 @m.fudan.edu.cn");
            return u;
        }
        return u+"@m.fudan.edu.cn";
    }

    private static void enterCas(FudanClient client) throws Exception {
        viaCasHttp(client);
    }

    private static JSONArray viaCasHttp(FudanClient client) throws Exception {
        String[] entries={
            EXMAIL+"/",
            FudanClient.IDP+"/authserver/login?service="+WebFlow.encode(EXMAIL+"/"),
            EXMAIL+"/cgi-bin/loginpage",
            FUDAN_LOGINPAGE+"?s=ssl&from=&vt=separate&d=m.fudan.edu.cn",
            FUDAN_LOGINPAGE
        };
        Exception last=null;
        for(String url:entries){
            try {
                Http.Resp r=Http.follow(client.jar,url,false);
                r=hopCgi(client.jar, r);
                if(WebFlow.loginPage(r.flow()) && sidOf(client.jar, r.url+" "+r.body).isEmpty()) continue;
                String sid=sidOf(client.jar, r.url+" "+r.body);
                if(sid.isEmpty()){
                    for(String frame:new String[]{
                        "https://exmail.qq.com/cgi-bin/frame_html",
                        "https://mail.m.fudan.edu.cn/cgi-bin/frame_html",
                        "https://exmail.qq.com/cgi-bin/today"
                    }){
                        try {
                            Http.Resp frameR=Http.follow(client.jar, frame, false);
                            frameR=hopCgi(client.jar, frameR);
                            sid=sidOf(client.jar, frameR.url+" "+frameR.body);
                            if(!sid.isEmpty()){ r=frameR; break; }
                        } catch(Exception ignored) {}
                    }
                }
                if(sid.isEmpty()) continue;
                client.mailSid=sid;
                return listBySid(client, client.jar, sid, r.body, 1);
            } catch(WebFlow.FlowException e){
                if("MAIL_AUTH_REJECTED".equals(e.category)||"MAIL_CAPTCHA".equals(e.category)) throw e;
                last=e;
            } catch(Exception e){ last=e; }
        }
        if(last!=null) throw last;
        return null;
    }

    static JSONArray viaWebmail(FudanClient client) throws Exception {
        return viaCasHttp(client);
    }

    static JSONArray viaExmailPassword(FudanClient client,String username,String password) throws Exception {
        String email=emailOf(username);
        String user=email.substring(0,email.indexOf('@'));
        String[] pages={
            EXMAIL_LOGINPAGE+"?s=ssl&from=&vt=separate&d=m.fudan.edu.cn",
            EXMAIL_LOGIN,
            FUDAN_LOGINPAGE+"?s=ssl&from=&vt=separate&d=m.fudan.edu.cn"
        };
        Exception last=null;
        for(String pageUrl:pages){
            CookieJar jar=new CookieJar();
            try {
                Http.Resp page=Http.fetch(jar, pageUrl, "GET", null, null, 18000);
                page=followHttp(jar, page, 6, pageUrl);
                if(page.body.toLowerCase(Locale.ROOT).contains("verifycode") && page.body.contains("imgcode"))
                    throw new WebFlow.FlowException("MAIL_CAPTCHA","腾讯企业邮要求验证码，请稍后再试");
                String modulus=PageParser.exmailPublicKey(page.body);
                String ts=PageParser.exmailTs(page.body);
                if(modulus.isEmpty()) modulus=DEFAULT_MODULUS;
                if(ts.isEmpty()){
                    last=new WebFlow.FlowException("MAIL_SID_MISSING","登录页未给出时间戳，改试下一入口");
                    continue;
                }
                String cipher=RsaPkcs1.encryptToExmail(password+"\n"+ts+"\n", modulus, "10001");
                LinkedHashMap<String,String> fields=PageParser.formFields(page.body);
                fields.put("uin", user);
                fields.put("qquin", user);
                fields.put("inputuin", email);
                fields.put("account", email);
                fields.put("uinname", email);
                fields.put("domain", "m.fudan.edu.cn");
                fields.put("aliastype", "other");
                fields.put("firstlogin", "false");
                fields.put("delegate_from", "");
                fields.put("action", "");
                fields.put("from", "");
                fields.put("vt", "separate");
                fields.put("s", "ssl");
                fields.put("p", cipher);
                StringBuilder zeros=new StringBuilder();
                for(int i=0;i<password.length();i++) zeros.append('0');
                fields.put("pp", zeros.toString());
                fields.put("ts", ts);
                fields.put("chg", "0");
                String action=PageParser.exmailFormAction(page.body);
                if(action.isEmpty()) action="https://exmail.qq.com/cgi-bin/login";
                try {
                    if(action.startsWith("/")) action="https://"+WebFlow.host(page.url)+action;
                    else if(!action.startsWith("http")) action=WebFlow.resolve(page.url, action);
                } catch(WebFlow.FlowException e){
                    last=e; continue;
                }
                String host=WebFlow.host(action);
                if(!WebFlow.mailHost(host) && !WebFlow.campusHost(host))
                    throw new WebFlow.FlowException("UNTRUSTED_REDIRECT", WebFlow.safeUrl(action));
                String body=encodeForm(fields);
                Http.Resp land=Http.fetch(jar, action, "POST", "application/x-www-form-urlencoded", body, 18000, page.url);
                land=followHttp(jar, land, 8, page.url);
                String blob=land.url+" "+land.body;
                String jsonSid=sidFromLoginJson(land.body);
                if(jsonSid.isEmpty()) jsonSid=PageParser.exmailSid(land.body);
                if(!jsonSid.isEmpty()){
                    client.mailSid=jsonSid;
                    warmFrame(jar, jsonSid, land.url);
                    JSONArray listed=listBySid(client, jar, jsonSid, land.body, 1);
                    mergeCookies(client.jar, jar);
                    if(listed.length()>0 || client.mailListConfirmed) return listed;
                }
                land=hopCgi(jar, land);
                land=hopCgi(jar, land);
                land=hopCgi(jar, land);
                blob=land.url+" "+land.body;
                if(PageParser.looksLikeExmailPasswordError(blob) || PageParser.exmailCgiUrl(land.body).startsWith("ERR:PASSWORD"))
                    throw new WebFlow.FlowException("MAIL_AUTH_REJECTED","腾讯企业邮拒绝了账密（学号@m.fudan.edu.cn）");
                String sid=sidOf(jar, blob);
                if(sid.isEmpty()) sid=cgiRedirectSid(jar, land);
                if(sid.isEmpty()){
                    for(String frame:new String[]{
                        "https://exmail.qq.com/cgi-bin/frame_html",
                        "https://mail.m.fudan.edu.cn/cgi-bin/frame_html",
                        "https://exmail.qq.com/cgi-bin/today",
                        "https://exmail.qq.com/cgi-bin/login?fun=passport"
                    }){
                        try {
                            Http.Resp fr=Http.fetch(jar, frame, "GET", null, null, 15000, land.url);
                            fr=followHttp(jar, fr, 4, land.url);
                            sid=sidOf(jar, fr.url+" "+fr.body);
                            if(!sid.isEmpty()){ land=fr; break; }
                        } catch(Exception ignored) {}
                    }
                }
                if(sid.isEmpty()) { last=new WebFlow.FlowException("MAIL_SID_MISSING","exmail 登录后未返回 sid"); continue; }
                client.mailSid=sid;
                warmFrame(jar, sid, land.url);
                JSONArray listed=listBySid(client, jar, sid, land.body, 1);
                mergeCookies(client.jar, jar);
                if(listed.length()>0 || client.mailListConfirmed) return listed;
                last=new WebFlow.FlowException("MAIL_LIST_FAILED","已登录企业邮但未能解析收件箱");
            } catch(WebFlow.FlowException e){
                if("MAIL_CAPTCHA".equals(e.category)) throw e;
                last=e;
            } catch(Exception e){ last=e; }
        }
        if(last!=null) throw last;
        throw new WebFlow.FlowException("MAIL_SID_MISSING","exmail.qq.com 账密登录未完成");
    }

    private static Http.Resp hopCgi(CookieJar jar, Http.Resp land) {
        try {
            String js=PageParser.exmailCgiUrl(land.body==null?"":land.body);
            if(js==null||js.isEmpty()) js=WebFlow.navigation(land.flow());
            if(js==null||js.isEmpty()) return land;
            if(js.startsWith("ERR:")) return land;
            if(js.toLowerCase(Locale.ROOT).contains("loginpage")||js.toLowerCase(Locale.ROOT).contains("errtype"))
                return land;
            String next=WebFlow.resolve(land.url, js);
            if(!WebFlow.mailHost(WebFlow.host(next)) && !WebFlow.campusHost(WebFlow.host(next))) return land;
            Http.Resp hop=Http.fetch(jar, next, "GET", null, null, 15000, land.url);
            hop=followHttp(jar, hop, 6, land.url);
            return hop;
        } catch(Exception e){ return land; }
    }

    private static String cgiRedirectSid(CookieJar jar, Http.Resp land) {
        try {
            Http.Resp hop=hopCgi(jar, land);
            return sidOf(jar, hop.url+" "+hop.body);
        } catch(Exception e){ return ""; }
    }

    private static Http.Resp followHttp(CookieJar jar, Http.Resp start, int max, String referer) throws Exception {
        Http.Resp r=start;
        java.util.HashSet<String> seen=new java.util.HashSet<>();
        seen.add(WebFlow.canonical(r.url));
        for(int i=0;i<max;i++){
            if(r.code<300||r.code>=400||r.location==null||r.location.isEmpty()) return r;
            String next;
            try { next=WebFlow.resolve(r.url, r.location); }
            catch(WebFlow.FlowException e){ return r; }
            if(!WebFlow.mailHost(WebFlow.host(next)) && !WebFlow.campusHost(WebFlow.host(next))) return r;
            if(!seen.add(WebFlow.canonical(next))) return r;
            r=Http.fetch(jar, next, "GET", null, null, 15000, referer);
        }
        return r;
    }

    private static JSONArray viaImap(FudanClient client,String username,String cred) throws Exception {
        String email=emailOf(username);
        String local=email.substring(0,email.indexOf('@'));
        String[] users=new String[]{email, local};
        String[] hosts=new String[]{IMAP_HOST, "hw.imap.exmail.qq.com"};
        Exception last=null;
        for(String host:hosts){
            for(String user:users){
                try {
                    JSONArray inbox=listImapFolderWithUserHost(client, user, cred, host, new String[]{"INBOX"}, false);
                    if(inbox!=null) return inbox;
                } catch(AuthenticationFailedException e){ last=e; }
                catch(Exception e){ last=e; }
            }
        }
        if(last!=null) throw last;
        return null;
    }

    private static JSONArray listImapFolder(FudanClient client, String[] names) {
        String cred=client.mailPassword==null||client.mailPassword.isEmpty()?client.password:client.mailPassword;
        if(cred==null||cred.isEmpty()) return null;
        try { return listImapFolderWithUserHost(client, emailOf(client.username), cred, IMAP_HOST, names, true); }
        catch(Exception e){ return null; }
    }

    private static JSONArray listImapFolderWithUser(FudanClient client,String username,String cred,String[] names,boolean trash) throws Exception {
        return listImapFolderWithUserHost(client, username, cred, IMAP_HOST, names, trash);
    }

    private static JSONArray listImapFolderWithUserHost(FudanClient client,String username,String cred,String host,String[] names,boolean trash) throws Exception {
        Store store=null;Folder folder=null;
        try {
            String email=username!=null&&username.contains("@")?username:emailOf(username);
            Properties p=new Properties();
            p.put("mail.store.protocol","imaps");
            p.put("mail.imaps.ssl.enable","true");
            p.put("mail.imaps.ssl.checkserveridentity","true");
            p.put("mail.imaps.ssl.protocols","TLSv1.2 TLSv1.3");
            p.put("mail.imaps.connectiontimeout","8000");
            p.put("mail.imaps.timeout","8000");
            p.put("mail.imaps.writetimeout","8000");
            store=Session.getInstance(p).getStore("imaps");store.connect(host,993,email,cred);
            Folder pick=null;
            for(String name:names){
                try { Folder f=store.getFolder(name); if(f!=null && f.exists()){ pick=f; break; } } catch(Exception ignored){}
            }
            if(pick==null) return trash?new JSONArray():null;
            folder=pick;folder.open(Folder.READ_ONLY);
            JSONArray out=new JSONArray();int count=folder.getMessageCount();if(count==0)return out;
            if(!(folder instanceof UIDFolder))throw new WebFlow.FlowException("MAIL_UID_UNAVAILABLE","服务器未提供稳定邮件 UID，未用可变化的序号替代");
            UIDFolder uid=(UIDFolder)folder;long validity=uid.getUIDValidity();
            Message[] messages=folder.getMessages(Math.max(1,count-40),count);
            FetchProfile fp=new FetchProfile();fp.add(FetchProfile.Item.ENVELOPE);fp.add(FetchProfile.Item.FLAGS);fp.add(UIDFolder.FetchProfileItem.UID);folder.fetch(messages,fp);
            SimpleDateFormat iso=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.US);iso.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            for(int i=messages.length-1;i>=0;i--){
                Message m=messages[i];String title=m.getSubject();if(title==null||title.isEmpty())title="（无主题）";
                String from=m.getFrom()==null?"":InternetAddress.toString(m.getFrom());
                long id=uid.getUID(m);if(id<=0)throw new WebFlow.FlowException("MAIL_UID_UNAVAILABLE","无效邮件 UID");
                boolean unread=m.getFlags()==null||!m.getFlags().contains(Flags.Flag.SEEN);
                JSONObject o=new JSONObject().put("id","mail-"+validity+"-"+id).put("source","mail").put("kind","mail")
                    .put("title",title).put("summary",from).put("from",from).put("url",EXMAIL)
                    .put("unread",unread).put("mailUid",id).put("uidValidity",validity).put("trashed",trash);
                java.util.Date when=m.getReceivedDate();if(when==null)when=m.getSentDate();
                if(when!=null){o.put("receivedAt",when.getTime());o.put("publishedAt",iso.format(when));}
                out.put(o);
            }
            return out;
        } finally {
            if(folder!=null&&folder.isOpen())try{folder.close(false);}catch(MessagingException ignored){}
            if(store!=null&&store.isConnected())try{store.close();}catch(MessagingException ignored){}
        }
    }

    private static boolean looksLikeBadPassword(String blob) {
        return PageParser.looksLikeExmailPasswordError(blob);
    }

    private static JSONArray listBySid(FudanClient client, CookieJar jar, String sid, String landBody) throws Exception {
        return listBySid(client, jar, sid, landBody, 1);
    }

    static JSONArray listBySidPublic(FudanClient client, CookieJar jar, String sid, String landBody, int folder) throws Exception {
        return listBySid(client, jar, sid, landBody, folder);
    }

    private static JSONArray listBySid(FudanClient client, CookieJar jar, String sid, String landBody, int folder) throws Exception {
        LIST_SID.set(sid==null?"":sid);
        try {
            return listBySidInner(client, jar, sid, landBody, folder);
        } finally {
            LIST_SID.remove();
        }
    }

    private static final ThreadLocal<String> LIST_SID=new ThreadLocal<>();

    private static JSONArray listBySidInner(FudanClient client, CookieJar jar, String sid, String landBody, int folder) throws Exception {
        String enc=WebFlow.encode(sid);
        String ref="https://exmail.qq.com/cgi-bin/frame_html?sid="+enc;
        String[] urls={
            "https://exmail.qq.com/cgi-bin/mail_list?sid="+enc+"&folderid="+folder+"&page=0&s=list",
            "https://mail.m.fudan.edu.cn/cgi-bin/mail_list?sid="+enc+"&folderid="+folder+"&page=0&s=list",
            "https://exmail.qq.com/cgi-bin/mail_list?sid="+sid+"&folderid="+folder+"&page=0&s=list",
            "https://exmail.qq.com/cgi-bin/mail_list?sid="+enc+"&folderid="+folder+"&page=0&s=list&t=mail_list.htm",
            "https://exmail.qq.com/cgi-bin/mail_list?sid="+enc+"&folderid="+folder+"&page=0&s=list&t=mail_list.json",
            "https://mail.m.fudan.edu.cn/cgi-bin/mail_list?sid="+enc+"&folderid="+folder+"&page=0&s=list&t=mail_list.json",
            "https://exmail.qq.com/cgi-bin/mail_list?sid="+enc+"&folderid="+folder+"&page=0&s=list&ef=js&t=mail_list.json",
            "https://exmail.qq.com/cgi-bin/mail_list?sid="+enc+"&folderid="+folder+"&page=0&s=list&t=mail_list.xml",
            "https://exmail.qq.com/cgi-bin/mail_list?sid="+enc+"&folderid="+folder+"&page=0&s=list&topmails=1",
            "https://exmail.qq.com/cgi-bin/today?sid="+enc+"&t=today.json",
            "https://exmail.qq.com/cgi-bin/today?sid="+enc,
            "https://mail.m.fudan.edu.cn/cgi-bin/today?sid="+enc,
            "https://w.mail.qq.com/cgi-bin/today?sid="+enc
        };
        JSONArray out=new JSONArray();
        boolean confirmed=false;
        String form="sid="+enc+"&folderid="+folder+"&page=0&s=list";
        for(String url:urls){
            Http.Resp r=Http.fetch(jar,url,"GET",null,null,15000, ref);
            r=followHttp(jar,r,4,ref);
            if(r.code>=200&&r.code<400){
                if(PageParser.hasValuedMailid(r.body) || PageParser.exmailInboxCount(r.body)==0)
                    confirmed=PageParser.looksLikeMailList(r.body);
                collectWebMails(r.body,out);
                if(out.length()>0){
                    client.mailListConfirmed=true;
                    if(folder==4) markTrashed(out);
                    return out;
                }
            }
            try {
                Http.Resp p=Http.fetch(jar,url.contains("?")?url.substring(0,url.indexOf('?')):url,
                    "POST","application/x-www-form-urlencoded",form,15000,url);
                p=followHttp(jar,p,3,url);
                if(p.code>=200&&p.code<400){
                    if(PageParser.hasValuedMailid(p.body) || PageParser.exmailInboxCount(p.body)==0)
                        confirmed=PageParser.looksLikeMailList(p.body);
                    collectWebMails(p.body,out);
                    if(out.length()>0){
                        client.mailListConfirmed=true;
                        if(folder==4) markTrashed(out);
                        return out;
                    }
                }
            } catch(Exception ignored) {}
        }
        if(folder==1) collectWebMails(landBody,out);
        if(PageParser.hasValuedMailid(landBody) || PageParser.exmailInboxCount(landBody)==0)
            confirmed = confirmed || PageParser.looksLikeMailList(landBody);
        client.mailListConfirmed = client.mailListConfirmed || out.length()>0 || (confirmed && PageParser.exmailInboxCount(landBody)==0);
        if(folder==4) markTrashed(out);
        return out;
    }

    private static void warmFrame(CookieJar jar, String sid, String referer) {
        if(sid==null||sid.isEmpty()) return;
        String[] frames={
            "https://exmail.qq.com/cgi-bin/frame_html?sid="+WebFlow.encode(sid),
            "https://mail.m.fudan.edu.cn/cgi-bin/frame_html?sid="+WebFlow.encode(sid)
        };
        for(String frame:frames){
            try {
                Http.fetch(jar, frame, "GET", null, null, 12000, referer);
                return;
            } catch(Exception ignored) {}
        }
    }

    private static String sidFromLoginJson(String body) {
        if(body==null) return "";
        String t=body.trim();
        if(!t.startsWith("{")) return "";
        try {
            JSONObject j=new JSONObject(t);
            JSONObject data=j.optJSONObject("data");
            if(data!=null){
                String s=data.optString("sid");
                if(PageParser.validExmailSid(s)) return s;
            }
            String s=j.optString("sid");
            if(PageParser.validExmailSid(s)) return s;
        } catch(Exception ignored) {}
        return "";
    }

    private static void markTrashed(JSONArray out) {
        for(int i=0;i<out.length();i++){
            JSONObject it=out.optJSONObject(i);
            if(it==null)continue;
            try { it.put("trashed", true); } catch(Exception ignored) {}
        }
    }

    static JSONArray listTrash(FudanClient client) throws Exception {
        Exception last=null;
        if(client.mailSid!=null && !client.mailSid.isEmpty()){
            try {
                JSONArray web=listBySid(client, client.jar, client.mailSid, "", 4);
                if(web.length()>0) return web;
            } catch(Exception e){ last=e; }
        }
        JSONArray imap=listImapFolder(client, new String[]{"已删除","Deleted Messages","Trash","Deleted Items","Bin"});
        if(imap!=null) return imap;
        if(last!=null) throw last;
        return new JSONArray();
    }

    private static String sidOf(CookieJar jar, String blob) {
        String sid=PageParser.exmailSid(blob);
        if(!sid.isEmpty()) return sid;
        if(jar==null) return "";
        for(String name:new String[]{"qm_sid"}){
            String v=jar.cookie(name);
            if(PageParser.validExmailSid(v)) return v;
        }
        return "";
    }

    static JSONObject read(FudanClient client, JSONObject item) throws Exception {
        String id=item.optString("id");
        String mailid=item.optString("mailid", item.optString("id"));
        if(mailid.startsWith("mail-web-")) mailid=mailid.substring("mail-web-".length());
        String sid=client.mailSid;
        boolean webId=id.startsWith("mail-web-") || (item.has("mailid") && !item.optString("mailid").isEmpty());
        if(sid!=null && !sid.isEmpty() && !mailid.isEmpty() && webId){
            String[] urls={
                "https://exmail.qq.com/cgi-bin/readmail?sid="+WebFlow.encode(sid)+"&mailid="+WebFlow.encode(mailid)+"&folderid=1&mode=html&t=read.html",
                "https://mail.m.fudan.edu.cn/cgi-bin/readmail?sid="+WebFlow.encode(sid)+"&mailid="+WebFlow.encode(mailid)+"&folderid=1&t=read.html"
            };
            for(String url:urls){
                try {
                    Http.Resp r=Http.follow(client.jar,url,false);
                    if(r.code<200||r.code>=400)continue;
                    if(WebFlow.loginPage(r.flow()))continue;
                    String html=extractMailHtml(r.body);
                    if(html.length()<8) continue;
                    item.put("unread", false);
                    return new JSONObject().put("status","ok").put("id",id)
                        .put("title",item.optString("title"))
                        .put("html",html).put("url",item.optString("url",EXMAIL));
                } catch(Exception ignored) {}
            }
        }
        if(id.startsWith("mail-") && !id.startsWith("mail-web-")){
            String body=readImapBody(client, item);
            if(body!=null){
                item.put("unread", false);
                return new JSONObject().put("status","ok").put("id",id)
                    .put("title",item.optString("title")).put("html",body)
                    .put("url",item.optString("url",EXMAIL));
            }
        }
        String summary=item.optString("summary");
        return new JSONObject().put("status","ok").put("id",id)
            .put("title",item.optString("title"))
            .put("html","<p>"+esc(summary)+"</p><p>未能拉取正文，可稍后重试。</p>")
            .put("url",item.optString("url",EXMAIL));
    }

    static JSONObject trash(FudanClient client, JSONArray items) throws Exception {
        int n=0; int fail=0;
        for(int i=0;i<items.length();i++){
            JSONObject it=items.optJSONObject(i);
            if(it==null) continue;
            if(!"mail".equals(it.optString("source"))) { n++; continue; }
            try {
                if(trashOne(client, it)) n++;
                else fail++;
            } catch(Exception e){ fail++; }
        }
        JSONObject o=new JSONObject().put("status", fail==0?"ok":"partial").put("moved",n).put("failed",fail);
        o.put("message", fail==0 ? ("已将 "+n+" 封移入回收站") : ("移入回收站 "+n+" 封，失败 "+fail+" 封"));
        return o;
    }

    private static boolean trashOne(FudanClient client, JSONObject it) throws Exception {
        String mailid=it.optString("mailid", it.optString("id"));
        if(mailid.startsWith("mail-web-")) mailid=mailid.substring("mail-web-".length());
        String sid=client.mailSid;
        if(sid!=null && !sid.isEmpty() && !mailid.isEmpty() && !mailid.startsWith("mail-")){
            String form="mailid="+WebFlow.encode(mailid)+"&folderid=1&destid=4&sid="+WebFlow.encode(sid);
            String[] urls={
                "https://exmail.qq.com/cgi-bin/movemail?sid="+WebFlow.encode(sid)+"&t=atom",
                "https://exmail.qq.com/cgi-bin/mail_mgr?sid="+WebFlow.encode(sid)+"&fun=move",
                "https://mail.m.fudan.edu.cn/cgi-bin/movemail?sid="+WebFlow.encode(sid)
            };
            for(String url:urls){
                try {
                    Http.Resp r=Http.fetch(client.jar,url,"POST","application/x-www-form-urlencoded",form,8000);
                    if(r.code>=200 && r.code<400) return true;
                } catch(Exception ignored) {}
            }
        }
        if(it.optLong("mailUid",0)>0) return trashImap(client, it);
        return false;
    }

    private static boolean trashImap(FudanClient client, JSONObject it) {
        long uid=it.optLong("mailUid",0);
        if(uid<=0) return false;
        String cred=client.mailPassword==null||client.mailPassword.isEmpty()?client.password:client.mailPassword;
        if(cred==null||cred.isEmpty()) return false;
        Store store=null; Folder inbox=null;
        try {
            String email=emailOf(client.username);
            Properties p=new Properties();p.put("mail.store.protocol","imaps");p.put("mail.imaps.ssl.enable","true");
            p.put("mail.imaps.ssl.checkserveridentity","true");p.put("mail.imaps.connectiontimeout","8000");
            p.put("mail.imaps.timeout","8000");
            store=Session.getInstance(p).getStore("imaps");store.connect(IMAP_HOST,993,email,cred);
            inbox=store.getFolder("INBOX");inbox.open(Folder.READ_WRITE);
            if(!(inbox instanceof UIDFolder)) return false;
            Message m=((UIDFolder)inbox).getMessageByUID(uid);
            if(m==null) return false;
            Folder dest=null;
            for(String name:new String[]{"已删除","Deleted Messages","Trash","Deleted Items","Bin"}){
                try { dest=store.getFolder(name); if(dest.exists()) break; dest=null; } catch(Exception ignored){ dest=null; }
            }
            if(dest!=null){
                inbox.copyMessages(new Message[]{m}, dest);
            }
            m.setFlag(Flags.Flag.DELETED, true);
            inbox.close(true); inbox=null;
            return true;
        } catch(Exception e){ return false; }
        finally {
            try { if(inbox!=null&&inbox.isOpen()) inbox.close(false); } catch(Exception ignored) {}
            try { if(store!=null&&store.isConnected()) store.close(); } catch(Exception ignored) {}
        }
    }

    private static String readImapBody(FudanClient client, JSONObject item) {
        long uid=item.optLong("mailUid",0);
        if(uid<=0) return null;
        String cred=client.mailPassword==null||client.mailPassword.isEmpty()?client.password:client.mailPassword;
        if(cred==null||cred.isEmpty()) return null;
        Store store=null; Folder inbox=null;
        try {
            String email=emailOf(client.username);
            Properties p=new Properties();p.put("mail.store.protocol","imaps");p.put("mail.imaps.ssl.enable","true");
            p.put("mail.imaps.ssl.checkserveridentity","true");p.put("mail.imaps.connectiontimeout","8000");
            p.put("mail.imaps.timeout","12000");
            store=Session.getInstance(p).getStore("imaps");store.connect(IMAP_HOST,993,email,cred);
            inbox=store.getFolder("INBOX");inbox.open(Folder.READ_WRITE);
            if(!(inbox instanceof UIDFolder)) return null;
            Message m=((UIDFolder)inbox).getMessageByUID(uid);
            if(m==null) return null;
            try { m.setFlag(Flags.Flag.SEEN, true); } catch(Exception ignored) {}
            return extractPart(m);
        } catch(Exception e){ return null; }
        finally {
            try { if(inbox!=null&&inbox.isOpen()) inbox.close(false); } catch(Exception ignored) {}
            try { if(store!=null&&store.isConnected()) store.close(); } catch(Exception ignored) {}
        }
    }

    private static String extractPart(Part part) throws Exception {
        if(part.isMimeType("text/html")) return sanitize(String.valueOf(part.getContent()));
        if(part.isMimeType("text/plain")) return "<pre>"+esc(String.valueOf(part.getContent()))+"</pre>";
        if(part.isMimeType("multipart/*")){
            Multipart mp=(Multipart)part.getContent();
            String html=null, text=null;
            for(int i=0;i<mp.getCount();i++){
                Part p=mp.getBodyPart(i);
                if(p.isMimeType("text/html") && html==null) html=sanitize(String.valueOf(p.getContent()));
                else if(p.isMimeType("text/plain") && text==null) text="<pre>"+esc(String.valueOf(p.getContent()))+"</pre>";
                else if(p.isMimeType("multipart/*")){
                    String inner=extractPart(p);
                    if(inner!=null && html==null) html=inner;
                }
            }
            return html!=null?html:text;
        }
        return null;
    }

    static String sanitize(String html) {
        if(html==null) return "";
        String t=html;
        t=t.replaceAll("(?is)<script[^>]*>.*?</script>","");
        t=t.replaceAll("(?is)<style[^>]*>.*?</style>","");
        t=t.replaceAll("(?is)<iframe[^>]*>.*?</iframe>","");
        t=t.replaceAll("(?is)<object[^>]*>.*?</object>","");
        t=t.replaceAll("(?is)<embed[^>]*>","");
        t=t.replaceAll("(?is)<link[^>]*>","");
        t=t.replaceAll("(?is)\\son\\w+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)","");
        t=t.replaceAll("(?is)javascript:","");
        return t;
    }

    static String extractMailHtml(String body) {
        if(body==null) return "";
        Matcher m=Pattern.compile("(?is)<(?:div|table)\\b[^>]*(?:id|class)=['\"][^'\"]*(?:content|mailcontent|mail-content|body)[^'\"]*['\"][^>]*>([\\s\\S]{20,})</(?:div|table)>").matcher(body);
        if(m.find()) return sanitize(m.group(1));
        m=Pattern.compile("(?is)<body[^>]*>([\\s\\S]+)</body>").matcher(body);
        if(m.find()) return sanitize(m.group(1));
        return sanitize(body);
    }

    private static String encodeForm(Map<String,String> fields) {
        StringBuilder sb=new StringBuilder();
        for(Map.Entry<String,String> e:fields.entrySet()){
            if(sb.length()>0)sb.append('&');
            sb.append(WebFlow.encode(e.getKey())).append('=').append(WebFlow.encode(e.getValue()==null?"":e.getValue()));
        }
        return sb.toString();
    }

    private static void mergeCookies(CookieJar dest, CookieJar src) {
        try {
            JSONArray a=new JSONArray(dest.dump());
            JSONArray b=new JSONArray(src.dump());
            for(int i=0;i<b.length();i++) a.put(b.opt(i));
            dest.load(a.toString());
        } catch(Exception ignored) {}
    }

    static void collectWebMails(String body, JSONArray out) {
        if(body==null||body.isEmpty())return;
        for(PageParser.WebMail m:PageParser.exmailCheckboxMails(body)){
            String from=m.fromName==null?"":m.fromName;
            if(from.isEmpty()) from=m.fromAddr==null?"":m.fromAddr;
            else if(m.fromAddr!=null && !m.fromAddr.isEmpty() && !from.contains(m.fromAddr))
                from=from+" <"+m.fromAddr+">";
            putMail(out, m.title, from, m.mailid, m.totime, m.unread);
        }
        if(out.length()>0)return;
        String t=PageParser.unwrapCgi(body);
        String jsonish=PageParser.jsToJson(t);
        try {
            String probe=jsonish.trim();
            if(probe.startsWith("{")||probe.startsWith("[")){
                Object node=probe.startsWith("[")?new JSONArray(probe):new JSONObject(probe);
                collectColumnMails(node,out);
                if(out.length()>0)return;
                walkMailJson(node,out,0);
                if(out.length()>0)return;
            }
        } catch(Exception ignored) {}
        try {
            if(t.startsWith("{")||t.startsWith("[")){
                Object node=t.startsWith("[")?new JSONArray(t):new JSONObject(t);
                collectColumnMails(node,out);
                if(out.length()>0)return;
                walkMailJson(node,out,0);
                if(out.length()>0)return;
            }
        } catch(Exception ignored) {}
        collectJsMails(t,out);
        if(out.length()>0)return;
        String cgiObj=extractJsObject(body, "cgiData");
        if(cgiObj.isEmpty()) cgiObj=extractJsObject(body, "mailList");
        if(!cgiObj.isEmpty()){
            try {
                String conv=PageParser.jsToJson(cgiObj);
                Object node=new JSONObject(conv.startsWith("{")?conv:cgiObj);
                collectColumnMails(node,out);
                walkMailJson(node,out,0);
                if(out.length()>0)return;
            } catch(Exception ignored) {}
            collectJsMails(cgiObj,out);
            if(out.length()>0)return;
        }
        Matcher xml=Pattern.compile("(?is)<mail\\b[^>]*>(.*?)</mail>").matcher(body);
        while(xml.find()&&out.length()<40){
            String block=xml.group(1);
            String title=xmlTag(block,"subject");
            if(title.length()<1) title=xmlTag(block,"subj");
            if(title.length()<1) continue;
            String from=xmlTag(block,"from");
            if(from.isEmpty()) from=xmlTag(block,"sender");
            String mailid=xmlTag(block,"mailid");
            if(mailid.isEmpty()) mailid=xmlTag(block,"id");
            long when=Filter.parseTime(xmlTag(block,"date"));
            if(when==0) when=Filter.parseTime(xmlTag(block,"time"));
            boolean unread=block.contains("unread")||"1".equals(xmlTag(block,"new"))||"true".equalsIgnoreCase(xmlTag(block,"ifnew"));
            putMail(out, title, from, mailid, when, unread);
        }
        if(out.length()>0)return;
        Matcher row=Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr>").matcher(body);
        while(row.find()&&out.length()<40){
            String html=row.group(1);
            if(!html.toLowerCase(Locale.ROOT).contains("subject") && !html.contains("mailid") && !html.contains("onclick")) continue;
            String title=firstText(html, new String[]{"subject","black","tt"});
            if(title.length()<2) title=PageParser.text(html);
            if(title.length()<2||title.length()>200)continue;
            String from=firstText(html, new String[]{"from","sender"});
            long when=htmlTime(html);
            boolean unread=html.toLowerCase(Locale.ROOT).contains("unread")||html.contains("ifnew")||html.contains("F_n");
            String mailid="";
            Matcher mid=Pattern.compile("mailid=([0-9A-Za-z_\\-]+)").matcher(html);
            if(mid.find()) mailid=mid.group(1);
            putMail(out, title, from, mailid, when, unread);
        }
    }

    static void collectJsMails(String body, JSONArray out) {
        if(body==null||body.isEmpty())return;
        Matcher rec=Pattern.compile("\\{([^{}]{8,1600})\\}").matcher(body);
        while(rec.find()&&out.length()<40){
            String block=rec.group(1);
            String title=jsField(block,"tt","subject","title","subj","t");
            if(title.length()<1) continue;
            String mailid=jsField(block,"mailid","id","mid");
            String from=jsField(block,"dr","from","sender","fa","fn","f","fr");
            if(mailid.isEmpty() && from.isEmpty() && !block.contains("ifnew") && !block.contains("idate") && !block.contains("abs"))
                continue;
            long when=Filter.parseTime(jsField(block,"idate","date","time","d"));
            if(when==0){
                String raw=jsField(block,"sentDate","receivedDate","date");
                try { if(!raw.isEmpty()) when=Long.parseLong(raw); } catch(NumberFormatException ignored) {}
                if(when>0&&when<3_000_000_000L) when*=1000L;
            }
            boolean unread=block.contains("ifnew:1")||block.contains("\"ifnew\":1")||block.contains("unread")
                    ||"1".equals(jsField(block,"new","ifnew"));
            putMail(out, title, from, mailid, when, unread);
        }
    }

    private static void putMail(JSONArray out, String title, String from, String mailid, long when, boolean unread) {
        try {
            String id=mailid==null||mailid.isEmpty()?String.valueOf(Math.abs((title+when).hashCode())):mailid;
            String url=mailUrl(mailid);
            JSONObject o=new JSONObject().put("id","mail-web-"+id)
                .put("source","mail").put("kind","mail").put("title",title)
                .put("summary",from==null||from.isEmpty()?"学生云邮箱":from).put("from",from==null?"":from)
                .put("url",url).put("unread",unread).put("mailid",mailid==null?"":mailid);
            if(when>0){o.put("receivedAt",when);o.put("publishedAt",iso(when));}
            out.put(o);
        } catch(Exception ignored) {}
    }

    private static String mailUrl(String mailid) {
        String sid=LIST_SID.get();
        if(sid==null) sid="";
        if(mailid==null||mailid.isEmpty()) return EXMAIL;
        if(!sid.isEmpty())
            return EXMAIL+"/cgi-bin/readmail?sid="+WebFlow.encode(sid)+"&mailid="+WebFlow.encode(mailid)+"&folderid=1";
        return EXMAIL+"/cgi-bin/readmail?mailid="+WebFlow.encode(mailid);
    }

    private static String jsField(String block, String... keys) {
        if(block==null||keys==null) return "";
        for(String k:keys){
            Matcher m=Pattern.compile("(?:^|[,\\s{])"+Pattern.quote(k)+"\\s*:\\s*(?:'([^']*)'|\"([^\"]*)\"|([^,}\\s]+))").matcher(block);
            if(!m.find()) continue;
            String v=m.group(1)!=null?m.group(1):m.group(2)!=null?m.group(2):m.group(3);
            if(v==null) continue;
            v=v.trim();
            if(v.isEmpty()||"null".equals(v)||"undefined".equals(v)) continue;
            return v;
        }
        return "";
    }

    private static void collectColumnMails(Object node, JSONArray out) {
        if(node==null||out.length()>=40)return;
        if(node instanceof JSONArray){
            JSONArray a=(JSONArray)node;
            for(int i=0;i<a.length();i++) collectColumnMails(a.opt(i),out);
            return;
        }
        if(!(node instanceof JSONObject))return;
        JSONObject rec=(JSONObject)node;
        JSONArray titles=rec.optJSONArray("t");
        if(titles==null) titles=rec.optJSONArray("tt");
        JSONArray ids=rec.optJSONArray("ids");
        if(ids==null) ids=rec.optJSONArray("id");
        JSONArray froms=rec.optJSONArray("f");
        if(froms==null) froms=rec.optJSONArray("dr");
        if(froms==null) froms=rec.optJSONArray("nf");
        JSONArray dates=rec.optJSONArray("d");
        if(dates==null) dates=rec.optJSONArray("idate");
        JSONArray news=rec.optJSONArray("ifnew");
        if(news==null) news=rec.optJSONArray("new");
        if(titles!=null && titles.length()>0){
            int n=titles.length();
            for(int i=0;i<n && out.length()<40;i++){
                String title=titles.optString(i,"").trim();
                if(title.isEmpty()||"null".equals(title)) continue;
                String mailid=ids==null?"":ids.optString(i,"");
                String from=froms==null?"":froms.optString(i,"");
                long when=0;
                if(dates!=null){
                    String ds=dates.optString(i,"");
                    when=Filter.parseTime(ds);
                    if(when==0){
                        try {
                            when=Long.parseLong(ds);
                            if(when>0&&when<3_000_000_000L) when*=1000L;
                        } catch(NumberFormatException ignored) {}
                    }
                }
                boolean unread=false;
                if(news!=null){
                    unread=news.optInt(i,0)==1 || news.optBoolean(i,false) || "1".equals(news.optString(i));
                }
                putMail(out, title, from, mailid, when, unread);
            }
            return;
        }
        java.util.Iterator<String> keys=rec.keys();
        while(keys.hasNext()) collectColumnMails(rec.opt(keys.next()),out);
    }

    private static void walkMailJson(Object node, JSONArray out, int depth) {
        if(node==null||depth>8||out.length()>=40)return;
        if(node instanceof JSONArray){
            JSONArray a=(JSONArray)node;
            for(int i=0;i<a.length();i++) walkMailJson(a.opt(i),out,depth+1);
            return;
        }
        if(!(node instanceof JSONObject))return;
        JSONObject rec=(JSONObject)node;
        String title=jsonText(rec, "subject", "title", "t", "subj", "tt");
        boolean looks=!title.isEmpty() && (rec.has("from")||rec.has("sender")||rec.has("mailid")
                ||rec.has("mailId")||rec.has("id")||rec.has("f")||rec.has("fromaddr")||rec.has("senderaddr")
                ||rec.has("fr")||rec.has("dr")||rec.has("mid"));
        if(!title.isEmpty()&&looks){
            String from=fromJson(rec.opt("from"));
            if(from.isEmpty()) from=fromJson(rec.opt("sender"));
            if(from.isEmpty()) from=fromJson(rec.opt("f"));
            if(from.isEmpty()) from=fromJson(rec.opt("fromaddr"));
            if(from.isEmpty()) from=fromJson(rec.opt("fr"));
            if(from.isEmpty()) from=fromJson(rec.opt("dr"));
            long when=rec.optLong("time", rec.optLong("sentDate", rec.optLong("receivedDate", rec.optLong("date",0))));
            if(when<=0) when=rec.optLong("d", 0);
            if(when>0&&when<3_000_000_000L) when*=1000L;
            if(when<=0) when=Filter.parseTime(jsonText(rec, "date", "time", "d"));
            boolean unread=rec.optBoolean("unread", rec.optBoolean("ifnew", rec.optInt("flag",1)==0 || rec.optInt("new",0)==1));
            String mailid=jsonText(rec, "mailid", "id", "mid");
            try {
                JSONObject o=new JSONObject().put("id","mail-web-"+mailid)
                    .put("source","mail").put("kind","mail").put("title",title)
                    .put("summary",from.isEmpty()?"学生云邮箱":from).put("from",from).put("url",mailUrl(mailid))
                    .put("unread",unread).put("mailid",mailid);
                if(when>0){o.put("receivedAt",when);o.put("publishedAt",iso(when));}
                out.put(o);
            } catch(Exception ignored) {}
        }
        java.util.Iterator<String> keys=rec.keys();
        while(keys.hasNext()) walkMailJson(rec.opt(keys.next()),out,depth+1);
    }

    private static String fromJson(Object raw) {
        if(raw==null)return "";
        if(raw instanceof JSONObject){
            JSONObject o=(JSONObject)raw;
            String n=o.optString("name", o.optString("nick"));
            String e=o.optString("email", o.optString("addr"));
            if(!n.isEmpty()&&!e.isEmpty())return n+" <"+e+">";
            return n.isEmpty()?e:n;
        }
        String s=String.valueOf(raw);return "null".equals(s)?"":s;
    }

    private static String jsonText(JSONObject rec, String... keys) {
        if(rec==null||keys==null)return "";
        for(String k:keys){
            if(!rec.has(k)) continue;
            Object raw=rec.opt(k);
            if(raw==null||raw instanceof JSONArray||raw instanceof JSONObject) continue;
            String s=String.valueOf(raw).trim();
            if(!s.isEmpty() && !"null".equals(s)) return s;
        }
        return "";
    }

    private static String extractJsObject(String body, String name) {
        if(body==null||name==null) return "";
        Matcher m=Pattern.compile(Pattern.quote(name)+"\\s*=\\s*\\{").matcher(body);
        if(!m.find()) return "";
        int start=m.end()-1;
        int depth=0;
        for(int i=start;i<body.length() && i<start+200000;i++){
            char c=body.charAt(i);
            if(c=='{') depth++;
            else if(c=='}'){
                depth--;
                if(depth==0) return body.substring(start, i+1);
            }
        }
        return "";
    }

    private static String xmlTag(String html, String name) {
        Matcher m=Pattern.compile("(?is)<"+name+"\\b[^>]*>(.*?)</"+name+">").matcher(html==null?"":html);
        if(!m.find()) return "";
        return PageParser.text(m.group(1));
    }

    private static String firstText(String html, String[] keys) {
        for(String k:keys){
            Matcher m=Pattern.compile("(?is)(?:id|class)=['\"][^'\"]*"+k+"[^'\"]*['\"][^>]*>(.*?)</").matcher(html);
            if(m.find()){
                String t=PageParser.text(m.group(1));
                if(!t.isEmpty())return t;
            }
        }
        return "";
    }

    private static long htmlTime(String html) {
        Matcher m=Pattern.compile("(20\\d{2}[-/]\\d{1,2}[-/]\\d{1,2}(?:\\s+\\d{1,2}:\\d{2}(?::\\d{2})?)?)").matcher(html);
        if(!m.find())return 0;
        return Filter.parseTime(m.group(1).replace('/','-'));
    }

    private static String iso(long ms) {
        SimpleDateFormat fmt=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX",Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return fmt.format(new java.util.Date(ms));
    }

    private static String esc(String s) {
        if(s==null) return "";
        return s.replace("&","&"+"amp;").replace("<","&"+"lt;").replace(">","&"+"gt;").replace("\"","&"+"quot;");
    }
}
