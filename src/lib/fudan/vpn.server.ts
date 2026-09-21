import { createCipheriv } from "node:crypto";

/** 复旦 WebVPN（wengine）固定密钥，与 wrdvpn 系网关一致 */
const KEY = Buffer.from("wrdvpnisthebest!");
const IV = Buffer.from("wrdvpnisthebest!");
const VPN_HOST = "https://webvpn.fudan.edu.cn";

export const WEBVPN_LOGIN = `${VPN_HOST}/login?cas_login=true`;

export function isVpnUrl(url: string): boolean {
  try {
    return new URL(url).hostname === "webvpn.fudan.edu.cn";
  } catch {
    return false;
  }
}

export function toVpnUrl(url: string): string {
  if (isVpnUrl(url)) return url;
  const u = new URL(url);
  const proto = u.protocol.replace(":", "");
  const cipher = createCipheriv("aes-128-cfb", KEY, IV);
  cipher.setAutoPadding(false);
  const enc = Buffer.concat([cipher.update(u.hostname, "utf8"), cipher.final()]);
  const hostSeg = `${IV.toString("hex")}${enc.toString("hex")}`;
  const rest = `${u.pathname}${u.search}` || "/";
  const path = rest.startsWith("/") ? rest.slice(1) : rest;
  return `${VPN_HOST}/${proto}/${hostSeg}/${path}`;
}

export function maybeVpnUrl(url: string, viaVpn: boolean): string {
  return viaVpn ? toVpnUrl(url) : url;
}

const CAMPUS_HOSTS = new Set([
  "fdjwgl.fudan.edu.cn",
  "jwfw.fudan.edu.cn",
  "xk.fudan.edu.cn",
  "yjsxk.fudan.edu.cn",
  "10.64.130.6",
]);

export function isCampusOnlyHost(url: string): boolean {
  try {
    return CAMPUS_HOSTS.has(new URL(url).hostname);
  } catch {
    return false;
  }
}
