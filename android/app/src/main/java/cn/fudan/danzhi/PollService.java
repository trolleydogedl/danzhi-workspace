package cn.fudan.danzhi;

import android.app.job.*;
import android.content.*;
import org.json.*;

/** Explicitly opted-in periodic job; never re-enters password/MFA login from background. */
public class PollService extends JobService {
    private static final int JOB_ID=17001;
    private volatile Thread worker;
    static void schedule(Context context) {
        SharedPreferences sp=context.getSharedPreferences("danzhi",Context.MODE_PRIVATE);
        if(!sp.getBoolean("backgroundEnabled",false))return;
        long period=Math.max(15,sp.getInt("intervalMin",15))*60_000L;
        JobInfo job=new JobInfo.Builder(JOB_ID,new ComponentName(context,PollService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(period).setPersisted(true).build();
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        if(scheduler==null||scheduler.schedule(job)!=JobScheduler.RESULT_SUCCESS)throw new IllegalStateException("系统未接受后台巡检任务");
    }
    static void cancel(Context context){JobScheduler scheduler=context.getSystemService(JobScheduler.class);if(scheduler!=null)scheduler.cancel(JOB_ID);}
    @Override public boolean onStartJob(JobParameters params) {
        SharedPreferences sp=getSharedPreferences("danzhi",MODE_PRIVATE);
        if(!sp.getBoolean("backgroundEnabled",false)||!sp.getBoolean("hasSession",false))return false;
        if(worker!=null&&worker.isAlive())return false;
        worker=new Thread(()->{
            try {
                JSONObject r=SessionCoordinator.run(this,client->{
                    JSONObject result=client.poll();SessionCoordinator.save(this,client,true);return result;
                });
                if("error".equals(r.optString("status"))){
                    int n=sp.getInt("backgroundFailures",0)+1;sp.edit().putInt("backgroundFailures",n).putString("backgroundError",r.optString("message")).putLong("lastBackgroundPollAt",System.currentTimeMillis()).apply();
                    return;
                }
                sp.edit().putInt("backgroundFailures",0).remove("backgroundError").putString("lastPoll",r.toString()).putLong("lastBackgroundPollAt",System.currentTimeMillis()).apply();
                KeepAliveService.notifyFresh(this,sp,r);
            }catch(Exception | LinkageError e){
                sp.edit().putString("backgroundError",Diagnostics.message(e)).apply();
            }finally{jobFinished(params,false);}
        },"danzhi-periodic");worker.start();return true;
    }
    @Override public boolean onStopJob(JobParameters params){Thread w=worker;if(w!=null)w.interrupt();return false;}
}
