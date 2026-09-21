"""Chromium UI tests with a recording native-bridge double. NOT Android WebView or real authentication."""
from pathlib import Path
from playwright.sync_api import sync_playwright
import json,sys,os,shutil
ROOT=Path('/workspace')
ANDROID=ROOT/'android'
HERE=Path('/workspace/danzhi-host-tests')
records=[]
with sync_playwright() as pw:
    browser=pw.chromium.launch(executable_path=os.environ.get('CHROMIUM_PATH') or shutil.which('chromium'),args=['--no-sandbox','--disable-dev-shm-usage'],headless=True)
    def page(saved=None):
        p=browser.new_page()
        p.set_default_timeout(3000)
        p.add_init_script('''window.calls=[];window.Danzhi={
            saved:()=>JSON.stringify(SAVED),
            login:(...a)=>calls.push(['login',...a]), poll:(...a)=>calls.push(['poll',...a]),
            logout:()=>calls.push(['logout']),setInterval:n=>calls.push(['setInterval',n]),
            setMailPassword:s=>calls.push(['setMailPassword',s]),setBackgroundEnabled:b=>calls.push(['background',b]),
            setSmsPhone:s=>calls.push(['smsPhone',s]),getQr:(...a)=>calls.push(['qr',...a]),
            refreshQr:()=>calls.push(['qr','1']),
            emptyRooms:(...a)=>calls.push(['rooms',...a]),buildings:()=>"[]",
            startScan:()=>calls.push(['scan']),openUrl:u=>calls.push(['openUrl',u]),requestKeepAlive:()=>calls.push(['notifications']),
            readItem:id=>calls.push(['readItem',id]),trashItems:ids=>calls.push(['trashItems',ids])};
        '''.replace('SAVED',json.dumps(saved or {'version':'1.7.0-dev','intervalMin':15,'hasSession':False})))
        errors=[];p.on('pageerror',lambda e:errors.append(str(e)))
        p.goto('about:blank')
        p.set_content((ANDROID/'app/src/main/assets/www/index.html').read_text())
        p.wait_for_timeout(50)
        return p,errors
    def run(name,f):
        try:f();records.append({'name':name,'status':'PASS'});print('PASS',name)
        except Exception as e:records.append({'name':name,'status':'FAIL','detail':str(e)});print('FAIL',name,str(e))
    def calls(p):return p.evaluate('calls')
    def login(p):
        p.locator('#user').fill('fixture-user');p.locator('#pw').fill('fixture-password');p.locator('#loginBtn').click()
        return calls(p)[-1][-1]
    def reply(p,obj):p.evaluate('(r)=>onLoginResult(JSON.stringify(r))',obj)
    def startup():
        p,e=page();assert p.locator('#login').is_visible();assert calls(p)==[];assert not e;p.close()
    run('fresh launch performs no native network action or settings write',startup)
    def restored():
        p,e=page({'version':'test','hasSession':True,'intervalMin':15});assert p.locator('#app').is_visible();assert calls(p)==[];assert not e;p.close()
    run('restored session launch still performs no poll or QR fetch',restored)
    def immediate():
        p,e=page();rid=login(p);assert rid;assert p.locator('#loginBtn').is_disabled();assert '正在连接' in p.locator('#loginStatus').inner_text();assert len(calls(p))==1;assert not e;p.close()
    run('login click gives immediate visible progress and one native call',immediate)
    def duplicate():
        p,e=page();login(p);p.evaluate('doLogin();doLogin();document.getElementById("loginForm").dispatchEvent(new Event("submit",{cancelable:true}));');assert len(calls(p))==1;assert not e;p.close()
    run('double-click plus form submit does not duplicate login',duplicate)
    def mfa():
        p,e=page();rid=login(p);reply(p,{'status':'mfa','requestId':rid});assert p.locator('#mfa').is_visible();assert len(calls(p))==1
        p.locator('#code').fill('123456');p.locator('#mfaBtn').click();c=calls(p)[-1];assert c[0]=='login' and c[2]=='' and c[4]=='123456';assert len(calls(p))==2;assert not e;p.close()
    run('MFA is a separate step and does not resend the password',mfa)
    def invalidotp():
        p,e=page();rid=login(p);reply(p,{'status':'mfa','requestId':rid});p.locator('#code').fill('ABCDEF');p.evaluate('doMfa()');assert len(calls(p))==1;assert '6 位' in p.locator('#mfaErr').inner_text();assert not e;p.close()
    run('six non-numeric OTP characters are rejected locally',invalidotp)
    def wrong():
        p,e=page();rid=login(p);reply(p,{'status':'error','message':'fixture invalid password','requestId':rid});assert p.locator('#user').input_value()=='fixture-user';assert p.locator('#pw').input_value()=='fixture-password';assert not p.locator('#loginBtn').is_disabled();assert 'fixture invalid password' in p.locator('#loginErr').inner_text();assert not e;p.close()
    run('login error preserves user input and re-enables the button',wrong)
    def stale():
        p,e=page();rid=login(p);reply(p,{'status':'ok','requestId':'stale'});assert p.locator('#login').is_visible();assert p.locator('#loginBtn').is_disabled();reply(p,{'status':'mfa','requestId':rid});assert p.locator('#mfa').is_visible();assert not e;p.close()
    run('stale callback cannot replace the current login state',stale)
    def success():
        p,e=page();rid=login(p);reply(p,{'status':'ok','requestId':rid});assert p.locator('#app').is_visible();assert len(calls(p))==1;assert p.locator('#pw').input_value()=='';assert not e;p.close()
    run('login completion does not launch competing poll and QR requests',success)
    def poll():
        p,e=page({'version':'test','hasSession':True});p.evaluate('doPoll(true);doPoll(true)');assert len(calls(p))==1;assert calls(p)[0][0]=='poll';rid=calls(p)[0][1]
        p.evaluate('(r)=>onPollResult(JSON.stringify(r))',{'status':'ok','requestId':rid,'items':[],'courses':[],'sources':{'math':{'ok':True,'count':2},'elearning':{'ok':False,'error':'AUTH_REQUIRED'}}})
        assert not p.locator('#pollBtn').is_disabled();assert 'AUTH_REQUIRED' in p.locator('#health').inner_text();assert not e;p.close()
    run('explicit poll is single-flight and displays per-source failure',poll)
    def bridgeerror():
        p,e=page({'version':'test','hasSession':True});p.evaluate('Danzhi.poll=()=>{throw new Error("bridge fixture failure")};doPoll(true)');assert not p.locator('#pollBtn').is_disabled();assert 'bridge fixture failure' in p.locator('#feedTitle').inner_text();assert not e;p.close()
    run('synchronous bridge failure does not leave poll permanently busy',bridgeerror)
    def credentials():
        p,e=page({'version':'test','hasSession':True});p.locator('.nav button[data-tab="set"]').click();p.locator('#mailPassword').fill('fixture-mail-credential');p.locator('#mailSave').click();assert calls(p)==[['setMailPassword','fixture-mail-credential']]
        p.evaluate('onMailSettings(JSON.stringify({status:"ok",message:"saved"}))');assert p.locator('#mailPassword').input_value()=='';assert not e;p.close()
    run('mail credential save is explicit and does not auto-authenticate',credentials)
    def background():
        p,e=page({'version':'test','hasSession':True});p.locator('.nav button[data-tab="set"]').click();p.locator('#backgroundEnabled').check();assert calls(p)==[['background',True]];assert not e;p.close()
    run('background polling is opt-in',background)
    def logout():
        p,e=page({'version':'test','hasSession':True});p.evaluate('doLogout()');assert p.locator('#app').is_visible();p.evaluate('onLogout(JSON.stringify({status:"error",message:"BUSY"}))');assert p.locator('#app').is_visible();p.evaluate('onLogout(JSON.stringify({status:"ok"}))');assert p.locator('#login').is_visible();assert not e;p.close()
    run('logout waits for native completion instead of pretending success',logout)
    def qrbusy():
        p,e=page({'version':'test','hasSession':True});p.evaluate('doPoll(true)');p.locator('.nav button[data-tab="pass"]').click();
        names=[c[0] for c in calls(p)];assert 'poll' in names and 'qr' in names
        qr=[c for c in calls(p) if c[0]=='qr']
        assert qr and qr[0][1] in ('1','true',True,1,'True')
        assert not e;p.close()
    run('QR tab still loads during an active poll',qrbusy)
    def qrforce():
        p,e=page({'version':'test','hasSession':True});p.locator('.nav button[data-tab="pass"]').click();
        p.evaluate('calls.splice(0)');p.locator('#qrBtn').click();
        c=calls(p);assert c and c[-1][0]=='qr' and str(c[-1][1]) in ('1','true','True')
        assert '已点到' in p.locator('#qrHint').inner_text() or '正在' in p.locator('#qrHint').inner_text()
        assert not e;p.close()
    run('refresh button forces a new campus-code fetch with visible feedback',qrforce)
    def theme_and_source():
        p,e=page({'version':'test','hasSession':True});p.locator('.nav button[data-tab="set"]').click();
        p.locator('#themeChips button[data-theme="ocean"]').click();
        assert p.evaluate("getComputedStyle(document.documentElement).getPropertyValue('--primary').trim()")=='#1D9A9A'
        p.locator('.nav button[data-tab="feed"]').click()
        p.evaluate('doPoll(true)');rid=calls(p)[-1][1]
        p.evaluate('(r)=>onPollResult(JSON.stringify(r))',{'status':'ok','requestId':rid,'courses':[],'sources':{},
            'items':[{'id':'n1','source':'jwc','kind':'notice','title':'教务通知','url':'https://jwc.fudan.edu.cn/x'}]})
        p.locator('#feedList .src-link').click();
        assert calls(p)[-1]==['openUrl','https://jwc.fudan.edu.cn/x']
        assert not e;p.close()
    run('theme chips change tokens and inbox can open original platform url',theme_and_source)
    def settings():
        p,e=page({'version':'test','hasSession':True});p.locator('.nav button[data-tab="set"]').click();p.locator('#seg button[data-min="30"]').click();assert calls(p)==[['setInterval',30]];assert '后台间隔 30' in p.locator('#segHint').inner_text();assert p.locator('#seg button[data-min="30"]').get_attribute('class')=='on';assert not e;p.close()
    run('interval selection has visible selected state and persists once',settings)
    def injection():
        p,e=page({'version':'test','hasSession':True});p.evaluate('doPoll(true)');rid=calls(p)[0][1]
        bad='<img src=x onerror="window.__injected=true">'
        p.evaluate('(r)=>onPollResult(JSON.stringify(r))',{'status':'partial','requestId':rid,'items':[],'courses':[],'sources':{'ecard':{'ok':False,'error':bad}}});p.wait_for_timeout(50)
        assert p.evaluate('!!window.__injected') is False;assert p.locator('#health img').count()==0;assert bad in p.locator('#health').inner_text();assert not e;p.close()
    run('server-derived error text cannot inject HTML into the privileged UI',injection)
    def feed_dates():
        p,e=page({'version':'test','hasSession':True});p.evaluate('doPoll(true)');rid=calls(p)[0][1]
        p.evaluate('(r)=>onPollResult(JSON.stringify(r))',{'status':'ok','requestId':rid,'courses':[],'sources':{},
            'items':[
                {'id':'old','source':'math','kind':'notice','title':'去年数院通知','url':'https://math.fudan.edu.cn/a/page.htm','publishedAt':'2025-04-08T00:00:00+08:00'},
                {'id':'mid','source':'jwc','kind':'notice','title':'昨日教务通知','url':'https://jwc.fudan.edu.cn/b/page.htm','publishedAt':'2026-09-19T00:00:00+08:00'},
                {'id':'new','source':'mail','kind':'mail','title':'刚到的邮件','url':'https://mail.m.fudan.edu.cn','receivedAt':1893456000000}
            ]})
        text=p.locator('#feedList').inner_text()
        assert text.index('刚到的邮件') < text.index('昨日教务通知') < text.index('去年数院通知')
        assert '邮箱' in text and '教务' in text and '数院' in text
        assert '9月19日' in text
        assert not e;p.close()
    run('feed mixes sources and sorts newest first with visible dates',feed_dates)
    def far_due():
        p,e=page({'version':'test','hasSession':True});p.evaluate('doPoll(true)');rid=calls(p)[0][1]
        p.evaluate('(r)=>onPollResult(JSON.stringify(r))',{'status':'ok','requestId':rid,'courses':[],'sources':{},
            'items':[
                {'id':'far','source':'elearning','kind':'ddl','title':'三个月后的作业','url':'https://elearning.fudan.edu.cn/a','publishedAt':'2026-08-01T00:00:00+08:00','dueAt':'2026-12-20T23:59:00+08:00','dueLabel':'还剩 91 天'},
                {'id':'news','source':'jwc','kind':'notice','title':'今日教务通知','url':'https://jwc.fudan.edu.cn/b','publishedAt':'2026-09-20T08:00:00+08:00'},
                {'id':'soon','source':'elearning','kind':'ddl','title':'两天后的作业','url':'https://elearning.fudan.edu.cn/c','publishedAt':'2026-09-10T00:00:00+08:00','dueAt':'2026-09-22T23:59:00+08:00','dueLabel':'还剩 2 天','dueSoon':True}
            ]})
        text=p.locator('#feedList').inner_text()
        assert text.index('即将到期') < text.index('两天后的作业')
        assert text.index('两天后的作业') < text.index('今日教务通知')
        assert text.index('今日教务通知') < text.index('三个月后的作业')
        assert not e;p.close()
    run('far-future homework does not bury recent notices',far_due)
    def last_inspect():
        import time
        ms=int(time.time()*1000)
        p,e=page({'version':'test','hasSession':True,'lastPoll':{'status':'ok','polledAt':ms,'items':[],'courses':[],'sources':{}}})
        assert '上次巡检成功' in p.locator('#feedMeta').inner_text();assert not e;p.close()
    run('inbox shows last successful inspect time',last_inspect)
    def overdue_not_soon():
        p,e=page({'version':'test','hasSession':True});p.evaluate('doPoll(true)');rid=calls(p)[0][1]
        p.evaluate('(r)=>onPollResult(JSON.stringify(r))',{'status':'ok','requestId':rid,'polledAt':1893456000000,'courses':[],'sources':{},
            'items':[
                {'id':'oldhw','source':'elearning','kind':'ddl','title':'九月十日作业','url':'https://elearning.fudan.edu.cn/x','publishedAt':'2026-09-01T00:00:00+08:00','dueAt':'2026-09-10T23:59:00+08:00','dueAtMs':1757520000000,'dueOverdue':True,'dueSoon':True},
                {'id':'soon','source':'elearning','kind':'ddl','title':'两天后的作业','url':'https://elearning.fudan.edu.cn/c','publishedAt':'2026-09-20T00:00:00+08:00','dueAt':'2026-09-22T23:59:00+08:00','dueSoon':True}
            ]})
        p.locator('#feedChips button[data-filter="soon"]').click()
        text=p.locator('#feedList').inner_text()
        assert '两天后的作业' in text
        assert '九月十日作业' not in text
        assert not e;p.close()
    run('即将到期 does not list overdue homework from earlier in the month',overdue_not_soon)
    def dan_mark():
        html=(ANDROID/'app/src/main/assets/www/index.html').read_text()
        assert 'M23 12v22' in html and 'M23 23h18' in html and 'M16 47h32' in html
        assert 'rx="1.2"' not in html
    run('app mark is 旦 (two-cell 日 plus 一) not a rounded 口',dan_mark)
    browser.close()
report={'kind':'Chromium UI with recording bridge double; NOT Android WebView','cases':records,'passed':sum(r['status']=='PASS' for r in records),'failed':sum(r['status']=='FAIL' for r in records)}
(HERE/'reports/ui-tests.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print('RESULT',report['passed'],'passed',report['failed'],'failed')
sys.exit(bool(report['failed']))
