package cn.fudan.danzhi.core;

import java.util.*;
import java.util.regex.*;

/** Small bounded parser for known public-news anchors and server-rendered QR fields. */
public final class PageParser {
    private static final Pattern DATE=Pattern.compile("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?");
    private PageParser() {}
    public static final class Notice {
        public final String title,url,date;
        Notice(String title,String url){this(title,url,"");}
        Notice(String title,String url,String date){this.title=title;this.url=url;this.date=date==null?"":date;}
    }
    public static String attr(String attrs,String name) {
        Matcher m=Pattern.compile("(?:^|\\s)"+Pattern.quote(name)+"\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",Pattern.CASE_INSENSITIVE).matcher(attrs);
        return m.find()?entities(m.group(1)!=null?m.group(1):m.group(2)!=null?m.group(2):m.group(3)):"";
    }
    public static String entities(String s) {
        String out=WebFlow.unescape(s).replace("&nbsp;"," ");
        Matcher m=Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);").matcher(out);StringBuffer b=new StringBuffer();
        while(m.find()) {String replacement=m.group();try{String v=m.group(1);int n=Integer.parseInt(v.startsWith("x")?v.substring(1):v,v.startsWith("x")?16:10);if(Character.isValidCodePoint(n))replacement=new String(Character.toChars(n));}catch(RuntimeException ignored){}m.appendReplacement(b,Matcher.quoteReplacement(replacement));}m.appendTail(b);return b.toString();
    }
    public static String text(String html) {
        return entities(html==null?"":html.replaceAll("(?is)<(?:script|style)\\b[^>]*>.*?</(?:script|style)\\s*>"," ").replaceAll("<[^>]*>"," ")).replaceAll("\\s+"," ").trim();
    }
    public static String nearbyDate(String html,int around) {
        if(html==null||around<0)return "";
        int from=Math.max(0,around-280);
        int to=Math.min(html.length(),around+640);
        String blob=html.substring(from,to);
        Matcher m=DATE.matcher(blob);
        if(m.find())return String.format(Locale.US,"%s-%02d-%02d",m.group(1),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)));
        Matcher day=Pattern.compile("class\\s*=\\s*['\"]day['\"][^>]*>\\s*(\\d{1,2})\\.(\\d{1,2})").matcher(blob);
        Matcher year=Pattern.compile("class\\s*=\\s*['\"]year['\"][^>]*>\\s*(20\\d{2})").matcher(blob);
        if(day.find()&&year.find())return String.format(Locale.US,"%s-%02d-%02d",year.group(1),Integer.parseInt(day.group(1)),Integer.parseInt(day.group(2)));
        return "";
    }
    public static List<Notice> notices(String base,String html) throws WebFlow.FlowException {
        List<Notice> out=new ArrayList<>();Set<String> seen=new HashSet<>();
        Matcher m=Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a\\s*>").matcher(html==null?"":html);
        while(m.find()&&out.size()<60) {
            String href=attr(m.group(1),"href");
            if(!(href.contains("page.htm")||href.contains("content")||href.matches(".*/c[0-9]+/.*")))continue;
            String url=WebFlow.resolve(base,href);
            if(!WebFlow.host(base).equals(WebFlow.host(url))||!seen.add(url))continue;
            String title=text(attr(m.group(1),"title"));if(title.isEmpty())title=text(m.group(2));
            if(title.length()<4||title.contains("line-height")||title.contains("{ ")||title.contains("function("))continue;
            out.add(new Notice(title,url,nearbyDate(html,m.end())));
        }
        return out;
    }
    public static String qrPayload(String html) {
        if(html==null)return "";
        Matcher m=Pattern.compile("(?is)<input\\b([^>]*)>").matcher(html);
        while(m.find()){
            String attrs=m.group(1);
            String id=attr(attrs,"id");
            String name=attr(attrs,"name");
            if(id.equals("myText")||id.equals("qrcode")||id.equals("qrCode")||id.equals("qrtext")
                    ||id.equalsIgnoreCase("code_content")||name.equals("myText")||name.equals("qrcode")
                    ||name.equalsIgnoreCase("code_content")||name.equals("qrCode")){
                String v=attr(attrs,"value");
                if(!v.isEmpty()) return v;
            }
        }
        m=Pattern.compile("QRCode\\.toCanvas\\([^,]+,\\s*([\"'])([^\"'\\\\]+)\\1").matcher(html);
        if(m.find()) return entities(m.group(2));
        m=Pattern.compile("(?is)new\\s+QRCode\\([^,]+,\\s*\\{[^}]{0,400}?text\\s*:\\s*([\"'])([^\"']+)\\1").matcher(html);
        if(m.find()) return entities(m.group(2));
        String fromJson=qrPayloadJson(html);
        if(!fromJson.isEmpty()) return fromJson;
        m=Pattern.compile("(?:qrcode|qrCode|qr_code|code_content|qrContent|lifeCode|identityCode)\\s*[:=]\\s*['\"]([A-Za-z0-9_\\-/=+]{12,})['\"]").matcher(html);
        return m.find()?m.group(1):"";
    }
    /** JSON from ecard AJAX: {qrcode|code|content|data}. */
    public static String qrPayloadJson(String body) {
        if(body==null||body.isEmpty())return "";
        String t=unwrapCgi(body);
        Matcher m=Pattern.compile("\"(?:qrcode|qrCode|qr_code|qrCodeStr|myText|barcode|payload|qrContent|code_content|qr|codeValue|barCode|identityCode|lifeCode|fudanCode|FudanQr)\"\\s*:\\s*\"([^\"]{8,})\"").matcher(t);
        if(m.find()) return entities(unescapeJson(m.group(1)));
        m=Pattern.compile("['\"](?:qrcode|qrCode|qr_code|myText|code_content|qrContent|lifeCode)['\"]\\s*:\\s*['\"]([^'\"]{8,})['\"]").matcher(t);
        if(m.find()) return entities(unescapeJson(m.group(1)));
        m=Pattern.compile("\"(?:qrcode|qrCode|qr_code|myText|code|content|payload|qr)\"\\s*:\\s*\"([^\"]{8,})\"").matcher(t);
        if(m.find()){
            String v=entities(unescapeJson(m.group(1)));
            if(v.length()>=12 && !v.matches("\\d{1,8}") && !v.equalsIgnoreCase("ok") && !v.equalsIgnoreCase("success"))
                return v;
        }
        m=Pattern.compile("\"data\"\\s*:\\s*\"([A-Za-z0-9_\\-/=+]{12,})\"").matcher(t);
        if(m.find()) return m.group(1);
        m=Pattern.compile("\"data\"\\s*:\\s*\\{[^}]{0,800}?\"(?:qrcode|qrCode|code|content|qr|myText)\"\\s*:\\s*\"([^\"]{8,})\"").matcher(t);
        if(m.find()) return entities(unescapeJson(m.group(1)));
        String trim=t.trim();
        if(trim.matches("[A-Za-z0-9_\\-/=+]{16,512}") && !trim.contains(" ")) return trim;
        return "";
    }
    public static java.util.List<String> qrAjaxPaths(String html) {
        java.util.ArrayList<String> out=new java.util.ArrayList<>();
        if(html==null||html.isEmpty()) return out;
        Matcher m=Pattern.compile("(?:url\\s*:\\s*|\\.get\\(|\\.post\\(|ajax\\()['\"]([^'\"]*(?:qrcode|getQr|getqr|qrCode|refresh)[^'\"]*)['\"]").matcher(html);
        while(m.find()){
            String u=m.group(1).trim();
            if(!u.isEmpty() && !out.contains(u)) out.add(u);
        }
        return out;
    }
    /** daystatus.asp is not valid JSON; only the status array is trusted. */
    public static String classroomStatusArray(String body) {
        Matcher block=Pattern.compile("\"status\"\\s*:\\s*(\\[.*?\\])",Pattern.DOTALL).matcher(body==null?"":body);
        return block.find()?block.group(1):"";
    }
    public static String exmailSid(String blob) {
        if(blob==null||blob.isEmpty())return "";
        Matcher m=Pattern.compile("\"sid\"\\s*:\\s*\"([0-9A-Za-z_,.~\\-]{8,128})\"").matcher(blob);
        while(m.find()){ if(validExmailSid(m.group(1))) return m.group(1); }
        m=Pattern.compile("(?:var\\s+|window\\.)sid\\s*=\\s*['\"]([0-9A-Za-z_,.~\\-]{8,128})['\"]").matcher(blob);
        while(m.find()){ if(validExmailSid(m.group(1))) return m.group(1); }
        m=Pattern.compile("(?:[?&#]|\\b)sid=([0-9A-Za-z_,.~\\-]{8,128})").matcher(blob);
        while(m.find()){ if(validExmailSid(m.group(1))) return m.group(1); }
        return "";
    }
    /** Tencent webmail sids look like 97o4qrOg3vpLOi5E,7 — comma included. Pure digits are UINs. */
    public static boolean validExmailSid(String s) {
        if(s==null||s.length()<8||s.length()>128) return false;
        boolean digit=true, letter=false;
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            if(c=='&'||c=='='||c==' '||c=='"'||c=='\''||c=='<'||c=='>') return false;
            if(!(Character.isLetterOrDigit(c)||c=='_'||c=='-'||c==','||c=='.'||c=='~')) return false;
            if(Character.isLetter(c)) letter=true;
            if(!Character.isDigit(c) && c!=',' && c!='-' && c!='_' && c!='.' && c!='~') { /* keep */ }
            if(!Character.isDigit(c)) digit=false;
        }
        return !digit && (letter || s.indexOf(',')>=0);
    }
    public static String exmailPublicKey(String html) {
        if(html==null)return "";
        Matcher m=Pattern.compile("(?:var\\s+)?(?:PublicKey|strPublicKey|g_pubKey|pubKey|str_key)\\s*=\\s*[\"']([0-9A-Fa-f]{64,})[\"']").matcher(html);
        if(m.find())return m.group(1);
        m=Pattern.compile("(?:n|modulus)\\s*[:=]\\s*[\"']([0-9A-Fa-f]{64,})[\"']").matcher(html);
        return m.find()?m.group(1):"";
    }
    public static String exmailTs(String html) {
        if(html==null)return "";
        Matcher m=Pattern.compile("(?:var\\s+)?PublicTs\\s*=\\s*[\"'](\\d{8,})[\"']").matcher(html);
        if(m.find())return m.group(1);
        m=Pattern.compile("(?is)<input\\b[^>]*name=['\"]ts['\"][^>]*>").matcher(html);
        while(m.find()){String v=attr(m.group(),"value");if(v.matches("\\d{8,}"))return v;}
        return "";
    }
    public static String exmailFormAction(String html) {
        if(html==null)return "";
        Matcher m=Pattern.compile("(?is)<form\\b([^>]*)>").matcher(html);
        while(m.find()){
            String action=attr(m.group(1),"action");
            if(action.contains("cgi-bin/login"))return action;
            String name=attr(m.group(1),"name");
            if("form1".equals(name)&&!action.isEmpty())return action;
        }
        return "";
    }
    /** Strip while(1); and Tencent &&N&& wrappers so JS objects can be inspected. */
    public static String unwrapCgi(String body) {
        if(body==null||body.isEmpty())return "";
        String t=body.trim();
        if(t.startsWith("while(1);")) t=t.substring("while(1);".length()).trim();
        Matcher m=Pattern.compile("^&&\\d+&&\\s*").matcher(t);
        if(m.find()) t=t.substring(m.end()).trim();
        return t;
    }
    /** Tencent CGI: &&0&&{url:'https://...'}  or &&1&&{errtype:1}. Also raw JSON {url,errtype}. Empty = not a CGI blob. */
    public static String exmailCgiUrl(String body) {
        if(body==null||body.isEmpty())return "";
        Matcher m=Pattern.compile("&&(\\d+)&&\\s*(\\{[\\s\\S]{0,8000}?\\})").matcher(body);
        String json="";
        int code=0;
        if(m.find()){
            try { code=Integer.parseInt(m.group(1)); } catch(NumberFormatException e){ return ""; }
            json=m.group(2);
        } else {
            String t=body.trim();
            if(t.startsWith("{") && t.length()<8000 && (t.contains("url")||t.contains("errtype")||t.contains("errcode"))){
                json=t;
            } else {
                Matcher loc=Pattern.compile("(?:targetUrl|locationValue)\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(body);
                return loc.find()?loc.group(1):"";
            }
        }
        String err=cgiField(json,"errtype");
        String errcode=cgiField(json,"errcode");
        if(code!=0 || (!err.isEmpty() && !"0".equals(err)) || (!errcode.isEmpty() && !"0".equals(errcode))){
            if("1".equals(err) || json.contains("密码") || json.contains("errcode"))
                return "ERR:PASSWORD";
            return "ERR:"+code;
        }
        String url=cgiField(json,"url");
        if(url.isEmpty()) url=cgiField(json,"redirect");
        if(url.isEmpty()) url=cgiField(json,"targetUrl");
        return url;
    }
    /** True only for the CGI password-error blob, not the login form's "用户名或密码" labels. */
    public static boolean looksLikeExmailPasswordError(String blob) {
        if(blob==null||blob.isEmpty())return false;
        String cgi=exmailCgiUrl(blob);
        if(cgi.startsWith("ERR:PASSWORD"))return true;
        return blob.contains("errtype=1") && (blob.contains("\"title\"") || blob.contains("errcode"));
    }
    /** Inbox list payload, including an actually-empty mailbox. Login HTML is not a list. */
    public static boolean looksLikeMailList(String body) {
        if(body==null||body.isEmpty())return false;
        String t=unwrapCgi(body);
        String low=t.toLowerCase(java.util.Locale.ROOT);
        if(hasValuedMailid(t)) return true;
        if(exmailInboxCount(t)==0 && (low.contains("id=\"_ut_c\"")||low.contains("id='_ut_c'"))) return true;
        if(low.contains("<mail") && (low.contains("</mail>")||low.contains("mailid"))) return true;
        if(t.startsWith("{")||t.startsWith("[")||low.contains("inf:")||low.contains("\"inf\"")
                ||low.contains("mls:")||low.contains("\"mls\"")){
            if(low.contains("mailid")||low.contains("\"mails\"")||low.contains("maillist")
                    ||low.contains("\"ids\"")||low.contains("ids:")||low.contains("newcnt")||low.contains("mailnum")
                    ||low.contains("mailcount")||low.contains("\"inf\"")||low.contains("inf:")
                    ||low.contains("\"cols\"")||low.contains("\"mls\"")||low.contains("'mls'")||low.contains("mls:"))
                return true;
        }
        if(low.contains("cgidata") && (low.contains("mailid")||low.contains("folderid"))) return true;
        return false;
    }
    public static boolean hasValuedMailid(String html) {
        if(html==null) return false;
        Matcher inp=Pattern.compile("(?is)<input\\b([^>]*)>").matcher(html);
        while(inp.find()){
            String attrs=inp.group(1);
            if(!"mailid".equalsIgnoreCase(attr(attrs,"name"))) continue;
            String v=attr(attrs,"value");
            if(v!=null && v.length()>=8) return true;
        }
        return Pattern.compile("mailid=([0-9A-Za-z_\\-]{8,})").matcher(html).find();
    }
    /** Visible inbox size from 收件箱 (共 N 封). -1 if unknown. */
    public static int exmailInboxCount(String html) {
        if(html==null||html.isEmpty()) return -1;
        Matcher m=Pattern.compile("id\\s*=\\s*['\"]_ut_c['\"][^>]*>\\s*(\\d+)").matcher(html);
        if(m.find()){
            try { return Integer.parseInt(m.group(1)); } catch(NumberFormatException ignored) {}
        }
        m=Pattern.compile("共\\s*<[^>]*>\\s*(\\d+)\\s*<[^>]*>\\s*封").matcher(html);
        if(m.find()){
            try { return Integer.parseInt(m.group(1)); } catch(NumberFormatException ignored) {}
        }
        m=Pattern.compile("共\\s*(\\d+)\\s*封").matcher(html);
        if(m.find()){
            try { return Integer.parseInt(m.group(1)); } catch(NumberFormatException ignored) {}
        }
        return -1;
    }
    static String cgiField(String json,String name) {
        Matcher m=Pattern.compile("['\"]?"+Pattern.quote(name)+"['\"]?\\s*:\\s*['\"]([^'\"]+)['\"]").matcher(json);
        if(m.find()) return WebFlow.unescape(m.group(1));
        m=Pattern.compile("['\"]?"+Pattern.quote(name)+"['\"]?\\s*:\\s*(\\d+)").matcher(json);
        return m.find()?m.group(1):"";
    }
    /** Convert Tencent CGI JS object literals (unquoted keys, single quotes) into JSON. */
    public static String jsToJson(String js) {
        if(js==null||js.isEmpty())return "";
        String t=unwrapCgi(js);
        StringBuilder out=new StringBuilder(t.length()+16);
        boolean inStr=false;
        char quote=0;
        for(int i=0;i<t.length();i++){
            char c=t.charAt(i);
            if(inStr){
                if(c=='\\'){
                    out.append(c);
                    if(i+1<t.length()) out.append(t.charAt(++i));
                    continue;
                }
                if(c==quote){ inStr=false; out.append('"'); continue; }
                if(c=='"' && quote=='\'') out.append("\\\"");
                else out.append(c);
                continue;
            }
            if(c=='\''||c=='"'){ inStr=true; quote=c; out.append('"'); continue; }
            if(c=='{'||c==','||c=='['){
                out.append(c);
                int j=i+1;
                while(j<t.length()&&Character.isWhitespace(t.charAt(j))){ out.append(t.charAt(j)); j++; }
                if(j<t.length()&&(Character.isLetter(t.charAt(j))||t.charAt(j)=='_')){
                    int k=j;
                    while(k<t.length()&&(Character.isLetterOrDigit(t.charAt(k))||t.charAt(k)=='_')) k++;
                    int m=k;
                    while(m<t.length()&&Character.isWhitespace(t.charAt(m))) m++;
                    if(m<t.length()&&t.charAt(m)==':'){
                        out.append('"').append(t,j,k).append('"');
                        i=k-1;
                        continue;
                    }
                }
                i=j-1;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
    public static LinkedHashMap<String,String> formFields(String html) {
        LinkedHashMap<String,String> out=new LinkedHashMap<>();
        if(html==null)return out;
        Matcher m=Pattern.compile("(?is)<input\\b([^>]*)>").matcher(html);
        while(m.find()){
            String name=attr(m.group(1),"name");
            if(name.isEmpty())continue;
            out.put(name,attr(m.group(1),"value"));
        }
        return out;
    }

    /** One row from Tencent mail_list HTML: checkbox name=mailid plus nearby title. */
    public static final class WebMail {
        public final String mailid, title, fromName, fromAddr;
        public final long totime;
        public final boolean unread;
        public WebMail(String mailid, String title, String fromName, String fromAddr, long totime, boolean unread) {
            this.mailid=mailid; this.title=title; this.fromName=fromName; this.fromAddr=fromAddr;
            this.totime=totime; this.unread=unread;
        }
    }
    public static List<WebMail> exmailCheckboxMails(String html) {
        List<WebMail> out=new ArrayList<>();
        if(html==null||html.isEmpty()) return out;
        Set<String> seen=new HashSet<>();
        Matcher inp=Pattern.compile("(?is)<input\\b([^>]*)>").matcher(html);
        List<int[]> spans=new ArrayList<>();
        List<String> attrList=new ArrayList<>();
        while(inp.find()){
            String attrs=inp.group(1);
            if(!"mailid".equalsIgnoreCase(attr(attrs,"name"))) continue;
            String mailid=attr(attrs,"value");
            if(mailid.isEmpty()||mailid.length()<8||!seen.add(mailid)) continue;
            spans.add(new int[]{inp.start(), inp.end()});
            attrList.add(attrs);
        }
        for(int i=0;i<attrList.size() && out.size()<200;i++){
            String attrs=attrList.get(i);
            String mailid=attr(attrs,"value");
            String fn=attr(attrs,"fn");
            String fa=attr(attrs,"fa");
            String unreadRaw=attr(attrs,"unread");
            if(unreadRaw.isEmpty()){
                Matcher u=Pattern.compile("(?i)\\bunread\\s*=\\s*(true|false|1|0)").matcher(attrs);
                if(u.find()) unreadRaw=u.group(1);
            }
            boolean unread="true".equalsIgnoreCase(unreadRaw)||"1".equals(unreadRaw);
            long when=0;
            String totime=attr(attrs,"totime");
            if(!totime.isEmpty()){
                try { when=Long.parseLong(totime.trim()); } catch(NumberFormatException ignored) {}
            }
            int from=spans.get(i)[1];
            int rowEnd=Math.min(html.length(), from+800);
            if(i+1<spans.size()) rowEnd=Math.min(rowEnd, spans.get(i+1)[0]);
            Matcher tr=Pattern.compile("(?i)</tr>").matcher(html);
            tr.region(from, Math.min(html.length(), from+2500));
            if(tr.find()) rowEnd=Math.min(rowEnd, tr.end());
            String window=html.substring(from, Math.max(from, rowEnd));
            String title=attr(attrs,"tt");
            if(junkMailTitle(title)) title=attr(attrs,"subject");
            if(junkMailTitle(title)) title=firstClassText(window, "(?:black\\s+s1|s1\\s+black)");
            if(junkMailTitle(title)) title=firstClassText(window, "(?:gt\\s+tf|tf\\s+gt)");
            if(junkMailTitle(title)) title=firstClassText(window, "\\bblack\\b");
            if(junkMailTitle(title)){
                Matcher t=Pattern.compile("(?is)class=['\"][^'\"]*\\btt\\b[^'\"]*['\"][^>]*>(.*?)</").matcher(window);
                if(t.find()) title=text(t.group(1));
            }
            if(junkMailTitle(title)) title=fn==null?"":fn.replace("&nbsp;"," ");
            if(junkMailTitle(title)) title="（无主题）";
            out.add(new WebMail(mailid, title, fn, fa, when, unread));
        }
        return out;
    }
    private static String firstClassText(String window, String classPat) {
        Matcher t=Pattern.compile("(?is)class=['\"][^'\"]*"+classPat+"[^'\"]*['\"][^>]*>(.*?)</").matcher(window);
        while(t.find()){
            String title=text(t.group(1));
            if(!junkMailTitle(title)) return title;
        }
        return "";
    }
    private static boolean junkMailTitle(String t) {
        if(t==null) return true;
        String s=t.trim();
        if(s.length()<1||s.length()>80) return true;
        if(s.contains("共") && s.contains("封")) return true;
        if(s.contains("收件箱")||s.contains("新窗口")||s.contains("读信")) return true;
        return false;
    }
    public static String unescapeJson(String s) {
        if(s==null||s.isEmpty()) return "";
        StringBuilder b=new StringBuilder(s.length());
        for(int i=0;i<s.length();i++){
            char c=s.charAt(i);
            if(c=='\\' && i+1<s.length()){
                char n=s.charAt(++i);
                switch(n){
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case '"': case '\'': case '\\': case '/': b.append(n); break;
                    case 'u':
                        if(i+4<s.length()){
                            try { b.append((char)Integer.parseInt(s.substring(i+1,i+5),16)); i+=4; }
                            catch(NumberFormatException e){ b.append('u'); }
                        } else b.append('u');
                        break;
                    default: b.append(n);
                }
            } else b.append(c);
        }
        return b.toString();
    }
    /** Inbox size from mail_list.json. -1 if unknown. */
    public static int mailListTotal(String body) {
        int n=exmailInboxCount(body);
        if(n>=0) return n;
        if(body==null||body.isEmpty()) return -1;
        Matcher m=Pattern.compile("\"(?:mailnum|mailcount|mailNum|mailCount|total)\"\\s*:\\s*(\\d+)").matcher(body);
        if(m.find()){
            try { return Integer.parseInt(m.group(1)); } catch(NumberFormatException ignored) {}
        }
        m=Pattern.compile("(?:mailnum|mailcount)\\s*:\\s*(\\d+)").matcher(body);
        if(m.find()){
            try { return Integer.parseInt(m.group(1)); } catch(NumberFormatException ignored) {}
        }
        return -1;
    }
    /** Tencent readmail CGI when sid/cookie expired. Not a letter. */
    public static boolean looksLikeMailSessionDead(String body) {
        if(body==null||body.isEmpty()) return false;
        String t=unwrapCgi(body);
        String low=t.toLowerCase(Locale.ROOT);
        if(low.contains("session_timeout")) return true;
        if(low.contains("cgi exception") || low.contains("\"title\":\"cgi exception\"") || low.contains("title : \"cgi exception\"") || low.contains("title:\"cgi exception\""))
            return true;
        if(low.contains("loginpage") && (low.contains("errcode")||low.contains("errmsg"))) return true;
        if(low.contains("errcode") && (low.contains("\"-2\"")||low.contains(": -2")||low.contains(":-2"))) {
            if(low.contains("readmail")||low.contains("appname")) return true;
        }
        return false;
    }
    public static String mailJsonContent(String body) {
        if(body==null||body.isEmpty()) return "";
        String t=unwrapCgi(body);
        // Nested cgiData / data.content first (Tencent read.json).
        Matcher nested=Pattern.compile("(?:cgiData|data)\\s*[:=]\\s*\\{([\\s\\S]{20,80000}?)\\}\\s*(?:,|;)").matcher(t);
        while(nested.find()){
            String inner=mailJsonContent("{"+nested.group(1)+"}");
            if(!inner.isEmpty()) return inner;
        }
        Matcher m=Pattern.compile("['\"]?(?:content|html|mailcontent|body|mailbody|contentHtml|bodyhtml|htmlbody|contentText|mailcontenthtml|bodyText|text)['\"]?\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(t);
        while(m.find()){
            String raw=m.group(1);
            String v=entities(unescapeJson(raw)).trim();
            // Some responses ship base64 HTML in content.
            if(v.length()>40 && v.matches("(?i)^[A-Za-z0-9+/=\\r\\n]+$") && v.length()%4==0){
                try {
                    byte[] dec=java.util.Base64.getDecoder().decode(v.replaceAll("\\s",""));
                    String decoded=new String(dec, java.nio.charset.StandardCharsets.UTF_8);
                    if(text(decoded).length()>=2) return decoded;
                } catch(Throwable ignored) {}
            }
            String plain=text(v);
            if(plain.length()>=2 && !looksLikeMailSessionDead(v)) return v;
        }
        m=Pattern.compile("(?:content|html|mailcontent)\\s*:\\s*'((?:\\\\.|[^'\\\\])*)'").matcher(t);
        if(m.find()){
            String v=entities(unescapeJson(m.group(1))).trim();
            if(text(v).length()>=2 && !looksLikeMailSessionDead(v)) return v;
        }
        return "";
    }
    public static String mailIframeSrc(String html) {
        if(html==null||html.isEmpty()) return "";
        Matcher m=Pattern.compile("(?is)<iframe\\b([^>]*)>").matcher(html);
        while(m.find()){
            String attrs=m.group(1);
            String src=attr(attrs,"src");
            if(src.isEmpty()||src.startsWith("javascript:")||src.startsWith("about:")) continue;
            String id=attr(attrs,"id").toLowerCase(Locale.ROOT);
            String name=attr(attrs,"name").toLowerCase(Locale.ROOT);
            String cls=attr(attrs,"class").toLowerCase(Locale.ROOT);
            String low=src.toLowerCase(Locale.ROOT);
            if(id.contains("content")||id.contains("mail")||name.contains("content")||name.contains("mail")
                    ||cls.contains("content")||cls.contains("mail")
                    ||low.contains("read")||low.contains("mail")||low.contains("content")||low.contains("cgi-bin"))
                return src;
        }
        return "";
    }
    public static String mailBodyHtml(String body) {
        if(body==null||body.isEmpty()) return "";
        if(looksLikeMailSessionDead(body)) return "";
        String json=mailJsonContent(body);
        if(!json.isEmpty()) return json;
        Matcher m=Pattern.compile("(?is)<(?:div|table|td)\\b[^>]*(?:id|class)=['\"][^'\"]*(?:mailContentContainer|contentDiv|mailcontent|mail-content|mail_content|contentcontainer|bodycontent|readmail_content)[^'\"]*['\"][^>]*>([\\s\\S]{8,}?)</(?:div|table|td)>").matcher(body);
        if(m.find()){
            String inner=m.group(1);
            if(text(inner).length()>=2 && mailIframeSrc(inner).isEmpty()) return inner;
        }
        m=Pattern.compile("(?is)<(?:div|table)\\b[^>]*(?:id|class)=['\"][^'\"]*(?:content|mailcontent|mail-content|body)[^'\"]*['\"][^>]*>([\\s\\S]{20,})</(?:div|table)>").matcher(body);
        if(m.find()){
            String inner=m.group(1);
            if(text(inner).length()>=2 && mailIframeSrc(inner).isEmpty()) return inner;
        }
        // Whole-page chrome (读信外壳 / iframe 正文) is not the letter.
        if(mailIframeSrc(body).length()>0) return "";
        String low=body.toLowerCase(Locale.ROOT);
        if(low.contains("<html") && (body.contains("读信")||low.contains("readmail")||low.contains("folderid")||low.contains("mail_list")))
            return "";
        m=Pattern.compile("(?is)<body[^>]*>([\\s\\S]+)</body>").matcher(body);
        if(m.find()){
            String inner=m.group(1);
            if(text(inner).length()>=2 && mailIframeSrc(inner).isEmpty() && !inner.contains("读信")) return inner;
        }
        if(low.contains("<html")||low.contains("<iframe")) return "";
        return text(body).length()>=2?body:"";
    }
}
