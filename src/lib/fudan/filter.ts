import type { FeedItem, FilterPrefs, Priority } from "./types";

export const DEFAULT_FILTER_PREFS: FilterPrefs = {
  dropAid: true,
  dropYouthLeague: true,
  dropStudentAffairs: true,
  dropAdminReport: true,
  keepMathBoost: true,
  keepDeadlineBoost: true,
};

type Rule = {
  id: string;
  label: string;
  weight: number;
  pattern: RegExp;
  pref?: keyof FilterPrefs;
  tag: string;
  always?: boolean;
};

const RULES: Rule[] = [
  {
    id: "obit",
    label: "讣告悼念",
    weight: -95,
    tag: "讣告",
    always: true,
    pattern: /讣告|悼念|逝世|沉痛|追悼会|遗体告别|永垂不朽|治丧|哀告|千古/,
  },
  {
    id: "eval",
    label: "评教招聘",
    weight: -92,
    tag: "评教",
    always: true,
    pattern:
      /评教|教学评估|教学质量评价|学生评教|教师评教|评教系统|评教工作|评教通知|评教安排|网上评教|评教招聘|助教招聘|教师招聘/,
  },
  {
    id: "hire",
    label: "招聘广告",
    weight: -88,
    tag: "招聘",
    always: true,
    pattern:
      /招聘启事|岗位招聘|兼职招聘|用工招聘|人才招聘|招募志愿者(?!.*必修)|实习生招聘|招聘会(?!.*就业指导讲座)/,
  },
  {
    id: "aid",
    label: "贫困资助",
    weight: -85,
    pref: "dropAid",
    tag: "资助噪音",
    pattern:
      /贫困生|家庭经济困难|国家助学金|助学贷款|勤工助学|困难补助|临时困难|资助中心|资助政策(?!.*学业奖)/,
  },
  {
    id: "youth",
    label: "团委常规",
    weight: -60,
    pref: "dropYouthLeague",
    tag: "团委",
    pattern:
      /主题团日|团委通知|团学联|青年大学习|青马工程|团支部(?!.*紧急)|志愿者招募(?!.*必修)|社团招新/,
  },
  {
    id: "affairs",
    label: "学工常规",
    weight: -50,
    pref: "dropStudentAffairs",
    tag: "学工",
    pattern:
      /学生工作部|学工例会|辅导员工作|文明寝室|宿舍卫生|心理普查(?!.*强制)|班会通知(?!.*考试)/,
  },
  {
    id: "admin",
    label: "行政汇报",
    weight: -45,
    pref: "dropAdminReport",
    tag: "行政",
    pattern:
      /工作总结|述职|会议纪要|党建工作(?!.*必修)|理论学习(?!.*考试)|宣传稿|新闻通稿/,
  },
  {
    id: "ddl",
    label: "截止/DDL",
    weight: 42,
    pref: "keepDeadlineBoost",
    tag: "截止",
    pattern:
      /截止|deadline|ddl|due\b|务必于|逾期|即将到期|今晚|明日截止|24:00前|23:59/,
  },
  {
    id: "exam",
    label: "考试",
    weight: 55,
    tag: "考试",
    pattern: /考试|期末|期中|补考|缓考|闭卷|开卷|quiz|midterm|final exam/,
  },
  {
    id: "coursework",
    label: "课业",
    weight: 38,
    tag: "课业",
    pattern:
      /作业|assignment|论文|实验报告|选课|退课|小学期|培养方案|课程大纲|成绩|挂科/,
  },
  {
    id: "math",
    label: "数院",
    weight: 36,
    pref: "keepMathBoost",
    tag: "数院",
    pattern:
      /数学科学学院|数院|数学系|数学分析|高等代数|抽象代数|实变|复变|微分几何|拓扑|概率论|泛函|数论/,
  },
  {
    id: "urgent-admin",
    label: "紧急行政",
    weight: 48,
    tag: "紧急",
    pattern:
      /安全教育|必做|必修任务|未完成待办|立即|紧急|务必|逾期将|学籍|处分|违纪/,
  },
  {
    id: "research",
    label: "科研学业",
    weight: 22,
    tag: "科研",
    pattern: /科研训练|大创|导师|研讨班|seminar|colloquium|学术报告(?!.*行政)/,
  },
];

const SOURCE_BASE: Record<FeedItem["source"], number> = {
  elearning: 18,
  ehall: 22,
  math: 14,
  jwc: 10,
  mail: 8,
};

const KIND_BASE: Record<FeedItem["kind"], number> = {
  exam: 28,
  ddl: 26,
  todo: 24,
  announcement: 8,
  notice: 6,
  mail: 4,
};

function haystack(item: Pick<FeedItem, "title" | "summary" | "course">): string {
  return `${item.title}\n${item.summary}\n${item.course ?? ""}`.toLowerCase();
}

export function scoreItem(
  raw: Omit<FeedItem, "score" | "tags" | "filtered" | "filterReason" | "priority">,
  prefs: FilterPrefs,
  now = Date.now(),
): FeedItem {
  let score = SOURCE_BASE[raw.source] + KIND_BASE[raw.kind];
  const tags: string[] = [];
  const reasons: string[] = [];
  const text = haystack(raw);

  for (const rule of RULES) {
    if (!rule.always && rule.pref && prefs[rule.pref] === false && rule.weight < 0) continue;
    if (!rule.always && rule.pref && prefs[rule.pref] === false && rule.weight > 0) continue;
    if (rule.pattern.test(text) || rule.pattern.test(raw.title)) {
      score += rule.weight;
      tags.push(rule.tag);
      if (rule.weight < 0) reasons.push(rule.label);
    }
  }

  if (raw.dueAt) {
    const due = new Date(raw.dueAt).getTime();
    if (Number.isFinite(due)) {
      const hours = (due - now) / 3600000;
      if (hours < 0) score += 12;
      else if (hours < 24) score += 34;
      else if (hours < 72) score += 22;
      else if (hours < 168) score += 10;
    }
  }

  const published = new Date(raw.publishedAt).getTime();
  if (Number.isFinite(published)) {
    const ageH = (now - published) / 3600000;
    if (ageH < 12) score += 8;
    else if (ageH > 24 * 21) score -= 12;
  }

  score = Math.max(-100, Math.min(100, score));

  let priority: Priority = "admin";
  if (score >= 55 || raw.kind === "exam" || (raw.kind === "ddl" && score >= 40)) {
    priority = "urgent";
  } else if (score >= 28 || raw.kind === "ddl" || raw.kind === "todo") {
    priority = "academic";
  } else if (score < 8) {
    priority = "noise";
  }

  const filtered = priority === "noise" || score < 6;
  return {
    ...raw,
    score,
    tags: [...new Set(tags)],
    filtered,
    filterReason: filtered ? reasons[0] || "低相关" : undefined,
    priority,
  };
}

export function applyFilter(items: FeedItem[], prefs: FilterPrefs): FeedItem[] {
  return items
    .map((item) =>
      scoreItem(
        {
          id: item.id,
          source: item.source,
          kind: item.kind,
          title: item.title,
          summary: item.summary,
          url: item.url,
          publishedAt: item.publishedAt,
          dueAt: item.dueAt,
          course: item.course,
        },
        prefs,
      ),
    )
    .filter((item) => !item.tags.some((t) => t === "讣告" || t === "评教" || t === "招聘"))
    .sort((a, b) => {
      const ta = Date.parse(a.publishedAt || "") || 0;
      const tb = Date.parse(b.publishedAt || "") || 0;
      return tb - ta;
    });
}

function rankPriority(p: Priority): number {
  return { urgent: 0, academic: 1, admin: 2, noise: 3 }[p];
}
