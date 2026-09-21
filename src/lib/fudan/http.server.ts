import { request as httpRequest } from "node:http";
import { request as httpsRequest } from "node:https";
import { gunzipSync, inflateSync } from "node:zlib";
import type { CookieRecord } from "./types.ts";
import { isCampusOnlyHost, maybeVpnUrl } from "./vpn.server.ts";

export const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

export class CookieJar {
  cookies: CookieRecord[];
  viaVpn = false;

  constructor(initial: CookieRecord[] = []) {
    this.cookies = initial.map((c) => ({ ...c }));
  }

  snapshot(): CookieRecord[] {
    return this.cookies.map((c) => ({ ...c }));
  }

  absorb(res: Response, url: string) {
    const host = safeHost(url);
    const raw = getSetCookie(res);
    for (const line of raw) {
      const parts = line.split(";").map((s) => s.trim());
      const nv = parts[0];
      if (!nv) continue;
      const eq = nv.indexOf("=");
      if (eq <= 0) continue;
      const name = nv.slice(0, eq).trim();
      const value = nv.slice(eq + 1).trim();
      if (!value || value === "delete" || /expired/i.test(line)) {
        this.cookies = this.cookies.filter((c) => !(c.name === name && hostMatches(host, c.domain)));
        continue;
      }
      let domain = host;
      let path = "/";
      for (const p of parts.slice(1)) {
        const [k, v] = p.split("=");
        if (!k) continue;
        const key = k.trim().toLowerCase();
        if (key === "domain" && v) domain = v.trim().replace(/^\./, "");
        if (key === "path" && v) path = v.trim() || "/";
      }
      const idx = this.cookies.findIndex(
        (c) => c.name === name && c.domain === domain && c.path === path,
      );
      const rec: CookieRecord = { name, value, domain, path };
      if (idx >= 0) this.cookies[idx] = rec;
      else this.cookies.push(rec);
    }
  }

  headerFor(url: string): string {
    const host = safeHost(url);
    const path = safePath(url);
    return this.cookies
      .filter((c) => hostMatches(host, c.domain) && path.startsWith(c.path || "/"))
      .map((c) => `${c.name}=${c.value}`)
      .join("; ");
  }
}

function hostMatches(host: string, domain: string): boolean {
  return host === domain || host.endsWith(`.${domain}`);
}

function safeHost(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return "";
  }
}

function safePath(url: string): string {
  try {
    return new URL(url).pathname || "/";
  } catch {
    return "/";
  }
}

function getSetCookie(res: Response): string[] {
  const anyHeaders = res.headers as Headers & {
    getSetCookie?: () => string[];
  };
  if (typeof anyHeaders.getSetCookie === "function") {
    return anyHeaders.getSetCookie();
  }
  const single = res.headers.get("set-cookie");
  return single ? [single] : [];
}

function stripFragment(url: string): string {
  const hash = url.indexOf("#");
  return hash >= 0 ? url.slice(0, hash) : url;
}

function headerRecord(init?: HeadersInit): Record<string, string> {
  const out: Record<string, string> = {};
  if (!init) return out;
  if (init instanceof Headers) {
    init.forEach((value, key) => {
      out[key] = value;
    });
    return out;
  }
  if (Array.isArray(init)) {
    for (const [key, value] of init) out[key] = value;
    return out;
  }
  return { ...init };
}

function hasHeader(headers: Record<string, string>, name: string): boolean {
  const needle = name.toLowerCase();
  return Object.keys(headers).some((key) => key.toLowerCase() === needle);
}

function encodeBody(body: BodyInit | null | undefined): Buffer | undefined {
  if (body == null) return undefined;
  if (typeof body === "string") return Buffer.from(body);
  if (body instanceof Uint8Array) return Buffer.from(body);
  if (body instanceof ArrayBuffer) return Buffer.from(new Uint8Array(body));
  throw new Error("不支持的请求体");
}

function decodePayload(buf: Buffer, encoding: string | undefined): Buffer {
  const enc = (encoding ?? "").toLowerCase();
  try {
    if (enc.includes("gzip")) return gunzipSync(buf);
    if (enc.includes("deflate")) return inflateSync(buf);
  } catch {
    return buf;
  }
  return buf;
}

function headerValue(value: string | string[] | undefined): string | undefined {
  if (value == null) return undefined;
  return Array.isArray(value) ? value[0] : value;
}

type NodeRequestOpts = {
  method: string;
  headers: Record<string, string>;
  body?: Buffer;
  timeoutMs: number;
  signal?: AbortSignal;
};

function nodeRequest(u: URL, opts: NodeRequestOpts): Promise<Response> {
  return new Promise((resolve, reject) => {
    const isHttps = u.protocol === "https:";
    const requestFn = isHttps ? httpsRequest : httpRequest;
    const headers = { ...opts.headers };
    if (opts.body) headers["Content-Length"] = String(opts.body.length);

    const req = requestFn(
      {
        protocol: u.protocol,
        hostname: u.hostname,
        port: u.port || (isHttps ? 443 : 80),
        path: `${u.pathname}${u.search}`,
        method: opts.method,
        headers,
        timeout: opts.timeoutMs,
      },
      (res) => {
        const chunks: Buffer[] = [];
        res.on("data", (chunk) => chunks.push(chunk as Buffer));
        res.on("end", () => {
          try {
            const raw = Buffer.concat(chunks);
            const buf = decodePayload(raw, headerValue(res.headers["content-encoding"]));
            const hdrs = new Headers();
            for (const [key, value] of Object.entries(res.headers)) {
              if (value == null) continue;
              if (key.toLowerCase() === "set-cookie") {
                const list = Array.isArray(value) ? value : [value];
                for (const line of list) hdrs.append("set-cookie", line);
              } else {
                hdrs.set(key, Array.isArray(value) ? value.join(", ") : value);
              }
            }
            resolve(
              new Response(Uint8Array.from(buf), {
                status: res.statusCode ?? 0,
                statusText: res.statusMessage ?? "",
                headers: hdrs,
              }),
            );
          } catch (err) {
            reject(err);
          }
        });
      },
    );

    req.on("timeout", () => {
      req.destroy();
      reject(new Error("连接复旦认证超时，请稍后重试"));
    });
    req.on("error", (err) => {
      reject(new Error(`连不上复旦认证（${err.message}）。请检查网络或改用安装包。`));
    });

    if (opts.signal) {
      if (opts.signal.aborted) {
        req.destroy();
        reject(new Error("请求已取消"));
        return;
      }
      opts.signal.addEventListener(
        "abort",
        () => {
          req.destroy();
          reject(new Error("请求已取消"));
        },
        { once: true },
      );
    }

    if (opts.body) req.write(opts.body);
    req.end();
  });
}

export async function fudanFetch(
  jar: CookieJar,
  url: string,
  init: RequestInit & { timeoutMs?: number } = {},
): Promise<Response> {
  const target = stripFragment(maybeVpnUrl(url, jar.viaVpn && isCampusOnlyHost(url)));
  const { timeoutMs = 18000, ...rest } = init;
  const headers = headerRecord(rest.headers);

  if (!hasHeader(headers, "User-Agent")) headers["User-Agent"] = UA;
  if (!hasHeader(headers, "Accept")) headers.Accept = "application/json, text/html, */*;q=0.8";
  if (!hasHeader(headers, "Accept-Language")) headers["Accept-Language"] = "zh-CN,zh;q=0.9,en;q=0.8";
  if (!hasHeader(headers, "Accept-Encoding")) headers["Accept-Encoding"] = "gzip, deflate";

  const cookie = jar.headerFor(target);
  if (cookie) headers.Cookie = cookie;

  const parsed = new URL(target);
  if (parsed.hostname.endsWith("id.fudan.edu.cn")) {
    if (!hasHeader(headers, "Origin")) headers.Origin = "https://id.fudan.edu.cn";
    if (!hasHeader(headers, "Referer")) headers.Referer = "https://id.fudan.edu.cn/ac-h5/";
  }

  try {
    const response = await nodeRequest(parsed, {
      method: (rest.method ?? "GET").toUpperCase(),
      headers,
      body: encodeBody(rest.body as BodyInit | undefined),
      timeoutMs,
      signal: rest.signal ?? undefined,
    });
    jar.absorb(response, target);
    return response;
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    if (/连不上|超时|取消/.test(msg)) throw err;
    throw new Error(`连不上复旦认证（${msg}）。请检查网络或改用安装包。`);
  }
}

export async function follow(
  jar: CookieJar,
  url: string,
  init: RequestInit & { timeoutMs?: number } = {},
  maxHops = 14,
): Promise<{ res: Response; url: string; body: string }> {
  let current = url;
  let method = (init.method ?? "GET").toUpperCase();
  let body = init.body;
  for (let i = 0; i < maxHops; i++) {
    const res = await fudanFetch(jar, current, {
      ...init,
      method,
      body,
      redirect: "manual",
    });
    const loc = res.headers.get("location");
    if (loc && res.status >= 300 && res.status < 400) {
      current = new URL(loc, current).href;
      if (res.status === 302 || res.status === 303 || res.status === 301) {
        method = "GET";
        body = undefined;
      }
      continue;
    }
    const text = await res.text();
    return { res, url: current, body: text };
  }
  throw new Error("重定向过多");
}

export function parseJsonSafe<T>(text: string): T | null {
  let trimmed = text.trim();
  if (!trimmed || trimmed[0] === "<") return null;
  if (/^while\s*\(\s*1\s*\)\s*;/.test(trimmed) || /^for\s*\(\s*;\s*;\s*\)\s*;/.test(trimmed)) {
    trimmed = trimmed
      .replace(/^while\s*\(\s*1\s*\)\s*;/, "")
      .replace(/^for\s*\(\s*;\s*;\s*\)\s*;/, "")
      .trim();
  }
  if (!trimmed) return null;
  try {
    return JSON.parse(trimmed) as T;
  } catch {
    const start = trimmed.search(/[\[{]/);
    if (start > 0) {
      try {
        return JSON.parse(trimmed.slice(start)) as T;
      } catch {
        return null;
      }
    }
    return null;
  }
}

export function looksLikeHtml(text: string): boolean {
  const s = text.trim().slice(0, 200).toLowerCase();
  return s.startsWith("<!doctype") || s.startsWith("<html") || s.includes("<head");
}
