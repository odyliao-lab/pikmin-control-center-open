package dev.ody.pikmincontrol;

import java.io.IOException;

/** Exact pre-install evidence. A matching core SO alone cannot prove a failed migration is harmless. */
final class CoreInstallSnapshot {
    static final String CORE="/data/adb/modules/pikmin_nectar_rpc";
    static final String GPS="/data/adb/modules/zygisk_pikmin_gps_copy";
    final String raw, coreCode, coreSo, gpsState;

    CoreInstallSnapshot(String value) throws IOException {
        if(value==null||value.length()>1024)throw new IOException("模組快照缺少或過大");
        String[] f=value.split("\t",-1);
        if(f.length!=8||!"v1".equals(f[0])||!f[1].matches("[1-9][0-9]{0,8}"))throw new IOException("模組快照格式不符");
        for(int i=2;i<=4;i++)hash(f[i]);
        new LegacyGpsSnapshot("v1\t"+f[5]+"\t"+f[6]+"\t"+f[7]);
        raw=value;coreCode=f[1];coreSo=f[3];gpsState=f[5];
    }
    private static void hash(String s)throws IOException{if(!s.matches("[a-f0-9]{64}"))throw new IOException("模組快照雜湊不符");}
    boolean unchanged(CoreInstallSnapshot now){return now!=null&&raw.equals(now.raw);}

    // No write commands; inaccessible paths, symlinks, staged updates and removal flags fail closed.
    static String command(){return "set -e; "
        +"test -d /data/adb/modules; test ! -L /data/adb/modules; "
        +"test ! -L /data/adb/modules_update; "
        +"for p in /data/adb/modules_update/pikmin_nectar_rpc /data/adb/modules_update/zygisk_pikmin_gps_copy; do test ! -e \"$p\"; test ! -L \"$p\"; done; "
        +"c="+CORE+"; g="+GPS+"; test -d \"$c\"; test ! -L \"$c\"; test ! -L \"$c/zygisk\"; "
        +"for p in \"$c/disable\" \"$c/remove\"; do test ! -e \"$p\"; test ! -L \"$p\"; done; "
        +"for p in \"$c/module.prop\" \"$c/zygisk/arm64-v8a.so\" \"$c/service.sh\"; do test -f \"$p\"; test ! -L \"$p\"; done; "
        +LegacyGpsSnapshot.fieldsCommand()
        +"printf 'v1\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s\\t%s' \"$(sed -n 's/^versionCode=//p' \"$c/module.prop\")\" \"$(h \"$c/module.prop\")\" \"$(h \"$c/zygisk/arm64-v8a.so\")\" \"$(h \"$c/service.sh\")\" \"$s\" \"$gp\" \"$gs\"";
    }
}
