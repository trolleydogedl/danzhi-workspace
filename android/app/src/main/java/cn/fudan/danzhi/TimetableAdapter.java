package cn.fudan.danzhi;

import cn.fudan.danzhi.core.*;
import org.json.*;
import java.util.*;
import java.util.regex.*;

/** Recognizes current lessons and legacy activities; unknown data is an error, not an empty term. */
final class TimetableAdapter {
    private TimetableAdapter() {}
    static JSONArray fetch(CookieJar jar,Http.Resp home) throws Exception {
        String html=home.body;
        String semester=extractSemester(html),student=TimetableText.studentIdFrom(home.url, html);
        if(student.isEmpty()) student=field(html,"studentId");
        JSONArray last=new JSONArray();
        Exception lastErr=null;
        if(!semester.isEmpty()) {
            // DanXi uses print-data without extra query; get-data 400s if Accept/params mismatch.
            java.util.ArrayList<String> urls=new java.util.ArrayList<>();
            urls.add(FudanClient.FDJWGL+"/student/for-std/course-table/semester/"+semester+"/print-data");
            urls.add(FudanClient.FDJWGL+"/student/for-std/course-table/get-data?semesterId="+WebFlow.encode(semester)
                    +(student.isEmpty()?"":"&studentId="+WebFlow.encode(student)));
            urls.add(FudanClient.FDJWGL+"/student/for-std/course-table/get-data?bizTypeId=2&semesterId="+WebFlow.encode(semester)
                    +(student.isEmpty()?"":"&studentId="+WebFlow.encode(student)));
            if(!student.isEmpty()){
                urls.add(FudanClient.FDJWGL+"/student/for-std/course-table/semester/"+semester+"/print-data?studentId="+WebFlow.encode(student));
            }
            for(String url:urls) {
                try {
                    Http.Resp data=Http.fetch(jar,url,"GET",null,null,15000,home.url);
                    if(data.code==400||data.code==401||data.code==403){
                        lastErr=new WebFlow.FlowException("HTTP_ERROR","HTTP "+data.code);
                        continue;
                    }
                    if(data.code<200||data.code>=400){
                        lastErr=new WebFlow.FlowException("HTTP_ERROR","HTTP "+data.code);
                        continue;
                    }
                    JSONObject json=tryObject(data.body);
                    if(json==null) json=findLessonsObject(data.body);
                    if(json==null) continue;
                    JSONArray parsed=parse(json);
                    if(parsed.length()>0)return parsed;
                    last=parsed;
                } catch(Exception e){ lastErr=e; }
            }
            String endpoint=lessonEndpoint(html);
            if(endpoint.isEmpty()) {
                Matcher scripts=Pattern.compile("(?is)<script\\b([^>]*)>").matcher(html);int fetched=0;
                while(scripts.find()&&fetched<3&&endpoint.isEmpty()) {
                    String src=PageParser.attr(scripts.group(1),"src");if(src.isEmpty())continue;
                    String abs=WebFlow.resolve(home.url,src);if(!WebFlow.host(abs).equals("fdjwgl.fudan.edu.cn"))continue;
                    if(!src.toLowerCase(Locale.ROOT).matches(".*(course.?table|timetable).*"))continue;
                    fetched++;Http.Resp script=Http.follow(jar,abs);WebFlow.requireHttpSuccess(script.flow());endpoint=lessonEndpoint(script.body);
                }
            }
            if(!endpoint.isEmpty()&&!student.isEmpty()) {
                String target=WebFlow.resolve(home.url,endpoint);
                if(!WebFlow.host(target).equals("fdjwgl.fudan.edu.cn"))throw new WebFlow.FlowException("UNTRUSTED_REDIRECT","课表数据地址不在教务系统");
                int q=target.indexOf('?');if(q>=0)target=target.substring(0,q);
                Http.Resp data=Http.follow(jar,target+"?semesterId="+WebFlow.encode(semester)+"&studentId="+WebFlow.encode(student));
                WebFlow.requireHttpSuccess(data.flow());
                JSONObject json=asObject(data.body);
                JSONArray parsed=parse(json);
                if(parsed.length()>0)return parsed;
                last=parsed;
            }
        }
        try {
            JSONObject embed=findLessonsObject(html);
            if(embed!=null){
                JSONArray parsed=parse(embed);
                if(parsed.length()>0)return parsed;
                last=parsed;
            }
        } catch(Exception ignored) {}
        if(last.length()>0)return last;
        if(semester.isEmpty())throw new WebFlow.FlowException("SCHEMA_UNVERIFIED",
            "课表页未提供当前 semesterId；未猜测学期");
        if(lastErr instanceof WebFlow.FlowException && !"HTTP_ERROR".equals(((WebFlow.FlowException)lastErr).category))
            throw lastErr;
        throw new WebFlow.FlowException("SCHEMA_UNVERIFIED",
            lastErr==null?"课表接口未返回 lessons / activities":("课表数据未取到："+lastErr.getMessage()));
    }
    static String extractSemester(String html) {
        return TimetableText.semesterIdFromHtml(html);
    }
    static String field(String html,String name) {
        Matcher m=Pattern.compile("(?:[\"']?"+name+"[\"']?)\\s*[:=]\\s*[\"']?(\\d+)").matcher(html==null?"":html);
        return m.find()?m.group(1):"";
    }
    static String lessonEndpoint(String html) {
        Matcher m=Pattern.compile("[\"']([^\"'\\s]*getLesson(?:\\?[^\"']*)?)[\"']").matcher(html==null?"":html);
        return m.find()?m.group(1):"";
    }
    static JSONObject asObject(String body) throws Exception {
        JSONObject o=tryObject(body);
        if(o==null)throw new WebFlow.FlowException("SCHEMA_UNVERIFIED","课表接口未返回 JSON 对象");
        return o;
    }
    static JSONObject tryObject(String body) {
        if(body==null)return null;
        String t=body.trim();
        if(t.startsWith("while(1);")) t=t.substring("while(1);".length()).trim();
        if(!t.startsWith("{"))return null;
        try { return new JSONObject(t); } catch(Exception e){ return null; }
    }
    static JSONObject findLessonsObject(String html) {
        if(html==null||html.isEmpty())return null;
        JSONObject direct=tryObject(html);
        if(direct!=null && (direct.has("lessons")||direct.has("activities")||direct.has("studentTableVms")||direct.has("data")))
            return direct;
        int idx=html.indexOf("\"lessons\"");
        if(idx<0) idx=html.indexOf("\"activities\"");
        if(idx<0) idx=html.indexOf("\"studentTableVms\"");
        if(idx<0) return null;
        int start=html.lastIndexOf('{', idx);
        if(start<0) return null;
        int depth=0;
        for(int i=start;i<html.length() && i<start+800000;i++){
            char c=html.charAt(i);
            if(c=='{') depth++;
            else if(c=='}'){
                depth--;
                if(depth==0){
                    return tryObject(html.substring(start,i+1));
                }
            }
        }
        return null;
    }
    static JSONArray parse(JSONObject json) throws Exception {
        if(json==null)throw new WebFlow.FlowException("SCHEMA_UNVERIFIED","空课表响应");
        if(json.has("error")||json.has("errors")||"unauthenticated".equals(json.optString("status")))
            throw new WebFlow.FlowException("API_ERROR","课表接口返回错误对象");
        JSONObject data=json.optJSONObject("data");if(data!=null)json=data;
        JSONArray out=new JSONArray(),lessons=json.optJSONArray("lessons");
        if(lessons!=null && lessons.length()>0) {
            for(int i=0;i<lessons.length();i++) {
                JSONObject lesson=lessons.optJSONObject(i);
                if(lesson==null)throw new WebFlow.FlowException("SCHEMA_UNVERIFIED","lessons 内含无效元素");
                JSONObject course=lesson.optJSONObject("course"),schedule=lesson.optJSONObject("scheduleText");
                if(course==null||course.optString("nameZh").isEmpty()||schedule==null)
                    throw new WebFlow.FlowException("SCHEMA_UNVERIFIED","lessons 缺少课程名或 scheduleText");
                JSONObject text=schedule.optJSONObject("dateTimePlacePersonText");
                if(text==null||!text.has("textZh"))throw new WebFlow.FlowException("SCHEMA_UNVERIFIED","scheduleText 缺少 textZh");
                for(TimetableText.Lesson l:TimetableText.parse(course.optString("nameZh"),text.optString("textZh"))) {
                    JSONArray weeks=new JSONArray();for(Integer w:l.weeks)weeks.put(w);
                    out.put(new JSONObject().put("name",l.name).put("teacher",l.teacher).put("location",l.location)
                        .put("day",l.day).put("startPeriod",l.startPeriod).put("endPeriod",l.endPeriod).put("weeksList",weeks));
                }
            }
            return mergeSlots(out);
        }
        boolean recognized=legacy(json,out);
        if(!recognized)throw new WebFlow.FlowException("SCHEMA_UNVERIFIED","课表既无 lessons，也无 activities");
        return mergeSlots(out);
    }
    static JSONArray mergeSlots(JSONArray in) throws Exception {
        if(in==null)return new JSONArray();
        LinkedHashMap<String,JSONObject> map=new LinkedHashMap<>();
        for(int i=0;i<in.length();i++){
            JSONObject c=in.optJSONObject(i);if(c==null)continue;
            String key=c.optString("name")+"|"+c.optInt("day")+"|"+c.optInt("startPeriod")+"|"+c.optInt("endPeriod");
            JSONObject cur=map.get(key);
            if(cur==null){map.put(key,new JSONObject(c.toString()));continue;}
            String a=cur.optString("teacher"), b=c.optString("teacher");
            if(!b.isEmpty()&&!a.contains(b)) cur.put("teacher", a.isEmpty()?b:a+"、"+b);
            String loc=c.optString("location");
            if(!loc.isEmpty()&&!cur.optString("location").contains(loc)){
                String prev=cur.optString("location");
                cur.put("location", prev.isEmpty()?loc:prev+" / "+loc);
            }
            JSONArray weeks=cur.optJSONArray("weeksList");if(weeks==null)weeks=new JSONArray();
            JSONArray extra=c.optJSONArray("weeksList");
            Set<Integer> seen=new TreeSet<>();
            for(int k=0;k<weeks.length();k++) seen.add(weeks.optInt(k));
            if(extra!=null)for(int k=0;k<extra.length();k++) seen.add(extra.optInt(k));
            JSONArray merged=new JSONArray();for(int w:seen)merged.put(w);
            cur.put("weeksList", merged);
        }
        JSONArray out=new JSONArray();
        for(JSONObject c:map.values()) out.put(c);
        return out;
    }
    private static boolean legacy(JSONObject json,JSONArray out)throws Exception {
        boolean recognized=false;JSONArray a=json.optJSONArray("activities");
        if(a!=null){recognized=true;for(int i=0;i<a.length();i++){
            JSONObject v=a.optJSONObject(i);if(v==null)throw new WebFlow.FlowException("SCHEMA_UNVERIFIED","activities 含无效项");
            int day=v.optInt("weekday",v.optInt("weekDay",v.optInt("dayOfWeek",-1)));if(day==0)day=7;int start=v.optInt("startUnit",v.optInt("start",-1)),end=v.optInt("endUnit",v.optInt("end",start));
            String name=v.optString("courseName",v.optString("name",v.optString("lessonName")));
            if(name.isEmpty()||day<1||day>7||start<1||end<start||end>20)throw new WebFlow.FlowException("SCHEMA_UNVERIFIED","旧版课表时间字段无效");
            JSONArray weeks=v.optJSONArray("weekIndexes");if(weeks==null)weeks=v.optJSONArray("weeks");
            out.put(new JSONObject().put("name",name).put("teacher",joinTeachers(v.opt("teachers")))
                .put("location",v.optString("room",v.optString("roomName",v.optString("place"))))
                .put("day",day).put("startPeriod",start).put("endPeriod",end).put("weeksList",weeks));
        }}
        JSONArray tables=json.optJSONArray("studentTableVms");if(tables!=null){recognized=true;for(int i=0;i<tables.length();i++){
            JSONObject t=tables.optJSONObject(i);if(t==null||!legacy(t,out))throw new WebFlow.FlowException("SCHEMA_UNVERIFIED","studentTableVms 缺少 activities");
        }}return recognized;
    }
    private static String joinTeachers(Object raw) {
        if(raw==null)return "";
        if(raw instanceof JSONArray){
            JSONArray a=(JSONArray)raw;StringBuilder sb=new StringBuilder();
            for(int i=0;i<a.length();i++){
                Object o=a.opt(i);String n="";
                if(o instanceof JSONObject)n=((JSONObject)o).optString("name",((JSONObject)o).optString("teacherName"));
                else if(o!=null)n=String.valueOf(o);
                if(n.isEmpty()||"null".equals(n))continue;
                if(sb.length()>0)sb.append("、");sb.append(n);
            }
            return sb.toString();
        }
        if(raw instanceof JSONObject)return ((JSONObject)raw).optString("name");
        String s=String.valueOf(raw);return "null".equals(s)?"":s;
    }
}
