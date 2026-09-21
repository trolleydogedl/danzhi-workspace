package cn.fudan.danzhi;

import cn.fudan.danzhi.core.FeedMerge;
import org.json.JSONArray;
import org.json.JSONObject;

final class Filter {
    static String cleanTitle(String raw) { return FeedMerge.cleanTitle(raw); }

    static boolean looksGarbage(String title) {
        if (title == null) return true;
        String t = title.trim();
        if (t.length() < 2 || t.length() > 240) return true;
        if (t.contains("{") || t.contains("line-height") || t.contains(".right_")) return true;
        if (t.matches("(?i).*\\b(css|nbsp)\\b.*")) return true;
        int han = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) han++;
        }
        return han == 0 && t.length() < 12;
    }

    static boolean dropNews(String title, String summary) {
        String text = (title + "\n" + (summary == null ? "" : summary));
        if (looksGarbage(title)) return true;
        return text.matches("(?s).*(讣告|悼念|逝世|追悼会|遗体告别|治丧|哀告|千古).*")
                || text.matches("(?s).*(评教|教学评估|评教系统|网上评教).*")
                || text.matches("(?s).*(招聘|评聘|招募|人才引进|岗位聘任|用工招聘|实习生招聘|主任招聘|职务聘任).*");
    }

    static boolean drop(String title, String summary) { return dropNews(title, summary); }

    static boolean protectedSource(String source) {
        return "elearning".equals(source) || "mail".equals(source) || "ehall".equals(source)
                || "ecard".equals(source) || "timetable".equals(source);
    }

    static long when(JSONObject it) {
        if (it == null) return 0;
        long r = it.optLong("receivedAt", 0);
        if (r > 1_000_000_000_000L) return r;
        if (r > 1_000_000_000L) return r * 1000L;
        long p = parseTime(it.optString("publishedAt"));
        if (p > 0) return p;
        return 0;
    }

    static long parseTime(String raw) {
        if (raw == null || raw.isEmpty() || "null".equals(raw)) return 0;
        try {
            if (raw.matches("\\d{13}")) return Long.parseLong(raw);
            if (raw.matches("\\d{10}")) return Long.parseLong(raw) * 1000L;
            String s = raw.trim();
            if (s.matches("20\\d{2}-\\d{2}-\\d{2}$")) s = s + "T00:00:00+08:00";
            javax.xml.datatype.DatatypeFactory f = javax.xml.datatype.DatatypeFactory.newInstance();
            try {
                return f.newXMLGregorianCalendar(s).toGregorianCalendar().getTimeInMillis();
            } catch (IllegalArgumentException ignored) {
            }
            java.text.SimpleDateFormat[] fmts = {
                    iso("yyyy-MM-dd'T'HH:mm:ss.SSSXXX"),
                    iso("yyyy-MM-dd'T'HH:mm:ssXXX"),
                    iso("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"),
                    iso("yyyy-MM-dd'T'HH:mm:ss'Z'"),
                    shanghai("yyyy-MM-dd HH:mm:ss"),
            };
            for (java.text.SimpleDateFormat fmt : fmts) {
                try { return fmt.parse(raw).getTime(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static java.text.SimpleDateFormat iso(String p) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(p, java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f;
    }

    private static java.text.SimpleDateFormat shanghai(String p) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(p, java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        return f;
    }

    static FeedMerge.Item asItem(JSONObject it) {
        FeedMerge.Item fi = new FeedMerge.Item();
        if (it == null) return fi;
        fi.id = it.optString("id");
        fi.source = it.optString("source");
        fi.kind = it.optString("kind");
        fi.title = it.optString("title");
        fi.summary = it.optString("summary");
        fi.url = it.optString("url");
        fi.course = it.optString("course");
        fi.dueAt = it.optString("dueAt");
        fi.receivedAt = when(it);
        fi.unread = it.optBoolean("unread", false);
        fi.trashed = it.optBoolean("trashed", false);
        return fi;
    }

    static JSONArray score(JSONArray items) {
        JSONArray out = new JSONArray();
        if (items == null) return out;
        java.util.LinkedHashMap<String, JSONObject> best = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, FeedMerge.Item> merged = new java.util.LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String title = cleanTitle(it.optString("title"));
            String summary = cleanTitle(it.optString("summary"));
            try {
                it.put("title", title);
                if (!summary.isEmpty()) it.put("summary", summary);
            } catch (Exception ignored) {}
            String source = it.optString("source");
            if (protectedSource(source)) {
                if (title.length() < 1) continue;
                if (title.contains("line-height") || title.contains(".right_")) continue;
            } else if (dropNews(title, summary)) {
                continue;
            }
            FeedMerge.Item fi = asItem(it);
            String k = FeedMerge.key(fi);
            FeedMerge.Item old = merged.get(k);
            if (old == null) {
                String slot = findNoticeSlot(merged, fi);
                if (slot != null) {
                    k = slot;
                    old = merged.get(k);
                }
            }
            if (old == null) {
                merged.put(k, fi);
                best.put(k, it);
            } else {
                FeedMerge.Item keep = FeedMerge.combine(fi, old);
                JSONObject pick = FeedMerge.better(fi, old) ? it : best.get(k);
                merged.put(k, keep);
                best.put(k, pick);
            }
        }
        java.util.List<JSONObject> list = new java.util.ArrayList<>();
        for (String k : best.keySet()) {
            JSONObject pick = best.get(k);
            FeedMerge.Item fi = merged.get(k);
            try {
                pick.put("title", fi.title);
                if (!FeedMerge.worthlessSummary(fi.summary)
                        && !FeedMerge.cleanTitle(fi.summary).equals(FeedMerge.cleanTitle(fi.title)))
                    pick.put("summary", fi.summary);
                else if (fi.course != null && !fi.course.isEmpty() && FeedMerge.courseScore(fi.course) > 2
                        && !FeedMerge.cleanTitle(fi.course).equals(FeedMerge.cleanTitle(fi.title)))
                    pick.put("summary", fi.course);
                else pick.put("summary", "");
                if (fi.course != null && !fi.course.isEmpty() && FeedMerge.courseScore(fi.course) > 2)
                    pick.put("course", fi.course);
                if (fi.url != null && !fi.url.isEmpty()) pick.put("url", fi.url);
                if (fi.dueAt != null && !fi.dueAt.isEmpty()) pick.put("dueAt", fi.dueAt);
                pick.put("filtered", false);
                String kind = pick.optString("kind");
                pick.put("priority", "ddl".equals(kind) || "exam".equals(kind) || "todo".equals(kind)
                        || "mail".equals(kind) ? "urgent" : "admin");
                long due = parseTime(pick.optString("dueAt"));
                long now = System.currentTimeMillis();
                if (FeedMerge.isAssignment(kind) && due > 0) {
                    pick.put("dueLabel", FeedMerge.dueLabel(due, now));
                    boolean soon = FeedMerge.dueSoon(due, now);
                    boolean overdue = FeedMerge.dueOverdue(due, now);
                    pick.put("dueSoon", soon);
                    pick.put("dueOverdue", overdue);
                    pick.put("dueUrgent", soon || overdue);
                    pick.put("dueAtMs", due);
                }
            } catch (Exception ignored) {}
            list.add(pick);
        }
        list.sort((a, b) -> Long.compare(when(b), when(a)));
        for (JSONObject it : list) out.put(it);
        return out;
    }

    static String findNoticeSlot(java.util.LinkedHashMap<String, FeedMerge.Item> merged, FeedMerge.Item neu) {
        if (neu == null || merged == null) return null;
        for (java.util.Map.Entry<String, FeedMerge.Item> e : merged.entrySet()) {
            if (FeedMerge.sameNotice(neu, e.getValue())) return e.getKey();
        }
        return null;
    }
}
