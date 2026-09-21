package cn.fudan.danzhi;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.WebView;
import android.view.ViewGroup;
import android.util.Base64;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import cn.fudan.danzhi.core.WebFlow;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Must execute on ART (API 26, 31, 34, 35). Fixture-backed, NOT live-campus acceptance. */
@RunWith(AndroidJUnit4.class)
public class AndroidRuntimeTest {
    private Context context;
    @Before public void setup() {
        context=ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("danzhi",Context.MODE_PRIVATE).edit().clear().commit();
        new CredentialStore(context).clear();
        SessionCoordinator.resetForTests();
        Http.useTestTransport(req->{throw new AssertionError("Unconfigured network fixture: "+WebFlow.safeUrl(req.url));});
    }
    @After public void cleanup() {
        Http.useTestTransport(null);SessionCoordinator.resetForTests();
        PollService.cancel(context);new CredentialStore(context).clear();
    }
    private static WebFlow.Response response(WebFlow.Request req,int code,String body,String location) {
        return new WebFlow.Response(code,req.url,body,location);
    }
    private static String js(ActivityScenario<MainActivity> scenario,String code)throws Exception {
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> value=new AtomicReference<>();
        scenario.onActivity(activity->{
            ViewGroup content=activity.findViewById(android.R.id.content);
            WebView view=(WebView)content.getChildAt(0);
            view.evaluateJavascript(code,r->{value.set(r);done.countDown();});
        });
        assertTrue("WebView callback did not finish",done.await(5,TimeUnit.SECONDS));return value.get();
    }
    private static void awaitJs(ActivityScenario<MainActivity> scenario,String predicate)throws Exception {
        long until=System.nanoTime()+10_000_000_000L;
        while(System.nanoTime()<until){if("true".equals(js(scenario,predicate)))return;Thread.sleep(100);}
        fail("WebView condition not reached: "+predicate);
    }
    @Test public void legacySavedAccountAndRepeatedStartupPerformNoNetwork()throws Exception {
        SharedPreferences sp=context.getSharedPreferences("danzhi",Context.MODE_PRIVATE);
        sp.edit().putString("username","fixture-user").putString("password","fixture-only")
            .putString("cookies","invalid legacy cookie dump").putBoolean("hasSession",true).commit();
        AtomicInteger calls=new AtomicInteger();
        Http.useTestTransport(req->{calls.incrementAndGet();throw new AssertionError("Startup made a network request");});
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            awaitJs(scenario,"typeof window.doLogin === 'function'");
            scenario.recreate();awaitJs(scenario,"typeof window.doLogin === 'function'");
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(0,calls.get());assertFalse(sp.contains("password"));assertFalse(sp.contains("cookies"));
            assertFalse(sp.getBoolean("backgroundEnabled",true));assertFalse(sp.getBoolean("hasSession",true));
        }
    }
    @Test public void nativeLinkageErrorIsVisibleInActualWebViewAndDoesNotKillProcess()throws Exception {
        context.getSharedPreferences("danzhi",Context.MODE_PRIVATE).edit().putInt("storageVersion",2).commit();
        AtomicInteger calls=new AtomicInteger();
        Http.useTestTransport(req->{calls.incrementAndGet();throw new NoSuchMethodError("fixture-only-compatibility-error");});
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            awaitJs(scenario,"typeof window.doLogin === 'function'");
            js(scenario,"document.getElementById('user').value='fixture-user';document.getElementById('pw').value='fixture-password';doLogin();");
            awaitJs(scenario,"document.getElementById('loginErr').textContent.includes('RUNTIME_INCOMPATIBLE')");
            assertEquals(1,calls.get());assertEquals("false",js(scenario,"document.getElementById('loginBtn').disabled"));
            assertEquals("true",js(scenario,"document.getElementById('pw').value==='fixture-password'"));
        }
    }
    @Test public void api26CompatibleEncodingAndByteStreamMethodsActuallyExecute()throws Exception {
        assertEquals("%E4%BD%A0%E5%A5%BD+%2B",WebFlow.encode("你好 +"));
        assertEquals("中文",Http.readAll(new ByteArrayInputStream("中文".getBytes(StandardCharsets.UTF_8))));
    }
    @Test public void mfaUsesOriginalChallengeAndNeverResendsPassword()throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        String publicKey=Base64.encodeToString(generator.generateKeyPair().getPublic().getEncoded(),Base64.NO_WRAP);
        AtomicInteger passwordCalls=new AtomicInteger(),otpCalls=new AtomicInteger(),starts=new AtomicInteger();
        Http.useTestTransport(req->{
            if(req.url.contains("/authserver/login")) {starts.incrementAndGet();return response(req,302,"",FudanClient.IDP+"/ac-h5/#/index?lck=fixture-lck");}
            if(req.url.endsWith("/queryAuthMethods"))return response(req,200,"{\"data\":[{\"authChainCode\":\"chain\",\"moduleCodes\":[\"userAndPwd\"]}],\"entityId\":\"entity\",\"requestType\":\"chain_type\"}",null);
            if(req.url.endsWith("/getJsPublicKey"))return response(req,200,new JSONObject().put("data",new JSONObject().put("data",publicKey)).toString(),null);
            if(req.url.endsWith("/authExecute")) {
                JSONObject body=new JSONObject(req.body);assertEquals("fixture-lck",body.getString("lck"));
                if("userAndPwd".equals(body.getString("authModuleCode"))) {
                    passwordCalls.incrementAndGet();assertFalse(body.getJSONObject("authPara").getString("password").equals("fixture-password"));
                    return response(req,200,"{\"code\":200,\"pageLevelNo\":2,\"requestNumber\":\"fixture-request\",\"authChainCode\":\"mfa-chain\",\"authModuleCode\":\"userAndOtp\"}",null);
                }
                otpCalls.incrementAndGet();assertEquals("userAndOtp",body.getString("authModuleCode"));
                assertEquals("fixture-request",body.getString("requestNumber"));assertEquals("mfa-chain",body.getString("authChainCode"));
                assertFalse(body.getJSONObject("authPara").has("password"));
                if("111111".equals(body.getJSONObject("authPara").getString("otpCode")))return response(req,200,"{\"code\":400,\"message\":\"fixture invalid OTP\"}",null);
                return response(req,200,"{\"code\":200,\"loginToken\":\"fixture-login-token\"}",null);
            }
            if(req.url.endsWith("/authnEngine"))return response(req,200,"<script>var locationValue = 'https://my.fudan.edu.cn/?ticket=fixture-ticket';</script>",null);
            if(WebFlow.host(req.url).equals("my.fudan.edu.cn"))return response(req,200,"<main>fixture portal landing</main>",null);
            throw new AssertionError("Unexpected fixture endpoint: "+WebFlow.safeUrl(req.url));
        });
        FudanClient client=new FudanClient();JSONObject first=client.login("fixture-user","fixture-password","","");
        assertEquals("mfa",first.getString("status"));assertFalse(first.has("lck"));
        try {client.login("fixture-user","","","111111");fail("Bad OTP must fail");}catch(Exception expected){assertTrue(expected.getMessage().contains("fixture invalid OTP"));}
        JSONObject second=client.login("fixture-user","","","123456");assertEquals("ok",second.getString("status"));
        assertEquals(1,passwordCalls.get());assertEquals(2,otpCalls.get());assertEquals(1,starts.get());
    }
    @Test public void businessLoginHtmlIsNotAcceptedAsServiceSession()throws Exception {
        Http.useTestTransport(req->response(req,200,"<input type='password'>",null));
        try {new FudanClient().enterPublic("https://elearning.fudan.edu.cn/login/cas");fail("Login HTML accepted");}
        catch(WebFlow.FlowException e){assertEquals("AUTH_REQUIRED",e.category);}
    }
    @Test public void currentLessonsJsonMapsToCourseSlots()throws Exception {
        JSONObject lesson=new JSONObject().put("course",new JSONObject().put("nameZh","测试课程"))
            .put("scheduleText",new JSONObject().put("dateTimePlacePersonText",new JSONObject().put("textZh","1~16周 星期一 1~2节 H101 教师甲")));
        JSONArray out=TimetableAdapter.parse(new JSONObject().put("lessons",new JSONArray().put(lesson)));
        assertEquals(1,out.length());assertEquals("测试课程",out.getJSONObject(0).getString("name"));
        assertEquals(1,out.getJSONObject(0).getInt("day"));assertEquals(16,out.getJSONObject(0).getJSONArray("weeksList").length());
    }
    @Test public void recognizedEmptyLessonsAreEmptyNotAuthenticationFailure()throws Exception {
        assertEquals(0,TimetableAdapter.parse(new JSONObject("{\"lessons\":[]}")).length());
    }
    @Test public void unknownTimetableSchemaIsNotEmptySuccess()throws Exception {
        try{TimetableAdapter.parse(new JSONObject("{\"message\":\"please login\"}"));fail("Unknown schema accepted");}
        catch(WebFlow.FlowException e){assertEquals("SCHEMA_UNVERIFIED",e.category);}
    }
    @Test public void legacyActivitiesStillParseOnAndroid()throws Exception {
        JSONArray out=TimetableAdapter.parse(new JSONObject("{\"activities\":[{\"courseName\":\"旧课\",\"weekday\":2,\"startUnit\":3,\"endUnit\":4}]}"));
        assertEquals(1,out.length());assertEquals(3,out.getJSONObject(0).getInt("startPeriod"));
    }
    @Test public void sessionCoordinatorRecoversAfterLinkageError()throws Exception {
        JSONObject failure=SessionCoordinator.run(context,c->{throw new VerifyError("synthetic method error");});
        assertEquals("error",failure.getString("status"));assertTrue(failure.getString("message").contains("RUNTIME_INCOMPATIBLE"));
        assertEquals("ok",SessionCoordinator.run(context,c->new JSONObject().put("status","ok")).getString("status"));
    }
    @Test public void secureCookieStorageRoundTripOnAndroidKeystore()throws Exception {
        CredentialStore vault=new CredentialStore(context);vault.put("cookies","fixture-sensitive-cookie");
        assertEquals("fixture-sensitive-cookie",vault.get("cookies"));vault.clear();assertEquals("",vault.get("cookies"));
    }
}
