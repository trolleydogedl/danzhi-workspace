package cn.fudan.danzhi.core;

/** Server challenge remains native and is reused by step two; no automatic OTP generation. */
public final class MfaState {
    public static final class Challenge {
        public final String username,chain,entityId,requestType,lck,requestNumber,referer,module;
        final long expiresNanos;
        public Challenge(String u,String c,String e,String t,String l,String n,String r,String m,long now) {
            username=u;chain=c;entityId=e;requestType=t;lck=l;requestNumber=n;referer=r;
            module=m==null||m.isEmpty()?"userAndOtp":m;expiresNanos=now+300_000_000_000L;
        }
    }
    private Challenge pending;
    public synchronized void set(Challenge c){pending=c;}
    public synchronized void clear(){pending=null;}
    public synchronized Challenge require(String username,String otp,long now) throws WebFlow.FlowException {
        if(otp==null||!otp.matches("[0-9]{6}"))throw new WebFlow.FlowException("OTP_INVALID","动态口令必须为 6 位数字");
        if(pending==null||now-pending.expiresNanos>=0){pending=null;throw new WebFlow.FlowException("MFA_EXPIRED","二次认证会话已过期，请重新输入学号密码");}
        if(!pending.username.equals(username))throw new WebFlow.FlowException("MFA_ACCOUNT_CHANGED","二次认证期间账号发生变化，请重新开始");
        return pending;
    }
}
