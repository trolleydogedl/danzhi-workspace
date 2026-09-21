/** 复旦本科常用节次（含大节合并显示） */
export const PERIODS: { n: number; start: string; end: string }[] = [
  { n: 1, start: "08:00", end: "08:45" },
  { n: 2, start: "08:55", end: "09:40" },
  { n: 3, start: "09:55", end: "10:40" },
  { n: 4, start: "10:50", end: "11:35" },
  { n: 5, start: "11:45", end: "12:30" },
  { n: 6, start: "13:30", end: "14:15" },
  { n: 7, start: "14:25", end: "15:10" },
  { n: 8, start: "15:25", end: "16:10" },
  { n: 9, start: "16:20", end: "17:05" },
  { n: 10, start: "17:15", end: "18:00" },
  { n: 11, start: "18:30", end: "19:15" },
  { n: 12, start: "19:25", end: "20:10" },
  { n: 13, start: "20:20", end: "21:05" },
];

export const WEEKDAYS = ["一", "二", "三", "四", "五", "六", "日"];

export function periodRangeLabel(start: number, end: number): string {
  const a = PERIODS.find((p) => p.n === start);
  const b = PERIODS.find((p) => p.n === end);
  if (!a || !b) return `第${start}-${end}节`;
  return `${a.start}–${b.end}`;
}

export function minutesFromMidnight(hhmm: string): number {
  const [h, m] = hhmm.split(":").map(Number);
  return h * 60 + m;
}

/** 默认 2026 秋开学：2026-09-07 周一 */
export const DEFAULT_WEEK_START = "2026-09-07";

export function academicWeek(weekStartIso: string, date = new Date()): number {
  const start = new Date(`${weekStartIso}T00:00:00+08:00`).getTime();
  const now = date.getTime();
  if (!Number.isFinite(start)) return 1;
  const w = Math.floor((now - start) / (7 * 86400000)) + 1;
  return Math.max(1, Math.min(22, w));
}

export function jsDayToFudan(jsDay: number): number {
  return jsDay === 0 ? 7 : jsDay;
}
