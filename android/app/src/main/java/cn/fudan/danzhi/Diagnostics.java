package cn.fudan.danzhi;

import cn.fudan.danzhi.core.WebFlow;
import org.json.JSONObject;

final class Diagnostics {
    private Diagnostics() {}
    static String message(Throwable e) {
        String category = e instanceof WebFlow.FlowException ? ((WebFlow.FlowException)e).category
            : e instanceof LinkageError ? "RUNTIME_INCOMPATIBLE" : e.getClass().getSimpleName();
        String text=e.getMessage()==null?"操作失败":e.getMessage();
        text=text.replaceAll("https?://[^\\s<>\"']+", "[URL 已脱敏]")
            .replaceAll("(?i)(password|passwd|otpCode|loginToken|ticket|lck|cookie|authorization)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]");
        if(text.startsWith(category+":")) text=text.substring(category.length()+1).trim();
        String out=category + ": " + text;
        return out.length()>240?out.substring(0,240):out;
    }
    static JSONObject error(Throwable e) {
        JSONObject out=new JSONObject();
        try {out.put("status","error").put("message",message(e));}catch(Exception ignored){}
        return out;
    }
}
