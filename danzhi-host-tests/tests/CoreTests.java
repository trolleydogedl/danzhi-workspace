import cn.fudan.danzhi.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Host-JVM regression tests. No Android or Fudan network is used. */
public final class CoreTests {
    interface Check {void run() throws Exception;}
    static int passed=0,failed=0;
    static final List<String> cases=new ArrayList<>();
    static void test(String name,Check c){try{c.run();passed++;cases.add("PASS "+name);System.out.println("PASS "+name);}catch(Throwable e){failed++;cases.add("FAIL "+name);System.out.println("FAIL "+name+" :: "+e);e.printStackTrace();}}
    static void eq(Object a,Object b){if(!Objects.equals(a,b))throw new AssertionError("expected="+b+" actual="+a);}
    static void yes(boolean v){if(!v)throw new AssertionError("false");}
    static void category(String name,Check c)throws Exception{try{c.run();throw new AssertionError("missing exception: "+name);}catch(WebFlow.FlowException e){eq(e.category,name);}}
    static final String ID="https://id.fudan.edu.cn";
    static Set<String> hosts(){return new HashSet<>(Arrays.asList("id.fudan.edu.cn","elearning.fudan.edu.cn","my.fudan.edu.cn"));}
    static WebFlow.Request req(String u){return new WebFlow.Request(u,"GET",null,null,12000);}
    static WebFlow.Response resp(int c,String u,String b,String l){return new WebFlow.Response(c,u,b,l);}
    static Map<String,List<String>> headers(String... v){Map<String,List<String>> h=new HashMap<>();h.put("sEt-CoOkIe",Arrays.asList(v));return h;}
    public static void main(String[] args)throws Exception {
        test("URLEncoder UTF-8 uses Java8-compatible overload",()->eq(WebFlow.encode("数 学+&"),"%E6%95%B0+%E5%AD%A6%2B%26"));
        test("relative redirect resolution",()->eq(WebFlow.resolve(ID+"/idp/a","../next?q=x&amp;v=1"),ID+"/next?q=x&v=1"));
        test("reject HTTPS downgrade",()->category("INSECURE_REDIRECT",()->WebFlow.resolve(ID,"http://id.fudan.edu.cn/a")));
        test("reject javascript redirect",()->category("INVALID_REDIRECT",()->WebFlow.resolve(ID,"javascript:alert(1)")));
        test("reject URL userinfo",()->category("INVALID_REDIRECT",()->WebFlow.resolve(ID,"https://attacker@id.fudan.edu.cn/a")));
        test("redact ticket lck and fragment",()->eq(WebFlow.safeUrl(ID+"/a?ticket=SECRET&lck=SECRET#SECRET"),ID+"/a"));
        test("200 login page is AUTH_REQUIRED",()->category("AUTH_REQUIRED",()->WebFlow.requireHttpSuccess(resp(200,"https://elearning.fudan.edu.cn/login","<input name=p type='password'>",null))));
        test("401 is not an empty collection",()->category("AUTH_REQUIRED",()->WebFlow.requireHttpSuccess(resp(401,"https://elearning.fudan.edu.cn/api/v1/courses","[]",null))));
        test("403 is not an empty collection",()->category("AUTH_REQUIRED",()->WebFlow.requireHttpSuccess(resp(403,"https://elearning.fudan.edu.cn/api/v1/courses","[]",null))));
        test("500 is HTTP_ERROR",()->category("HTTP_ERROR",()->WebFlow.requireHttpSuccess(resp(500,ID+"/a","bad",null))));
        test("CAS lck survives second redirect and fragment",()->{
            AtomicInteger calls=new AtomicInteger();WebFlow.Response r=WebFlow.follow(q->{int n=calls.incrementAndGet();return resp(302,q.url,"",n==1?"/idp/intermediate":"/ac-h5/#/index?lck=abc123");},req(ID+"/authserver/login"),true,hosts());
            eq(calls.get(),2);yes(r.url.endsWith("#/#")==false);yes(r.url.contains("lck=abc123"));
        });
        test("CAS authnEngine literal navigation consumes ticket",()->{
            AtomicInteger calls=new AtomicInteger();WebFlow.Response r=WebFlow.follow(q->{if(calls.incrementAndGet()==1)return resp(200,q.url,"var locationValue = 'https://my.fudan.edu.cn/?ticket=ST-test';",null);return resp(200,q.url,"portal",null);},req(ID+"/idp/authCenter/authnEngine"),false,hosts());eq(calls.get(),2);eq(r.body,"portal");
        });
        test("307 retains POST body only same host",()->{AtomicInteger c=new AtomicInteger();WebFlow.follow(q->{if(c.incrementAndGet()==1)return resp(307,q.url,"","/b");eq(q.method,"POST");eq(q.body,"abc");return resp(200,q.url,"ok",null);},new WebFlow.Request(ID+"/a","POST","text/plain","abc",5000),false,hosts());eq(c.get(),2);});
        test("308 cross-host body forwarding refused",()->category("UNTRUSTED_REDIRECT",()->WebFlow.follow(q->resp(308,q.url,"","https://my.fudan.edu.cn/"),new WebFlow.Request(ID+"/a","POST","text/plain","SECRET",5000),false,hosts())));
        test("303 changes POST to GET",()->{AtomicInteger c=new AtomicInteger();WebFlow.follow(q->{if(c.incrementAndGet()==1)return resp(303,q.url,"","/b");eq(q.method,"GET");eq(q.body,null);return resp(200,q.url,"ok",null);},new WebFlow.Request(ID+"/a","POST","text/plain","abc",5000),false,hosts());});
        test("external redirect blocked",()->category("UNTRUSTED_REDIRECT",()->WebFlow.follow(q->resp(302,q.url,"","https://example.com/"),req(ID),true,hosts())));
        test("redirect cycle returns last page instead of failing",()->{
            AtomicInteger c=new AtomicInteger();
            WebFlow.Response r=WebFlow.follow(q->{c.incrementAndGet();return resp(302,q.url,"loop-body","/loop");},req(ID),true,hosts());
            yes(c.get()<=3);yes(r.body.contains("loop")||r.code==302);
        });
        test("literal location.replace navigation",()->eq(WebFlow.navigation(resp(200,ID,"window.location.replace('/ok');",null)),"/ok"));
        test("arbitrary JavaScript not evaluated",()->eq(WebFlow.navigation(resp(200,ID,"window.location = decrypt(secret);",null)),null));
        test("interrupt cancels redirect flow",()->{Thread.currentThread().interrupt();try{WebFlow.follow(q->resp(200,q.url,"",null),req(ID),true,hosts());throw new AssertionError();}catch(InterruptedException expected){}finally{Thread.interrupted();}});
        test("cookie header case-insensitive",()->{CookieStore s=new CookieStore();s.absorb(ID+"/a",headers("sid=abc; Path=/; Secure"),1000);eq(s.header(ID+"/b",1000),"sid=abc");});
        test("host-only cookie never leaks to sibling",()->{CookieStore s=new CookieStore();s.absorb(ID+"/",headers("sid=abc; Path=/"),1000);eq(s.header("https://ecard.fudan.edu.cn/",1000),"");eq(s.header("https://sub.id.fudan.edu.cn/",1000),"");});
        test("domain cookie obeys suffix boundary",()->{CookieStore s=new CookieStore();s.absorb(ID+"/",headers("sid=abc; Domain=.fudan.edu.cn; Path=/"),1000);eq(s.header("https://ecard.fudan.edu.cn/",1000),"sid=abc");eq(s.header("https://evilfudan.edu.cn/",1000),"");});
        test("cookie unrelated Domain rejected",()->{CookieStore s=new CookieStore();s.absorb(ID+"/",headers("sid=abc; Domain=example.com; Path=/"),1000);eq(s.snapshot().size(),0);});
        test("cookie edu.cn parent rejected",()->{CookieStore s=new CookieStore();s.absorb(ID+"/",headers("sid=abc; Domain=edu.cn; Path=/"),1000);eq(s.snapshot().size(),0);});
        test("secure cookie never sent over HTTP",()->{CookieStore s=new CookieStore();s.absorb(ID+"/",headers("sid=abc; Secure; Path=/"),1000);eq(s.header("http://id.fudan.edu.cn/",1000),"");});
        test("cookie path segment boundary",()->{CookieStore s=new CookieStore();s.absorb(ID+"/epay/a",headers("sid=abc; Path=/epay"),1000);eq(s.header(ID+"/epay/x",1000),"sid=abc");eq(s.header(ID+"/epayment",1000),"");});
        test("cookie default path is parent",()->{CookieStore s=new CookieStore();s.absorb(ID+"/epay/a",headers("sid=abc"),1000);eq(s.header(ID+"/other",1000),"");eq(s.header(ID+"/epay/b",1000),"sid=abc");});
        test("cookie expiry survives persistence without renewal",()->{CookieStore s=new CookieStore();s.absorb(ID+"/",headers("sid=abc; Max-Age=2; Path=/"),1000);CookieStore r=new CookieStore();r.restore(s.snapshot(),1000);eq(r.header(ID+"/",2000),"sid=abc");eq(r.header(ID+"/",3001),"");});
        test("expired cookie deleted",()->{CookieStore s=new CookieStore();s.absorb(ID+"/",headers("sid=abc; Path=/"),1000);s.absorb(ID+"/",headers("sid=gone; Path=/; Max-Age=0"),1000);eq(s.header(ID+"/",1000),"");});
        test("__Host cookie prefix restrictions",()->{CookieStore s=new CookieStore();s.absorb(ID+"/",headers("__Host-sid=abc; Domain=fudan.edu.cn; Path=/; Secure"),1000);eq(s.snapshot().size(),0);});
        test("cookie restore last write wins and dedupes",()->{
            CookieStore s=new CookieStore();
            CookieStore.Entry a=new CookieStore.Entry();a.name="sid";a.value="old";a.domain="id.fudan.edu.cn";a.path="/";a.hostOnly=true;a.secure=true;a.expiresAt=-1;
            CookieStore.Entry b=new CookieStore.Entry();b.name="sid";b.value="new";b.domain="id.fudan.edu.cn";b.path="/";b.hostOnly=true;b.secure=true;b.expiresAt=-1;
            s.restore(Arrays.asList(a,b),1000);
            eq(s.header(ID+"/",1000),"sid=new");
            eq(s.snapshot().size(),1);
        });
        test("cookie restore preserves host-only",()->{CookieStore s=new CookieStore();s.absorb(ID+"/",headers("sid=abc; Path=/"),1000);CookieStore r=new CookieStore();r.restore(s.snapshot(),1000);eq(r.header("https://sub.id.fudan.edu.cn/",1000),"");});
        test("student id from 树维 info url",()->eq(TimetableText.studentIdFrom("https://fdjwgl.fudan.edu.cn/student/for-std/course-table/info/412345","<html>"),"412345"));
        test("student id from semester-index",()->eq(TimetableText.studentIdFrom("https://fdjwgl.fudan.edu.cn/student/for-std/course-table/semester-index/88",""),"88"));
        test("cookie longest-path ordering",()->{CookieStore s=new CookieStore();s.absorb(ID+"/",headers("sid=root; Path=/","sid=deep; Path=/epay"),1000);eq(s.header(ID+"/epay/a",1000),"sid=deep; sid=root");});
        test("lesson text multi-slot sorts by weekday and period",()->{List<TimetableText.Lesson> l=TimetableText.parse("高数","1~16周 星期三 3~4节 H310 教师甲;\n1~16周 星期一 1~2节 H101 教师乙");eq(l.size(),2);eq(l.get(0).day,1);eq(l.get(0).startPeriod,1);eq(l.get(0).location,"H101");eq(l.get(0).weeks.size(),16);});
        test("odd weeks retained",()->eq(TimetableText.weeks("1-8周(单)"),new TreeSet<>(Arrays.asList(1,3,5,7))));
        test("even and separate weeks retained",()->eq(TimetableText.weeks("2～8周（双）,12周"),new TreeSet<>(Arrays.asList(2,4,6,8,12))));
        test("single period and Sunday",()->{TimetableText.Lesson l=TimetableText.parse("体育","第3周 星期天 5节 体育馆").get(0);eq(l.day,7);eq(l.startPeriod,5);eq(l.endPeriod,5);});
        test("empty schedule has zero arranged slots",()->eq(TimetableText.parse("无安排","").size(),0));
        test("unknown schedule must not invent Monday period one",()->{try{TimetableText.parse("课","下一周早上未知");throw new AssertionError();}catch(IllegalArgumentException expected){}});
        test("inverted period range rejected",()->{try{TimetableText.parse("课","1周 星期一 5~2节 教室");throw new AssertionError();}catch(IllegalArgumentException expected){}});
        test("news accepts title before href",()->{List<PageParser.Notice> n=PageParser.notices("https://jwc.fudan.edu.cn/9397/list.htm","<a title='2026 年教学通知' class='x' href='/a/b/page.htm'>更多</a>");eq(n.size(),1);eq(n.get(0).title,"2026 年教学通知");});
        test("news strips CSS and markup",()->{List<PageParser.Notice> n=PageParser.notices("https://math.fudan.edu.cn/tzgg/list.htm","<a href='/x/page.htm'><style>.bad{line-height:2}</style><span>数学竞赛报名通知</span></a>");eq(n.get(0).title,"数学竞赛报名通知");});
        test("news deduplicates links and rejects external sites",()->eq(PageParser.notices("https://jwc.fudan.edu.cn/9397/list.htm","<a href='/x/page.htm'>教务通知一</a><a href='/x/page.htm'>教务通知一</a><a href='https://evil.test/x/page.htm'>外部站点</a>").size(),1));
        test("QR input attribute order independent",()->eq(PageParser.qrPayload("<input value='official&amp;payload' class='x' id='myText'>"),"official&payload"));
        test("QR missing never synthesized from student id",()->eq(PageParser.qrPayload("<input id='studentId' value='123456'>"),""));
        test("QR literal renderer parsed",()->eq(PageParser.qrPayload("QRCode.toCanvas(canvas, 'server-issued-token');"),"server-issued-token"));
        test("task LinkageError returned and gate released",()->{TaskGate g=new TaskGate();TaskGate.Result<String> r=g.run(()->{throw new NoSuchMethodError("test");});yes(r.error instanceof NoSuchMethodError);eq(g.run(()->"next").value,"next");});
        test("task VerifyError returned",()->{TaskGate g=new TaskGate();yes(g.run(()->{throw new VerifyError("test");}).error instanceof VerifyError);});
        test("task VM fatal error not misleadingly swallowed",()->{TaskGate g=new TaskGate();try{g.run(()->{throw new OutOfMemoryError("synthetic");});throw new AssertionError();}catch(OutOfMemoryError expected){}eq(g.run(()->1).value,1);});
        test("overlapping login poll rejected rather than racing",()->{TaskGate g=new TaskGate();CountDownLatch in=new CountDownLatch(1),done=new CountDownLatch(1);Thread a=new Thread(()->g.run(()->{in.countDown();done.await(2,TimeUnit.SECONDS);return 1;}));a.start();yes(in.await(2,TimeUnit.SECONDS));yes(g.run(()->2).busy);done.countDown();a.join(2000);eq(g.run(()->3).value,3);});
        test("IDP fragment redirect is AUTH_REQUIRED rather than HTTP success",()->category("AUTH_REQUIRED",()->WebFlow.requireHttpSuccess(resp(302,ID+"/ac-h5/#/index?lck=fixture","",null))));
        test("MFA second step retains the exact native challenge",()->{MfaState m=new MfaState();MfaState.Challenge c=new MfaState.Challenge("u","chain","entity","type","lck","request","referer","userAndOtp",100);m.set(c);yes(m.require("u","123456",200)==c);});
        test("MFA retry does not consume or replace challenge",()->{MfaState m=new MfaState();MfaState.Challenge c=new MfaState.Challenge("u","c","e","t","l","n","r","userAndOtp",100);m.set(c);yes(m.require("u","111111",200)==m.require("u","222222",201));});
        test("MFA without first step is rejected",()->category("MFA_EXPIRED",()->new MfaState().require("u","123456",100)));
        test("MFA rejects non-numeric six-character code",()->category("OTP_INVALID",()->new MfaState().require("u","ABCDEF",100)));
        test("MFA expires after five minutes",()->{MfaState m=new MfaState();m.set(new MfaState.Challenge("u","c","e","t","l","n","r","userAndOtp",100));category("MFA_EXPIRED",()->m.require("u","123456",300_000_000_100L));});
        test("MFA cannot be applied to another account",()->{MfaState m=new MfaState();m.set(new MfaState.Challenge("u","c","e","t","l","n","r","userAndOtp",100));category("MFA_ACCOUNT_CHANGED",()->m.require("other","123456",200));});
        test("MFA clear invalidates the pending challenge",()->{MfaState m=new MfaState();m.set(new MfaState.Challenge("u","c","e","t","l","n","r","userAndOtp",100));m.clear();category("MFA_EXPIRED",()->m.require("u","123456",200));});
        test("fudan subdomain redirect is trusted even if not in the explicit set",()->{
            AtomicInteger c=new AtomicInteger();
            WebFlow.Response r=WebFlow.follow(q->{if(c.incrementAndGet()==1)return resp(302,q.url,"","https://ecard.fudan.edu.cn/epay/x");return resp(200,q.url,"ecard",null);},req(ID),false,hosts());
            eq(c.get(),2);eq(r.body,"ecard");
        });
        test("ehall subdomain redirect is trusted",()->{
            AtomicInteger c=new AtomicInteger();
            WebFlow.follow(q->{if(c.incrementAndGet()==1)return resp(302,q.url,"","https://ehall.fudan.edu.cn/x");return resp(200,q.url,"ok",null);},req(ID),false,hosts());
            eq(c.get(),2);
        });
        test("classroom LAN host is trusted",()->eq(WebFlow.campusHost("10.64.130.6"),true));
        test("weixin host is not trusted",()->eq(WebFlow.campusHost("open.weixin.qq.com"),false));
        test("ptlogin2 is trusted for tencent webmail",()->eq(WebFlow.campusHost("ssl.ptlogin2.qq.com"),true));
        test("200 page with weixin location assignment is kept",()->{
            AtomicInteger c=new AtomicInteger();
            WebFlow.Response r=WebFlow.follow(q->{c.incrementAndGet();return resp(200,"https://ehall.fudan.edu.cn/x","window.location='https://open.weixin.qq.com/connect';",null);},new WebFlow.Request("https://ehall.fudan.edu.cn/x","GET",null,null,12000),false,hosts());
            eq(c.get(),1);yes(r.body.contains("weixin"));
        });
        test("HTTP 302 to weixin is still blocked",()->category("UNTRUSTED_REDIRECT",()->WebFlow.follow(q->resp(302,q.url,"","https://open.weixin.qq.com/x"),new WebFlow.Request("https://ehall.fudan.edu.cn/x","GET",null,null,12000),false,hosts())));
        test("same course different teachers merge into one slot",()->{
            List<TimetableText.Lesson> l=TimetableText.parse("高数","1~8周 星期一 1~2节 H310 教师甲;9~16周 星期一 1~2节 H310 教师乙");
            eq(l.size(),1);eq(l.get(0).weeks.size(),16);yes(l.get(0).teacher.contains("教师甲"));yes(l.get(0).teacher.contains("教师乙"));
        });
        test("different weekdays stay separate courses",()->eq(TimetableText.parse("高数","1~16周 星期一 1~2节 H101 甲;1~16周 星期三 3~4节 H310 乙").size(),2));
        test("exmail public key and ts parsed",()->{
            String html="var PublicKey = \"CF87D7B4C864F4842F1D337491A48FFF54B73A17300E8E42FA365420393AC0346AE55D8AFAD975DFA175FAF0106CBA81AF1DDE4ACEC284DAC6ED9A0D8FEB1CC070733C58213EFFED46529C54CEA06D774E3CC7E073346AEBD6C66FC973F299EB74738E400B22B1E7CDC54E71AED059D228DFEB5B29C530FF341502AE56DDCFE9\";var PublicTs=\"1657285628\";<form name='form1' action='/cgi-bin/login'><input name='ts' value='1657285628'><input name='uin' value=''></form>";
            eq(PageParser.exmailTs(html),"1657285628");
            yes(PageParser.exmailPublicKey(html).startsWith("CF87"));
            eq(PageParser.exmailFormAction(html),"/cgi-bin/login");
            eq(PageParser.formFields(html).get("ts"),"1657285628");
        });
        test("exmail RSA ciphertext is base64",()->{
            String c=RsaPkcs1.encryptToBase64("secret\n1657285628\n",PageParser.exmailPublicKey("var PublicKey = \"CF87D7B4C864F4842F1D337491A48FFF54B73A17300E8E42FA365420393AC0346AE55D8AFAD975DFA175FAF0106CBA81AF1DDE4ACEC284DAC6ED9A0D8FEB1CC070733C58213EFFED46529C54CEA06D774E3CC7E073346AEBD6C66FC973F299EB74738E400B22B1E7CDC54E71AED059D228DFEB5B29C530FF341502AE56DDCFE9\";"),"10001");
            yes(c.length()>80);yes(c.matches("[A-Za-z0-9+/=]+"));
        });
        test("exmail login uses tom wu hex2b64 not raw byte base64",()->{
            eq(RsaPkcs1.hex2b64("4d"),"TQ==");
            eq(RsaPkcs1.hex2b64("14f"),"FP==");
            String std=java.util.Base64.getEncoder().encodeToString(new byte[]{0x14,0x0f});
            yes(!std.equals("FP=="));
            String c=RsaPkcs1.encryptToExmail("secret\n1657285628\n",PageParser.exmailPublicKey("var PublicKey = \"CF87D7B4C864F4842F1D337491A48FFF54B73A17300E8E42FA365420393AC0346AE55D8AFAD975DFA175FAF0106CBA81AF1DDE4ACEC284DAC6ED9A0D8FEB1CC070733C58213EFFED46529C54CEA06D774E3CC7E073346AEBD6C66FC973F299EB74738E400B22B1E7CDC54E71AED059D228DFEB5B29C530FF341502AE56DDCFE9\";"),"10001");
            yes(c.length()>80);yes(c.matches("[A-Za-z0-9+/=]+"));
        });
        test("js object literal becomes json",()->{
            String j=PageParser.jsToJson("{ids:['ZL1'],t:['hello'],f:['dean']}");
            yes(j.contains("\"ids\""));yes(j.contains("\"ZL1\""));yes(j.contains("\"hello\""));
        });
        test("cgi wrapper unwraps tencent mail list",()->{
            String raw="&&0&&{ids:['ZL1'],inf:[{id:'ZL1',tt:'hello'}]}";
            yes(PageParser.unwrapCgi(raw).startsWith("{ids:"));
            yes(PageParser.looksLikeMailList(raw));
            yes(PageParser.looksLikeMailList("while(1);{\"ids\":[],\"inf\":[],\"mailcount\":0}"));
        });
        test("task wait acquires lock after first work",()->{
            TaskGate g=new TaskGate();CountDownLatch in=new CountDownLatch(1),hold=new CountDownLatch(1);
            Thread a=new Thread(()->g.run(()->{in.countDown();hold.await(2,TimeUnit.SECONDS);return 1;}));
            a.start();yes(in.await(2,TimeUnit.SECONDS));
            Thread b=new Thread(()->{try{Thread.sleep(80);}catch(InterruptedException ignored){}hold.countDown();});
            b.start();
            TaskGate.Result<Integer> r=g.run(()->2,500);
            eq(r.value,Integer.valueOf(2));yes(!r.busy);
            a.join(2000);b.join(2000);
        });
        test("exmail.qq.com is trusted for student webmail",()->eq(WebFlow.campusHost("exmail.qq.com"),true));
        test("mail.qq.com is trusted for student webmail",()->eq(WebFlow.campusHost("w.mail.qq.com"),true));
        test("id.qq.com is trusted for tencent webmail login",()->eq(WebFlow.campusHost("id.qq.com"),true));
        test("weixin remains untrusted",()->eq(WebFlow.campusHost("open.weixin.qq.com"),false));
        test("quoted Location is resolved",()->eq(WebFlow.resolve(ID,"\"/next?q=1\""),ID+"/next?q=1"));
        test("spaces in Location are encoded rather than rejected",()->yes(WebFlow.resolve(ID+"/a","/b?q=hello world").contains("%20")));
        test("password form does not follow its own JS location",()->{
            AtomicInteger c=new AtomicInteger();
            WebFlow.Response r=WebFlow.follow(q->{c.incrementAndGet();return resp(200,q.url,"<input type=password name=p> window.location='/next';",null);},req(ID+"/loginpage"),false,hosts());
            eq(c.get(),1);yes(r.body.contains("password"));
        });
        test("invalid JS navigation is skipped instead of failing the page",()->{
            java.util.concurrent.atomic.AtomicInteger c=new java.util.concurrent.atomic.AtomicInteger();
            WebFlow.Response r=WebFlow.follow(q->{c.incrementAndGet();return resp(200,q.url,"window.location='javascript:alert(1)';",null);},req(ID),false,hosts());
            eq(c.get(),1);yes(r.body.contains("javascript"));
        });
        test("canvas Link rel=next is parsed",()->eq(WebFlow.relNext("<https://elearning.fudan.edu.cn/api/v1/courses?page=2>; rel=\"next\", <https://elearning.fudan.edu.cn/api/v1/courses?page=1>; rel=\"prev\""),"https://elearning.fudan.edu.cn/api/v1/courses?page=2"));
        test("canvas Link rel=next without quotes",()->eq(WebFlow.relNext("<https://elearning.fudan.edu.cn/api/v1/courses?page=3>; rel=next"),"https://elearning.fudan.edu.cn/api/v1/courses?page=3"));
        test("HTTP 302 javascript Location is skipped not failed",()->{
            AtomicInteger c=new AtomicInteger();
            WebFlow.Response r=WebFlow.follow(q->{c.incrementAndGet();return resp(302,q.url,"logged-in","javascript:void(0)");},req(ID),false,hosts());
            eq(c.get(),1);eq(r.body,"logged-in");
        });
        test("semester selected option wins over JSON.parse",()->eq(TimetableText.semesterIdFromHtml(
            "<select id='allSemesters'><option value='111'>old</option><option value='253' selected>current</option></select>var semesters = JSON.parse('{\\\"id\\\":999}');"),"253"));
        test("semester JSON.parse escaped quotes without guessing",()->eq(TimetableText.semesterIdFromHtml(
            "var semesters = JSON.parse('[{\\\"id\\\":201},{\\\"id\\\":253}]');"),"253"));
        test("missing semester is empty not invented",()->eq(TimetableText.semesterIdFromHtml("<html>no table</html>"),""));
        test("classroom status array extracted from invalid JSON blob",()->{
            String arr=PageParser.classroomStatusArray("{\"status\":[{\"id\":\"1\",\"room\":\"H2101\"}],\"time3\":\"<script>\"}");
            yes(arr.contains("H2101"));
        });
        test("exmail sid from landing url",()->eq(PageParser.exmailSid("https://exmail.qq.com/cgi-bin/frame_html?sid=AbCDefgh1234&r=1"),"AbCDefgh1234"));
        test("exmail sid from json",()->eq(PageParser.exmailSid("{\"sid\":\"AbCDefgh1234\",\"r\":1}"),"AbCDefgh1234"));
        test("exmail sid keeps comma suffix",()->eq(PageParser.exmailSid("{\"retcode\":\"0\",\"data\":{\"errcode\":\"\",\"sid\":\"97o4qrOg3vpLOi5E,7\"}}"),"97o4qrOg3vpLOi5E,7"));
        test("exmail sid from html url with comma",()->eq(PageParser.exmailSid("/cgi-bin/mail_list?sid=97o4qrOg3vpLOi5E,7&folderid=1"),"97o4qrOg3vpLOi5E,7"));
        test("numeric uin is not a mail sid",()->eq(PageParser.exmailSid("sid=337910093&r=1"),""));
        test("checkbox mailid value may appear before name",()->{
            String html="<input type=\"checkbox\" value=\"ZC0021-FVjdQ1pJMBSlRlI4K1aOM0l\" name=\"mailid\" fn=\"教务\" fa=\"jwc@fudan.edu.cn\" unread=true totime=\"1789989765000\">"
                +"<u class=\"black s1\">习题0921</u>";
            java.util.List<PageParser.WebMail> mails=PageParser.exmailCheckboxMails(html);
            eq(mails.size(),1);
            eq(mails.get(0).mailid,"ZC0021-FVjdQ1pJMBSlRlI4K1aOM0l");
            eq(mails.get(0).title,"习题0921");
            yes(PageParser.hasValuedMailid(html));
        });
        test("checkbox mail rows include title from and time",()->{
            String html="<tr><td><input totime=\"1789989765000\" unread=true fn=\"Dean\" fa=\"a@b.edu\" name=\"mailid\" value=\"ZC0021-FVjdQ1pJMBSlRlI4K1aOM0l\"></td>"
                +"<td class=\"gt tf\"><u class=\"black s1\">异常登录提醒</u></td></tr>";
            java.util.List<PageParser.WebMail> mails=PageParser.exmailCheckboxMails(html);
            eq(mails.size(),1);
            eq(mails.get(0).mailid,"ZC0021-FVjdQ1pJMBSlRlI4K1aOM0l");
            eq(mails.get(0).title,"异常登录提醒");
            eq(mails.get(0).fromName,"Dean");
            yes(mails.get(0).unread);
            eq(mails.get(0).totime,1789989765000L);
        });
        test("empty mailid template is not a confirmed inbox",()->{
            String html="<form><input name=\"mailid\" type=\"hidden\" value=\"\" /><span id=\"_ut_c\">66</span></form>";
            yes(!PageParser.looksLikeMailList(html));
            eq(PageParser.exmailInboxCount(html),66);
            eq(PageParser.exmailCheckboxMails(html).size(),0);
        });
        test("folder count zero is an empty inbox",()->{
            String html="<span id=\"_ut_c\">0</span>共 0 封<input name=\"mailid\" value=\"\">";
            yes(PageParser.looksLikeMailList(html));
            eq(PageParser.exmailInboxCount(html),0);
        });
        test("mail title skips folder count caption",()->{
            String html="<input totime=\"1\" unread=false fn=\"Dean\" fa=\"a@b.edu\" name=\"mailid\" value=\"ZC0021-ABCDEFGHIJKLMN012345\">"
                +"<span class=\"f_size normal black\">(共 66 封)</span><u class=\"black s1\">习题0921</u>";
            java.util.List<PageParser.WebMail> mails=PageParser.exmailCheckboxMails(html);
            eq(mails.size(),1);
            eq(mails.get(0).title,"习题0921");
        });
        test("elearning announcement and conversation collapse",()->{
            FeedMerge.Item a=new FeedMerge.Item();a.id="a";a.source="elearning";a.kind="announcement";
            a.title="统计学课程论文评分标准调整通知";a.summary="统计学课程论文评分标准调整通知";
            FeedMerge.Item b=new FeedMerge.Item();b.id="b";b.source="elearning";b.kind="mail";
            b.title="统计学课程论文评分标准调整通知";b.summary="课堂";
            List<FeedMerge.Item> out=FeedMerge.collapse(Arrays.asList(a,b));
            eq(out.size(),1);eq(out.get(0).title,"统计学课程论文评分标准调整通知");
        });
        test("assignment remaining hours",()->eq(FeedMerge.dueLabel(3_600_000L,0L),"还剩 1 小时"));
        test("assignment remaining days",()->eq(FeedMerge.dueLabel(3L*86_400_000L,0L),"还剩 3 天"));
        test("mail items never collapse across ids",()->{
            FeedMerge.Item a=new FeedMerge.Item();a.id="mail-1";a.source="mail";a.kind="mail";a.title="同一主题";
            FeedMerge.Item b=new FeedMerge.Item();b.id="mail-2";b.source="mail";b.kind="mail";b.title="同一主题";
            eq(FeedMerge.collapse(Arrays.asList(a,b)).size(),2);
        });
        test("math reprint and elearning copy collapse",()->{
            FeedMerge.Item a=new FeedMerge.Item();a.id="m";a.source="math";a.kind="notice";
            a.title="关于征集复旦大学数学科学学院2026年事项的通知";a.summary="数院";
            FeedMerge.Item b=new FeedMerge.Item();b.id="e";b.source="elearning";a.kind="announcement";
            b.kind="announcement";b.title="关于征集复旦大学数学科学学院2026年事项的通知";b.summary="课堂";
            eq(FeedMerge.collapse(Arrays.asList(a,b)).size(),1);
        });
        test("academic talks with different titles stay",()->{
            FeedMerge.Item a=new FeedMerge.Item();a.source="elearning";a.kind="announcement";a.title="【学术】代数几何讨论班";
            FeedMerge.Item b=new FeedMerge.Item();b.source="elearning";b.kind="announcement";b.title="【学术】概率论报告会";
            eq(FeedMerge.collapse(Arrays.asList(a,b)).size(),2);
        });
        test("homework reminder and assignment collapse",()->{
            FeedMerge.Item a=new FeedMerge.Item();a.source="elearning";a.kind="ddl";a.title="计算方法作业5";a.url="https://elearning.fudan.edu.cn/courses/1/assignments/5";
            FeedMerge.Item b=new FeedMerge.Item();b.source="elearning";b.kind="announcement";b.title="【作业提醒】计算方法作业5";b.url="https://elearning.fudan.edu.cn/planner/5";
            FeedMerge.Item c=new FeedMerge.Item();c.source="elearning";c.kind="ddl";c.title="计算方法作业5";c.url="https://elearning.fudan.edu.cn/calendar_events/9";
            eq(FeedMerge.collapse(Arrays.asList(a,b,c)).size(),1);
            eq(FeedMerge.homeworkId("【作业提醒】计算方法作业5"),"计算方法作业5");
        });
        test("homework link notice and assignment collapse",()->{
            FeedMerge.Item a=new FeedMerge.Item();a.source="elearning";a.kind="ddl";a.title="计算方法作业5";a.url="https://elearning.fudan.edu.cn/courses/1/assignments/5";
            FeedMerge.Item b=new FeedMerge.Item();b.source="elearning";b.kind="announcement";b.title="【作业链接】计算方法作业5";b.url="https://elearning.fudan.edu.cn/courses/1/discussion_topics/9";
            FeedMerge.Item c=new FeedMerge.Item();c.source="elearning";c.kind="ddl";c.title="作业链接：计算方法作业5";c.url="https://elearning.fudan.edu.cn/todo/5";
            eq(FeedMerge.stripDecor("【作业链接】计算方法作业5"),"计算方法作业5");
            eq(FeedMerge.homeworkId("作业链接：计算方法作业5"),"计算方法作业5");
            eq(FeedMerge.collapse(Arrays.asList(a,b,c)).size(),1);
        });
        test("elearning announcement and assignment with same stem collapse",()->{
            FeedMerge.Item a=new FeedMerge.Item();a.source="elearning";a.kind="ddl";a.title="统计学课程论文";
            FeedMerge.Item b=new FeedMerge.Item();b.source="elearning";b.kind="announcement";b.title="【即将到期】统计学课程论文";
            eq(FeedMerge.collapse(Arrays.asList(a,b)).size(),1);
        });
        test("chinese ordinal homework merges with arabic number",()->{
            FeedMerge.Item a=new FeedMerge.Item();a.source="elearning";a.kind="ddl";a.title="线性代数第三次作业";
            FeedMerge.Item b=new FeedMerge.Item();b.source="elearning";b.kind="ddl";b.title="线性代数作业3";
            eq(FeedMerge.homeworkId("线性代数第三次作业"),"线性代数作业3");
            eq(FeedMerge.collapse(Arrays.asList(a,b)).size(),1);
        });
        test("same canvas assignment url merges different titles",()->{
            FeedMerge.Item a=new FeedMerge.Item();a.source="elearning";a.kind="ddl";a.title="HW 3";a.url="https://elearning.fudan.edu.cn/courses/9/assignments/3";
            FeedMerge.Item b=new FeedMerge.Item();b.source="elearning";b.kind="ddl";b.title="第三次作业";b.url="https://elearning.fudan.edu.cn/courses/9/assignments/3";
            eq(FeedMerge.collapse(Arrays.asList(a,b)).size(),1);
        });
        test("different homework numbers stay",()->{
            FeedMerge.Item a=new FeedMerge.Item();a.source="elearning";a.kind="ddl";a.title="计算方法作业3";
            FeedMerge.Item b=new FeedMerge.Item();b.source="elearning";b.kind="ddl";b.title="计算方法作业5";
            eq(FeedMerge.collapse(Arrays.asList(a,b)).size(),2);
        });
        test("due soon is seven days and not overdue",()->{
            long now=1_000_000L;
            yes(FeedMerge.dueSoon(now+6L*86_400_000L, now));
            yes(!FeedMerge.dueSoon(now+20L*86_400_000L, now));
            yes(!FeedMerge.dueSoon(now-1000L, now));
            yes(FeedMerge.dueOverdue(now-1000L, now));
            yes(!FeedMerge.dueOverdue(now+1000L, now));
        });
        test("exmail cgi success url",()->eq(PageParser.exmailCgiUrl("&&0&&{url:'https://exmail.qq.com/cgi-bin/frame_html?sid=AbCDefgh1234'}"),
            "https://exmail.qq.com/cgi-bin/frame_html?sid=AbCDefgh1234"));
        test("exmail cgi quoted url",()->eq(PageParser.exmailCgiUrl("&&0&&{\"url\":\"https://exmail.qq.com/cgi-bin/login?fun=passport\"}"),
            "https://exmail.qq.com/cgi-bin/login?fun=passport"));
        test("exmail cgi raw json url",()->eq(PageParser.exmailCgiUrl("{\"url\":\"https://exmail.qq.com/cgi-bin/frame_html?sid=AbCDefgh1234\"}"),
            "https://exmail.qq.com/cgi-bin/frame_html?sid=AbCDefgh1234"));
        test("exmail cgi password error",()->eq(PageParser.exmailCgiUrl("&&1&&{errtype:1,title:'密码错误'}"),"ERR:PASSWORD"));
        test("login page labels are not password errors",()->eq(PageParser.looksLikeExmailPasswordError("<form>用户名或密码<input type=password>"),false));
        test("cgi password blob is a password error",()->eq(PageParser.looksLikeExmailPasswordError("&&1&&{errtype:1,title:'密码错误'}"),true));
        test("empty mailbox json is a mail list",()->eq(PageParser.looksLikeMailList("{\"ids\":[],\"inf\":[],\"mailcount\":0}"),true));
        test("qq mls json is a mail list",()->eq(PageParser.looksLikeMailList("{\"mls\":[{\"t\":\"hello\",\"f\":\"dean\"}]}"),true));
        test("login html is not a mail list",()->eq(PageParser.looksLikeMailList("<form><input type=password name=p>用户名或密码</form>"),false));
        test("news reads jwc table date",()->{
            List<PageParser.Notice> n=PageParser.notices("https://jwc.fudan.edu.cn/9397/list.htm",
                "<td align='left'><a href='/14/a3/c25325a791715/page.htm' title='关于复旦大学推免生替补推荐情况的说明'>关于复旦大学推免生替补推荐情况的说明</a></td><td align='right' class='ti'>2026-09-20</td>");
            eq(n.size(),1);eq(n.get(0).date,"2026-09-20");
        });
        test("news reads math list date",()->{
            List<PageParser.Notice> n=PageParser.notices("https://math.fudan.edu.cn/tzgg/list.htm",
                "<li class='news'><div class='news_date'><div class='day'>04.08</div><div class='year'>2026</div></div><div class='news_title'><a href='/db/89/c52033a777097/page.htm' title='华东杯大学生数学建模邀请赛通知'>华东杯大学生数学建模邀请赛通知</a></div><div class='news_time'>2026-04-08</div></li>");
            eq(n.size(),1);eq(n.get(0).date,"2026-04-08");
        });
        test("adjacent checkbox mails keep their own titles",()->{
            String html="<table><tr><td><input name=\"mailid\" value=\"ZC0021-AAAAAAAAAAAAAAA0001\" fn=\"dl dldl\" fa=\"a@b.edu\" unread=true totime=\"1789990000000\"></td>"
                +"<td><u class=\"black\">test</u></td></tr>"
                +"<tr><td><input name=\"mailid\" value=\"ZC0021-BBBBBBBBBBBBBBB0002\" fn=\"腾讯企业邮箱\" fa=\"noreply@exmail.qq.com\" unread=false totime=\"1789980000000\"></td>"
                +"<td><u class=\"black s1\">异地登录提醒</u></td></tr></table>";
            java.util.List<PageParser.WebMail> mails=PageParser.exmailCheckboxMails(html);
            eq(mails.size(),2);
            eq(mails.get(0).title,"test");
            eq(mails.get(0).fromName,"dl dldl");
            eq(mails.get(1).title,"异地登录提醒");
            eq(mails.get(1).fromName,"腾讯企业邮箱");
        });
        test("mail title prefers tt attribute over later black s1",()->{
            String html="<input name=\"mailid\" value=\"ZC0021-CCCCCCCCCCCCCCC0003\" tt=\"test\" fn=\"dl dldl\">"
                +"<u class=\"black\">test</u><u class=\"black s1\">异地登录提醒</u>";
            java.util.List<PageParser.WebMail> mails=PageParser.exmailCheckboxMails(html);
            eq(mails.size(),1);
            eq(mails.get(0).title,"test");
        });
        test("QR json qrCode field",()->eq(PageParser.qrPayload("{\"qrCode\":\"official-life-code-token\"}"),"official-life-code-token"));
        test("QR json nested data.qrcode",()->eq(PageParser.qrPayloadJson("{\"data\":{\"qrcode\":\"nested-official-qr\"}}"),"nested-official-qr"));
        test("life-code HTML page is not an XHR document",()->eq(WebFlow.ajaxHeader("https://ecard.fudan.edu.cn/epay/wxpage/fudan/zfm/qrcode?url=0"),false));
        test("life-code getQr endpoint is XHR",()->eq(WebFlow.ajaxHeader("https://ecard.fudan.edu.cn/epay/wxpage/fudan/zfm/qrcode/getQr"),true));
        test("consume qrcode endpoint is XHR",()->eq(WebFlow.ajaxHeader("https://ecard.fudan.edu.cn/epay/consume/qrcode"),true));
        test("print-data is not XHR",()->eq(WebFlow.ajaxHeader("https://fdjwgl.fudan.edu.cn/student/for-std/course-table/semester/1/print-data"),false));
        test("get-data is XHR",()->eq(WebFlow.ajaxHeader("https://fdjwgl.fudan.edu.cn/student/for-std/course-table/get-data?semesterId=1"),true));
        test("mail json content unescapes html",()->{
            String v=PageParser.mailJsonContent("&&0&&{content:\"<p>hello \\u4e16\\u754c</p>\"}");
            yes(v.contains("hello"));
            yes(PageParser.text(v).contains("世界"));
        });
        test("mail iframe src from content frame",()->eq(
            PageParser.mailIframeSrc("<html>读信<iframe id=\"contentFrame\" src=\"/cgi-bin/readmail?sid=Ab&mailid=Z1&t=read.html\"></iframe></html>"),
            "/cgi-bin/readmail?sid=Ab&mailid=Z1&t=read.html"));
        test("mail chrome page is not treated as body",()->eq(
            PageParser.mailBodyHtml("<html><body>读信 回复 转发<iframe id=\"contentFrame\" src=\"/cgi-bin/readmail?content=1\"></iframe></body></html>"),
            ""));
        test("mail list total from json",()->eq(PageParser.mailListTotal("{\"mailcount\":66,\"ids\":[]}"),66));
        test("session timeout cgi is not a letter body",()->{
            String blob="{title : \"cgi exception\",appname : \"readmail\",errcode: \"-2\",errmsg: \" \\x3cscript\\x3egetTop().location.href=\\x22/cgi-bin/loginpage?s=session_timeout\\x22;\\x3c/script\\x3e\" }";
            yes(PageParser.looksLikeMailSessionDead(blob));
            eq(PageParser.mailBodyHtml(blob),"");
        });
        System.out.println("RESULT "+passed+" passed "+failed+" failed");if(failed>0)System.exit(1);
    }
}
