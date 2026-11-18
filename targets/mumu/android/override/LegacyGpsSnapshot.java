package dev.ody.pikmincontrol;

import java.io.IOException;

/** Standalone GPS identity, usable even when no automation core has ever been installed. */
final class LegacyGpsSnapshot {
    static final String BASE="/data/adb/modules/zygisk_pikmin_gps_copy",STAGED="/data/adb/modules_update/zygisk_pikmin_gps_copy";
    final String raw,state,prop,so;
    LegacyGpsSnapshot(String value)throws IOException {
        String[] f=value==null?new String[0]:value.split("\t",-1);
        if(f.length!=4||!"v1".equals(f[0]))throw new IOException("GPS模組快照格式不符");
        if("absent".equals(f[1])) {
            if(!"-".equals(f[2])||!"-".equals(f[3]))throw new IOException("缺少GPS卻回報雜湊");
        }else if((!"enabled".equals(f[1])&&!"disabled".equals(f[1]))||!f[2].matches("[a-f0-9]{64}")||!f[3].matches("[a-f0-9]{64}"))
            throw new IOException("GPS模組狀態或雜湊不符");
        raw=value;state=f[1];prop=f[2];so=f[3];
    }
    static LegacyGpsSnapshot from(CoreInstallSnapshot core)throws IOException {
        String[] f=core.raw.split("\t",-1);return new LegacyGpsSnapshot("v1\t"+f[5]+"\t"+f[6]+"\t"+f[7]);
    }
    static String parents() {
        return "set -e; test -d /data/adb/modules; test ! -L /data/adb/modules; test ! -L /data/adb/modules_update; "
            +"if test -e /data/adb/modules_update; then test -d /data/adb/modules_update; fi; "
            +"test ! -e "+STAGED+"; test ! -L "+STAGED+"; test ! -L "+BASE+"; ";
    }
    static String fieldsCommand() {
        return parents()+"g="+BASE+"; h() { sha256sum \"$1\" | cut -d ' ' -f1; }; s=absent; gp=-; gs=-; "
            +"if test -e \"$g\"; then test -d \"$g\"; test -d \"$g/zygisk\"; test ! -L \"$g/zygisk\"; "
            +"test ! -e \"$g/remove\"; test ! -L \"$g/remove\"; test ! -L \"$g/disable\"; "
            +"for p in \"$g/module.prop\" \"$g/zygisk/x86_64.so\"; do test -f \"$p\"; test ! -L \"$p\"; done; "
            +"s=enabled; if test -e \"$g/disable\"; then test -f \"$g/disable\"; s=disabled; fi; "
            +"gp=$(h \"$g/module.prop\"); gs=$(h \"$g/zygisk/x86_64.so\"); fi; ";
    }
    static String command(){return fieldsCommand()+"printf 'v1\\t%s\\t%s\\t%s' \"$s\" \"$gp\" \"$gs\"";}
    String proof(boolean disabled) {
        if(state.equals("absent"))return parents()+"test ! -e "+BASE+"; ";
        return parents()+"test -d "+BASE+"; test -d "+BASE+"/zygisk; test ! -L "+BASE+"/zygisk; "
            +"for f in module.prop zygisk/x86_64.so; do test -f "+BASE+"/\"$f\"; test ! -L "+BASE+"/\"$f\"; done; "
            +"test ! -e "+BASE+"/remove; test ! -L "+BASE+"/remove; test ! -L "+BASE+"/disable; "
            +"test \"$(sha256sum "+BASE+"/module.prop | cut -d ' ' -f1)\" = "+prop+"; "
            +"test \"$(sha256sum "+BASE+"/zygisk/x86_64.so | cut -d ' ' -f1)\" = "+so+"; "
            +(disabled?"test -f ":"test ! -e ")+BASE+"/disable; ";
    }
}
