import { createHash, createHmac, createPublicKey, publicEncrypt, constants } from "node:crypto";
import { applyFilter, DEFAULT_FILTER_PREFS } from "./filter.ts";
import { CookieJar, follow, fudanFetch, looksLikeHtml, parseJsonSafe, UA } from "./http.server.ts";
import { isCampusOnlyHost, maybeVpnUrl, toVpnUrl, WEBVPN_LOGIN } from "./vpn.server.ts";
import type {
  CookieRecord,
  CourseMeeting,
  FeedItem,
  FilterPrefs,
  LoginResult,
  MfaChallenge,
  MfaMethod,
  PollResult,
  Profile,
  SourceReport,
} from "./types.ts";
import { djb2 } from "../utils.ts";

const IDP = "https://id.fudan.edu.cn";
const IDP_API = `${IDP}/idp`;
const DEFAULT_SERVICE = "https://my.fudan.edu.cn/";
const ELEARNING = "https://elearning.fudan.edu.cn";
const EHALL = "https://ehall.fudan.edu.cn";
const FDJWGL = "https://fdjwgl.fudan.edu.cn";
const JWFW = "https://jwfw.fudan.edu.cn";
const MAIL = "https://mail.fudan.edu.cn";

function shaId(source: string, url: string, title: string): string {
  return `${source}-${djb2(`${url}|${title}`)}`;
}

function rsaEncrypt(plaintext: string, publicKeyB64: string): string {
  const key = createPublicKey({
    key: Buffer.from(publicKeyB64, "base64"),
    format: "der",
    type: "spki",
  });
  const enc = publicEncrypt(
    { key, padding: constants.RSA_PKCS1_PADDING },
    Buffer.from(plaintext, "utf8"),
  );
  return enc.toString("base64");
}

function base32Decode(secret: string): Buffer {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  const clean = secret.replace(/[\s=-]/g, "").toUpperCase();
  let bits = "";
  for (const ch of clean) {
    const v = alphabet.indexOf(ch);
    if (v < 0) continue;
    bits += v.toString(2).padStart(5, "0");
  }
  const bytes: number[] = [];
  for (let i = 0; i + 8 <= bits.length; i += 8) {
    bytes.push(parseInt(bits.slice(i, i + 8), 2));
  }
  return Buffer.from(bytes);
}

export function totpNow(secret: string, at = Date.now()): string {
  const key = base32Decode(secret);
  const counter = Math.floor(at / 30000);
  const buf = Buffer.alloc(8);
  buf.writeUInt32BE(Math.floor(counter / 0x100000000), 0);
  buf.writeUInt32BE(counter >>> 0, 4);
  const hmac = createHmac("sha1", key).update(buf).digest();
  const offset = hmac[hmac.length - 1]! & 0xf;
  const code =
    ((hmac[offset]! & 0x7f) << 24) |
    ((hmac[offset + 1]! & 0xff) << 16) |
    ((hmac[offset + 2]! & 0xff) << 8) |
    (hmac[offset + 3]! & 0xff);
  return String(code % 1_000_000).padStart(6, "0");
}

async function postJson(jar: CookieJar, url: string, payload: unknown, referer: string) {
  const res = await fudanFetch(jar, url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Referer: referer,
      Origin: IDP,
      "User-Agent": UA,
    },
    body: JSON.stringify(payload),
  });
  const text = await res.text();
  const json = parseJsonSafe<Record<string, unknown>>(text);
  if (!json) throw new Error(`UIS 非 JSON 响应 (${res.status})`);
  return json;
}

function authSucceeded(auth: Record<string, unknown>): boolean {
  const code = String(auth.code ?? "");
  return code === "200" || code === "0";
}

async function beginCas(
  jar: CookieJar,
  service: string,
): Promise<{ lck: string; referer: string; entityId: string }> {
  const starts = [
    `${IDP}/idp/authCenter/authenticate?service=${encodeURIComponent(service)}`,
    `${IDP}/authserver/login?service=${encodeURIComponent(service)}`,
  ];
  let entityId = service.replace(/\/$/, "");
  for (const start of starts) {
    let current = start;
    for (let hop = 0; hop < 8; hop++) {
      const res = await fudanFetch(jar, current, { redirect: "manual" });
      const loc = res.headers.get("location") ?? "";
      const blob = `${loc} ${res.url} ${current}`;
      const hit = blob.match(/lck=([\w_-]+)/);
      if (hit?.[1]) {
        const referer = loc || current;
        const em = referer.match(/entityId=([^&#]+)/);
        if (em?.[1]) {
          try {
            entityId = decodeURIComponent(em[1]);
          } catch {
            entityId = em[1];
          }
        }
        return { lck: hit[1], referer, entityId };
      }
      if (loc && res.status >= 300 && res.status < 400) {
        current = new URL(loc, current).href;
        continue;
      }
      break;
    }
  }
  throw new Error("无法连接统一认证（登录令牌丢失）。请改用安装包在手机上登录。");
}

export async function startLogin(input: {
  username: string;
  password: string;
  totpSecret?: string;
  totpCode?: string;
}): Promise<LoginResult> {
  const jar = new CookieJar();
  const service = DEFAULT_SERVICE;
  const { lck, referer, entityId: entityFromUrl } = await beginCas(jar, service);

  const methods = await postJson(jar, `${IDP_API}/authn/queryAuthMethods`, { lck }, referer);
  const modules = (methods.data as Array<Record<string, unknown>> | undefined) ?? [];
  const pwdChain =
    modules.find((m) => {
      const codes = (m.moduleCodes as string[] | undefined) ?? [];
      return codes.includes("userAndPwd") || m.moduleCode === "userAndPwd";
    }) ?? modules[0];
  if (!pwdChain) throw new Error("认证链中未找到密码模块");

  const entityId = (methods.entityId as string) || entityFromUrl || service.replace(/\/$/, "");
  const requestType = (methods.requestType as string) || "chain_type";
  const authChainCode = String(pwdChain.authChainCode ?? "");

  const pubBody = await postJson(jar, `${IDP_API}/authn/getJsPublicKey`, {}, referer);
  const pub =
    typeof pubBody.data === "string"
      ? pubBody.data
      : ((pubBody.data as { data?: string } | undefined)?.data ?? "");
  if (!pub) throw new Error("未拿到 UIS RSA 公钥");

  const encrypted = rsaEncrypt(input.password, pub);
  const auth = await postJson(
    jar,
    `${IDP_API}/authn/authExecute`,
    {
      authModuleCode: "userAndPwd",
      authChainCode,
      entityId,
      requestType,
      lck,
      authPara: {
        loginName: input.username,
        password: encrypted,
        verifyCode: "",
      },
    },
    referer,
  );

  if (!authSucceeded(auth)) {
    throw new Error(String(auth.message || "学号或密码不正确"));
  }

  const loginToken = auth.loginToken as string | undefined;
  const pageLevel = Number(auth.pageLevelNo ?? 0);
  const requestNumber = String(auth.requestNumber ?? "");
  const nextChain = String(auth.authChainCode ?? authChainCode);
  const nextModule = String(auth.authModuleCode ?? "");
  const nextCodes = collectModuleCodes(auth);
  const needsMfa =
    pageLevel === 2 ||
    /otp/i.test(nextModule) ||
    nextCodes.some((c) => /otp|sms|mail|email/i.test(c)) ||
    !loginToken;

  if (loginToken && !needsMfa) {
    await finishCas(jar, loginToken, referer, service);
    const session = await assertSession(jar);
    const profile = await loadProfile(jar, input.username);
    return { status: "ok", cookies: jar.snapshot(), profile, note: session.note };
  }

  if (nextCodes.length && !nextCodes.some((c) => /otp/i.test(c))) {
    throw new Error(
      "此账号第二关不是 Authenticator。请到 id.fudan.edu.cn → 安全设置绑定动态口令后再登录。邮箱/短信验证码已关闭。",
    );
  }

  const challenge: MfaChallenge = {
    lck,
    requestNumber,
    authChainCode: nextChain,
    entityId,
    requestType,
    username: input.username,
    modules: ["userAndOtp"],
    cookies: jar.snapshot(),
    referer,
    service,
  };

  // Always return the MFA step. Never auto-submit TOTP from a stored secret.
  if (input.totpCode?.trim()) {
    return completeMfa({ challenge, method: "userAndOtp", code: input.totpCode.trim() });
  }

  return { status: "mfa", challenge };
}

export async function completeMfa(input: {
  challenge: MfaChallenge;
  method: MfaMethod;
  code: string;
}): Promise<LoginResult> {
  const jar = new CookieJar(input.challenge.cookies);
  const ch = input.challenge;
  const referer = ch.referer || `${IDP}/authserver/login`;
  const auth = await postJson(
    jar,
    `${IDP_API}/authn/authExecute`,
    {
      authModuleCode: "userAndOtp",
      authChainCode: ch.authChainCode,
      entityId: ch.entityId,
      requestType: ch.requestType,
      lck: ch.lck,
      requestNumber: ch.requestNumber,
      authPara: {
        loginName: ch.username,
        otpCode: input.code.trim(),
        verifyCode: "",
      },
    },
    referer,
  );
  if (!authSucceeded(auth)) {
    throw new Error(String(auth.message || "动态口令不正确"));
  }
  const loginToken = auth.loginToken as string | undefined;
  if (!loginToken) {
    const nextCodes = collectModuleCodes(auth);
    if (nextCodes.some((c) => /sms|mail|email/i.test(c)) && !nextCodes.some((c) => /otp/i.test(c))) {
      throw new Error("动态口令未通过，系统仍要求邮箱/短信。请在 UIS 安全设置改绑 Authenticator。");
    }
    throw new Error("二次认证未返回 loginToken。请确认 Authenticator 时间已同步后再试");
  }
  await finishCas(jar, loginToken, referer, ch.service || DEFAULT_SERVICE);
  const session = await assertSession(jar);
  const profile = await loadProfile(jar, ch.username);
  return { status: "ok", cookies: jar.snapshot(), profile, note: session.note };
}

async function finishCas(
  jar: CookieJar,
  loginToken: string,
  referer: string,
  _service: string,
) {
  const res = await fudanFetch(jar, `${IDP_API}/authCenter/authnEngine`, {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      Referer: referer,
      Origin: IDP,
    },
    body: `loginToken=${encodeURIComponent(loginToken)}`,
    redirect: "manual",
  });
  const html = await res.text();
  const loc =
    html.match(/var\s+locationValue\s*=\s*"([^"]+)"/)?.[1] ||
    html.match(/window\.location\s*=\s*"([^"]+)"/)?.[1] ||
    html.match(/window\.location\.replace\(\s*"([^"]+)"/)?.[1] ||
    res.headers.get("location") ||
    "";
  if (!loc) throw new Error("CAS 未返回 service ticket（authnEngine 无跳转）");
  const ticketUrl = new URL(decodeHtml(loc), IDP).href;
  if (!/ticket=/.test(ticketUrl)) {
    throw new Error("CAS 跳转缺少 ticket，二次认证可能未真正完成");
  }
  await follow(jar, ticketUrl);
  try {
    await enterService(jar, `${ELEARNING}/login/cas`);
  } catch {
    /* 巡检时再换票 */
  }
}

async function assertSession(jar: CookieJar): Promise<{ note: string }> {
  const alive = await sessionAlive(jar);
  if (!alive) throw new Error("登录流程结束但 CAS 会话无效，请重试动态口令");
  let canvas = false;
  try {
    await enterService(jar, `${ELEARNING}/login/cas`);
    const me = await canvasJson<unknown>(jar, `${ELEARNING}/api/v1/users/self`);
    canvas = Boolean(me && !isCanvasUnauth(me) && !looksLikeHtml(JSON.stringify(me)));
    if (me && typeof me === "object" && !Array.isArray(me) && (me as { name?: string }).name) {
      canvas = true;
    }
  } catch {
    canvas = false;
  }
  const n = jar.snapshot().length;
  if (canvas) {
    return { note: `动态口令通过。CAS 与 eLearning 会话均已确认（${n} 枚 cookie）` };
  }
  return {
    note: `动态口令通过，CAS 会话已建立（${n} 枚 cookie）。eLearning 将在巡检时再换票`,
  };
}

function collectModuleCodes(auth: Record<string, unknown>): string[] {
  const out: string[] = [];
  const push = (v: unknown) => {
    if (typeof v === "string" && v) out.push(v);
    if (Array.isArray(v)) for (const x of v) push(x);
    if (v && typeof v === "object") {
      const rec = v as Record<string, unknown>;
      push(rec.moduleCode);
      push(rec.moduleCodes);
      push(rec.authModuleCode);
    }
  };
  push(auth.moduleCodes);
  push(auth.authModuleCode);
  push(auth.data);
  return [...new Set(out)];
}

export function decodeHtml(s: string): string {
  return s
    .replace(/\u0026amp;/gi, "&")
    .replace(/\u0026quot;/gi, '"')
    .replace(/\u0026#39;/g, "'")
    .replace(/\u0026#x27;/gi, "'")
    .replace(/\u0026#x2f;/gi, "/")
    .replace(/\u0026#47;/g, "/")
    .replace(/\u0026lt;/gi, "<")
    .replace(/\u0026gt;/gi, ">");
}

async function casTo(jar: CookieJar, service: string): Promise<string> {
  const hopped = await follow(
    jar,
    `${IDP}/authserver/login?service=${encodeURIComponent(service)}`,
  );
  return hopped.url;
}

function isIdpLoginSpa(url: string, body = ""): boolean {
  if (!url.includes("id.fudan.edu.cn")) return false;
  if (url.includes("ticket=")) return false;
  if (/\/ac\//.test(url) || /ac-h5/.test(url) || /#\/index/.test(url)) return true;
  return /统一身份认证|queryAuthMethods|authn\/authExecute/.test(body) && /lck=/.test(`${url}\n${body}`);
}

async function enterService(jar: CookieJar, url: string) {
  const attempts = [
    url,
    `${IDP}/idp/authCenter/authenticate?service=${encodeURIComponent(url)}`,
    `${IDP}/authserver/login?service=${encodeURIComponent(url)}`,
  ];
  let last: { res: Response; url: string; body: string } | undefined;
  for (const target of attempts) {
    try {
      const hopped = await follow(jar, target, { timeoutMs: 18000 });
      last = hopped;
      if (!isIdpLoginSpa(hopped.url, hopped.body)) return hopped;
    } catch {
      /* next */
    }
  }
  if (last && !isIdpLoginSpa(last.url, last.body)) return last;
  throw new Error("统一认证未进入子系统（Authenticator 会话未建立）");
}

async function ensureWebvpn(jar: CookieJar) {
  try {
    await casTo(jar, WEBVPN_LOGIN);
    jar.viaVpn = true;
  } catch {
    jar.viaVpn = true;
    await follow(jar, WEBVPN_LOGIN);
  }
}

async function campusFollow(jar: CookieJar, url: string) {
  try {
    const hopped = await follow(jar, url, { timeoutMs: 12000 });
    if (hopped.res.status >= 400 && isCampusOnlyHost(url)) throw new Error("campus-blocked");
    return hopped;
  } catch {
    if (!jar.viaVpn) await ensureWebvpn(jar);
    const vpn = toVpnUrl(url);
    return follow(jar, vpn, { timeoutMs: 20000 });
  }
}

async function campusGet(jar: CookieJar, url: string, init: RequestInit = {}) {
  const tryOnce = async (viaVpn: boolean) => {
    const target = maybeVpnUrl(url, viaVpn);
    const res = await fudanFetch(jar, target, { ...init, timeoutMs: viaVpn ? 22000 : 14000 });
    return { res, url: target, body: await res.text() };
  };
  try {
    const first = await tryOnce(jar.viaVpn);
    if (first.res.status === 403 || first.res.status >= 500) throw new Error(String(first.res.status));
    return first;
  } catch (err) {
    if (jar.viaVpn) throw err;
    await ensureWebvpn(jar);
    return tryOnce(true);
  }
}

async function loadProfile(jar: CookieJar, studentId: string): Promise<Profile> {
  const profile: Profile = { studentId, name: studentId, college: "复旦大学" };
  try {
    await enterService(jar, `${ELEARNING}/login/cas`);
    const me = await canvasJson<{ name?: string; login_id?: string; primary_email?: string }>(
      jar,
      `${ELEARNING}/api/v1/users/self`,
    );
    if (me && !Array.isArray(me) && !isCanvasUnauth(me) && me.name) profile.name = me.name;
    if (me && !Array.isArray(me) && me.primary_email) profile.email = me.primary_email;
  } catch {
    /* canvas 可能尚未开通 */
  }
  return profile;
}

function asArray<T>(raw: unknown): T[] {
  if (Array.isArray(raw)) return raw as T[];
  if (!raw || typeof raw !== "object") return [];
  const rec = raw as Record<string, unknown>;
  for (const k of [
    "courses",
    "items",
    "announcements",
    "events",
    "todo_list",
    "planner_items",
    "assignments",
    "list",
    "data",
  ]) {
    if (Array.isArray(rec[k])) return rec[k] as T[];
  }
  if (Array.isArray(rec.d)) return rec.d as T[];
  if (rec.d && typeof rec.d === "object") {
    const d = rec.d as Record<string, unknown>;
    if (Array.isArray(d.list)) return d.list as T[];
    if (Array.isArray(d.data)) return d.data as T[];
  }
  return [];
}

function isCanvasUnauth(raw: unknown): boolean {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return false;
  const rec = raw as Record<string, unknown>;
  const status = String(rec.status ?? "");
  if (/unauth|未经身份|未授权|unauthorized/i.test(status)) return true;
  const err = rec.errors ?? rec.error ?? rec.message;
  if (!err) return false;
  const blob = typeof err === "string" ? err : JSON.stringify(err);
  return /unauth|需要用户授权|invalid access token|not authorized|登录/i.test(blob);
}

async function canvasJson<T>(jar: CookieJar, url: string): Promise<T | null> {
  const res = await fudanFetch(jar, url, {
    headers: { Accept: "application/json, text/plain, */*" },
    timeoutMs: 16000,
  });
  if (res.status >= 300 && res.status < 400) {
    const loc = res.headers.get("location");
    if (loc) {
      try {
        const hopped = await follow(jar, new URL(loc, url).href);
        if (looksLikeHtml(hopped.body)) return null;
        return parseJsonSafe<T>(hopped.body);
      } catch {
        return null;
      }
    }
  }
  const text = await res.text();
  if (looksLikeHtml(text)) return null;
  return parseJsonSafe<T>(text);
}

async function canvasPages<T>(jar: CookieJar, url: string, maxPages = 6): Promise<T[]> {
  const all: T[] = [];
  const seen = new Set<string>();
  let next: string | null = url;
  for (let i = 0; i < maxPages && next && !seen.has(next); i++) {
    seen.add(next);
    const raw: unknown = await canvasJson<unknown>(jar, next);
    if (raw == null) break;
    if (isCanvasUnauth(raw)) break;
    const page: T[] = asArray<T>(raw);
    all.push(...page);
    if (page.length === 0) break;
    next = page.length >= 40 ? bumpCanvasPage(next) : null;
  }
  return all;
}

function bumpCanvasPage(url: string): string {
  const u = new URL(url);
  const page = Number(u.searchParams.get("page") || "1");
  u.searchParams.set("page", String(page + 1));
  return u.toString();
}

type Timed<T> = { value: T; report: SourceReport };

async function timed<T>(fn: () => Promise<T>, empty: T): Promise<Timed<T>> {
  const t0 = Date.now();
  try {
    const value = await fn();
    const count = Array.isArray(value) ? value.length : 1;
    return { value, report: { ok: true, count, ms: Date.now() - t0 } };
  } catch (err) {
    return {
      value: empty,
      report: {
        ok: false,
        count: 0,
        ms: Date.now() - t0,
        error: err instanceof Error ? err.message : String(err),
      },
    };
  }
}

export async function pollAll(input: {
  cookies: CookieRecord[];
  username: string;
  password?: string;
  totpSecret?: string;
  prefs?: FilterPrefs;
}): Promise<PollResult> {
  let jar = new CookieJar(input.cookies);
  let needReauth = false;
  let profile: Profile | undefined;
  let loginNote: string | undefined;

  const alive = await sessionAlive(jar);
  if (!alive) {
    if (input.password) {
      try {
        const login = await startLogin({
          username: input.username,
          password: input.password,
          totpSecret: input.totpSecret,
        });
        if (login.status === "ok") {
          jar = new CookieJar(login.cookies);
          profile = login.profile;
          loginNote = login.note ?? "已用动态口令重登";
        } else {
          needReauth = true;
          loginNote = "会话过期，需要手动输入 Authenticator 6 位码";
        }
      } catch (err) {
        needReauth = true;
        loginNote = err instanceof Error ? err.message : "自动重登失败";
      }
    } else {
      needReauth = true;
      loginNote = "会话过期且未保存密码";
    }
  }

  const prefs = input.prefs ?? DEFAULT_FILTER_PREFS;
  const [math, jwc, elearn, ehall, mail, table] = await Promise.all([
    timed(
      () =>
        fetchNewsList("math", "https://math.fudan.edu.cn/tzgg/list.htm", [
          "https://math.fudan.edu.cn/xxgg/list.htm",
          "https://math.fudan.edu.cn/21703/list.htm",
        ]),
      [] as FeedItem[],
    ),
    timed(
      () =>
        fetchNewsList("jwc", "https://jwc.fudan.edu.cn/tzgg/list.htm", [
          "https://jwc.fudan.edu.cn/9397/list.htm",
          "https://jwc.fudan.edu.cn/jxtz/list.htm",
        ]),
      [] as FeedItem[],
    ),
    timed(() => fetchElearning(jar), { items: [] as FeedItem[], courses: [] as CourseMeeting[] }),
    timed(() => fetchEhall(jar), [] as FeedItem[]),
    timed(() => fetchMail(jar), [] as FeedItem[]),
    timed(() => fetchTimetable(jar), [] as CourseMeeting[]),
  ]);

  const elearnItems = "items" in elearn.value ? elearn.value.items : [];
  const elearnCourses = "items" in elearn.value ? elearn.value.courses : [];
  const merged = applyFilter(
    [...math.value, ...jwc.value, ...elearnItems, ...ehall.value, ...mail.value],
    prefs,
  );
  const courses = table.value.length ? table.value : elearnCourses;

  if (!profile) {
    try {
      profile = await loadProfile(jar, input.username);
    } catch {
      profile = { studentId: input.username, name: input.username };
    }
  }

  return {
    items: merged,
    courses,
    profile,
    sources: {
      math: math.report,
      jwc: jwc.report,
      elearning: { ...elearn.report, count: elearnItems.length },
      ehall: ehall.report,
      mail: mail.report,
      timetable: table.report,
    },
    polledAt: new Date().toISOString(),
    needReauth,
    cookies: jar.snapshot(),
    loginNote,
  };
}

async function sessionAlive(jar: CookieJar): Promise<boolean> {
  try {
    const res = await fudanFetch(
      jar,
      `${IDP}/authserver/login?service=${encodeURIComponent(DEFAULT_SERVICE)}`,
      { redirect: "manual" },
    );
    const loc = res.headers.get("location") ?? "";
    if (res.status >= 300 && loc.includes("ticket=")) return true;
    if (res.status >= 300 && loc.includes("/ac/")) return false;
    const hopped = loc
      ? await follow(jar, new URL(loc, IDP).href)
      : { url: "", body: "" };
    return hopped.url.includes("ticket=") || hopped.url.includes("my.fudan.edu.cn");
  } catch {
    return false;
  }
}

function stripTags(html: string): string {
  return html
    .replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<script[\s\S]*?<\/script>/gi, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/gi, " ")
    .replace(/&/g, "&")
    .replace(/\s+/g, " ")
    .trim();
}

function extractNews(html: string, base: string, source: "math" | "jwc"): FeedItem[] {
  const items: FeedItem[] = [];
  const seen = new Set<string>();
  const re = /<a([^>]+)>([\s\S]{0,240}?)<\/a>/gi;
  let m: RegExpExecArray | null;
  while ((m = re.exec(html)) && items.length < 40) {
    const attrs = m[1] ?? "";
    const href = attrs.match(/href=["']([^"']+)["']/i)?.[1] ?? "";
    const attrTitle = attrs.match(/title=["']([^"']+)["']/i)?.[1] ?? "";
    let title = stripTags(attrTitle) || stripTags(m[2] ?? "");
    if (!title || title.length < 4 || title.length > 80) continue;
    if (!/page\.htm|content|article|info|\/c\d+/i.test(href)) continue;
    if (/讣告|悼念|逝世|评教|招聘|评聘/.test(title)) continue;
    let url: string;
    try {
      url = new URL(href, base).href;
    } catch {
      continue;
    }
    if (seen.has(url)) continue;
    seen.add(url);
    const around = html.slice(Math.max(0, m.index - 200), Math.min(html.length, (m.index || 0) + (m[0]?.length || 0) + 500));
    const dm = around.match(/(20\d{2})[-/.年](\d{1,2})[-/.月](\d{1,2})/);
    let publishedAt = "";
    if (dm) {
      publishedAt = `${dm[1]}-${String(dm[2]).padStart(2, "0")}-${String(dm[3]).padStart(2, "0")}T00:00:00+08:00`;
    }
    items.push({
      id: shaId(source, url, title),
      source,
      kind: "notice",
      title,
      summary: title,
      url,
      publishedAt,
      score: 0,
      tags: [],
      filtered: false,
      priority: "admin",
    });
  }
  return items;
}

async function fetchNewsList(
  source: "math" | "jwc",
  primary: string,
  fallbacks: string[],
): Promise<FeedItem[]> {
  let lastErr: Error | undefined;
  for (const url of [primary, ...fallbacks]) {
    try {
      const res = await fudanFetch(new CookieJar(), url, {
        headers: { Accept: "text/html" },
      });
      if (res.status === 403) {
        lastErr = new Error(`${source} 站点 WAF 拦截（403），校外 IP 常见`);
        continue;
      }
      const html = await res.text();
      if (res.status >= 400) {
        lastErr = new Error(`${source} HTTP ${res.status}`);
        continue;
      }
      const items = extractNews(html, url, source);
      if (items.length) return items;
      lastErr = new Error(`${source} 页面结构未匹配到通知列表`);
    } catch (err) {
      lastErr = err instanceof Error ? err : new Error(String(err));
    }
  }
  throw lastErr ?? new Error(`${source} 拉取失败`);
}

async function fetchElearning(jar: CookieJar): Promise<{
  items: FeedItem[];
  courses: CourseMeeting[];
}> {
  await enterService(jar, `${ELEARNING}/login/cas`);
  const items: FeedItem[] = [];
  const courses: CourseMeeting[] = [];

  const courseUrls = [
    `${ELEARNING}/api/v1/courses?enrollment_state=active&per_page=100`,
    `${ELEARNING}/api/v1/courses?enrollment_state=completed&per_page=100`,
    `${ELEARNING}/api/v1/users/self/favorites/courses?per_page=50`,
    `${ELEARNING}/api/v1/courses?enrollment_state=invited_or_pending&per_page=50`,
  ];
  let courseList: { id: number; name: string; course_code?: string }[] = [];
  let lastRaw: unknown = null;
  const seenCourse = new Set<number>();
  for (const url of courseUrls) {
    const raw = await canvasJson<unknown>(jar, url);
    lastRaw = raw ?? lastRaw;
    if (raw == null) continue;
    if (isCanvasUnauth(raw)) {
      throw new Error("eLearning 会话无效（未经身份验证）。请用 Authenticator 重登后再巡检");
    }
    const list = asArray<{ id: number; name: string; course_code?: string }>(raw);
    for (const c of list) {
      if (!Number(c.id) || !c.name || seenCourse.has(c.id)) continue;
      seenCourse.add(c.id);
      courseList.push(c);
    }
  }
  if (lastRaw == null && !courseList.length) {
    throw new Error("eLearning 返回了登录页，CAS 未完成。请绑定动态口令后重登");
  }

  const topCourses = Array.isArray(courseList) ? courseList.slice(0, 80) : [];
  const codes = topCourses.map((c) => `context_codes[]=course_${c.id}`).join("&");

  if (codes) {
    const annsRaw = await canvasJson<unknown>(
      jar,
      `${ELEARNING}/api/v1/announcements?${codes}&start_date=${encodeURIComponent(new Date(Date.now() - 40 * 86400000).toISOString())}&per_page=50`,
    );
    if (!isCanvasUnauth(annsRaw)) {
      const anns = asArray<{
        id: number;
        title: string;
        message?: string;
        posted_at?: string;
        url?: string;
        html_url?: string;
        context_code?: string;
      }>(annsRaw);
      for (const a of anns) {
        if (!a?.title) continue;
        const course = courseList.find((c) => a.context_code === `course_${c.id}`);
        items.push({
          id: shaId("elearning", String(a.id), a.title),
          source: "elearning",
          kind: "announcement",
          title: a.title,
          summary: stripHtml(a.message || a.title).slice(0, 220),
          url: a.url || a.html_url || `${ELEARNING}/courses/${course?.id ?? ""}`,
          publishedAt: a.posted_at || new Date().toISOString(),
          course: course?.name,
          score: 0,
          tags: [],
          filtered: false,
          priority: "academic",
        });
      }
    }
  }

  const plannerRaw = await canvasJson<unknown>(
    jar,
    `${ELEARNING}/api/v1/planner/items?start_date=${encodeURIComponent(new Date(Date.now() - 7 * 86400000).toISOString())}&end_date=${encodeURIComponent(new Date(Date.now() + 21 * 86400000).toISOString())}&per_page=80`,
  );
  if (!isCanvasUnauth(plannerRaw)) {
    const planner = asArray<{
      plannable_type?: string;
      plannable?: { title?: string; due_at?: string; html_url?: string };
      context_name?: string;
      html_url?: string;
    }>(plannerRaw);
    for (const p of planner) {
      const title = p.plannable?.title;
      if (!title) continue;
      const type = p.plannable_type || "";
      const kind: FeedItem["kind"] =
        type.includes("calendar") || /exam|考试/.test(title)
          ? "exam"
          : type.includes("assignment") || type.includes("quiz")
            ? "ddl"
            : "announcement";
      items.push({
        id: shaId("elearning", p.html_url || title, title),
        source: "elearning",
        kind,
        title,
        summary: p.context_name || title,
        url: p.plannable?.html_url || p.html_url || `${ELEARNING}/`,
        publishedAt: new Date().toISOString(),
        dueAt: p.plannable?.due_at,
        course: p.context_name,
        score: 0,
        tags: [],
        filtered: false,
        priority: "academic",
      });
    }
  }

  const todosRaw = await canvasJson<unknown>(jar, `${ELEARNING}/api/v1/users/self/todo?per_page=40`);
  if (!isCanvasUnauth(todosRaw)) {
    const todos = asArray<{
      assignment?: { name?: string; due_at?: string; html_url?: string };
      context_name?: string;
      html_url?: string;
    }>(todosRaw);
    for (const t of todos) {
      const title = t.assignment?.name;
      if (!title) continue;
      items.push({
        id: shaId("elearning", t.html_url || title, title),
        source: "elearning",
        kind: "ddl",
        title,
        summary: t.context_name || "Canvas 待办",
        url: t.assignment?.html_url || t.html_url || `${ELEARNING}/`,
        publishedAt: new Date().toISOString(),
        dueAt: t.assignment?.due_at,
        course: t.context_name,
        score: 0,
        tags: [],
        filtered: false,
        priority: "urgent",
      });
    }
  }

  const missingRaw = await canvasJson<unknown>(
    jar,
    `${ELEARNING}/api/v1/users/self/missing_submissions?per_page=40`,
  );
  if (!isCanvasUnauth(missingRaw)) {
    const missing = asArray<{
      name?: string;
      due_at?: string;
      html_url?: string;
      course_id?: number;
    }>(missingRaw);
    for (const m of missing) {
      if (!m.name) continue;
      const course = courseList.find((c) => c.id === m.course_id);
      items.push({
        id: shaId("elearning", m.html_url || m.name, m.name),
        source: "elearning",
        kind: "ddl",
        title: m.name,
        summary: course?.name || "未提交作业",
        url: m.html_url || `${ELEARNING}/`,
        publishedAt: new Date().toISOString(),
        dueAt: m.due_at,
        course: course?.name,
        score: 0,
        tags: [],
        filtered: false,
        priority: "urgent",
      });
    }
  }

  const upcomingRaw = await canvasJson<unknown>(
    jar,
    `${ELEARNING}/api/v1/users/self/upcoming_events`,
  );
  if (!isCanvasUnauth(upcomingRaw)) {
    const upcoming = asArray<{
      title?: string;
      start_at?: string;
      location_name?: string;
      html_url?: string;
      type?: string;
      assignment?: { name?: string; due_at?: string; html_url?: string };
      context_name?: string;
    }>(upcomingRaw);
    for (const ev of upcoming) {
      const title = ev.title || ev.assignment?.name;
      if (!title) continue;
      if (ev.assignment || ev.type === "assignment" || ev.type === "quiz") {
        items.push({
          id: shaId("elearning", ev.html_url || title, title),
          source: "elearning",
          kind: /exam|考试/.test(title) ? "exam" : "ddl",
          title,
          summary: ev.context_name || title,
          url: ev.assignment?.html_url || ev.html_url || `${ELEARNING}/`,
          publishedAt: new Date().toISOString(),
          dueAt: ev.assignment?.due_at || ev.start_at,
          course: ev.context_name,
          score: 0,
          tags: [],
          filtered: false,
          priority: "academic",
        });
        continue;
      }
      if (!ev.start_at) continue;
      const start = new Date(ev.start_at);
      const day = start.getDay() === 0 ? 7 : start.getDay();
      courses.push({
        id: shaId("cal", ev.html_url || title, ev.start_at),
        name: title,
        location: ev.location_name,
        day,
        startPeriod: guessPeriod(start),
        endPeriod: guessPeriod(start) + 1,
      });
    }
  }

  const assignmentTargets = topCourses.slice(0, 40);
  await mapPool(assignmentTargets, 4, async (c) => {
    const list = await canvasPages<{
      name?: string;
      due_at?: string;
      html_url?: string;
      id?: number;
      workflow_state?: string;
      published?: boolean;
    }>(jar, `${ELEARNING}/api/v1/courses/${c.id}/assignments?include[]=submission&per_page=50`, 6);
    for (const a of list) {
      if (!a.name) continue;
      if ((a.workflow_state === "unpublished" || a.published === false) && !a.due_at) continue;
      items.push({
        id: shaId("elearning", a.html_url || String(a.id ?? a.name), a.name),
        source: "elearning",
        kind: /exam|考试|quiz/i.test(a.name) ? "exam" : "ddl",
        title: a.name,
        summary: c.name,
        url: a.html_url || `${ELEARNING}/courses/${c.id}/assignments/${a.id ?? ""}`,
        publishedAt: new Date().toISOString(),
        dueAt: a.due_at,
        course: c.name,
        score: 0,
        tags: [],
        filtered: false,
        priority: "academic",
      });
    }
    const quizzes = await canvasPages<{
      title?: string;
      name?: string;
      due_at?: string;
      html_url?: string;
      id?: number;
    }>(jar, `${ELEARNING}/api/v1/courses/${c.id}/quizzes?per_page=50`, 4);
    for (const q of quizzes) {
      const name = q.title || q.name;
      if (!name) continue;
      items.push({
        id: shaId("elearning", q.html_url || String(q.id ?? name), name),
        source: "elearning",
        kind: /exam|考试/i.test(name) ? "exam" : "ddl",
        title: name,
        summary: c.name,
        url: q.html_url || `${ELEARNING}/courses/${c.id}/quizzes/${q.id ?? ""}`,
        publishedAt: new Date().toISOString(),
        dueAt: q.due_at,
        course: c.name,
        score: 0,
        tags: [],
        filtered: false,
        priority: "academic",
      });
    }
  });

  const streamRaw = await canvasJson<unknown>(
    jar,
    `${ELEARNING}/api/v1/users/self/activity_stream?per_page=40`,
  );
  if (!isCanvasUnauth(streamRaw)) {
    const stream = asArray<{
      type?: string;
      title?: string;
      message?: string;
      created_at?: string;
      html_url?: string;
      course_id?: number;
    }>(streamRaw);
    for (const ev of stream) {
      const type = String(ev.type || "");
      if (!/Announcement|DiscussionTopic|Submission|Message|Conversation/i.test(type)) continue;
      const title = stripHtml(String(ev.title || ev.message || "")).slice(0, 160);
      if (!title) continue;
      const course = courseList.find((c) => c.id === ev.course_id);
      const kind: FeedItem["kind"] = /Submission/i.test(type) ? "ddl" : "announcement";
      items.push({
        id: shaId("elearning", ev.html_url || title, title),
        source: "elearning",
        kind,
        title,
        summary: course?.name || type,
        url: ev.html_url || `${ELEARNING}/`,
        publishedAt: ev.created_at || new Date().toISOString(),
        course: course?.name,
        score: 0,
        tags: [],
        filtered: false,
        priority: "academic",
      });
    }
  }

  const day = (offset: number) =>
    new Date(Date.now() + offset * 86400000).toISOString().slice(0, 10);
  const calRaw = await canvasJson<unknown>(
    jar,
    `${ELEARNING}/api/v1/calendar_events?type=assignment&start_date=${day(-3)}&end_date=${day(21)}&per_page=50`,
  );
  if (!isCanvasUnauth(calRaw)) {
    const evs = asArray<{
      title?: string;
      start_at?: string;
      end_at?: string;
      html_url?: string;
      description?: string;
      context_name?: string;
      type?: string;
    }>(calRaw);
    for (const ev of evs) {
      const title = ev.title;
      if (!title) continue;
      items.push({
        id: shaId("elearning", ev.html_url || title, title),
        source: "elearning",
        kind: /exam|考试|quiz/i.test(title) ? "exam" : "ddl",
        title,
        summary: ev.context_name || stripHtml(ev.description || title).slice(0, 160),
        url: ev.html_url || `${ELEARNING}/`,
        publishedAt: new Date().toISOString(),
        dueAt: ev.end_at || ev.start_at,
        course: ev.context_name,
        score: 0,
        tags: [],
        filtered: false,
        priority: "academic",
      });
    }
  }

  if (!items.length && !courseList.length) {
    throw new Error("eLearning 未返回课程（可能尚未完成 CAS 或账号无 Canvas 选课）");
  }
  return { items: dedupe(items), courses };
}

async function mapPool<T>(items: T[], limit: number, fn: (item: T) => Promise<void>): Promise<void> {
  let i = 0;
  const workers = Array.from({ length: Math.min(limit, Math.max(items.length, 0)) }, async () => {
    while (i < items.length) {
      const cur = items[i++];
      if (cur !== undefined) await fn(cur);
    }
  });
  await Promise.all(workers);
}

function guessPeriod(d: Date): number {
  const minutes = d.getHours() * 60 + d.getMinutes();
  if (minutes < 9 * 60 + 40) return 1;
  if (minutes < 11 * 60 + 40) return 3;
  if (minutes < 15 * 60 + 10) return 6;
  if (minutes < 17 * 60 + 10) return 8;
  return 11;
}

function stripHtml(html: string): string {
  return html.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
}

async function fetchEhall(jar: CookieJar): Promise<FeedItem[]> {
  const cas =
    `${EHALL}/fudan_rh/frontend/cas/index?redirect=${encodeURIComponent(EHALL)}`;
  await enterService(jar, cas);
  const items: FeedItem[] = [];
  const urls = [
    `${EHALL}/personal/frontend/data/info`,
    `${EHALL}/schedule/frontend/default/index`,
    `${EHALL}/schedule/frontend/calendar/calendar`,
    `${EHALL}/schedule/frontend/default/week-list?calendar_id=0`,
    `${EHALL}/xisu/frontend/task/list?status=0`,
    `${EHALL}/xisu/frontend/task/apply?status=0`,
    `${EHALL}/fudan/frontend/home/daily`,
    `${EHALL}/frontend/vpage/check-auth?project_id=1`,
  ];
  let loggedIn = false;
  for (const url of urls) {
    try {
      const hopped = await follow(jar, url);
      if (isIdpLoginSpa(hopped.url, hopped.body)) continue;
      if (hopped.url.includes("id.fudan.edu.cn")) continue;
      loggedIn = true;
      const json = parseJsonSafe<unknown>(hopped.body);
      if (json) {
        collectTodos(json, items);
        collectDaily(json, items);
      }
    } catch {
      /* next */
    }
  }
  if (items.length) return dedupe(items);
  if (!loggedIn) throw new Error("eHall 会话失效，需要重新登录");
  return [];
}

function collectDaily(node: unknown, out: FeedItem[]) {
  const rec = node && typeof node === "object" ? (node as Record<string, unknown>) : null;
  if (!rec) return;
  const d = (rec.d && typeof rec.d === "object" ? rec.d : rec) as Record<string, unknown>;
  const list = asArray<{ content?: string; detail?: string; class?: string; url?: string; time?: string; title?: string }>(
    d.list ?? d,
  );
  for (const ev of list) {
    const title = String(ev.content || ev.title || "").trim();
    if (!title || title.length < 2) continue;
    out.push({
      id: shaId("ehall", ev.url || title, title),
      source: "ehall",
      kind: /课|course/i.test(String(ev.class || ev.detail || title)) ? "todo" : "todo",
      title,
      summary: [ev.time, ev.detail, ev.class].filter(Boolean).join(" · ") || "今日日程",
      url: ev.url || `${EHALL}/`,
      publishedAt: new Date().toISOString(),
      score: 0,
      tags: [],
      filtered: false,
      priority: "academic",
    });
  }
}

function collectTodos(node: unknown, out: FeedItem[], depth = 0) {
  if (depth > 8 || node == null) return;
  if (Array.isArray(node)) {
    for (const x of node) collectTodos(x, out, depth + 1);
    return;
  }
  if (typeof node !== "object") return;
  const rec = node as Record<string, unknown>;
  const title = String(
    rec.task_name ??
      rec.title ??
      rec.name ??
      rec.taskName ??
      rec.processName ??
      rec.subject ??
      rec.appName ??
      rec.content ??
      "",
  );
  const looksTodo =
    rec.todoId ||
    rec.taskId ||
    rec.task_id ||
    rec.inst_id ||
    rec.processInstanceId ||
    rec.status === "todo" ||
    rec.todo === true ||
    rec.isTodo === true ||
    rec.scheduleId ||
    rec.itemType === "todo" ||
    rec.form_url ||
    rec.jumpUrl;
  if (title && title.length >= 2 && looksTodo) {
    out.push({
      id: shaId("ehall", String(rec.form_url ?? rec.jumpUrl ?? rec.url ?? rec.task_id ?? rec.id ?? title), title),
      source: "ehall",
      kind: "todo",
      title,
      summary: String(rec.app_name ?? rec.appName ?? rec.node_name ?? rec.source ?? rec.category ?? "办事大厅"),
      url: String(rec.form_url ?? rec.jumpUrl ?? rec.url ?? rec.pcUrl ?? rec.mobileUrl ?? rec.form_mobile_url ?? `${EHALL}/`),
      publishedAt: new Date().toISOString(),
      dueAt: rec.limitTime ? String(rec.limitTime) : rec.dueTime ? String(rec.dueTime) : undefined,
      score: 0,
      tags: [],
      filtered: false,
      priority: "urgent",
    });
  }
  for (const v of Object.values(rec)) {
    if (v && typeof v === "object") collectTodos(v, out, depth + 1);
  }
}

async function fetchMail(jar: CookieJar): Promise<FeedItem[]> {
  const services = [
    `${MAIL}/coremail/s/json?func=login:direct:cas`,
    `${MAIL}/coremail/index.jsp`,
    `${MAIL}/coremail/login.jsp`,
    `${MAIL}/`,
  ];
  let sid = "";
  for (const service of services) {
    try {
      const hopped = await enterService(jar, service).catch(() =>
        follow(jar, `${IDP}/authserver/login?service=${encodeURIComponent(service)}`),
      );
      sid =
        hopped.url.match(/[?&]sid=([^&]+)/i)?.[1] ||
        jar.snapshot().find((c) => /^sid$|coremail\.sid/i.test(c.name))?.value ||
        hopped.body.match(/sid['"]?\s*[:=]\s*['"]([A-Za-z0-9_-]{8,})['"]/)?.[1] ||
        "";
      if (sid) break;
    } catch {
      /* next */
    }
  }
  if (!sid) {
    return [];
  }
  const res = await fudanFetch(
    jar,
    `${MAIL}/coremail/s/json?sid=${encodeURIComponent(sid)}&func=mbox:listMessages`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        fid: 1,
        start: 0,
        limit: 20,
        order: "date",
        desc: true,
      }),
    },
  );
  const json = parseJsonSafe<{
    var?: Array<{ id?: string; subject?: string; from?: string; sentDate?: number }>;
  }>(await res.text());
  const list = json?.var ?? [];
  return list.slice(0, 20).map((m) => ({
    id: shaId("mail", String(m.id ?? m.subject), String(m.subject)),
    source: "mail" as const,
    kind: "mail" as const,
    title: String(m.subject || "(无主题)"),
    summary: String(m.from || "复旦云邮箱"),
    url: MAIL,
    publishedAt: m.sentDate ? new Date(m.sentDate).toISOString() : new Date().toISOString(),
    score: 0,
    tags: [],
    filtered: false,
    priority: "admin" as const,
  }));
}

async function fetchTimetable(jar: CookieJar): Promise<CourseMeeting[]> {
  const errors: string[] = [];
  try {
    const fromJwgl = await fetchJwglTable(jar);
    if (fromJwgl.length) return fromJwgl;
    errors.push("fdjwgl 空课表");
  } catch (err) {
    errors.push(err instanceof Error ? err.message : String(err));
  }
  try {
    const fromDaily = await fetchEhallDailyCourses(jar);
    if (fromDaily.length) return fromDaily;
  } catch (err) {
    errors.push(err instanceof Error ? err.message : String(err));
  }
  try {
    const fromEams = await fetchEamsTable(jar);
    if (fromEams.length) return fromEams;
    errors.push("jwfw 无 TaskActivity");
  } catch (err) {
    errors.push(err instanceof Error ? err.message : String(err));
  }
  try {
    const fromPg = await fetchPgTable(jar);
    if (fromPg.length) return fromPg;
  } catch (err) {
    errors.push(err instanceof Error ? err.message : String(err));
  }
  throw new Error(`课表未取到：${errors.slice(0, 3).join("；")}`);
}

async function fetchJwglTable(jar: CookieJar): Promise<CourseMeeting[]> {
  const tableUrl = `${FDJWGL}/student/for-std/course-table`;
  const sso = `${FDJWGL}/student/sso/login?refer=${encodeURIComponent(tableUrl)}`;
  try {
    await enterService(jar, sso);
  } catch {
    try {
      await casTo(jar, sso);
    } catch {
      /* continue */
    }
  }
  const home = await campusFollow(jar, tableUrl);
  if (home.url.includes("id.fudan.edu.cn") && home.url.includes("/ac/")) {
    throw new Error("教务课表 CAS 未完成");
  }
  const semId = extractSemesterId(home.body);
  const dataUrls = [
    semId ? `${FDJWGL}/student/for-std/course-table/semester/${semId}/print-data` : "",
    semId ? `${FDJWGL}/student/for-std/course-table/get-data?semesterId=${semId}` : "",
    `${FDJWGL}/student/for-std/course-table/get-data`,
  ].filter(Boolean);

  for (const url of dataUrls) {
    const got = await campusGet(jar, url, { headers: { Accept: "application/json, text/html" } });
    const json = parseJsonSafe<unknown>(got.body);
    const parsed = json ? parseJwglJson(json) : [];
    if (parsed.length) return parsed;
  }

  const fromHtml = parseJwglHtml(home.body);
  if (fromHtml.length) return fromHtml;
  throw new Error("fdjwgl 未解析到 activities（可能需 WebVPN）");
}

function extractSemesterId(html: string): string {
  const selected =
    html.match(/id=["']allSemesters["'][\s\S]*?<option[^>]+value=["'](\d+)["'][^>]*selected/i)?.[1] ||
    html.match(/id=["']allSemesters["'][\s\S]*?<option[^>]*selected[^>]*value=["'](\d+)["']/i)?.[1];
  if (selected) return selected;
  const fromSelect =
    html.match(/id=["']allSemesters["'][\s\S]*?<option[^>]+value=["'](\d+)["']/)?.[1];
  if (fromSelect) return fromSelect;
  const fromAssign = html.match(/semesterId["']?\s*[:=]\s*["']?(\d+)/)?.[1];
  if (fromAssign) return fromAssign;
  const raw = html.match(/var\s+semesters\s*=\s*JSON\.parse\(([\s\S]*?)\);/);
  if (raw?.[1]) {
    const cleaned = raw[1].trim().replace(/^['"]|['"]$/g, "").replace(/\\"/g, '"').replace(/\\'/g, "'");
    const json = parseJsonSafe<unknown>(cleaned) ?? parseJsonSafe<unknown>(raw[1]);
    if (Array.isArray(json) && json[0] && typeof json[0] === "object") {
      const id = (json[0] as { id?: number | string }).id;
      if (id != null) return String(id);
    }
  }
  return "";
}

function teacherLabel(raw: unknown): string {
  if (typeof raw === "string") return raw;
  if (Array.isArray(raw)) return raw.map(teacherLabel).filter(Boolean).join("、");
  if (raw && typeof raw === "object") {
    const rec = raw as Record<string, unknown>;
    return String(rec.name ?? rec.teacherName ?? rec.personName ?? rec.fullName ?? "");
  }
  return "";
}

export function parseJwglJson(raw: unknown): CourseMeeting[] {
  const meetings: CourseMeeting[] = [];
  const visit = (node: unknown) => {
    if (!node || typeof node !== "object") return;
    const rec = node as Record<string, unknown>;
    const tables = rec.studentTableVms;
    if (Array.isArray(tables)) {
      for (const t of tables) visit(t);
    }
    const activities = rec.activities;
    if (Array.isArray(activities)) {
      for (const act of activities) {
        if (!act || typeof act !== "object") continue;
        const a = act as Record<string, unknown>;
        const name = String(a.courseName ?? a.name ?? a.lessonName ?? "");
        if (!name) continue;
        const weekdayRaw = Number(a.weekday ?? a.weekDay ?? a.dayOfWeek ?? 0);
        const day = weekdayRaw === 0 ? 7 : weekdayRaw;
        const start = Number(a.startUnit ?? a.start ?? 1);
        const end = Number(a.endUnit ?? a.end ?? start);
        const weeks = Array.isArray(a.weekIndexes)
          ? (a.weekIndexes as number[])
          : Array.isArray(a.weeks)
            ? (a.weeks as number[])
            : [];
        const teachers = teacherLabel(a.teachers ?? a.teacher ?? a.teacherName);
        meetings.push({
          id: shaId("kb", name, `${day}-${start}-${String(a.room ?? "")}`),
          name,
          teacher: teachers || undefined,
          location: String(a.room ?? a.roomName ?? a.place ?? "") || undefined,
          day,
          startPeriod: Math.max(1, start),
          endPeriod: Math.max(start, end),
          weeksList: weeks.map(Number).filter((n) => Number.isFinite(n)),
        });
      }
    }
    for (const v of Object.values(rec)) {
      if (v && typeof v === "object" && v !== activities && v !== tables) visit(v);
    }
  };
  visit(raw);
  return mergeAdjacent(meetings);
}

function parseJwglHtml(html: string): CourseMeeting[] {
  const jsonMatch = html.match(/\{[\s\S]*"studentTableVms"[\s\S]*\}/);
  if (!jsonMatch) return [];
  try {
    return parseJwglJson(JSON.parse(jsonMatch[0]));
  } catch {
    return [];
  }
}

async function fetchEhallDailyCourses(jar: CookieJar): Promise<CourseMeeting[]> {
  const hopped = await follow(jar, `${EHALL}/fudan/frontend/home/daily`);
  const json = parseJsonSafe<unknown>(hopped.body);
  if (!json) return [];
  const rec = json as Record<string, unknown>;
  const d = (rec.d && typeof rec.d === "object" ? rec.d : rec) as Record<string, unknown>;
  const list = asArray<{
    content?: string;
    detail?: string;
    class?: string;
    time?: string;
    url?: string;
  }>(d.list ?? json);
  const meetings: CourseMeeting[] = [];
  for (const ev of list) {
    const name = String(ev.content || "").trim();
    if (!name) continue;
    const blob = `${ev.time || ""} ${ev.detail || ""} ${ev.class || ""}`;
    const dayMatch = blob.match(/周([一二三四五六日天])/);
    const periodMatch = blob.match(/(\d{1,2})\s*[-–~到至]\s*(\d{1,2})\s*节/);
    const dayMap: Record<string, number> = { 一: 1, 二: 2, 三: 3, 四: 4, 五: 5, 六: 6, 日: 7, 天: 7 };
    const day = dayMatch ? dayMap[dayMatch[1] ?? ""] ?? 0 : 0;
    if (!day) continue;
    const start = periodMatch ? Number(periodMatch[1]) : 1;
    const end = periodMatch ? Number(periodMatch[2]) : start;
    meetings.push({
      id: shaId("daily", name, `${day}-${start}`),
      name,
      location: ev.class || undefined,
      day,
      startPeriod: start,
      endPeriod: end,
    });
  }
  return mergeAdjacent(meetings);
}

async function fetchEamsTable(jar: CookieJar): Promise<CourseMeeting[]> {
  await casTo(jar, `${JWFW}/eams/home.action`);
  const urls = [
    `${JWFW}/eams/courseTableForStd.action`,
    `${JWFW}/eams/courseTableForStd!innerIndex.action`,
  ];
  for (const url of urls) {
    try {
      const hopped = await campusFollow(jar, url);
      const ids = hopped.body.match(/bg\.form\.addInput\(form,\s*"ids",\s*"(\d+)"\)/)?.[1];
      const semester = hopped.body.match(/semester\.id"?\s*[:=]\s*"?(\d+)/)?.[1];
      if (!ids) {
        const meetings = parseTaskActivity(hopped.body);
        if (meetings.length) return meetings;
        continue;
      }
      const table = await fudanFetch(jar, `${JWFW}/eams/courseTableForStd!courseTable.action`, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body: new URLSearchParams({
          ignoreHead: "1",
          "setting.kind": "std",
          startWeek: "1",
          semester: semester ? `{"id":${semester}}` : "",
          ids,
        }).toString(),
      });
      const meetings = parseTaskActivity(await table.text());
      if (meetings.length) return meetings;
    } catch {
      /* next */
    }
  }
  return [];
}

async function fetchPgTable(jar: CookieJar): Promise<CourseMeeting[]> {
  await casTo(jar, "https://yjsxk.fudan.edu.cn/yjsxkapp/sys/xsxkappfudan/index.html");
  const url = `https://yjsxk.fudan.edu.cn/yjsxkapp/sys/xsxkappfudan/xsxkCourse/loadKbxx.do?_=${Date.now()}`;
  const got = await campusGet(jar, url, { headers: { Accept: "application/json" } });
  const json = parseJsonSafe<unknown>(got.body);
  if (!json) return [];
  const meetings: CourseMeeting[] = [];
  const walk = (node: unknown) => {
    if (!node || typeof node !== "object") return;
    if (Array.isArray(node)) {
      for (const x of node) walk(x);
      return;
    }
    const rec = node as Record<string, unknown>;
    const name = String(rec.KCMC ?? rec.kcmc ?? rec.courseName ?? rec.KCM ?? "");
    if (name && (rec.XQJ || rec.xqj || rec.SKXQ || rec.weekday)) {
      const day = Number(rec.XQJ ?? rec.xqj ?? rec.SKXQ ?? rec.weekday);
      meetings.push({
        id: shaId("pg", name, `${day}-${String(rec.SKJC ?? rec.jc ?? "")}`),
        name,
        teacher: String(rec.JSXM ?? rec.xm ?? rec.teacher ?? "") || undefined,
        location: String(rec.JASMC ?? rec.cdmc ?? rec.room ?? "") || undefined,
        day: day === 0 ? 7 : day,
        startPeriod: Number(String(rec.SKJC ?? rec.jc ?? "1").split("-")[0] || 1),
        endPeriod: Number(String(rec.SKJC ?? rec.jc ?? "1").split("-").at(-1) || 1),
      });
    }
    for (const v of Object.values(rec)) if (v && typeof v === "object") walk(v);
  };
  walk(json);
  return mergeAdjacent(meetings);
}

function parseTaskActivity(html: string): CourseMeeting[] {
  const meetings: CourseMeeting[] = [];
  const re =
    /new\s+TaskActivity\(([^;]+)\)[\s\S]*?index\s*=\s*(\d+)\s*\*\s*unitCount\s*\+\s*(\d+)/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(html))) {
    const args = splitJsArgs(m[1] ?? "");
    const day = Number(m[2]);
    const unit = Number(m[3]);
    const name = unquote(args[3] || args[2] || "课程");
    const loc = unquote(args[5] || args[4] || "");
    const teacher = unquote(args[1] || "");
    const weeks = unquote(args[6] || "");
    meetings.push({
      id: createHash("sha1").update(`${name}|${day}|${unit}|${loc}`).digest("hex").slice(0, 12),
      name,
      teacher,
      location: loc,
      day: day + 1,
      startPeriod: unit + 1,
      endPeriod: unit + 1,
      weeks,
      weeksList: weeksToList(weeks),
    });
  }
  return mergeAdjacent(meetings);
}

function splitJsArgs(s: string): string[] {
  const out: string[] = [];
  let cur = "";
  let q: string | null = null;
  for (let i = 0; i < s.length; i++) {
    const ch = s[i]!;
    if (q) {
      if (ch === q) q = null;
      cur += ch;
    } else if (ch === "'" || ch === '"') {
      q = ch;
      cur += ch;
    } else if (ch === ",") {
      out.push(cur.trim());
      cur = "";
    } else cur += ch;
  }
  if (cur.trim()) out.push(cur.trim());
  return out;
}

function unquote(s: string): string {
  return s.replace(/^['"]|['"]$/g, "").replace(/\\u([0-9a-fA-F]{4})/g, (_, h) =>
    String.fromCharCode(parseInt(h, 16)),
  );
}

function weeksToList(bitmap: string): number[] {
  const out: number[] = [];
  for (let i = 0; i < bitmap.length; i++) {
    if (bitmap[i] === "1") out.push(i);
  }
  return out;
}

function mergeAdjacent(list: CourseMeeting[]): CourseMeeting[] {
  const sorted = [...list].sort(
    (a, b) => a.day - b.day || a.startPeriod - b.startPeriod || a.name.localeCompare(b.name),
  );
  const out: CourseMeeting[] = [];
  for (const cur of sorted) {
    const prev = out[out.length - 1];
    if (
      prev &&
      prev.name === cur.name &&
      prev.day === cur.day &&
      prev.location === cur.location &&
      cur.startPeriod === prev.endPeriod + 1
    ) {
      prev.endPeriod = cur.endPeriod;
    } else out.push({ ...cur });
  }
  return out;
}

function dedupe(items: FeedItem[]): FeedItem[] {
  const seen = new Set<string>();
  return items.filter((it) => {
    if (seen.has(it.id)) return false;
    seen.add(it.id);
    return true;
  });
}

export async function fetchCampusQr(cookies: CookieRecord[]): Promise<{
  status: string;
  payload?: string;
  message?: string;
  url?: string;
}> {
  const jar = new CookieJar(cookies);
  const qrUrl = "https://ecard.fudan.edu.cn/epay/wxpage/fudan/zfm/qrcode";
  try {
    await enterService(jar, qrUrl);
  } catch {
    /* still try the page */
  }
  const hopped = await follow(jar, qrUrl);
  const html = hopped.body || "";
  const payload =
    html.match(/id=["']myText["'][^>]*value=["']([^"']+)["']/)?.[1] ||
    html.match(/value=["']([^"']+)["'][^>]*id=["']myText["']/)?.[1] ||
    html.match(/QRCode\.toCanvas\([^,]+,\s*["']([^"']+)["']/)?.[1] ||
    "";
  if (!payload) {
    return { status: "error", message: "未从一卡通页解析到生活码。网页端请改用安装包过闸。" };
  }
  return { status: "ok", payload, url: hopped.url };
}

