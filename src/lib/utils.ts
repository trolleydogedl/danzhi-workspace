import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function djb2(input: string): string {
  let hash = 5381;
  for (let i = 0; i < input.length; i++) {
    hash = ((hash << 5) + hash) ^ input.charCodeAt(i);
  }
  return (hash >>> 0).toString(16).padStart(8, "0");
}

export function formatRelativeZh(iso: string, now = Date.now()): string {
  const t = new Date(iso).getTime();
  if (!Number.isFinite(t)) return "";
  const diff = t - now;
  const abs = Math.abs(diff);
  const min = Math.round(abs / 60000);
  const hour = Math.round(abs / 3600000);
  const day = Math.round(abs / 86400000);
  if (abs < 45000) return "刚刚";
  if (min < 60) return diff >= 0 ? `${min} 分钟后` : `${min} 分钟前`;
  if (hour < 24) return diff >= 0 ? `${hour} 小时后` : `${hour} 小时前`;
  if (day < 8) return diff >= 0 ? `${day} 天后` : `${day} 天前`;
  return new Date(iso).toLocaleDateString("zh-CN", {
    month: "numeric",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}
