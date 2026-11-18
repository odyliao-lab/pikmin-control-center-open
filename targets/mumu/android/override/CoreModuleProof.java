package dev.ody.pikmincontrol;

import java.io.IOException;
import java.util.Map;

/** Emulator flavor proof: both ABIs and service must match the signed APK pair. */
final class CoreModuleProof {
    static String paths(String base)throws IOException {
        if(!base.equals(CoreUpdater.BASE)&&!base.equals(CoreUpdater.STAGED))throw new IOException("非實驗核心路徑");
        return "set -e; test -d /data/adb/modules; test ! -L /data/adb/modules; test ! -L /data/adb/modules_update; "
            +"for b in /data/adb/modules /data/adb/modules_update; do for id in pikmin_nectar_rpc zygisk_pikmin_gps_copy; do test ! -e $b/$id; test ! -L $b/$id; done; done; "
            +"test -d "+base+"; test ! -L "+base+"; test -d "+base+"/zygisk; test ! -L "+base+"/zygisk; "
            +"for f in module.prop service.sh payload-arm64.so zygisk/x86_64.so; do test -f "+base+"/\"$f\"; test ! -L "+base+"/\"$f\"; done; ";
    }
    static String hashes(String base,byte[] zip)throws Exception {
        Map<String,byte[]> files=SetupBundle.unzip(zip,CoreUpdater.moduleEntries(),12*1024*1024);
        StringBuilder proof=new StringBuilder("true");
        for(String name:new String[]{"service.sh","zygisk/x86_64.so","payload-arm64.so"}){
            if(!files.containsKey(name))throw new IOException("核心檔案不完整");
            proof.append(" && test \"$(sha256sum ").append(base).append('/').append(name)
                .append(" | cut -d ' ' -f1)\" = ").append(SetupBundle.sha(files.get(name)));
        }
        return proof.toString();
    }
    static String knownInstalled(CorePair target,byte[] targetZip,CorePair old,byte[] oldZip)throws Exception {
        if(target.code<=old.code)throw new IOException("核心版本順序不符");
        String base=CoreUpdater.BASE;
        return paths(base)+"code=$(sed -n 's/^versionCode=//p' "+base+"/module.prop); "
            +"{ test \"$code\" = "+target.code+" || test \"$code\" = "+old.code+"; }; "
            +"{ { "+hashes(base,targetZip)+"; } || { "+hashes(base,oldZip)+"; }; }";
    }
}
