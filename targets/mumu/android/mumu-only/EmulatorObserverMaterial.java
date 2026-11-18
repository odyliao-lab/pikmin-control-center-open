package dev.ody.pikmincontrol;

import java.io.IOException;

/** Signed flavor asset metadata only. No staging, chmod, repair or execution. */
final class EmulatorObserverMaterial {
    final long appCode,soSize,dexSize;
    final String soSha,dexSha,root;
    private EmulatorObserverMaterial(long app,long so,String soSha,long dex,String dexSha,String root){
        appCode=app;soSize=so;this.soSha=soSha;dexSize=dex;this.dexSha=dexSha;this.root=root;
    }
    static EmulatorObserverMaterial parse(String wire,long installedCode,String root)throws IOException {
        if(wire==null||wire.length()>512||wire.indexOf('\r')>=0||wire.indexOf('\0')>=0||
           root==null||!root.matches("/data/local/tmp/pcc-emu-planting-test-[0-9a-f]{32}"))throw new IOException("Invalid observer material scope");
        String[] f=wire.split("\t",-1);
        if(f.length!=7||!f[0].equals("ro1")||!f[6].equals("end\n")||!f[2].matches("[1-9][0-9]*")||
           !f[1].matches("[1-9][0-9]*")||!f[4].matches("[1-9][0-9]*")||
           !f[3].matches("[0-9a-f]{64}")||!f[5].matches("[0-9a-f]{64}"))throw new IOException("Invalid observer asset declaration");
        try {
            long app=Long.parseLong(f[1]),so=Long.parseLong(f[2]),dex=Long.parseLong(f[4]);
            if(app!=installedCode||so<20||so>12*1024*1024||dex<112||dex>1024*1024)throw new IOException("Observer declaration differs from installed flavor");
            return new EmulatorObserverMaterial(app,so,f[3],dex,f[5],root);
        }catch(NumberFormatException bad){throw new IOException("Observer asset bounds",bad);}
    }
    String guard(){
        String command="set -e; test ! -L "+root+"; test -d "+root+"; test \"$(stat -c %u:%g:%a "+root+")\" = 0:0:700; ";
        return command+file("apk-probe.so",soSize,soSha)+file("classes.dex",dexSize,dexSha)
            +"test ! -L /data/local/tmp/pcc-emulator-apk-proof; test -d /data/local/tmp/pcc-emulator-apk-proof; "
            +"test \"$(stat -c %u:%g:%a /data/local/tmp/pcc-emulator-apk-proof)\" = 0:0:711; ";
    }
    private String file(String leaf,long size,String sha){
        String path=root+"/"+leaf;
        return "test ! -L "+path+"; test -f "+path+"; test \"$(stat -c %u:%g:%a:%h:%s "+path+")\" = 0:0:444:1:"+size+"; "
            +"test \"$(sha256sum "+path+" | cut -d ' ' -f1)\" = "+sha+"; ";
    }
    String launch(int gamePid,int seconds){
        if(gamePid<=0||seconds<10||seconds>43200)throw new IllegalArgumentException("Observer lifetime scope");
        return guard()+"test \"$(pidof com.nianticlabs.pikmin)\" = "+gamePid+"; "
            +"export CLASSPATH="+root+"/classes.dex; exec app_process /system/bin dev.ody.pccbridge.RootApkPublisher "
            +root+" "+gamePid+" "+seconds+" stdin-owner";
    }
    String stage(String cache){
        if(cache==null||!cache.matches("/data/user/0/dev\\.ody\\.pikmincontrol\\.mumu/cache/pcc-observer-[0-9a-f]{32}"))throw new IllegalArgumentException("Observer cache scope");
        // Only a NEW Root-private destination. No reuse, repair or deletion.
        return "set -e; test \"$(id -u)\" = 0; test ! -e "+root+"; test ! -L "+root+"; umask 077; mkdir -m 700 "+root+"; set -C; "
            +copy(cache,"apk-probe.so",soSize,soSha)+copy(cache,"classes.dex",dexSize,dexSha)
            +guard()+"printf observer-staged";
    }
    private String copy(String cache,String leaf,long size,String sha){
        String source=cache+"/"+leaf,destination=root+"/"+leaf;
        return "test ! -L "+cache+"; test -d "+cache+"; test ! -L "+source+"; test -f "+source+"; "
            +"cat "+source+" > "+destination+"; test \"$(stat -c %u:%g:%a:%h:%s "+destination+")\" = 0:0:600:1:"+size+"; "
            +"test \"$(sha256sum "+destination+" | cut -d ' ' -f1)\" = "+sha+"; chmod 444 "+destination+"; ";
    }
}
