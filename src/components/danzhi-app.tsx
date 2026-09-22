"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import {
  DanMark,
  IconDoor,
  IconDownload,
  IconEye,
  IconEyeOff,
  IconInbox,
  IconLogout,
  IconNote,
  IconQr,
  IconRefresh,
  IconSettings,
  IconSpinner,
  IconTable,
  PressButton,
} from "@/components/danzhi-icons";
import { completeMfaFn, fetchQrFn, pollAllFn, startLoginFn } from "@/lib/fudan/actions";
import { clearVault, loadVault, patchVault, saveVault } from "@/lib/crypto/vault";
import { useAppStore, type TabId } from "@/lib/store";
import { PERIODS, periodRangeLabel, jsDayToFudan } from "@/lib/fudan/periods";
import { cn, formatRelativeZh } from "@/lib/utils";
import type { FeedItem } from "@/lib/fudan/types";

const WEEK = "一二三四五六日";
const SOURCE_ZH: Record<string, string> = {
  math: "数院",
  jwc: "教务",
  elearning: "课堂",
  ehall: "办事",
  mail: "邮箱",
  ecard: "一卡通",
  timetable: "课表",
};
const LOGIN_TIMEOUT_MS = 28000;
const APK = "/danzhi-1.31.0.apk";
const APK_NAME = "danzhi-1.31.0.apk";

function dueSoon(dueAt?: string): boolean {
  if (!dueAt) return false;
  const t = Date.parse(dueAt);
  if (!Number.isFinite(t)) return false;
  const left = t - Date.now();
  return left > 0 && left <= 7 * 24 * 3600 * 1000;
}

function dueOverdue(dueAt?: string): boolean {
  if (!dueAt) return false;
  const t = Date.parse(dueAt);
  return Number.isFinite(t) && t < Date.now();
}

function dueLabel(dueAt?: string): string {
  if (!dueAt) return "";
  const t = Date.parse(dueAt);
  if (!Number.isFinite(t)) return "";
  const ms = t - Date.now();
  if (ms <= 0) return "已逾期";
  const min = Math.ceil(ms / 60000);
  if (min < 60) return `还剩 ${Math.max(1, min)} 分钟`;
  const hour = Math.ceil(ms / 3600000);
  if (hour < 24) return `还剩 ${hour} 小时`;
  return `还剩 ${Math.ceil(ms / 86400000)} 天`;
}

function persistDraft(username: string) {
  try {
    sessionStorage.setItem("dz_user", username);
  } catch {
    /* private mode */
  }
}

function explainError(e: unknown): string {
  if (typeof e === "string" && e.trim()) return e;
  const m =
    e instanceof Error
      ? e.message
      : e && typeof e === "object" && "message" in e
        ? String((e as { message: unknown }).message)
        : "";
  if (
    /Cannot read properties of undefined/i.test(m) ||
    /reading ['"]fetch['"]/i.test(m) ||
    /fetch is not/i.test(m) ||
    /network path was not found/i.test(m) ||
    /ENOTFOUND|ECONNRESET|ECONNREFUSED|ETIMEDOUT|cert|ssl/i.test(m) ||
    /Failed to fetch|NetworkError|Load failed/i.test(m)
  ) {
    return "网页预览连不上复旦统一认证。请下载安装包，在手机上登录。";
  }
  return m.replace(/^登录失败[:：]\s*/, "") || "登录失败，请重试";
}

function withTimeout<T>(p: Promise<T>, ms: number, message: string): Promise<T> {
  return new Promise((resolve, reject) => {
    const t = window.setTimeout(() => reject(new Error(message)), ms);
    p.then(
      (v) => {
        window.clearTimeout(t);
        resolve(v);
      },
      (e) => {
        window.clearTimeout(t);
        reject(e);
      },
    );
  });
}

function formatLastPoll(raw: string): string {
  const t = Date.parse(raw);
  if (!Number.isFinite(t)) return "";
  const parts = new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(new Date(t));
  const get = (k: string) => parts.find((p) => p.type === k)?.value || "";
  const now = new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    month: "numeric",
    day: "numeric",
  }).formatToParts(new Date());
  const nowGet = (k: string) => now.find((p) => p.type === k)?.value || "";
  const hm = `${get("hour")}:${get("minute")}`;
  if (get("month") === nowGet("month") && get("day") === nowGet("day")) return `上次巡检成功 ${hm}`;
  return `上次巡检成功 ${get("month")}月${get("day")}日 ${hm}`;
}

function dueStamp(dueAt?: string): string {
  if (!dueAt) return "";
  const t = Date.parse(dueAt);
  if (!Number.isFinite(t)) return "";
  const d = new Date(t);
  const parts = new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).formatToParts(d);
  const get = (k: string) => parts.find((p) => p.type === k)?.value || "";
  return `截止 ${get("month")}.${get("day")} ${get("hour")}:${get("minute")}`;
}

function FeedCard({ it }: { it: FeedItem }) {
  const due =
    it.kind === "ddl" || it.kind === "exam" || it.kind === "todo" ? dueLabel(it.dueAt) : "";
  const stamp =
    it.kind === "ddl" || it.kind === "exam" || it.kind === "todo"
      ? dueStamp(it.dueAt) || formatRelativeZh(it.publishedAt)
      : formatRelativeZh(it.publishedAt) || "日期未知";
  const summary = it.summary && it.summary !== it.title ? it.summary : "";
  return (
    <a
      href={it.url}
      target="_blank"
      rel="noreferrer"
      className={cn("slip p-4")}
    >
      <span className="stamp">{stamp || "日期未知"}</span>
      <span className="rounded-full bg-surface px-2 py-0.5 text-xs text-muted">
        {SOURCE_ZH[it.source] || it.source}
        {it.kind === "mail" ? " · 邮件" : ""}
        {it.kind === "ddl" || it.kind === "exam" || it.kind === "todo" ? " · 作业" : ""}
      </span>
      <strong className="mt-2 block pr-16 text-base font-medium leading-snug">{it.title}</strong>
      {summary ? <p className="mt-1 text-xs leading-relaxed text-subtle">{summary}</p> : null}
      {due ? (
        <p className="mt-2 inline-flex items-center gap-2 rounded-full bg-danger/10 px-2.5 py-1 text-sm font-bold text-danger">
          <span className="size-2 rounded-full bg-danger" />
          {due}
        </p>
      ) : null}
    </a>
  );
}

const TABS: { id: TabId; label: string; Icon: typeof IconInbox }[] = [
  { id: "feed", label: "信匣", Icon: IconInbox },
  { id: "table", label: "课表", Icon: IconTable },
  { id: "pass", label: "校园码", Icon: IconQr },
  { id: "rooms", label: "教室", Icon: IconDoor },
  { id: "notes", label: "笔记", Icon: IconNote },
  { id: "settings", label: "设置", Icon: IconSettings },
];

export function DanzhiApp() {
  const phase = useAppStore((s) => s.phase);
  const tab = useAppStore((s) => s.tab);
  const setTab = useAppStore((s) => s.setTab);
  const profile = useAppStore((s) => s.profile);
  const items = useAppStore((s) => s.items);
  const courses = useAppStore((s) => s.courses);
  const cookies = useAppStore((s) => s.cookies);
  const sources = useAppStore((s) => s.sources);
  const polling = useAppStore((s) => s.polling);
  const pollError = useAppStore((s) => s.pollError);
  const loginNote = useAppStore((s) => s.loginNote);
  const lastPollAt = useAppStore((s) => s.lastPollAt);
  const challenge = useAppStore((s) => s.challenge);
  const applySession = useAppStore((s) => s.applySession);
  const applyPoll = useAppStore((s) => s.applyPoll);
  const setChallenge = useAppStore((s) => s.setChallenge);
  const setPolling = useAppStore((s) => s.setPolling);
  const setPollError = useAppStore((s) => s.setPollError);
  const logout = useAppStore((s) => s.logout);
  const enterDemo = useAppStore((s) => s.enterDemo);

  const [username, setUsername] = useState(() => {
    try {
      return sessionStorage.getItem("dz_user") || "";
    } catch {
      return "";
    }
  });
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [showPw, setShowPw] = useState(false);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState("");
  const [err, setErr] = useState("");
  const [qrPayload, setQrPayload] = useState("");
  const [qrMsg, setQrMsg] = useState("");
  const [selectedDay, setSelectedDay] = useState(jsDayToFudan(new Date().getDay()));
  const inFlight = useRef(false);
  const totpRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      await useAppStore.persist.rehydrate();
      const vault = await loadVault();
      if (cancelled) return;
      if (vault?.username) setUsername((u) => u || vault.username);
      const st = useAppStore.getState();
      if (st.phase === "app" && st.cookies.length && vault?.password) {
        setPassword(vault.password);
        void runPoll(vault.username, vault.password, st.cookies);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (phase === "mfa") totpRef.current?.focus();
  }, [phase]);

  async function runPoll(user: string, pass: string, cookieJar = cookies) {
    setPolling(true);
    setPollError(null);
    try {
      const res = await pollAllFn({
        data: { cookies: cookieJar, username: user, password: pass },
      });
      applyPoll(res);
      if (res.cookies?.length) await patchVault({ cookies: res.cookies });
    } catch (e) {
      setPollError(explainError(e));
    } finally {
      setPolling(false);
    }
  }

  async function onLogin() {
    persistDraft(username);
    if (!username.trim() || !password) {
      setErr("请填写学号和密码");
      setStatus("");
      return;
    }
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setErr("");
    setStatus("已点到。正在连接复旦统一认证…");
    try {
      const res = await withTimeout(
        startLoginFn({
          data: {
            username: username.trim(),
            password,
          },
        }),
        LOGIN_TIMEOUT_MS,
        "登录超时。复旦认证较慢，请再试一次，学号密码还在。",
      );
      persistDraft(username.trim());
      await saveVault({
        username: username.trim(),
        password,
        cookies: res.status === "ok" ? res.cookies : undefined,
      });
      if (res.status === "ok") {
        applySession(res.profile, res.cookies);
        void runPoll(username.trim(), password, res.cookies);
      } else {
        setStatus("");
        setChallenge(res.challenge);
        setCode("");
        setErr("");
      }
    } catch (e) {
      setErr(explainError(e));
      setStatus("");
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  async function onMfa() {
    if (!challenge) return;
    if (code.trim().length !== 6) {
      setErr("请输入 6 位动态口令");
      return;
    }
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setErr("");
    setStatus("已点到。正在校验动态口令…");
    try {
      const res = await withTimeout(
        completeMfaFn({ data: { challenge, code: code.trim() } }),
        LOGIN_TIMEOUT_MS,
        "校验超时，请换一枚新的 6 位码再试。",
      );
      if (res.status === "ok") {
        applySession(res.profile, res.cookies);
        await patchVault({ cookies: res.cookies });
        void runPoll(username.trim(), password, res.cookies);
      } else {
        setChallenge(res.challenge);
        setErr("仍需二次认证，请换一枚新的 6 位码");
        setStatus("");
      }
    } catch (e) {
      setErr(explainError(e));
      setStatus("");
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  async function loadQr() {
    if (!cookies.length) return;
    setQrMsg("正在向一卡通拉取官方生活码…");
    try {
      const r = await fetchQrFn({ data: { cookies } });
      if (r.status === "ok" && r.payload) {
        setQrPayload(r.payload);
        setQrMsg("已更新");
      } else {
        setQrPayload("");
        setQrMsg(r.message || "生活码失败");
      }
    } catch (e) {
      setQrMsg(explainError(e));
    }
  }

  useEffect(() => {
    if (phase === "app" && tab === "pass") void loadQr();
  }, [phase, tab]);

  function onLogout() {
    clearVault();
    logout();
    setPassword("");
    setCode("");
    setErr("");
    setStatus("");
    inFlight.current = false;
    setBusy(false);
  }

  const visibleItems = items.filter((it) => !it.filtered);
  const overdueItems = visibleItems
    .filter((it) => (it.kind === "ddl" || it.kind === "exam" || it.kind === "todo") && dueOverdue(it.dueAt))
    .slice()
    .sort((a, b) => (Date.parse(a.dueAt || "") || 0) - (Date.parse(b.dueAt || "") || 0));
  const soonItems = visibleItems
    .filter((it) => (it.kind === "ddl" || it.kind === "exam" || it.kind === "todo") && dueSoon(it.dueAt))
    .slice()
    .sort((a, b) => (Date.parse(a.dueAt || "") || 0) - (Date.parse(b.dueAt || "") || 0));
  const specialIds = new Set([...overdueItems, ...soonItems].map((it) => it.id));
  const recentItems = visibleItems
    .filter((it) => !specialIds.has(it.id))
    .slice()
    .sort((a, b) => {
      const ta = Date.parse(a.publishedAt || "") || 0;
      const tb = Date.parse(b.publishedAt || "") || 0;
      return tb - ta;
    });
  const todayCourses = useMemo(
    () =>
      courses
        .filter((c) => c.day === selectedDay)
        .sort((a, b) => a.startPeriod - b.startPeriod),
    [courses, selectedDay],
  );

  if (phase !== "app") {
    return (
      <main className="relative mx-auto flex min-h-dvh max-w-lg flex-col bg-bg px-5 pb-10 pt-12">
        <DanMark className="size-[76px]" />
        <p className="mt-6 text-xs font-semibold tracking-[0.08em] text-subtle">复旦校园小助手</p>
        <h1 className="mt-2 text-5xl font-bold tracking-tight">旦知</h1>
        <p className="mt-3 max-w-[36ch] text-sm leading-relaxed text-muted">
          先填学号密码。通过后再打开 Authenticator，输入 6 位码。
        </p>

        {phase !== "mfa" ? (
          <div className="card-shadow mt-8 rounded-3xl bg-elev p-4">
            <p className="text-xs font-medium tracking-[0.18em] text-subtle">步骤 1 / 2</p>
            <label className="mt-3 block text-xs font-medium text-muted">
              学号
              <input
                id="user"
                className="mt-1.5 h-12 w-full rounded-xl border border-border bg-bg px-3.5 text-fg outline-none transition-[border-color] duration-150 focus:border-primary/40"
                inputMode="numeric"
                autoComplete="username"
                value={username}
                onChange={(e) => {
                  setUsername(e.target.value);
                  persistDraft(e.target.value);
                }}
                placeholder="UIS 学号"
                disabled={busy}
              />
            </label>
            <label className="mt-4 block text-xs font-medium text-muted">
              密码
              <div className="relative mt-1.5">
                <input
                  id="pass"
                  className="h-12 w-full rounded-xl border border-border bg-bg px-3.5 pr-12 text-fg outline-none transition-[border-color] duration-150 focus:border-primary/40"
                  type={showPw ? "text" : "password"}
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      e.preventDefault();
                      void onLogin();
                    }
                  }}
                  placeholder="UIS 密码"
                  disabled={busy}
                />
                <button
                  type="button"
                  className="absolute right-1.5 top-1/2 grid size-10 -translate-y-1/2 place-items-center text-subtle"
                  onClick={() => setShowPw((v) => !v)}
                  aria-label={showPw ? "隐藏密码" : "显示密码"}
                >
                  {showPw ? <IconEyeOff className="size-5" /> : <IconEye className="size-5" />}
                </button>
              </div>
            </label>
            <PressButton
              className="mt-5"
              busy={busy}
              busyLabel="正在连接统一认证…"
              onPointerDown={() => void onLogin()}
              onClick={() => void onLogin()}
            >
              登录
            </PressButton>
            {busy || status ? (
              <div className="status-banner" role="status">
                <IconSpinner className="mt-0.5 size-5 shrink-0" />
                <div>
                  <p className="text-sm font-medium">{status || "正在连接复旦统一认证…"}</p>
                  <p className="sub">通常需要几秒，学号密码会保留</p>
                </div>
              </div>
            ) : null}
            {err ? (
              <p className="mt-3 text-sm leading-relaxed text-danger" role="alert">
                {err}
              </p>
            ) : null}
          </div>
        ) : (
          <div className="card-shadow mt-8 rounded-3xl bg-elev p-4">
            <p className="text-xs font-medium tracking-[0.18em] text-subtle">步骤 2 / 2</p>
            <p className="mt-2 text-sm text-muted">
              学号密码已通过。打开 Authenticator，输入当前 6 位码。
            </p>
            <label className="mt-4 block text-xs font-medium text-muted">
              6 位码
              <input
                id="totp"
                ref={totpRef}
                className="mt-1.5 h-12 w-full rounded-xl border border-border bg-bg px-3.5 font-mono text-xl tracking-[0.4em] outline-none"
                inputMode="numeric"
                value={code}
                onChange={(e) => setCode(e.target.value.replace(/\D/g, "").slice(0, 6))}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault();
                    void onMfa();
                  }
                }}
                placeholder="000000"
                disabled={busy}
                autoComplete="one-time-code"
              />
            </label>
            <PressButton
              className="mt-5"
              busy={busy}
              busyLabel="正在校验动态口令…"
              disabled={code.length < 6}
              onPointerDown={() => void onMfa()}
              onClick={() => void onMfa()}
            >
              确认
            </PressButton>
            {busy || status ? (
              <div className="status-banner" role="status">
                <IconSpinner className="mt-0.5 size-5 shrink-0" />
                <div>
                  <p className="text-sm font-medium">{status || "正在校验动态口令…"}</p>
                  <p className="sub">请稍候，不要重复点击</p>
                </div>
              </div>
            ) : null}
            <button
              type="button"
              className="mt-2 h-12 w-full rounded-xl text-sm text-muted"
              onClick={() => {
                setChallenge(null);
                setStatus("");
                setErr("");
                setCode("");
              }}
            >
              返回上一步
            </button>
            {err ? (
              <p className="mt-3 text-sm text-danger" role="alert">
                {err}
              </p>
            ) : null}
          </div>
        )}

        <button
          type="button"
          className="mt-6 text-sm text-muted underline-offset-4 hover:underline"
          onClick={enterDemo}
        >
          先看演示看板
        </button>
        <a
          href={APK}
          download={APK_NAME}
          className="mt-3 inline-flex items-center gap-1.5 text-sm text-muted underline-offset-4 hover:underline"
        >
          <IconDownload className="size-4" />
          下载 Android 安装包 1.31.0
        </a>
      </main>
    );
  }

  return (
    <div className="min-h-dvh bg-bg text-fg">
      {tab === "feed" ? (
        <section className="mx-auto max-w-lg px-5 pb-28 pt-8">
          <p className="text-xs font-semibold tracking-[0.08em] text-subtle">信匣</p>
          <h1 className="mt-1 text-3xl font-bold">
            {polling ? "正在巡检" : visibleItems.length ? `${visibleItems.length} 条` : "今日已清"}
          </h1>
          {lastPollAt ? (
            <p className="mt-1 text-xs text-subtle">{formatLastPoll(lastPollAt)}</p>
          ) : null}
          {loginNote ? <p className="mt-2 text-xs text-ok">{loginNote}</p> : null}
          {pollError ? <p className="mt-2 text-sm text-danger">{pollError}</p> : null}
          <div className="mt-5 flex flex-col gap-2">
            {overdueItems.length ? (
              <p className="mt-1 text-xs font-bold text-danger">已逾期未交 · {overdueItems.length}</p>
            ) : null}
            {overdueItems.map((it) => (
              <FeedCard key={it.id} it={it} />
            ))}
            {soonItems.length ? (
              <p className="mt-1 text-xs font-bold text-muted">即将到期 · {soonItems.length}</p>
            ) : null}
            {soonItems.map((it) => (
              <FeedCard key={it.id} it={it} />
            ))}
            {soonItems.length || overdueItems.length ? (
              recentItems.length ? <p className="mt-3 text-xs font-bold text-muted">最近</p> : null
            ) : null}
            {recentItems.map((it) => (
              <FeedCard key={it.id} it={it} />
            ))}
            {!visibleItems.length && !polling ? (
              <p className="text-sm text-subtle">过滤后暂无条目。</p>
            ) : null}
          </div>
        </section>
      ) : null}

      {tab === "table" ? (
        <section className="mx-auto max-w-lg px-5 pb-28 pt-8">
          <p className="text-xs font-medium tracking-[0.22em] text-subtle">课表</p>
          <h1 className="mt-1 text-3xl font-bold">
            {selectedDay === jsDayToFudan(new Date().getDay())
              ? "今日课表"
              : `周${WEEK[selectedDay - 1]}`}
          </h1>
          <NextCard courses={courses} />
          <div className="mt-3 flex gap-1.5 overflow-auto pb-2">
            {[1, 2, 3, 4, 5, 6, 7].map((d) => (
              <button
                key={d}
                onClick={() => setSelectedDay(d)}
                className={cn(
                  "h-14 min-w-14 rounded-xl px-3 text-sm",
                  d === selectedDay ? "bg-primary text-on" : "bg-elev text-muted card-shadow",
                )}
              >
                周{WEEK[d - 1]}
              </button>
            ))}
          </div>
          <div className="mt-2 flex flex-col gap-2">
            {todayCourses.map((c) => (
              <article key={c.id} className="card-shadow rounded-2xl bg-elev p-4">
                <p className="text-xs tabular-nums text-subtle">
                  {periodRangeLabel(c.startPeriod, c.endPeriod)} · 第{c.startPeriod}
                  {c.endPeriod !== c.startPeriod ? `–${c.endPeriod}` : ""}节
                </p>
                <strong className="mt-1 block">{c.name}</strong>
                <p className="text-xs text-subtle">
                  {[c.location, c.teacher].filter(Boolean).join(" · ") || "地点待更新"}
                </p>
              </article>
            ))}
            {!todayCourses.length ? (
              <p className="text-sm text-subtle">这一天没有排课。</p>
            ) : null}
          </div>
        </section>
      ) : null}

      {tab === "pass" ? (
        <section className="mx-auto max-w-lg px-5 pb-28 pt-8">
          <p className="text-xs font-medium tracking-[0.22em] text-subtle">校园码</p>
          <h1 className="mt-1 text-3xl font-bold">生活码</h1>
          <p className="mt-2 text-sm text-subtle">{qrMsg || "点刷新拉取生活码。"}</p>
          <div className="card-shadow mt-4 rounded-3xl bg-elev p-4 text-center">
            {qrPayload ? (
              <img
                alt="生活码"
                className="mx-auto size-56 bg-elev p-2"
                src={`https://api.qrserver.com/v1/create-qr-code/?size=440x440&data=${encodeURIComponent(qrPayload)}`}
              />
            ) : (
              <p className="py-16 text-sm text-subtle">暂无码</p>
            )}
          </div>
          <PressButton className="mt-4" onClick={() => void loadQr()}>
            <IconRefresh className="size-5" />
            刷新生活码
          </PressButton>
          <a
            href={APK}
            download={APK_NAME}
            className="card-shadow mt-3 flex h-12 w-full items-center justify-center rounded-xl bg-elev text-sm"
          >
            下载安装包
          </a>
        </section>
      ) : null}

      {tab === "rooms" ? (
        <section className="mx-auto max-w-lg px-5 pb-28 pt-8">
          <p className="text-xs font-medium tracking-[0.22em] text-subtle">空教室</p>
          <h1 className="mt-1 text-3xl font-bold">哪间空着</h1>
          <p className="mt-2 text-xs text-subtle">每格是一节课。描边的是当前这节，所有教室同一列。</p>
          <div className="mt-4 flex flex-col gap-2">
            {[
              { name: "H2101", seats: "60", busy: [true, true, false, false, false, true, false, false, false, false, false, false, false] },
              { name: "H2105", seats: "48", busy: [false, false, true, true, false, false, false, false, true, true, false, false, false] },
              { name: "H3105", seats: "90", busy: [true, true, true, true, false, false, false, false, false, false, false, false, false] },
            ].map((room, idx) => {
              const now = new Date();
              const mins = now.getHours() * 60 + now.getMinutes();
              const cur = PERIODS.find((p) => {
                const [sh, sm] = p.start.split(":").map(Number);
                const [eh, em] = p.end.split(":").map(Number);
                return mins >= sh * 60 + sm && mins <= eh * 60 + em;
              });
              const curN = cur ? cur.n : 0;
              const freeNow = curN ? !room.busy[curN - 1] : true;
              return (
                <article key={room.name} className="card-shadow rounded-2xl bg-elev p-4">
                  {idx === 0 ? (
                    <div className="mb-2 flex gap-0.5">
                      {PERIODS.map((p) => (
                        <span
                          key={p.n}
                          className={cn(
                            "flex-1 text-center text-[9px] font-bold",
                            p.n === curN ? "text-fg" : "text-subtle",
                          )}
                        >
                          {p.n}
                        </span>
                      ))}
                    </div>
                  ) : null}
                  <div className="flex items-center justify-between">
                    <strong>{room.name}</strong>
                    <span className={cn("rounded-full px-2 py-0.5 text-xs", freeNow ? "bg-ok/20 text-ok" : "bg-busy/20 text-busy")}>
                      {freeNow ? "空闲" : "占用"}
                    </span>
                  </div>
                  <p className="mt-1 text-xs text-subtle">座位 {room.seats}</p>
                  <div className="mt-2 flex gap-0.5">
                    {PERIODS.map((p) => (
                      <i
                        key={p.n}
                        className={cn(
                          "h-4 flex-1 rounded-sm",
                          room.busy[p.n - 1] ? "bg-busy" : "bg-ok",
                          p.n === curN ? "outline outline-2 outline-offset-1 outline-fg" : "",
                        )}
                      />
                    ))}
                  </div>
                </article>
              );
            })}
          </div>
          <a
            href={APK}
            download={APK_NAME}
            className="btn-press mt-6"
          >
            <IconDownload className="size-5" />
            下载安装包查询空教室
          </a>
        </section>
      ) : null}

      {tab === "notes" ? <NotesPanel /> : null}

      {tab === "settings" ? (
        <section className="mx-auto max-w-lg px-5 pb-28 pt-8">
          <p className="text-xs font-medium tracking-[0.22em] text-subtle">设置</p>
          <h1 className="mt-1 text-3xl font-bold">
            {profile?.name || profile?.studentId || "已登录"}
          </h1>
          <p className="mt-1 text-xs text-subtle">版本 1.31.0 · 网页看板</p>
          <div className="card-shadow mt-4 rounded-2xl bg-elev p-4 text-sm">
            {Object.entries(sources).map(([k, v]) => (
              <p key={k} className="my-1 flex justify-between">
                <span>{SOURCE_ZH[k] || k}</span>
                <span className={v.ok ? "text-ok" : "text-danger"}>
                  {v.ok ? `${v.count} 条` : v.error || "失败"}
                </span>
              </p>
            ))}
            {!Object.keys(sources).length ? <p className="text-subtle">尚无巡检</p> : null}
          </div>
          <PressButton
            className="mt-4"
            busy={polling}
            busyLabel="巡检中…"
            onClick={() => void runPoll(username, password)}
          >
            <IconRefresh className="size-5" />
            立即巡检
          </PressButton>
          <a
            href={APK}
            download={APK_NAME}
            className="card-shadow mt-3 flex h-12 w-full items-center justify-center gap-2 rounded-xl bg-elev text-sm"
          >
            <IconDownload className="size-4" />
            下载 Android 安装包
          </a>
          <button
            type="button"
            className="mt-3 flex h-12 w-full items-center justify-center gap-2 rounded-xl text-sm text-muted"
            onClick={onLogout}
          >
            <IconLogout className="size-4" />
            退出
          </button>
        </section>
      ) : null}

      <nav className="fixed inset-x-3 bottom-[max(10px,env(safe-area-inset-bottom))] grid grid-cols-6 rounded-[28px] border border-border bg-elev/95 p-1.5 shadow-card backdrop-blur">
        {TABS.map(({ id, label, Icon }) => (
          <button
            key={id}
            onClick={() => setTab(id)}
            className={cn(
              "flex h-12 flex-col items-center justify-center gap-0.5 rounded-[22px] text-[10px]",
              tab === id ? "bg-surface font-bold text-primary" : "text-subtle",
            )}
          >
            <Icon className="size-6" />
            {label}
          </button>
        ))}
      </nav>
    </div>
  );
}

function NextCard({
  courses,
}: {
  courses: { name: string; location?: string; day: number; startPeriod: number; endPeriod: number }[];
}) {
  const day = jsDayToFudan(new Date().getDay());
  const now = new Date();
  const mins = now.getHours() * 60 + now.getMinutes();
  const today = courses.filter((c) => c.day === day).sort((a, b) => a.startPeriod - b.startPeriod);
  let hit: (typeof today)[number] | null = null;
  let state: "wait" | "now" = "wait";
  let left = 0;
  for (const c of today) {
    const a = PERIODS.find((p) => p.n === c.startPeriod);
    const b = PERIODS.find((p) => p.n === c.endPeriod);
    if (!a || !b) continue;
    const [sh, sm] = a.start.split(":").map(Number);
    const [eh, em] = b.end.split(":").map(Number);
    const s = sh * 60 + sm;
    const e = eh * 60 + em;
    if (mins < s) {
      hit = c;
      state = "wait";
      left = s - mins;
      break;
    }
    if (mins < e) {
      hit = c;
      state = "now";
      left = e - mins;
      break;
    }
  }
  if (!hit) {
    return (
      <div className="card-shadow mt-4 rounded-2xl bg-elev p-4">
        <p className="text-sm text-subtle">今天接下来没有课。</p>
      </div>
    );
  }
  const hh = Math.floor(left / 60);
  const mm = left % 60;
  const when = hh > 0 ? `${hh} 小时 ${mm} 分` : `${mm} 分钟`;
  return (
    <div className="mt-4 rounded-[22px] bg-primary p-5 text-on shadow-card">
      <p className="text-xs tracking-[0.18em] opacity-70">
        {state === "now" ? "正在上课，距离下课" : "下节课还有"}
      </p>
      <p className="mt-1 text-3xl font-bold tabular-nums">{when}</p>
      <p className="mt-2 text-sm">{hit.name}</p>
      <p className="mt-1 text-xs opacity-70">
        {hit.location || ""} {periodRangeLabel(hit.startPeriod, hit.endPeriod)}
      </p>
    </div>
  );
}

type Note = {
  id: string;
  title: string;
  body: string;
  pinned: boolean;
  attachments: { name: string; mime: string; data: string }[];
  created: number;
  updated: number;
};

function loadWebNotes(): Note[] {
  try {
    return JSON.parse(localStorage.getItem("dz_notes") || "[]") as Note[];
  } catch {
    return [];
  }
}

function NotesPanel() {
  const [notes, setNotes] = useState<Note[]>(() => loadWebNotes());
  const [editing, setEditing] = useState<Note | null>(null);
  const persist = (list: Note[]) => {
    setNotes(list);
    try {
      localStorage.setItem("dz_notes", JSON.stringify(list));
    } catch {
      /* ignore quota */
    }
  };
  const sorted = [...notes].sort((a, b) => {
    if (!!a.pinned !== !!b.pinned) return a.pinned ? -1 : 1;
    return (b.updated || 0) - (a.updated || 0);
  });
  if (editing) {
    return (
      <section className="mx-auto max-w-lg px-5 pb-28 pt-8">
        <p className="text-xs font-medium tracking-[0.22em] text-subtle">笔记</p>
        <h1 className="mt-1 text-3xl font-bold">{editing.title || "新笔记"}</h1>
        <input
          className="mt-4 h-12 w-full rounded-2xl border border-border bg-elev px-4"
          placeholder="标题"
          value={editing.title}
          onChange={(e) => setEditing({ ...editing, title: e.target.value })}
        />
        <textarea
          className="mt-3 min-h-40 w-full rounded-2xl border border-border bg-elev p-3"
          placeholder="记点什么…"
          value={editing.body}
          onChange={(e) => setEditing({ ...editing, body: e.target.value })}
        />
        <PressButton
          className="mt-4"
          onClick={() => {
            const draft = {
              ...editing,
              title: editing.title.trim() || (editing.body || "未命名").slice(0, 18),
              updated: Date.now(),
            };
            const next = notes.some((n) => n.id === draft.id)
              ? notes.map((n) => (n.id === draft.id ? draft : n))
              : [draft, ...notes];
            persist(next);
            setEditing(null);
          }}
        >
          保存
        </PressButton>
        <button
          type="button"
          className="mt-2 flex h-12 w-full items-center justify-center rounded-xl text-sm"
          onClick={() => setEditing({ ...editing, pinned: !editing.pinned })}
        >
          {editing.pinned ? "取消置顶" : "置顶"}
        </button>
        <button
          type="button"
          className="mt-1 flex h-12 w-full items-center justify-center rounded-xl text-sm text-danger"
          onClick={() => {
            persist(notes.filter((n) => n.id !== editing.id));
            setEditing(null);
          }}
        >
          删除
        </button>
        <button
          type="button"
          className="mt-1 flex h-12 w-full items-center justify-center rounded-xl text-sm text-muted"
          onClick={() => setEditing(null)}
        >
          返回
        </button>
      </section>
    );
  }
  return (
    <section className="mx-auto max-w-lg px-5 pb-28 pt-8">
      <p className="text-xs font-medium tracking-[0.22em] text-subtle">笔记</p>
      <h1 className="mt-1 text-3xl font-bold">随手记</h1>
      <p className="mt-2 text-sm text-subtle">本地保存。安装包里还可以附照片和文件。</p>
      <PressButton
        className="mt-4"
        onClick={() =>
          setEditing({
            id: "n-" + Date.now().toString(36),
            title: "",
            body: "",
            pinned: false,
            attachments: [],
            created: Date.now(),
            updated: Date.now(),
          })
        }
      >
        写一条
      </PressButton>
      <div className="mt-4 flex flex-col gap-2">
        {sorted.map((n) => (
          <button
            key={n.id}
            type="button"
            className="card-shadow rounded-2xl bg-elev p-4 text-left"
            onClick={() => setEditing(n)}
          >
            {n.pinned ? <span className="text-xs font-bold text-primary">置顶</span> : null}
            <strong className="mt-1 block">{n.title || "未命名"}</strong>
            <p className="mt-1 text-xs text-subtle">{(n.body || "").slice(0, 80)}</p>
          </button>
        ))}
        {!sorted.length ? <p className="text-sm text-subtle">还没有笔记。点「写一条」记下作业或闸机提醒。</p> : null}
      </div>
    </section>
  );
}
