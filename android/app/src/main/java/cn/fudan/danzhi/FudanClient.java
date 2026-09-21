package cn.fudan.danzhi;

import cn.fudan.danzhi.core.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FudanClient {
    static final String IDP = "https://id.fudan.edu.cn";
    static final String IDP_API = IDP + "/idp";
    static final String SERVICE = "https://my.fudan.edu.cn/";
    static final String ELEARNING = "https://elearning.fudan.edu.cn";
    static final String FDJWGL = "https://fdjwgl.fudan.edu.cn";
    static final String EHALL = "https://ehall.fudan.edu.cn";
    static final String MATH = "https://math.fudan.edu.cn/tzgg/list.htm";
    static final String JWC = "https://jwc.fudan.edu.cn/9397/list.htm";

    final CookieJar jar = new CookieJar();
    String username = "";
    String password = "";
    String mailPassword = "";
    boolean mailAuthBlocked = false;
    String mailSid = "";
    boolean mailListConfirmed = false;
    JSONArray lastItems = new JSONArray();
    JSONArray lastTrash = new JSONArray();
    java.util.LinkedHashSet<String> hiddenIds = new java.util.LinkedHashSet<>();
    final MfaState mfa = new MfaState();

    JSONObject login(String username, String password, String totpSecret, String totpCode) throws Exception {
        String user = username == null ? "" : username.trim();
        if (totpCode != null && !totpCode.trim().isEmpty()) {
            MfaState.Challenge c = mfa.require(user, totpCode.trim(), System.nanoTime());
            return completeOtp(c.username,c.chain,c.entityId,c.requestType,c.lck,c.requestNumber,c.referer,c.module,totpCode.trim());
        }
        if (user.isEmpty() || password == null || password.isEmpty())
            throw new WebFlow.FlowException("CREDENTIALS_REQUIRED", "请输入学号和 UIS 密码");
        mfa.clear();
        this.username = user;
        this.password = password == null ? "" : password;
        jar.clear();
        jar.viaVpn = false;
        CasStart cas = beginCas();
        String lck = cas.lck;
        String referer = cas.referer;

        JSONObject methods = postJson(IDP_API + "/authn/queryAuthMethods",
                new JSONObject().put("lck", lck), referer);
        JSONArray modules = methods.optJSONArray("data");
        String authChainCode = "";
        String entityId = methods.optString("entityId", cas.entityId);
        String requestType = methods.optString("requestType", "chain_type");
        if (modules != null && modules.length() > 0) {
            JSONObject first = modules.optJSONObject(0);
            if (first != null) authChainCode = first.optString("authChainCode", "");
            for (int i = 0; i < modules.length(); i++) {
                JSONObject m = modules.optJSONObject(i);
                if (m == null) continue;
                JSONArray codes = m.optJSONArray("moduleCodes");
                boolean pwd = "userAndPwd".equals(m.optString("moduleCode"));
                if (codes != null) {
                    for (int j = 0; j < codes.length(); j++) {
                        if ("userAndPwd".equals(codes.optString(j))) pwd = true;
                    }
                }
                if (pwd) {
                    authChainCode = m.optString("authChainCode", authChainCode);
                    break;
                }
            }
        }
        JSONObject pubBody = postJson(IDP_API + "/authn/getJsPublicKey", new JSONObject(), referer);
        Object keyData = pubBody.opt("data");
        String pub = keyData instanceof String ? (String)keyData
                : keyData instanceof JSONObject ? ((JSONObject)keyData).optString("data", "") : "";
        if (pub.isEmpty()) throw new Exception("未拿到 UIS RSA 公钥");
        String encrypted = Crypto.rsaEncrypt(password, pub);
        JSONObject authPara = new JSONObject()
                .put("loginName", username)
                .put("password", encrypted)
                .put("verifyCode", "");
        JSONObject auth = postJson(IDP_API + "/authn/authExecute", new JSONObject()
                .put("authModuleCode", "userAndPwd")
                .put("authChainCode", authChainCode)
                .put("entityId", entityId)
                .put("requestType", requestType)
                .put("lck", lck)
                .put("authPara", authPara), referer);
        if (!"200".equals(String.valueOf(auth.opt("code")))) {
            throw new Exception(auth.optString("message", "学号或密码不正确"));
        }
        String loginToken = auth.optString("loginToken", "");
        int pageLevel = auth.optInt("pageLevelNo", 0);
        String requestNumber = String.valueOf(auth.opt("requestNumber"));
        String nextChain = auth.optString("authChainCode", authChainCode);
        String nextMod = auth.optString("authModuleCode", "");
        boolean needsMfa = pageLevel == 2 || nextMod.toLowerCase().contains("otp") || loginToken.isEmpty();
        if (!loginToken.isEmpty() && !needsMfa) {
            finishCas(loginToken, referer);
            return ok(sessionNote("密码认证完成"));
        }
        mfa.set(new MfaState.Challenge(user,nextChain,entityId,requestType,lck,
                requestNumber,referer,nextMod,System.nanoTime()));
        // No lck, requestNumber, cookies, password or OTP secret is exposed to JavaScript.
        return new JSONObject().put("status", "mfa").put("message", "请输入 Authenticator 的 6 位动态口令");
    }

    private static final class CasStart {
        final String lck;
        final String referer;
        final String entityId;
        CasStart(String lck, String referer, String entityId) {
            this.lck = lck;
            this.referer = referer;
            this.entityId = entityId;
        }
    }

    /** UIS now parks lck in the 302 Location *fragment* (`/ac-h5/#/index?lck=`). Never follow that SPA hop. */
    private CasStart beginCas() throws Exception {
        Http.Resp r = Http.follow(jar, IDP + "/authserver/login?service=" + enc(SERVICE), true);
        String redirect = r.url + " " + (r.location == null ? "" : r.location);
        Matcher m = Pattern.compile("lck=([\\w_-]+)").matcher(redirect);
        if (!m.find()) throw new WebFlow.FlowException("CAS_CHALLENGE_MISSING", "统一认证未返回登录令牌，HTTP " + r.code);
        String entityId = SERVICE.replaceAll("/$", "");
        Matcher em = Pattern.compile("entityId=([^&#]+)").matcher(redirect);
        if (em.find()) entityId = java.net.URLDecoder.decode(em.group(1), "UTF-8");
        return new CasStart(m.group(1), r.url, entityId);
    }

    JSONObject completeOtp(String username, String chain, String entityId, String requestType,
                           String lck, String requestNumber, String referer, String module, String code) throws Exception {
        JSONObject auth = postJson(IDP_API + "/authn/authExecute", new JSONObject()
                .put("authModuleCode", module)
                .put("authChainCode", chain)
                .put("entityId", entityId)
                .put("requestType", requestType)
                .put("lck", lck)
                .put("requestNumber", requestNumber)
                .put("authPara", new JSONObject()
                        .put("loginName", username)
                        .put("otpCode", code)
                        .put("verifyCode", "")), referer);
        if (!"200".equals(String.valueOf(auth.opt("code")))) {
            throw new Exception(auth.optString("message", "动态口令不正确"));
        }
        String loginToken = auth.optString("loginToken", "");
        if (loginToken.isEmpty()) throw new Exception("二次认证未返回 loginToken");
        finishCas(loginToken, referer);
        mfa.clear();
        return ok(sessionNote("动态口令通过"));
    }

    private void finishCas(String loginToken, String referer) throws Exception {
        Http.Resp res = Http.fetch(jar, IDP_API + "/authCenter/authnEngine", "POST",
                "application/x-www-form-urlencoded", "loginToken=" + enc(loginToken), 18000, referer);
        String loc = res.location;
        if (loc == null || loc.isEmpty()) loc = WebFlow.navigation(res.flow());
        if (loc == null || !loc.contains("ticket="))
            throw new WebFlow.FlowException("CAS_TICKET_MISSING", "认证结束但未返回服务票据");
        loc = WebFlow.resolve(res.url, loc);
        if (!WebFlow.host(loc).equals(WebFlow.host(SERVICE)))
            throw new WebFlow.FlowException("CAS_CALLBACK_MISMATCH", "票据回调不是预期的门户主机");
        Http.Resp landing = Http.follow(jar, loc);
        WebFlow.requireHttpSuccess(landing.flow());
        if (!WebFlow.host(landing.url).equals(WebFlow.host(SERVICE)))
            throw new WebFlow.FlowException("CAS_CALLBACK_MISMATCH", "门户票据回调未完成");
        // No hidden eCard / Canvas warm-up. Each source validates its own authenticated response.
    }

    private boolean isIdpSpa(Http.Resp r) { return r != null && WebFlow.loginPage(r.flow()); }

    Http.Resp enterPublic(String url) throws Exception { return enterService(url); }

    private Http.Resp enterService(String url) throws Exception {
        // Start with the business system's real SSO entry. Follow its own registered service callback.
        // A fetched HTML page is transport success only, never evidence of authenticated API access.
        Http.Resp r = Http.follow(jar, url, true);
        WebFlow.requireHttpSuccess(r.flow());
        return r;
    }

    Http.Resp enterPortal(String url) throws Exception {
        Http.Resp r = Http.follow(jar, url, false);
        WebFlow.requireHttpSuccess(r.flow());
        return r;
    }

    boolean sessionAlive() {
        try {
            Http.Resp r = Http.follow(jar, IDP + "/authserver/login?service=" + enc(SERVICE), true);
            WebFlow.requireHttpSuccess(r.flow());
            return WebFlow.host(r.url).equals(WebFlow.host(SERVICE));
        } catch (Exception | LinkageError e) { return false; }
    }

    JSONObject poll() throws Exception {
        JSONArray items = new JSONArray();
        JSONArray courses = new JSONArray();
        final JSONObject sources = new JSONObject();
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            Future<JSONArray> mathF = pool.submit(() -> takeNews("math", MATH, sources));
            Future<JSONArray> jwcF = pool.submit(() -> takeNews("jwc", JWC, sources));
            Future<JSONArray> mailF = pool.submit(() -> takeMail(sources));
            Future<JSONObject> elF = pool.submit(() -> takeElearning(sources));
            Future<JSONArray> hallF = pool.submit(() -> takeEhall(sources));
            Future<JSONArray> tableF = pool.submit(() -> takeTimetable(sources));
            concat(items, awaitArr(mathF));
            concat(items, awaitArr(jwcF));
            concat(items, awaitArr(mailF));
            JSONObject el = awaitObj(elF);
            if (el != null) {
                concat(items, el.optJSONArray("items"));
                concat(courses, el.optJSONArray("courses"));
            }
            concat(items, awaitArr(hallF));
            concat(courses, awaitArr(tableF));
        } finally {
            pool.shutdownNow();
        }
        JSONObject out = new JSONObject();
        boolean failed=false;Iterator<String> sourceKeys=sources.keys();
        while(sourceKeys.hasNext()){JSONObject source=sources.optJSONObject(sourceKeys.next());if(source!=null&&!source.optBoolean("ok"))failed=true;}
        JSONArray scored = Filter.score(items);
        JSONArray inbox = new JSONArray();
        JSONArray recycled = new JSONArray();
        for (int i = 0; i < scored.length(); i++) {
            JSONObject it = scored.optJSONObject(i);
            if (it == null) continue;
            String id = it.optString("id");
            if (!id.isEmpty() && hiddenIds.contains(id)) {
                try { it.put("trashed", true); } catch (Exception ignored) {}
                recycled.put(it);
            } else inbox.put(it);
        }
        java.util.Set<String> seenTrash = new java.util.HashSet<>();
        JSONArray trash = new JSONArray();
        for (int i = 0; i < recycled.length(); i++) {
            JSONObject it = recycled.optJSONObject(i);
            if (it != null && seenTrash.add(it.optString("id"))) trash.put(it);
        }
        for (int i = 0; i < lastTrash.length(); i++) {
            JSONObject it = lastTrash.optJSONObject(i);
            if (it != null && seenTrash.add(it.optString("id"))) trash.put(it);
        }
        out.put("status", failed?"partial":"ok");
        out.put("items", inbox);
        out.put("trash", trash);
        out.put("courses", sortCourses(courses));
        out.put("sources", sources);
        out.put("polledAt", System.currentTimeMillis());
        out.put("mailHost", "mail.m.fudan.edu.cn");
        lastItems = inbox;
        lastTrash = trash;
        return out;
    }

    private JSONArray takeNews(String source, String url, JSONObject sources) {
        JSONArray items = new JSONArray();
        collectNews(items, sources, source, url);
        return items;
    }
    private JSONArray takeMail(JSONObject sources) {
        try {
            JSONArray mail = MailClient.fetch(this, username, mailPassword);
            putSrc(sources, "mail", src(true, mail.length(), null));
            return mail;
        } catch (Exception | LinkageError e) {
            putSrc(sources, "mail", srcQuiet(false, 0, Diagnostics.message(e)));
            return new JSONArray();
        }
    }
    private JSONObject takeElearning(JSONObject sources) {
        try {
            JSONObject el = fetchElearning();
            int n = el.optJSONArray("items") == null ? 0 : el.optJSONArray("items").length();
            putSrc(sources, "elearning", src(true, n, null));
            return el;
        } catch (Exception | LinkageError e) {
            putSrc(sources, "elearning", srcQuiet(false, 0, Diagnostics.message(e)));
            return null;
        }
    }
    private JSONArray takeEhall(JSONObject sources) {
        try {
            JSONArray hall = fetchEhall();
            putSrc(sources, "ehall", src(true, hall.length(), null));
            return hall;
        } catch (Exception | LinkageError e) {
            putSrc(sources, "ehall", srcQuiet(false, 0, Diagnostics.message(e)));
            return new JSONArray();
        }
    }
    private JSONArray takeTimetable(JSONObject sources) {
        try {
            JSONArray table = fetchTimetable();
            putSrc(sources, "timetable", src(true, table.length(), null));
            return table;
        } catch (Exception | LinkageError e) {
            putSrc(sources, "timetable", srcQuiet(false, 0, Diagnostics.message(e)));
            return new JSONArray();
        }
    }
    private static void putSrc(JSONObject sources, String key, JSONObject val) {
        synchronized (sources) {
            try { sources.put(key, val); } catch (Exception ignored) {}
        }
    }
    private static JSONObject srcQuiet(boolean ok, int count, String err) {
        try { return src(ok, count, err); } catch (Exception e) { return new JSONObject(); }
    }
    private static JSONArray awaitArr(Future<JSONArray> f) {
        try { JSONArray a = f.get(50, TimeUnit.SECONDS); return a == null ? new JSONArray() : a; }
        catch (Exception e) { return new JSONArray(); }
    }
    private static JSONObject awaitObj(Future<JSONObject> f) {
        try { return f.get(50, TimeUnit.SECONDS); }
        catch (Exception e) { return null; }
    }

    private static JSONArray sortCourses(JSONArray courses) {
        JSONArray merged;
        try { merged = TimetableAdapter.mergeSlots(courses == null ? new JSONArray() : courses); }
        catch (Exception e) { merged = courses == null ? new JSONArray() : courses; }
        JSONArray out = new JSONArray();
        java.util.List<JSONObject> list = new java.util.ArrayList<>();
        for (int i = 0; i < merged.length(); i++) {
            JSONObject c = merged.optJSONObject(i);
            if (c != null) list.add(c);
        }
        list.sort((a, b) -> {
            int d = a.optInt("day", 9) - b.optInt("day", 9);
            if (d != 0) return d;
            return a.optInt("startPeriod", 99) - b.optInt("startPeriod", 99);
        });
        for (JSONObject c : list) out.put(c);
        return out;
    }

    private JSONObject fetchElearning() throws Exception {
        enterService(ELEARNING + "/login/cas");
        Object profile = getJson(ELEARNING + "/api/v1/users/self");
        if (!(profile instanceof JSONObject) || !((JSONObject)profile).has("id"))
            throw new WebFlow.FlowException("AUTH_REQUIRED", "eLearning 用户身份尚未通过验证");
        JSONArray active = getJsonArrayPages(ELEARNING + "/api/v1/courses?enrollment_state=active&per_page=100", 4);
        JSONArray courses = mergeCourses(
                active,
                getJsonArrayPagesQuiet(ELEARNING + "/api/v1/courses?enrollment_state=invited_or_pending&per_page=50", 1)
        );
        if (courses.length() == 0) {
            courses = getJsonArrayPages(ELEARNING + "/api/v1/users/self/favorites/courses?per_page=50", 2);
        }
        if (courses.length() == 0) {
            courses = getJsonArrayPages(ELEARNING + "/api/v1/courses?per_page=100", 3);
        }
        java.util.HashSet<Integer> activeIds = new java.util.HashSet<>();
        for (int i = 0; i < active.length(); i++) {
            JSONObject c = active.optJSONObject(i);
            if (c != null) activeIds.add(c.optInt("id"));
        }
        if (activeIds.isEmpty()) {
            for (int i = 0; i < courses.length(); i++) {
                JSONObject c = courses.optJSONObject(i);
                if (c != null) activeIds.add(c.optInt("id"));
            }
        }
        java.util.LinkedHashMap<String, JSONObject> uniq = new java.util.LinkedHashMap<>();
        java.util.HashSet<String> submittedHw = new java.util.HashSet<>();
        java.util.HashSet<String> submittedUrls = new java.util.HashSet<>();
        int nCourse = courses.length();
        for (int start = 0; start < nCourse; start += 8) {
            StringBuilder codes = new StringBuilder();
            int end = Math.min(nCourse, start + 8);
            for (int i = start; i < end; i++) {
                JSONObject c = courses.optJSONObject(i);
                if (c == null) continue;
                if (codes.length() > 0) codes.append("&");
                codes.append("context_codes[]=course_").append(c.optInt("id"));
            }
            if (codes.length() == 0) continue;
            try {
                JSONArray anns = getJsonArrayPages(ELEARNING + "/api/v1/announcements?" + codes
                        + "&latest_only=false&start_date=" + enc(iso(-120)) + "&end_date=" + enc(iso(21)) + "&per_page=50", 2);
                for (int i = 0; i < anns.length(); i++) {
                    JSONObject a = anns.optJSONObject(i);
                    if (a == null) continue;
                    String url = firstNonEmpty(a.optString("html_url"), a.optString("url"),
                            ELEARNING + "/courses/" + a.optString("context_code").replace("course_", "") + "/discussion_topics/" + a.opt("id"));
                    remember(uniq, item("elearning", "announcement", a.optString("title"),
                            strip(a.optString("message", a.optString("title"))),
                            url, a.optString("posted_at"), null, a.optString("context_code")));
                }
            } catch (Exception ignored) {}
        }
        try {
            JSONArray planner = getJsonArrayPages(ELEARNING + "/api/v1/planner/items?start_date="
                    + enc(iso(-21)) + "&end_date=" + enc(iso(45)) + "&per_page=80", 2);
            for (int i = 0; i < planner.length(); i++) {
                JSONObject p = planner.optJSONObject(i);
                if (p == null) continue;
                JSONObject pl = p.optJSONObject("plannable");
                if (pl == null) continue;
                String title = pl.optString("title", pl.optString("name"));
                if (title.isEmpty()) continue;
                if (canvasSubmitted(p, pl)) {
                    markSubmitted(submittedHw, submittedUrls, title,
                            firstNonEmpty(pl.optString("html_url"), p.optString("html_url")));
                    continue;
                }
                String type = p.optString("plannable_type");
                String kind = type.contains("assignment") || type.contains("quiz") ? "ddl" : "announcement";
                if (title.contains("考试") || type.contains("calendar_event")) kind = "exam";
                if (type.contains("discussion")) kind = "announcement";
                remember(uniq, item("elearning", kind, title, p.optString("context_name", title),
                        firstNonEmpty(pl.optString("html_url"), p.optString("html_url"),
                                ELEARNING + "/planner/" + p.optString("plannable_id", title)),
                        firstNonEmpty(pl.optString("created_at"), pl.optString("updated_at")),
                        pl.optString("due_at", null),
                        p.optString("context_name")));
            }
        } catch (Exception ignored) {}
        try {
            JSONArray todos = getJsonArrayPages(ELEARNING + "/api/v1/users/self/todo?per_page=50", 2);
            for (int i = 0; i < todos.length(); i++) {
                JSONObject t = todos.optJSONObject(i);
                if (t == null) continue;
                JSONObject a = t.optJSONObject("assignment");
                if (a == null) a = t.optJSONObject("quiz");
                if (a == null) continue;
                remember(uniq, item("elearning", "ddl", a.optString("name"), t.optString("context_name", "Canvas 待办"),
                        firstNonEmpty(a.optString("html_url"), ELEARNING + "/todo/" + a.opt("id")),
                        firstNonEmpty(a.optString("created_at"), a.optString("updated_at")), a.optString("due_at", null),
                        t.optString("context_name")));
            }
        } catch (Exception ignored) {}
        try {
            JSONArray missing = getJsonArrayPages(ELEARNING + "/api/v1/users/self/missing_submissions?include[]=planner_overrides&filter[]=submittable&per_page=50", 2);
            for (int i = 0; i < missing.length(); i++) {
                JSONObject m = missing.optJSONObject(i);
                if (m == null) continue;
                String name = m.optString("name");
                if (name.isEmpty()) continue;
                remember(uniq, item("elearning", "ddl", name, "未提交作业",
                        firstNonEmpty(m.optString("html_url"), ELEARNING + "/assignments/" + m.opt("id")),
                        firstNonEmpty(m.optString("created_at"), m.optString("updated_at")), m.optString("due_at", null), null));
            }
        } catch (Exception ignored) {}
        int assignN = courses.length();
        ExecutorService asgPool = Executors.newFixedThreadPool(6);
        List<Future<?>> asgJobs = new ArrayList<>();
        for (int i = 0; i < assignN; i++) {
            JSONObject c = courses.optJSONObject(i);
            if (c == null) continue;
            final int cid = c.optInt("id");
            if (cid <= 0 || (!activeIds.isEmpty() && !activeIds.contains(cid))) continue;
            final String courseName = c.optString("name");
            asgJobs.add(asgPool.submit(() -> {
                try {
                    JSONArray asg = getJsonArrayPages(ELEARNING + "/api/v1/courses/" + cid
                            + "/assignments?include[]=submission&include[]=all_dates&per_page=100", 1);
                    for (int j = 0; j < asg.length(); j++) {
                        JSONObject a = asg.optJSONObject(j);
                        if (a == null) continue;
                        String name = a.optString("name");
                        if (name.isEmpty()) continue;
                        boolean unpublished = "unpublished".equals(a.optString("workflow_state"))
                                || (a.has("published") && !a.optBoolean("published", true));
                        if (unpublished && a.optString("due_at").isEmpty() && a.optString("unlock_at").isEmpty())
                            continue;
                        JSONObject sub = a.optJSONObject("submission");
                        boolean submitted = sub != null && (
                                "submitted".equals(sub.optString("workflow_state"))
                                || "graded".equals(sub.optString("workflow_state"))
                                || "pending_review".equals(sub.optString("workflow_state"))
                                || sub.optBoolean("submitted", false)
                                || sub.optBoolean("excused", false)
                                || sub.optString("submitted_at").length() > 4);
                        if (submitted) {
                            synchronized (submittedHw) {
                                markSubmitted(submittedHw, submittedUrls, name,
                                        firstNonEmpty(a.optString("html_url"), ELEARNING + "/courses/" + cid + "/assignments/" + a.opt("id")));
                            }
                            continue;
                        }
                        synchronized (uniq) {
                            remember(uniq, item("elearning", name.contains("考试") ? "exam" : "ddl", name, courseName,
                                    firstNonEmpty(a.optString("html_url"), ELEARNING + "/courses/" + cid + "/assignments/" + a.opt("id")),
                                    firstNonEmpty(a.optString("created_at"), a.optString("updated_at"), a.optString("posted_at")),
                                    a.optString("due_at", null),
                                    courseName));
                        }
                    }
                } catch (Exception ignored) {}
            }));
        }
        for (Future<?> job : asgJobs) {
            try { job.get(12, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        asgPool.shutdownNow();
        dropSubmitted(uniq, submittedHw, submittedUrls);
        JSONArray items = new JSONArray();
        for (JSONObject v : uniq.values()) items.put(v);
        JSONObject out = new JSONObject();
        out.put("items", items);
        out.put("courses", new JSONArray());
        return out;
    }

    JSONObject readItem(String id) throws Exception {
        JSONObject it = findItem(id);
        if (it == null) throw new WebFlow.FlowException("ITEM_MISSING", "找不到这条记录，请先巡检");
        String source = it.optString("source");
        if ("mail".equals(source)) return MailClient.read(this, it);
        if ("elearning".equals(source)) return readElearningItem(it);
        String summary = it.optString("summary", it.optString("title"));
        return new JSONObject().put("status", "ok").put("id", id)
                .put("title", it.optString("title"))
                .put("html", "<p>" + MailClient.sanitize(summary).replace("<", "<") + "</p>")
                .put("url", it.optString("url"));
    }

    JSONObject trashItems(String idsJson) throws Exception {
        JSONArray raw = new JSONArray(idsJson == null ? "[]" : idsJson);
        JSONArray mail = new JSONArray();
        JSONArray recycled = new JSONArray();
        java.util.LinkedHashMap<String, JSONObject> byId = new java.util.LinkedHashMap<>();
        for (int i = 0; i < lastItems.length(); i++) {
            JSONObject it = lastItems.optJSONObject(i);
            if (it != null) byId.put(it.optString("id"), it);
        }
        for (int i = 0; i < lastTrash.length(); i++) {
            JSONObject it = lastTrash.optJSONObject(i);
            if (it != null) byId.putIfAbsent(it.optString("id"), it);
        }
        java.util.LinkedHashSet<String> drop = new java.util.LinkedHashSet<>();
        for (int i = 0; i < raw.length(); i++) {
            Object node = raw.opt(i);
            JSONObject it = null;
            String id = "";
            if (node instanceof JSONObject) {
                it = (JSONObject) node;
                id = it.optString("id");
                if (!id.isEmpty()) byId.putIfAbsent(id, it);
            } else {
                id = raw.optString(i);
            }
            if (id.isEmpty()) continue;
            drop.add(id);
            JSONObject full = byId.get(id);
            if (full == null) full = it;
            if (full == null) {
                try { full = new JSONObject().put("id", id).put("source", "mail").put("trashed", true); }
                catch (Exception e) { continue; }
            }
            try { full.put("trashed", true); } catch (Exception ignored) {}
            hiddenIds.add(id);
            recycled.put(full);
            if ("mail".equals(full.optString("source")) || id.startsWith("mail")) mail.put(full);
        }
        JSONArray kept = new JSONArray();
        for (int i = 0; i < lastItems.length(); i++) {
            JSONObject it = lastItems.optJSONObject(i);
            if (it == null) continue;
            if (!drop.contains(it.optString("id"))) kept.put(it);
        }
        JSONObject result;
        try {
            result = MailClient.trash(this, mail);
        } catch (Exception e) {
            result = new JSONObject().put("status", "partial").put("moved", 0).put("failed", mail.length())
                    .put("message", "邮箱服务器未能同步，已先从信匣移出");
        }
        int local = 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        JSONArray trash = new JSONArray();
        for (int i = 0; i < recycled.length(); i++) {
            JSONObject it = recycled.optJSONObject(i);
            if (it != null && seen.add(it.optString("id"))) { trash.put(it); local++; }
        }
        for (int i = 0; i < lastTrash.length(); i++) {
            JSONObject it = lastTrash.optJSONObject(i);
            if (it != null && seen.add(it.optString("id"))) trash.put(it);
        }
        lastItems = kept;
        lastTrash = trash;
        int serverMoved = result.optInt("moved", 0);
        result.put("items", lastItems);
        result.put("trash", lastTrash);
        result.put("removed", local);
        result.put("moved", local);
        if (local == 0) result.put("message", "没有可移入回收站的条目。长按信匣里的一条再点「移入回收站」");
        else if (mail.length() > 0 && serverMoved == 0)
            result.put("message", "已将 "+local+" 条移入应用回收站（邮箱稍后同步）。点「回收站」查看");
        else result.put("message", "已将 "+local+" 条移入回收站。点顶部「回收站」查看");
        return result;
    }

    private JSONObject findItem(String id) {
        if (id == null) return null;
        for (int i = 0; i < lastItems.length(); i++) {
            JSONObject it = lastItems.optJSONObject(i);
            if (it != null && id.equals(it.optString("id"))) return it;
        }
        return null;
    }

    private JSONObject readElearningItem(JSONObject it) throws Exception {
        String url = it.optString("url");
        String api = canvasApiFromUrl(url);
        String title = it.optString("title");
        String html = "<p>" + strip(it.optString("summary")) + "</p>";
        if (api != null) {
            try {
                Object raw = getJson(api);
                if (raw instanceof JSONObject) {
                    JSONObject o = (JSONObject) raw;
                    if (!o.optString("title").isEmpty()) title = o.optString("title");
                    if (!o.optString("name").isEmpty() && title.equals(it.optString("title"))) title = o.optString("name");
                    String body = o.optString("message", o.optString("description", o.optString("body")));
                    if (body.isEmpty() && o.optJSONArray("messages") != null) {
                        JSONArray msgs = o.optJSONArray("messages");
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < msgs.length(); i++) {
                            JSONObject m = msgs.optJSONObject(i);
                            if (m == null) continue;
                            sb.append("<p><strong>").append(strip(m.optString("author", m.optString("author_name"))))
                                    .append("</strong></p>").append(m.optString("body", m.optString("message")));
                        }
                        body = sb.toString();
                    }
                    if (!body.isEmpty()) html = MailClient.sanitize(body);
                }
            } catch (Exception ignored) {}
        }
        return new JSONObject().put("status", "ok").put("id", it.optString("id"))
                .put("title", title).put("html", html).put("url", url);
    }

    private static String canvasApiFromUrl(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("elearning\\.fudan\\.edu\\.cn/courses/(\\d+)/assignments/(\\d+)").matcher(url);
        if (m.find()) return ELEARNING + "/api/v1/courses/" + m.group(1) + "/assignments/" + m.group(2);
        m = Pattern.compile("elearning\\.fudan\\.edu\\.cn/courses/(\\d+)/discussion_topics/(\\d+)").matcher(url);
        if (m.find()) return ELEARNING + "/api/v1/courses/" + m.group(1) + "/discussion_topics/" + m.group(2);
        m = Pattern.compile("elearning\\.fudan\\.edu\\.cn/courses/(\\d+)/quizzes/(\\d+)").matcher(url);
        if (m.find()) return ELEARNING + "/api/v1/courses/" + m.group(1) + "/quizzes/" + m.group(2);
        m = Pattern.compile("elearning\\.fudan\\.edu\\.cn/conversations/(\\d+)").matcher(url);
        if (m.find()) return ELEARNING + "/api/v1/conversations/" + m.group(1);
        return null;
    }

    private JSONArray getJsonArrayPages(String url, int maxPages) throws Exception {
        JSONArray all = new JSONArray();
        String next = url;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < Math.max(1, maxPages) && next != null && seen.add(next); i++) {
            Http.Resp r;
            try {
                r = Http.fetch(jar, next, "GET", null, null, 10000);
                if (r.code >= 300 && r.code < 400 && r.location != null && !r.location.isEmpty()) {
                    r = Http.follow(jar, next, false);
                }
            } catch (Exception e) {
                break;
            }
            if (r.code == 401 || r.code == 403 || WebFlow.loginPage(r.flow()))
                throw new WebFlow.FlowException("AUTH_REQUIRED", "eLearning 会话无效");
            if (r.code < 200 || r.code >= 400) break;
            Object json = parseJsonBody(r.body);
            if (json == null) break;
            JSONArray page = asArrayOrEmpty(json);
            concat(all, page);
            if (page.length() == 0) break;
            String more = WebFlow.relNext(r.link);
            if (more != null && !more.isEmpty() && !more.startsWith("http")) {
                try { more = WebFlow.resolve(r.url, more); } catch (Exception e) { more = null; }
            }
            if (more == null && page.length() >= 40) more = bumpPage(next);
            next = more;
        }
        return all;
    }

    private static String bumpPage(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("([?&]page=)(\\d+)").matcher(url);
        if (m.find()) {
            int n = Integer.parseInt(m.group(2)) + 1;
            return url.substring(0, m.start(2)) + n + url.substring(m.end(2));
        }
        return url + (url.contains("?") ? "&" : "?") + "page=2";
    }

    private static JSONArray mergeCourses(JSONArray... lists) {
        JSONArray out = new JSONArray();
        java.util.LinkedHashSet<Integer> seen = new java.util.LinkedHashSet<>();
        if (lists == null) return out;
        for (JSONArray list : lists) {
            if (list == null) continue;
            for (int i = 0; i < list.length(); i++) {
                JSONObject c = list.optJSONObject(i);
                if (c == null) continue;
                int id = c.optInt("id");
                if (id <= 0 || !seen.add(id)) continue;
                out.put(c);
            }
        }
        return out;
    }

    private JSONArray getJsonArrayPagesQuiet(String url, int maxPages) {
        try { return getJsonArrayPages(url, maxPages); }
        catch (Exception e) { return new JSONArray(); }
    }

    private static void markSubmitted(java.util.Set<String> hw, java.util.Set<String> urls, String title, String url) {
        String id = FeedMerge.homeworkId(title);
        if (id != null && !id.isEmpty()) hw.add(id);
        if (url != null && !url.isEmpty()) urls.add(url);
        Matcher m = Pattern.compile("elearning\\.fudan\\.edu\\.cn/courses/\\d+/assignments/\\d+").matcher(url == null ? "" : url);
        if (m.find()) urls.add("https://" + m.group());
    }

    private static boolean canvasSubmitted(JSONObject item, JSONObject plannable) {
        if (item != null) {
            Object raw = item.opt("submissions");
            if (raw instanceof Boolean && (Boolean) raw) return true;
            if (raw instanceof JSONObject) {
                JSONObject s = (JSONObject) raw;
                if (s.optBoolean("submitted") || s.optBoolean("graded") || s.optBoolean("excused")) return true;
                String st = s.optString("workflow_state");
                if ("submitted".equals(st) || "graded".equals(st) || "pending_review".equals(st)) return true;
            }
        }
        if (plannable != null) {
            JSONObject s = plannable.optJSONObject("submission");
            if (s != null) {
                String st = s.optString("workflow_state");
                if ("submitted".equals(st) || "graded".equals(st) || "pending_review".equals(st)) return true;
                if (s.optBoolean("submitted") || s.optString("submitted_at").length() > 4) return true;
            }
        }
        return false;
    }

    private static void dropSubmitted(java.util.LinkedHashMap<String, JSONObject> uniq,
                                      java.util.Set<String> submittedHw, java.util.Set<String> submittedUrls) {
        if (uniq == null || uniq.isEmpty()) return;
        java.util.ArrayList<String> drop = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, JSONObject> e : uniq.entrySet()) {
            JSONObject it = e.getValue();
            if (it == null) continue;
            if (!FeedMerge.isAssignment(it.optString("kind")) && !it.optString("title").contains("作业")) continue;
            String url = it.optString("url");
            String hw = FeedMerge.homeworkId(it.optString("title"));
            boolean hit = (!hw.isEmpty() && submittedHw.contains(hw));
            if (!hit && url != null) {
                for (String u : submittedUrls) {
                    if (!u.isEmpty() && url.contains(u.replace("https://", "").replace("http://", ""))) { hit = true; break; }
                }
            }
            if (hit) drop.add(e.getKey());
        }
        for (String k : drop) uniq.remove(k);
    }

    private static void remember(java.util.LinkedHashMap<String, JSONObject> uniq, JSONObject it) {
        if (it == null || uniq == null) return;
        String title = it.optString("title");
        if (title.isEmpty()) return;
        FeedMerge.Item fi = Filter.asItem(it);
        String key = FeedMerge.key(fi);
        JSONObject old = uniq.get(key);
        if (old == null) { uniq.put(key, it); return; }
        FeedMerge.Item oi = Filter.asItem(old);
        JSONObject pick = FeedMerge.better(fi, oi) ? it : old;
        FeedMerge.Item keep = FeedMerge.combine(fi, oi);
        try {
            pick.put("title", keep.title);
            if (!FeedMerge.worthlessSummary(keep.summary)) pick.put("summary", keep.summary);
            else if (FeedMerge.courseScore(keep.course) > 2) pick.put("summary", keep.course);
            if (FeedMerge.courseScore(keep.course) > 2) pick.put("course", keep.course);
            if (keep.url != null && !keep.url.isEmpty()) pick.put("url", keep.url);
            if (keep.dueAt != null && !keep.dueAt.isEmpty()) pick.put("dueAt", keep.dueAt);
        } catch (Exception ignored) {}
        uniq.put(key, pick);
    }

    private static boolean betterItem(JSONObject neu, JSONObject old) {
        String nu = neu.optString("url"), ou = old.optString("url");
        int ns = urlScore(nu), os = urlScore(ou);
        if (ns != os) return ns > os;
        boolean nd = neu.has("dueAt") && !neu.optString("dueAt").isEmpty();
        boolean od = old.has("dueAt") && !old.optString("dueAt").isEmpty();
        if (nd != od) return nd;
        return neu.optString("summary").length() > old.optString("summary").length();
    }

    private static int urlScore(String url) {
        if (url == null) return 0;
        if (url.contains("/assignments/")) return 5;
        if (url.contains("/discussion_topics/")) return 4;
        if (url.contains("/quizzes/")) return 4;
        if (url.contains("/conversations/")) return 3;
        if (url.contains("/calendar_events/")) return 2;
        if (url.contains("elearning.fudan.edu.cn/") && url.length() > 40) return 1;
        return 0;
    }

    private static Object parseJsonBody(String body) {
        if (body == null) return null;
        String t = body.trim();
        if (t.startsWith("while(1);")) t = t.substring("while(1);".length()).trim();
        try {
            if (t.startsWith("[")) return new JSONArray(t);
            if (t.startsWith("{")) return new JSONObject(t);
        } catch (Exception ignored) {}
        return null;
    }

    private static JSONArray asArrayOrEmpty(Object raw) {
        try { return asArray(raw); } catch (Exception e) { return new JSONArray(); }
    }


    private JSONArray fetchEhall() throws Exception {
        String[] entries = {
                EHALL + "/fudan_rh/frontend/cas/index?redirect=" + enc(EHALL),
                EHALL + "/manage/common/login/index?redirect=" + enc(EHALL),
                EHALL + "/login",
                IDP + "/authserver/login?service=" + enc(EHALL + "/"),
                EHALL + "/",
                EHALL
        };
        Exception last=null;
        boolean in=false;
        for(String cas:entries){
            try {
                Http.Resp r=Http.follow(jar,cas,false);
                if(isIdpSpa(r)) { last=new WebFlow.FlowException("AUTH_REQUIRED","办事大厅 CAS 未完成"); continue; }
                String host=WebFlow.host(r.url);
                if(host.endsWith("fudan.edu.cn") && host.contains("ehall")) { in=true; break; }
                if(host.endsWith("fudan.edu.cn")) { in=true; }
            } catch(WebFlow.FlowException e){
                if("UNTRUSTED_REDIRECT".equals(e.category)) { last=e; continue; }
                last=e;
            } catch(Exception e){ last=e; }
        }
        if(!in && last!=null) throw last;
        String[] urls = {
                EHALL + "/personal/frontend/data/info",
                EHALL + "/schedule/frontend/default/index",
                EHALL + "/schedule/frontend/calendar/calendar",
                EHALL + "/xisu/frontend/task/list?status=0",
                EHALL + "/xisu/frontend/task/apply?status=0",
                EHALL + "/fudan/frontend/home/daily",
                EHALL + "/taskcenter/api/todo",
                EHALL + "/jsonp/userDesktopInfo.json",
                EHALL + "/frontend/vpage/check-auth?project_id=1"
        };
        JSONArray items = new JSONArray();
        boolean hit = false;
        for (String url : urls) {
            try {
                Http.Resp r = Http.follow(jar, url);
                if (isIdpSpa(r)) continue;
                if (r.url.contains("id.fudan.edu.cn")) continue;
                if (r.code<200||r.code>=400) continue;
                if (WebFlow.host(r.url).endsWith("fudan.edu.cn")) in = true;
                JSONObject json = tryJsonObj(r.body);
                if (json == null || json.has("errors") || json.has("error")) continue;
                hit = true;
                collectTodos(json, items, 0);
            } catch (Exception ignored) {
            }
        }
        if (!hit && !in) throw new WebFlow.FlowException("SCHEMA_UNVERIFIED", "办事大厅未返回可验证的事项列表；不能报告 0 条");
        return items;
    }

    private void collectTodos(Object node, JSONArray out, int depth) {
        if (depth > 8 || node == null) return;
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) collectTodos(arr.opt(i), out, depth + 1);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject rec = (JSONObject) node;
        String title = rec.optString("task_name", rec.optString("title", rec.optString("name", rec.optString("taskName", rec.optString("content")))));
        boolean looks = rec.has("todoId") || rec.has("taskId") || rec.has("task_id") || rec.has("inst_id")
                || rec.has("processInstanceId") || rec.has("form_url") || rec.has("jumpUrl")
                || "todo".equals(rec.optString("status")) || rec.optBoolean("todo");
        if (!title.isEmpty() && looks) {
            out.put(item("ehall", "todo", title, rec.optString("app_name", rec.optString("appName", "办事大厅")),
                    rec.optString("form_url", rec.optString("jumpUrl", rec.optString("url", rec.optString("pcUrl", EHALL)))),
                    rec.optString("createTime", rec.optString("create_time", rec.optString("limitTime"))),
                    rec.optString("limitTime", null), null));
        }
        Iterator<String> keys = rec.keys();
        while (keys.hasNext()) collectTodos(rec.opt(keys.next()), out, depth + 1);
    }

    private JSONArray fetchTimetable() throws Exception {
        String table = FDJWGL + "/student/for-std/course-table";
        String sso = FDJWGL + "/student/sso/login?refer=" + enc(table);
        Exception last=null;
        try { enterPortal(sso); } catch(Exception e){ last=e; }
        Http.Resp home=null;
        try {
            home = Http.follow(jar, table, false);
            if (WebFlow.loginPage(home.flow())) {
                Http.Resp cas = Http.follow(jar, IDP + "/authserver/login?service=" + enc(table), true);
                if (cas.url.contains("ticket=") || (cas.location != null && cas.location.contains("ticket="))) {
                    String loc = cas.url.contains("ticket=")?cas.url:cas.location;
                    Http.follow(jar, loc, false);
                }
                home = Http.follow(jar, table, false);
            }
            WebFlow.requireHttpSuccess(home.flow());
        } catch (Exception e) {
            last=e;
            jar.viaVpn = true;
            try { Http.follow(jar, Vpn.LOGIN, false); } catch (Exception ignored) {}
            home = Http.follow(jar, table, false);
            WebFlow.requireHttpSuccess(home.flow());
        }
        try {
            return TimetableAdapter.fetch(jar, home);
        } catch (Exception e) {
            last=e;
            try {
                Http.Resp daily = Http.follow(jar, EHALL + "/fudan/frontend/home/daily");
                JSONObject dj = tryJsonObj(daily.body);
                JSONArray fromDaily = parseDailyCourses(dj);
                if (fromDaily.length() > 0) return fromDaily;
            } catch (Exception ignored) {}
            if (last instanceof WebFlow.FlowException) throw last;
            throw new WebFlow.FlowException("SCHEMA_UNVERIFIED", last==null?"课表未取到":last.getMessage());
        }
    }

    private JSONArray parseDailyCourses(JSONObject json) {
        JSONArray out = new JSONArray();
        if (json == null) return out;
        JSONObject d = json.optJSONObject("d");
        JSONArray list = d != null ? d.optJSONArray("list") : json.optJSONArray("list");
        if (list == null) return out;
        for (int i = 0; i < list.length(); i++) {
            JSONObject ev = list.optJSONObject(i);
            if (ev == null) continue;
            String name = ev.optString("content");
            if (name.isEmpty()) continue;
            String blob = ev.optString("time") + ev.optString("detail") + ev.optString("class");
            Matcher dm = Pattern.compile("周([一二三四五六日天])").matcher(blob);
            if (!dm.find()) continue;
            String wd = dm.group(1);
            int day = "一".equals(wd) ? 1 : "二".equals(wd) ? 2 : "三".equals(wd) ? 3
                    : "四".equals(wd) ? 4 : "五".equals(wd) ? 5 : "六".equals(wd) ? 6 : 7;
            Matcher pm = Pattern.compile("(\\d{1,2})\\s*[-–~到至]\\s*(\\d{1,2})\\s*节").matcher(blob);
            int start = 1, end = 1;
            if (pm.find()) {
                start = Integer.parseInt(pm.group(1));
                end = Integer.parseInt(pm.group(2));
            }
            JSONObject c = new JSONObject();
            try {
                c.put("name", name);
                c.put("location", ev.optString("class"));
                c.put("day", day);
                c.put("startPeriod", start);
                c.put("endPeriod", end);
                out.put(c);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private JSONArray parseJwgl(JSONObject json) {
        JSONArray out = new JSONArray();
        if (json == null) return out;
        walkJwgl(json, out);
        return out;
    }

    private void walkJwgl(Object node, JSONArray out) {
        if (node == null) return;
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) walkJwgl(arr.opt(i), out);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject rec = (JSONObject) node;
        JSONArray acts = rec.optJSONArray("activities");
        if (acts != null) {
            for (int i = 0; i < acts.length(); i++) {
                JSONObject a = acts.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("courseName", a.optString("name"));
                if (name.isEmpty()) continue;
                int weekday = a.optInt("weekday", 1);
                if (weekday == 0) weekday = 7;
                JSONObject c = new JSONObject();
                try {
                    c.put("name", name);
                    c.put("teacher", joinTeachers(a.opt("teachers")));
                    c.put("location", a.optString("room", a.optString("roomName")));
                    c.put("day", weekday);
                    c.put("startPeriod", Math.max(1, a.optInt("startUnit", 1)));
                    c.put("endPeriod", Math.max(1, a.optInt("endUnit", a.optInt("startUnit", 1))));
                    c.put("weeksList", a.optJSONArray("weekIndexes"));
                    out.put(c);
                } catch (Exception ignored) {
                }
            }
        }
        JSONArray tables = rec.optJSONArray("studentTableVms");
        if (tables != null) walkJwgl(tables, out);
    }

    private void collectNews(JSONArray items, JSONObject sources, String source, String url) {
        try {
            Http.Resp r = Http.follow(new CookieJar(), url);
            WebFlow.requireHttpSuccess(r.flow());
            int n = 0;
            List<PageParser.Notice> notices = PageParser.notices(url,r.body);
            for (PageParser.Notice a : notices) {
                if (Filter.drop(a.title,a.title) || Filter.looksGarbage(a.title)) continue;
                String published = a.date.isEmpty() ? null : a.date + "T00:00:00+08:00";
                items.put(item(source,"notice",a.title,a.title,a.url,published,null,null));
                if (++n >= 24) break;
            }
            JSONObject result = src(!notices.isEmpty(),n,notices.isEmpty()?"SCHEMA_UNVERIFIED: 页面未匹配到通知链接":null);
            result.put("auth", "public");
            synchronized (sources) { sources.put(source,result); }
        } catch (Exception | LinkageError e) {
            try { synchronized (sources) { sources.put(source,src(false,0,Diagnostics.message(e))); } } catch (Exception ignored) {}
        }
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile(name + "\\s*=\\s*['\"]([^'\"]*)['\"]", Pattern.CASE_INSENSITIVE).matcher(attrs);
        return m.find() ? m.group(1) : "";
    }

    private static String joinUrl(String base, String href) {
        try {
            return new java.net.URL(new java.net.URL(base), href).toString();
        } catch (Exception e) {
            return href;
        }
    }

    private JSONObject postJson(String url, JSONObject payload, String referer) throws Exception {
        Http.Resp r = Http.fetch(jar, url, "POST", "application/json", payload.toString(), 18000, referer);
        if (r.code < 200 || r.code >= 300) throw new WebFlow.FlowException("HTTP_ERROR", "UIS HTTP " + r.code);
        JSONObject json = tryJsonObj(r.body);
        if (json == null) throw new Exception("UIS 非 JSON 响应 (" + r.code + ")");
        return json;
    }

    private Object getJson(String url) throws Exception {
        Http.Resp r = Http.follow(jar, url);
        WebFlow.requireHttpSuccess(r.flow());
        if (r.body.trim().startsWith("<")) throw new WebFlow.FlowException("SCHEMA_UNVERIFIED", "接口返回 HTML 而非 JSON");
        String t = r.body.trim();
        if (t.startsWith("while(1);")) t = t.substring("while(1);".length()).trim();
        if (t.startsWith("[")) return new JSONArray(t);
        if (t.startsWith("{")) {
            JSONObject o = new JSONObject(t);
            if (o.optString("status").contains("未经身份") || o.optString("status").contains("unauth")) {
                throw new Exception("eLearning 会话无效（未经身份验证）");
            }
            return o;
        }
        throw new Exception("非 JSON");
    }

    private static JSONArray asArray(Object raw) throws Exception {
        if (raw instanceof JSONArray) return (JSONArray) raw;
        if (raw instanceof JSONObject) {
            JSONObject o = (JSONObject) raw;
            if (o.has("errors") || "unauthenticated".equals(o.optString("status"))
                    || o.optString("status").contains("未经身份")) {
                throw new WebFlow.FlowException("API_ERROR", "接口返回错误对象，而非列表");
            }
            JSONObject d = o.optJSONObject("d");
            if (d != null) {
                if (d.optJSONArray("list") != null) return d.optJSONArray("list");
                if (d.optJSONArray("data") != null) return d.optJSONArray("data");
            }
            for (String k : new String[]{"courses", "items", "announcements", "data", "list"}) {
                if (o.optJSONArray(k) != null) return o.optJSONArray(k);
            }
        }
        throw new WebFlow.FlowException("SCHEMA_UNVERIFIED", "接口没有返回预期列表结构");
    }

    private static JSONObject tryJsonObj(String text) {
        try {
            String t = text.trim();
            if (t.startsWith("{")) return new JSONObject(t);
            if (t.startsWith("[")) return new JSONObject().put("list", new JSONArray(t));
        } catch (Exception ignored) {
        }
        return null;
    }

    private static JSONObject item(String source, String kind, String title, String summary,
                                   String url, String published, String due, String course) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", source + "-" + Integer.toUnsignedString((source + "|" + kind + "|" + url + "|" + title).hashCode()));
            o.put("source", source);
            o.put("kind", kind);
            o.put("title", title);
            o.put("summary", summary == null ? title : summary);
            o.put("url", url);
            if (published != null && !published.isEmpty() && !"null".equals(published)) {
                o.put("publishedAt", published);
                long t = Filter.parseTime(published);
                if (t > 0) o.put("receivedAt", t);
            }
            if (due != null && !due.isEmpty() && !"null".equals(due)) {
                o.put("dueAt", due);
            }
            if (course != null && !course.isEmpty()) o.put("course", course);
        } catch (Exception ignored) {
        }
        return o;
    }

    private static JSONObject src(boolean ok, int count, String err) throws Exception {
        JSONObject o = new JSONObject().put("ok", ok).put("count", count);
        if (err != null) o.put("error", err);
        return o;
    }

    private static JSONObject ok(String note) throws Exception {
        return new JSONObject().put("status", "ok").put("note", note);
    }

    private String sessionNote(String prefix) {
        return prefix + "。门户回调已完成；邮箱、eLearning、一卡通及课表将分别验证，不由公开通知推断登录成功。";
    }

    private static String joinTeachers(Object raw) {
        if (raw == null) return "";
        if (raw instanceof JSONArray) {
            JSONArray a = (JSONArray) raw;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                String name = teacherName(a.opt(i));
                if (name.isEmpty()) continue;
                if (sb.length() > 0) sb.append("、");
                sb.append(name);
            }
            return sb.toString();
        }
        return teacherName(raw);
    }

    private static String teacherName(Object o) {
        if (o == null) return "";
        if (o instanceof JSONObject) {
            JSONObject rec = (JSONObject) o;
            String n = rec.optString("name", rec.optString("teacherName", rec.optString("personName")));
            return n;
        }
        String s = String.valueOf(o);
        return "null".equals(s) ? "" : s;
    }

    private static void concat(JSONArray a, JSONArray b) {
        if (b == null) return;
        for (int i = 0; i < b.length(); i++) a.put(b.opt(i));
    }

    private static String joinArr(JSONArray a) {
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            if (sb.length() > 0) sb.append("、");
            sb.append(a.optString(i));
        }
        return sb.toString();
    }

    private static String strip(String html) {
        return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String firstNonEmpty(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.isEmpty() && !"null".equals(v)) return v;
        }
        return "";
    }

    private static String iso(int days) {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        fmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return fmt.format(new java.util.Date(System.currentTimeMillis() + days * 86400000L));
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String decodeHtml(String s) {
        return s.replace("\u0026amp;", "\u0026")
                .replace("\u0026quot;", "\"")
                .replace("\u0026#x2f;", "/")
                .replace("\u0026lt;", "<")
                .replace("\u0026gt;", ">");
    }
}
