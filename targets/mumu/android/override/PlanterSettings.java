package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.SharedPreferences;

/** Desired setting survives missing native samples. Native still validates every operation. */
final class PlanterSettings {
    private static final java.util.concurrent.atomic.AtomicBoolean started=new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.ScheduledExecutorService loop =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("planter_desired_v1",0);}
    static String desired(Context c){return prefs(c).getString("mode","off");}
    static int steps(Context c,int fallback){return prefs(c).getInt("steps",fallback);}
    static synchronized void chooseSteps(Context c,int mask)throws Exception {
        PlanterSteps.encode(mask);
        if(!prefs(c).edit().putInt("steps",mask).commit())throw new java.io.IOException("補種種類儲存失敗");
    }
    static synchronized void choose(Context c,String mode)throws Exception {
        if(!mode.equals("auto")&&!mode.equals("pluck")&&!mode.equals("off"))throw new IllegalArgumentException("育苗模式不符");
        if(!prefs(c).edit().putString("mode",mode).commit())throw new java.io.IOException("育苗設定儲存失敗");
    }
    static String message(Context c){return prefs(c).getString("message","自動育苗預設關閉");}
    static void start(Context c){
        if(!started.compareAndSet(false,true))return;final Context app=c.getApplicationContext();
        loop.scheduleWithFixedDelay(()->reconcile(app),0,15,java.util.concurrent.TimeUnit.SECONDS);
    }
    static synchronized void reconcile(Context c){
        if(EmulatorSavedDailyRuntime.managed(c))return;
        try {
            {
                if(EmulatorDailySupportWindow.planter(c,desired(c),false).equals("off")&&!CoreUpdater.running&&!SetupDeployment.pending(c)){
                    PlanterGateway.setMode("off");prefs(c).edit().putString("message","自動育苗已關閉").apply();return;
                }
                if(CoreInstallWizard.preparing(c)||CoreUpdater.running||SetupDeployment.pending(c)||UpdateInstaller.pending(c)
                        ||WorkStatus.stoppingAll||WorkStatus.blocked(c))throw new IllegalStateException("工作停止或核心處理中，保留育苗設定");
                if(CoreUpdater.pending(c))CoreUpdater.review(c);
                String wanted=EmulatorDailySupportWindow.planter(c,desired(c),false);
                if(!wanted.equals("off"))CoreUpdater.requireFiles(c,CoreUpdater.pair(c));

                // Rewriting also repairs a root-only control file without touching the native ledger.
                synchronized(GpsWorkLock.class){
                    if(CoreInstallWizard.preparing(c)||CoreUpdater.pending(c)||SetupDeployment.pending(c)||UpdateInstaller.pending(c)
                            ||WorkStatus.stoppingAll||WorkStatus.blocked(c))throw new IllegalStateException("核心或工作狀態已變動，稍後重新套用");
                    PlanterGateway.setMode(EmulatorDailySupportWindow.planter(c,wanted,true));
                    if(prefs(c).contains("steps"))PlanterGateway.setSteps(prefs(c).getInt("steps",PlanterSteps.DEFAULT));
                }
                PlanterState state=PlanterGateway.read();
                String result=wanted.equals("off")?"自動育苗已關閉":state.fresh?"育苗已開啟 · "+state.status:"育苗已開啟，等待遊戲回報";
                // Continuous native observation owns anomaly recovery.
                prefs(c).edit().putString("message",result).apply();
            }
        }catch(Exception e){prefs(c).edit().putString("message","設定已保存，等待條件具備後自動重試："+e.getMessage()).apply();}
    }
}
