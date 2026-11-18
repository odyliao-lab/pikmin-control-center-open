package dev.ody.pikmincontrol;
import android.app.*;import android.content.*;import android.content.pm.*;import android.os.Build;import java.nio.file.*;
public final class InstallerCallbackChecks {
 static int checks;static boolean phone;
 static void check(boolean ok){checks++;if(!ok)throw new AssertionError("installer case "+checks);}
 static Intent result(int expected,int received,int status){return new Intent().putExtra("expected_session",expected).putExtra(PackageInstaller.EXTRA_SESSION_ID,received).putExtra(PackageInstaller.EXTRA_STATUS,status);}
 static UpdateInstallActivity deliver(Intent i){UpdateInstallActivity a=new UpdateInstallActivity();a.setIntent(i);a.onCreate(null);return a;}
 static void rejected(Context c)throws Exception{int created=Context.pm.installer.created;try{UpdateInstaller.begin(c);throw new AssertionError("admitted");}catch(IllegalStateException|java.io.IOException expected){}check(Context.pm.installer.created==created&&!UpdateInstaller.preparing);}
 public static void main(String[] args)throws Exception{
  phone=args[0].equals("phone");Context c=new Context();Path dir=Files.createTempDirectory("cc-installer-test");Context.directory=dir.toFile();Files.write(UpdateController.apk(c).toPath(),new byte[]{1,2,3});PackageInstaller pm=Context.pm.installer;
  try{
   for(int api:new int[]{28,31,32,33,34,35}){
    Build.VERSION.SDK_INT=api;PendingIntent p=UpdateInstaller.callback(c,731);
    check(p.intent.target==UpdateInstallActivity.class&&p.request==731&&p.intent.getIntExtra("expected_session",-1)==731);
    check(p.intent.action.equals("dev.ody.pikmincontrol.INSTALL_RESULT.731"));
    check((p.flags&PendingIntent.FLAG_UPDATE_CURRENT)!=0&&((p.flags&PendingIntent.FLAG_MUTABLE)!=0)==(api>=31));
    check(api>=34?p.options!=null&&p.options.creatorMode==ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED:p.options==null);
   }
   Build.VERSION.SDK_INT=35;UpdateInstaller.begin(c);int id=UpdateInstaller.session(c);
   check(id>0&&pm.committed!=null&&pm.committed.options.creatorMode==ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED&&UpdateStore.verifies==1);
   check(pm.params.userAction==(phone?PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED:PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
           &&pm.params.pkg.equals(UpdateInfo.PACKAGE));
   check(UpdateInstaller.pending(c)&&(!phone||UpdatePreparation.held));
   rejected(c);
   UpdateInstallActivity stale=deliver(result(id,id+1,-1));check(stale.finished&&stale.started.isEmpty()&&UpdateInstaller.session(c)==id);
   stale=deliver(result(id+1,id+1,0));check(stale.started.isEmpty()&&UpdateInstaller.session(c)==id);
   check(!UpdateStore.values.getBoolean("install_needs_action",false));
   Intent system=new Intent();UpdateInstallActivity confirm=deliver(result(id,id,-1).putExtra(Intent.EXTRA_INTENT,system));check(confirm.finished&&confirm.started.get(0)==system&&UpdateInstaller.pending(c));
   check(UpdateStore.values.getBoolean("install_needs_action",false)==phone);
   deliver(result(id,id,PackageInstaller.STATUS_FAILURE));check(!UpdateInstaller.pending(c)&&!UpdatePreparation.held&&(!phone||PhoneUpdateFlow.ended==1&&UpdatePreparation.ended==1));
   check(!UpdateStore.values.map.containsKey("install_needs_action"));
   UpdateInstaller.begin(c);id=UpdateInstaller.session(c);
   UpdateInstallActivity missing=deliver(result(id,id,-1));check(!UpdateInstaller.pending(c)&&!UpdatePreparation.held&&pm.abandoned==1&&missing.started.get(0).target==MainActivity.class);
   check(UpdateStore.values.map.get("install_result").toString().contains("無法開啟 Android"));
   UpdateInstaller.begin(c);id=UpdateInstaller.session(c);UpdateInstallActivity blocked=new UpdateInstallActivity();blocked.reject=system;blocked.onNewIntent(result(id,id,-1).putExtra(Intent.EXTRA_INTENT,system));check(!UpdateInstaller.pending(c)&&pm.abandoned==2);
   UpdateInstaller.begin(c);id=UpdateInstaller.session(c);int ended=PhoneUpdateFlow.ended;pm.abandonFails=true;deliver(result(id,id,-1));check(UpdateInstaller.pending(c)&&(!phone||UpdatePreparation.held&&PhoneUpdateFlow.ended==ended));pm.abandonFails=false;UpdateInstaller.cancel(c);check(!UpdateInstaller.pending(c)&&!UpdatePreparation.held);
   UpdateStore.reject=true;rejected(c);UpdateStore.reject=false;
   Context.pm.allowed=false;rejected(c);Context.pm.allowed=true;
   GpsWorkLock.blocked=true;rejected(c);GpsWorkLock.blocked=false;
   if(!phone){SetupDeployment.active=true;rejected(c);SetupDeployment.active=false;FeedingGateway.active=true;rejected(c);FeedingGateway.active=false;}
   for(int api:new int[]{28,30,31}){
    Build.VERSION.SDK_INT=api;UpdateInstaller.begin(c);id=UpdateInstaller.session(c);
    int action=api<31?0:phone?PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED:PackageInstaller.SessionParams.USER_ACTION_REQUIRED;
    check(pm.params.userAction==action);
    UpdateInstaller.cancel(c);check(!UpdateInstaller.pending(c));
   }
   Build.VERSION.SDK_INT=35;UpdateInstaller.begin(c);id=UpdateInstaller.session(c);
   UpdateInstallActivity success=deliver(result(id,id,PackageInstaller.STATUS_SUCCESS));
   check(!UpdateInstaller.pending(c)&&!UpdatePreparation.held&&success.finished&&success.started.size()==1&&success.started.get(0).target==MainActivity.class);
   UpdateInstaller.begin(c);UpdateStore.version=57;check(!UpdateInstaller.pending(c)&&!UpdatePreparation.held&&!UpdateStore.values.map.containsKey("session"));
   System.out.println(args[0]+": "+checks+" actual installer/callback checks; Android and recovery collaborators are doubles, NOT device validation");
  }finally{UpdateStore.worker.shutdownNow();try(var paths=Files.walk(dir)){for(Path p:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(p);}}
 }
}
