package cn.fudan.danzhi.core;

import java.util.concurrent.atomic.AtomicBoolean;

/** Serializes native session mutations and guarantees recovery after a recoverable/linkage failure. */
public final class TaskGate {
    public interface Work<T>{T run() throws Exception;}
    public static final class Result<T>{
        public final T value; public final Throwable error;public final boolean busy;
        Result(T v,Throwable e,boolean b){value=v;error=e;busy=b;}
        public boolean ok(){return !busy&&error==null;}
    }
    private final AtomicBoolean active=new AtomicBoolean();
    public <T> Result<T> run(Work<T> work){ return run(work,0); }
    /** waitMs=0 rejects immediately when busy; otherwise wait up to waitMs for the lock. */
    public <T> Result<T> run(Work<T> work, long waitMs){
        long deadline=System.nanoTime()+Math.max(0,waitMs)*1_000_000L;
        while(true){
            if(active.compareAndSet(false,true)){
                try {return new Result<>(work.run(),null,false);}
                catch(VirtualMachineError e){throw e;}
                catch(ThreadDeath e){throw e;}
                catch(Throwable e){return new Result<>(null,e,false);}
                finally {
                    active.set(false);
                    synchronized(this){ notifyAll(); }
                }
            }
            if(waitMs<=0)return new Result<>(null,null,true);
            long remaining=deadline-System.nanoTime();
            if(remaining<=0)return new Result<>(null,null,true);
            synchronized(this){
                if(active.get()){
                    try { wait(Math.max(1L, remaining/1_000_000L)); }
                    catch(InterruptedException e){ Thread.currentThread().interrupt(); return new Result<>(null,e,false); }
                }
            }
        }
    }
}
