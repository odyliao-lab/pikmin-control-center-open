package dev.ody.pikmincontrol;

import java.io.IOException;
import java.util.Map;

/** File evidence for a known pair, independent of Magisk's pre-reboot metadata rewrite. */
final class CoreModuleProof {
    static String paths(String base)throws IOException {
        if(!base.equals(CoreUpdater.BASE)&&!base.equals(CoreUpdater.STAGED))throw new IOException("非核心模組路徑");
        return "set -e; test -d /data/adb/modules; test ! -L /data/adb/modules; test ! -L /data/adb/modules_update; "
            +"test -d "+base+"; test ! -L "+base+"; test -d "+base+"/zygisk; test ! -L "+base+"/zygisk; "
            +"for f in module.prop service.sh zygisk/arm64-v8a.so; do test -f "+base+"/\"$f\"; test ! -L "+base+"/\"$f\"; done; ";
    }
    static String hashes(String base,byte[] zip)throws Exception {
        Map<String,byte[]> files=SetupBundle.unzip(zip,CoreUpdater.moduleEntries(),12*1024*1024);
        if(!files.containsKey("service.sh")||!files.containsKey("zygisk/arm64-v8a.so"))throw new IOException("核心檔案不完整");
        return "test \"$(sha256sum "+base+"/zygisk/arm64-v8a.so | cut -d ' ' -f1)\" = "+SetupBundle.sha(files.get("zygisk/arm64-v8a.so"))
            +" && test \"$(sha256sum "+base+"/service.sh | cut -d ' ' -f1)\" = "+SetupBundle.sha(files.get("service.sh"));
    }
    static String knownInstalled(CorePair target,byte[] targetZip,CorePair old,byte[] oldZip)throws Exception {
        if(target.code<=old.code)throw new IOException("核心版本順序不符");
        String base=CoreUpdater.BASE;
        return paths(base)+"code=$(sed -n 's/^versionCode=//p' "+base+"/module.prop); "
            +"{ test \"$code\" = "+target.code+" || test \"$code\" = "+old.code+"; }; "
            +"{ { "+hashes(base,targetZip)+"; } || { "+hashes(base,oldZip)+"; }; }";
    }
}
