import { scoreItem } from "./filter";
import { DEFAULT_FILTER_PREFS } from "./filter";
import type { CourseMeeting, FeedItem, Profile } from "./types";

function isoOffset(hours: number): string {
  return new Date(Date.now() + hours * 3600000).toISOString();
}

function raw(
  partial: Omit<FeedItem, "score" | "tags" | "filtered" | "filterReason" | "priority">,
): FeedItem {
  return scoreItem(partial, DEFAULT_FILTER_PREFS);
}

export const DEMO_PROFILE: Profile = {
  studentId: "26800180106",
  name: "数院同学",
  college: "数学科学学院",
  email: "26800180106@m.fudan.edu.cn",
};

export function buildDemoItems(): FeedItem[] {
  return [
    raw({
      id: "demo-exam-ana",
      source: "elearning",
      kind: "exam",
      title: "数学分析（I）期中考试安排",
      summary:
        "考试时间：第 8 周周六 09:00–11:00，地点：光华楼东辅 202。闭卷，允许携带一本无注释教材。请提前 15 分钟入场。",
      url: "https://elearning.fudan.edu.cn/",
      publishedAt: isoOffset(-20),
      dueAt: isoOffset(24 * 9),
      course: "数学分析（I）",
    }),
    raw({
      id: "demo-ddl-hw",
      source: "elearning",
      kind: "ddl",
      title: "高等代数习题课作业 第 4 次",
      summary: "提交 Jordan 标准形相关证明题 1–6。截止日期今晚 23:59，逾期系统自动关闭。",
      url: "https://elearning.fudan.edu.cn/",
      publishedAt: isoOffset(-6),
      dueAt: isoOffset(8),
      course: "高等代数（I）",
    }),
    raw({
      id: "demo-far-hw",
      source: "elearning",
      kind: "ddl",
      title: "实变函数大作业",
      summary: "学期末提交综述。截止日期还早，不会压在信匣最上头。",
      url: "https://elearning.fudan.edu.cn/",
      publishedAt: isoOffset(-24 * 18),
      dueAt: isoOffset(24 * 80),
      course: "实变函数",
    }),
    raw({
      id: "demo-todo-safe",
      source: "ehall",
      kind: "todo",
      title: "2026 级本科生安全教育（必修）",
      summary: "请于本周日前完成在线安全教育测试，未完成将限制选课资格。",
      url: "https://ehall.fudan.edu.cn/",
      publishedAt: isoOffset(-30),
      dueAt: isoOffset(24 * 3),
    }),
    raw({
      id: "demo-math-seminar",
      source: "math",
      kind: "notice",
      title: "数院本科生学术报告：随机矩阵与自由概率",
      summary:
        "报告人：特聘教授。时间：周三 16:00，地点：光华东主楼 2201。欢迎分析与概率方向同学参加。",
      url: "https://math.fudan.edu.cn/",
      publishedAt: isoOffset(-10),
    }),
    raw({
      id: "demo-jwc-select",
      source: "jwc",
      kind: "notice",
      title: "关于 2026–2027 学年第一学期补选课的通知",
      summary: "补选课系统将于下周一向本科生开放。请核对本学期培养方案未完成学分。",
      url: "https://jwc.fudan.edu.cn/",
      publishedAt: isoOffset(-48),
    }),
    raw({
      id: "demo-ann-geo",
      source: "elearning",
      kind: "announcement",
      title: "解析几何：本周五习题课教室调整",
      summary: "原 H3105 改至 H2101，时间仍为第 3–4 节。",
      url: "https://elearning.fudan.edu.cn/",
      publishedAt: isoOffset(-4),
      course: "解析几何",
    }),
    raw({
      id: "demo-mail",
      source: "mail",
      kind: "mail",
      title: "研究生招生办公室：推免材料补交通知",
      summary: "招生办 <yzb@fudan.edu.cn>",
      url: "https://mail.m.fudan.edu.cn/",
      publishedAt: isoOffset(-2),
    }),
  ];
}

export const DEMO_COURSES: CourseMeeting[] = [
    {
      id: "c1",
      name: "数学分析（I）",
      teacher: "甲（1-8周）、乙（9-16周）",
      location: "H2101",
      day: 1,
      startPeriod: 1,
      endPeriod: 2,
    },
    {
      id: "c2",
      name: "高等代数（I）",
      teacher: "丙",
      location: "H3105",
      day: 1,
      startPeriod: 3,
      endPeriod: 4,
    },
    {
      id: "c3",
      name: "解析几何",
      teacher: "丁",
      location: "H2101",
      day: 3,
      startPeriod: 3,
      endPeriod: 4,
    },
    {
      id: "c4",
      name: "大学英语",
      teacher: "戊",
      location: "HGX201",
      day: 2,
      startPeriod: 6,
      endPeriod: 7,
    },
  ];

