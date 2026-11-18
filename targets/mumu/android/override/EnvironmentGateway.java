package dev.ody.pikmincontrol;

import android.content.Context;
import android.os.Build;

final class EnvironmentGateway {
    static final String GAME="/data/user/0/com.nianticlabs.pikmin/files/";
    static EnvironmentReport read(Context c) throws Exception {
        String raw=collect(c);
        return new EnvironmentReport(raw,version(c,c.getPackageName()),Build.VERSION.SDK_INT,
                raw.contains("\nprobe_complete=yes\n"),System.currentTimeMillis());
    }
    static String collect(Context c) throws Exception {
        String command="printf 'root_uid='; id -u; printf 'native='; sed -n 's/^version=//p' /data/adb/modules/pcc_emulator_bridge_canary/module.prop 2>/dev/null; echo; "
                +"printf 'native_disabled='; if test -e /data/adb/modules/pcc_emulator_bridge_canary/disable; then echo yes; else echo no; fi; "
                +"printf 'copy='; sed -n 's/^version=//p' /data/adb/modules/zygisk_pikmin_gps_copy/module.prop 2>/dev/null; echo; "
                +"printf 'runtime='; cut -f2 "+GAME+"compatibility_status.tsv 2>/dev/null; echo; printf 'pid='; pidof com.nianticlabs.pikmin; echo; "
                +"awk -F '\t' 'NR==1 {print \"guard_schema=\"$1; print \"guard_at=\"$2; print \"guard_pid=\"$3; print \"guard_limit=\"$12}' "+GAME+"nearby_gate_status.tsv; "
                +"printf 'dispatch_perm='; stat -c %a /data/local/tmp/pikmin-dispatch-mode.txt 2>/dev/null; echo; "
                +"printf 'magisk='; magisk -V 2>/dev/null; echo; "
                +"printf 'native_code='; sed -n 's/^versionCode=//p' /data/adb/modules/pcc_emulator_bridge_canary/module.prop 2>/dev/null; echo; "
                +"printf 'native_pending='; if test -e /data/adb/modules_update/pcc_emulator_bridge_canary/module.prop; then echo yes; else echo no; fi; "
                +"printf 'copy_pending='; if test -e /data/adb/modules_update/zygisk_pikmin_gps_copy/module.prop; then echo yes; else echo no; fi; "
                +"printf 'copy_disabled='; if test -e /data/adb/modules/zygisk_pikmin_gps_copy/disable; then echo yes; else echo no; fi; true";
        BoundedCommand.Result r=BoundedCommand.root(command,6,8192);
        return r.text+"\nprobe_complete="+(r.ok&&!r.truncated?"yes":"no")+"\n"
                +"game="+version(c,"com.nianticlabs.pikmin")+"\njoystick="+version(c,JoystickAdapter.PACKAGE)
                +"\nil2cpp_size="+il2cppSize(c)+"\n"+CoreUpdater.environmentEvidence(c);
    }
    static long il2cppSize(Context c){
        try {
            android.content.pm.ApplicationInfo info=c.getPackageManager().getApplicationInfo("com.nianticlabs.pikmin",0);
            java.io.File lib=new java.io.File(info.nativeLibraryDir,"libil2cpp.so");if(lib.isFile())return lib.length();
            java.util.List<String> paths=new java.util.ArrayList<>();paths.add(info.sourceDir);if(info.splitSourceDirs!=null)java.util.Collections.addAll(paths,info.splitSourceDirs);
            for(String path:paths)try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(path)){
                java.util.zip.ZipEntry e=zip.getEntry("lib/arm64-v8a/libil2cpp.so");if(e!=null)return e.getSize();
            }
        }catch(Exception ignored){}return -1;
    }
    static String version(Context c,String name){try{return c.getPackageManager().getPackageInfo(name,0).versionName;}catch(Exception e){return "未知";}}
}
