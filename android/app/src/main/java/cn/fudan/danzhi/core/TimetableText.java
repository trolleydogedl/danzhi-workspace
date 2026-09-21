package cn.fudan.danzhi.core;

import java.util.*;
import java.util.regex.*;

/** Parses scheduleText.dateTimePlacePersonText.textZh. Does not invent a weekday or period on failure. */
public final class TimetableText {
    public static final class Lesson {
        public String name,location,teacher;
        public int day,startPeriod,endPeriod;
        public final SortedSet<Integer> weeks=new TreeSet<>();
    }
    private static final Pattern ROW=Pattern.compile("^(.*?)\\s*(?:星期|周)([一二三四五六日天])\\s*(\\d{1,2})(?:\\s*[~～—–\\-至]\\s*(\\d{1,2}))?\\s*节\\s*(.*?)\\s*$");
    private TimetableText(){}
    public static List<Lesson> parse(String name,String text) {
        List<Lesson> out=new ArrayList<>();if(text==null||text.trim().isEmpty())return out;
        for(String row:text.replace("\r","").split("[;；\\n]+")){
            row=row.trim();if(row.isEmpty())continue;
            Matcher m=ROW.matcher(row);
            if(!m.matches())throw new IllegalArgumentException("课表时间文本格式不支持；未将其误报为无课");
            Lesson l=new Lesson();l.name=name;
            int day="一二三四五六日天".indexOf(m.group(2))+1;l.day=Math.min(day,7);
            l.startPeriod=Integer.parseInt(m.group(3));l.endPeriod=m.group(4)==null?l.startPeriod:Integer.parseInt(m.group(4));
            if(l.startPeriod<1||l.endPeriod<l.startPeriod||l.endPeriod>20)throw new IllegalArgumentException("课表节次超出范围");
            String[] tail=m.group(5).trim().split("\\s+",2);
            l.location=tail.length>0?tail[0]:"";l.teacher=tail.length>1?tail[1]:"";
            l.weeks.addAll(weeks(m.group(1)));out.add(l);
        }
        List<Lesson> merged=merge(out);
        merged.sort(Comparator.comparingInt((Lesson l)->l.day).thenComparingInt(l->l.startPeriod));
        return merged;
    }
    /** Same course / weekday / periods taught by different teachers stay one row. */
    public static List<Lesson> merge(List<Lesson> in) {
        if(in==null||in.size()<=1)return in==null?new ArrayList<Lesson>():in;
        Map<String,Lesson> map=new LinkedHashMap<>();
        Map<String,List<String>> bits=new LinkedHashMap<>();
        for(Lesson l:in){
            String key=l.name+"|"+l.day+"|"+l.startPeriod+"|"+l.endPeriod;
            Lesson cur=map.get(key);
            if(cur==null){
                Lesson copy=copy(l);
                map.put(key,copy);
                List<String> list=new ArrayList<>();
                String bit=teacherBit(l);
                if(!bit.isEmpty())list.add(bit);
                bits.put(key,list);
                continue;
            }
            cur.weeks.addAll(l.weeks);
            if(l.location!=null&&!l.location.isEmpty()&&(cur.location==null||!cur.location.contains(l.location))){
                cur.location=(cur.location==null||cur.location.isEmpty())?l.location:cur.location+" / "+l.location;
            }
            String bit=teacherBit(l);
            List<String> list=bits.get(key);
            if(!bit.isEmpty()&&!list.contains(bit))list.add(bit);
        }
        List<Lesson> out=new ArrayList<>();
        for(Map.Entry<String,Lesson> e:map.entrySet()){
            Lesson l=e.getValue();
            List<String> list=bits.get(e.getKey());
            if(list!=null&&!list.isEmpty())l.teacher=join("、",list);
            out.add(l);
        }
        return out;
    }
    public static String weekRanges(SortedSet<Integer> weeks){
        if(weeks==null||weeks.isEmpty())return "";
        StringBuilder sb=new StringBuilder();
        Integer start=null,prev=null;
        for(int w:weeks){
            if(start==null){start=prev=w;continue;}
            if(w==prev+1){prev=w;continue;}
            appendRange(sb,start,prev);
            start=prev=w;
        }
        appendRange(sb,start,prev);
        return sb.toString();
    }
    private static void appendRange(StringBuilder sb,int start,int end){
        if(sb.length()>0)sb.append("、");
        if(start==end)sb.append(start);else sb.append(start).append("-").append(end);
    }
    private static String teacherBit(Lesson l){
        String name=l.teacher==null?"":l.teacher.trim();
        String wr=weekRanges(l.weeks);
        if(name.isEmpty())return "";
        if(wr.isEmpty())return name;
        return name+"（"+wr+"周）";
    }
    private static Lesson copy(Lesson l){
        Lesson c=new Lesson();
        c.name=l.name;c.location=l.location==null?"":l.location;c.teacher=l.teacher==null?"":l.teacher;
        c.day=l.day;c.startPeriod=l.startPeriod;c.endPeriod=l.endPeriod;c.weeks.addAll(l.weeks);
        return c;
    }
    private static String join(String sep,List<String> parts){
        StringBuilder sb=new StringBuilder();
        for(String p:parts){if(sb.length()>0)sb.append(sep);sb.append(p);}
        return sb.toString();
    }
    public static SortedSet<Integer> weeks(String raw){
        SortedSet<Integer> out=new TreeSet<>();String s=raw==null?"":raw;
        boolean odd=s.contains("单"),even=s.contains("双");
        s=s.replaceAll("[第周()（）单双\\s]","");
        if(s.isEmpty())return out;
        for(String part:s.split("[,，、]")){
            Matcher m=Pattern.compile("^(\\d{1,2})(?:[~～—–\\-至](\\d{1,2}))?$").matcher(part);
            if(!m.matches())throw new IllegalArgumentException("课表周次格式不支持");
            int a=Integer.parseInt(m.group(1)),b=m.group(2)==null?a:Integer.parseInt(m.group(2));
            if(a<1||b<a||b>60)throw new IllegalArgumentException("课表周次超出范围");
            for(int w=a;w<=b;w++)if((!odd||w%2==1)&&(!even||w%2==0))out.add(w);
        }
        return out;
    }
    /** Same rules as DanXi: selected #allSemesters option, then JSON.parse, never invent an id. */
    public static String semesterIdFromHtml(String html) {
        if(html==null||html.isEmpty())return "";
        Matcher select=Pattern.compile("(?is)<select\\b[^>]*id=['\"]allSemesters['\"][^>]*>(.*?)</select>").matcher(html);
        if(select.find()) {
            Matcher options=Pattern.compile("(?is)<option\\b([^>]*)>").matcher(select.group(1));
            String first="";
            while(options.find()){
                String v=PageParser.attr(options.group(1),"value");
                if(v.matches("\\d+")&&first.isEmpty())first=v;
                if(Pattern.compile("(?i)(?:^|\\s)selected(?:\\s|=|$)").matcher(options.group(1)).find()&&v.matches("\\d+"))return v;
            }
            if(!first.isEmpty())return first;
        }
        Matcher field=Pattern.compile("(?:[\"']?semesterId[\"']?)\\s*[:=]\\s*[\"']?(\\d+)").matcher(html);
        if(field.find())return field.group(1);
        Matcher raw=Pattern.compile("var\\s+semesters\\s*=\\s*JSON\\.parse\\(([\\s\\S]*?)\\);").matcher(html);
        if(raw.find()){
            String blob=raw.group(1).replace("\\'","'").replace("\\\"","\"");
            Matcher idm=Pattern.compile("\"id\"\\s*:\\s*\"?(\\d+)").matcher(blob);
            String last="";
            while(idm.find()) last=idm.group(1);
            if(!last.isEmpty())return last;
        }
        Matcher print=Pattern.compile("/semester/(\\d+)/print-data").matcher(html);
        if(print.find())return print.group(1);
        Matcher getData=Pattern.compile("(?:get-data\\?|[&?])semesterId=(\\d+)").matcher(html);
        if(getData.find())return getData.group(1);
        return "";
    }
}
