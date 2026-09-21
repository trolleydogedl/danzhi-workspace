package cn.fudan.danzhi.core;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Collapse near-duplicate campus messages. Mail stays one-id-one-letter. */
public final class FeedMerge {
    private FeedMerge() {}
    private static final Pattern HW = Pattern.compile("(.+?)作业\\s*[第]?\\s*([0-9０-９]{1,3})");
    private static final Pattern HW_NTH = Pattern.compile("第\\s*([0-9０-９]{1,3})\\s*次");
    private static final long WEEK_MS = 7L * 24L * 60L * 60L * 1000L;

    public static final class Item {
        public String id="", source="", kind="", title="", summary="", url="", course="", dueAt="";
        public long receivedAt;
        public boolean unread, trashed;
        public Item copy() {
            Item c=new Item();
            c.id=id;c.source=source;c.kind=kind;c.title=title;c.summary=summary;c.url=url;c.course=course;c.dueAt=dueAt;
            c.receivedAt=receivedAt;c.unread=unread;c.trashed=trashed;return c;
        }
    }

    public static String cleanTitle(String raw) {
        if (raw == null) return "";
        String t = raw;
        t = t.replace("&"+"nbsp;", " ").replace("&"+"amp;", "&").replace("&"+"lt;", "<")
                .replace("&"+"gt;", ">").replace("&"+"#39;", "'").replace("&"+"quot;", "\"");
        t = t.replace('\u00a0', ' ');
        t = t.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        t = t.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        t = t.replaceAll("<[^>]+>", " ");
        t = t.replaceAll("\\s+", " ").trim();
        return t;
    }

    public static String normTitle(String title) {
        String t = cleanTitle(title);
        if (t.isEmpty()) return "";
        t = t.replaceAll("[\\s\\p{Punct}·—–-]+", "");
        String han = t.replaceAll("[^\\u4e00-\\u9fff]", "");
        if (han.length() >= 8) return han;
        return t.toLowerCase(Locale.ROOT);
    }

    public static boolean worthlessSummary(String summary) {
        if (summary == null) return true;
        String t = cleanTitle(summary);
        if (t.isEmpty()) return true;
        String low = t.toLowerCase(Locale.ROOT);
        if (low.matches("announcement|discussion|discussiontopic|discussionentry|assignment|conversation|message|messagechanged|submission|quiz|calendar event|course_\\d+"))
            return true;
        if (t.equals("课堂") || t.equals("通知") || t.equals("公告") || t.equals("课堂消息")) return true;
        return false;
    }

    public static String stripDecor(String title) {
        String t = cleanTitle(title);
        t = t.replaceAll("【[^】]{0,16}】", "");
        t = t.replaceAll("\\[[^\\]]{0,16}]", "");
        t = t.replaceAll("(?i)^(通知|公告|转发|分享|课堂|作业提醒|作业通知|即将到期|assignment|due)\\s*[:：\\-—]*", "");
        t = t.replaceAll("（[^）]{0,24}）$", "");
        t = t.replaceAll("\\([^)]{0,24}\\)$", "");
        return t.trim();
    }

    public static String noticeStem(String title) {
        return normTitle(stripDecor(title));
    }

    static String asciiDigits(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '０' && c <= '９') b.append((char)('0' + (c - '０')));
            else b.append(c);
        }
        return b.toString();
    }

    /** Stable id for the same homework scraped from planner / calendar / assignments / 作业提醒. */
    public static String homeworkId(String title) {
        String t = stripDecor(title);
        if (t.isEmpty()) return "";
        Matcher m = HW.matcher(t);
        if (m.find()) {
            String course = m.group(1).replaceAll("[\\s\\p{Punct}A-Za-z0-9._]+", "");
            String num = asciiDigits(m.group(2));
            if (course.length() >= 2) return course + "作业" + num;
            return "作业" + num;
        }
        if (t.contains("作业")) {
            Matcher n = HW_NTH.matcher(t);
            if (n.find()) {
                String course = t.replaceAll("作业.*", "").replaceAll("[\\s\\p{Punct}A-Za-z0-9._]+", "");
                if (course.length() >= 2) return course + "作业" + asciiDigits(n.group(1));
            }
        }
        return "";
    }

    public static String kindGroup(String kind) {
        if ("ddl".equals(kind) || "exam".equals(kind) || "todo".equals(kind)) return "asg";
        if ("mail".equals(kind)) return "mail";
        return "notice";
    }

    public static String key(Item it) {
        if (it == null) return "";
        if ("mail".equals(it.source)) return "mail|" + (it.id == null || it.id.isEmpty() ? it.title : it.id);
        String hw = homeworkId(it.title);
        if (!hw.isEmpty() && ("elearning".equals(it.source) || isAssignment(it.kind)))
            return "hw|" + hw;
        if ("asg".equals(kindGroup(it.kind)))
            return it.source + "|asg|" + noticeStem(it.title);
        return "notice|" + noticeStem(it.title);
    }

    public static boolean sameNotice(Item a, Item b) {
        if (a == null || b == null) return false;
        if ("mail".equals(a.source) || "mail".equals(b.source)) return false;
        String ha = homeworkId(a.title), hb = homeworkId(b.title);
        if (!ha.isEmpty() && ha.equals(hb)) return true;
        String ga = kindGroup(a.kind), gb = kindGroup(b.kind);
        boolean aAsg = "asg".equals(ga);
        boolean bAsg = "asg".equals(gb);
        if (aAsg != bAsg) return false;
        if (aAsg) return noticeStem(a.title).equals(noticeStem(b.title)) && a.source.equals(b.source);
        String sa = noticeStem(a.title), sb = noticeStem(b.title);
        if (sa.isEmpty() || sb.isEmpty()) return false;
        if (sa.equals(sb)) return true;
        if (sa.length() >= 12 && sb.length() >= 12 && (sa.startsWith(sb) || sb.startsWith(sa))) return true;
        return false;
    }

    public static int courseScore(String course) {
        if (course == null || course.isEmpty()) return 0;
        String t = course.trim();
        if (t.matches("(?i)announcement|discussion|message|assignment")) return 0;
        if (t.matches("(?i)course_\\d+")) return 1;
        int han = 0;
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) >= 0x4e00 && t.charAt(i) <= 0x9fff) han++;
        return 3 + Math.min(24, t.length()) + han;
    }

    public static int urlScore(String url) {
        if (url == null) return 0;
        if (url.contains("/assignments/")) return 5;
        if (url.contains("/discussion_topics/")) return 4;
        if (url.contains("/quizzes/")) return 4;
        if (url.contains("/conversations/")) return 3;
        if (url.contains("/calendar_events/")) return 2;
        if (url.contains("elearning.fudan.edu.cn/") && url.length() > 40) return 1;
        return 0;
    }

    public static boolean better(Item neu, Item old) {
        if (old == null) return true;
        if (neu == null) return false;
        boolean nw = worthlessSummary(neu.summary), ow = worthlessSummary(old.summary);
        if (nw != ow) return !nw;
        boolean nsame = cleanTitle(neu.summary).equals(cleanTitle(neu.title));
        boolean osame = cleanTitle(old.summary).equals(cleanTitle(old.title));
        if (nsame != osame) return !nsame;
        int ns = neu.summary == null ? 0 : neu.summary.length();
        int os = old.summary == null ? 0 : old.summary.length();
        if (ns != os) return ns > os;
        int nu = urlScore(neu.url), ou = urlScore(old.url);
        if (nu != ou) return nu > ou;
        boolean nd = neu.dueAt != null && !neu.dueAt.isEmpty();
        boolean od = old.dueAt != null && !old.dueAt.isEmpty();
        if (nd != od) return nd;
        if (isAssignment(neu.kind) != isAssignment(old.kind)) return isAssignment(neu.kind);
        return courseScore(neu.course) > courseScore(old.course);
    }

    public static Item combine(Item a, Item b) {
        Item keep = better(a, b) ? a.copy() : b.copy();
        Item other = keep.id.equals(a.id) && keep.summary.equals(a.summary) ? b : a;
        if (worthlessSummary(keep.summary) && !worthlessSummary(other.summary)) keep.summary = other.summary;
        if (cleanTitle(keep.summary).equals(cleanTitle(keep.title)) && !worthlessSummary(other.summary)
                && !cleanTitle(other.summary).equals(cleanTitle(other.title)))
            keep.summary = other.summary;
        if ((keep.course == null || keep.course.isEmpty() || courseScore(keep.course) < courseScore(other.course))
                && other.course != null && !other.course.isEmpty()) keep.course = other.course;
        if ((keep.dueAt == null || keep.dueAt.isEmpty()) && other.dueAt != null && !other.dueAt.isEmpty())
            keep.dueAt = other.dueAt;
        if (urlScore(other.url) > urlScore(keep.url)) keep.url = other.url;
        if (other.receivedAt > keep.receivedAt) keep.receivedAt = other.receivedAt;
        if (other.unread) keep.unread = true;
        if (isAssignment(other.kind) && !isAssignment(keep.kind)) keep.kind = other.kind;
        return keep;
    }

    public static List<Item> collapse(List<Item> in) {
        LinkedHashMap<String, Item> best = new LinkedHashMap<String, Item>();
        if (in == null) return new ArrayList<Item>();
        for (Item it : in) {
            if (it == null) continue;
            it.title = cleanTitle(it.title);
            it.summary = cleanTitle(it.summary);
            if (it.title.isEmpty()) continue;
            String k = key(it);
            Item old = best.get(k);
            if (old == null) best.put(k, it.copy());
            else best.put(k, combine(it, old));
        }
        List<Item> first = new ArrayList<Item>(best.values());
        List<Item> out = new ArrayList<Item>();
        for (Item it : first) {
            boolean merged = false;
            for (int i = 0; i < out.size(); i++) {
                if (sameNotice(it, out.get(i))) {
                    out.set(i, combine(it, out.get(i)));
                    merged = true;
                    break;
                }
            }
            if (!merged) out.add(it);
        }
        return out;
    }

    /** Remaining time for assignments. Empty string if no due date. */
    public static String dueLabel(long dueAt, long now) {
        if (dueAt <= 0) return "";
        long ms = dueAt - now;
        if (ms <= 0) return "已逾期";
        long min = (ms + 59999) / 60000;
        if (min < 60) return "还剩 " + Math.max(1, min) + " 分钟";
        long hour = (ms + 3599999) / 3600000;
        if (hour < 24) return "还剩 " + hour + " 小时";
        long day = (ms + 86399999) / 86400000;
        return "还剩 " + day + " 天";
    }

    /** Upcoming due: still in the future and within 7 days. Overdue is not "即将到期". */
    public static boolean dueSoon(long dueAt, long now) {
        if (dueAt <= 0) return false;
        long d = dueAt - now;
        return d > 0 && d <= WEEK_MS;
    }

    public static boolean dueOverdue(long dueAt, long now) {
        return dueAt > 0 && dueAt < now;
    }

    public static boolean isAssignment(String kind) {
        return "ddl".equals(kind) || "exam".equals(kind) || "todo".equals(kind);
    }
}
