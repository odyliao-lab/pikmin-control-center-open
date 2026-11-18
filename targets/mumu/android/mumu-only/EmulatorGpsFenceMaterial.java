package dev.ody.pikmincontrol;
import java.io.IOException;

/** Metadata from this flavor's signed asset. Never treats an old stage or lock
 * name as authority. Product actor only; no fixture path argument permitted. */
final class EmulatorGpsFenceMaterial {
    final long appCode,size;final String sha,root;
    private EmulatorGpsFenceMaterial(long app,long size,String sha,String root){this.appCode=app;this.size=size;this.sha=sha;this.root=root;}
    static EmulatorGpsFenceMaterial parse(String wire,long code,String root)throws IOException{
        if(wire==null||wire.length()>256||wire.indexOf('\r')>=0||wire.indexOf('\0')>=0||root==null||
                !root.matches("/data/local/tmp/pcc-gps-fence-stage-[0-9a-f]{32}"))throw new IOException("GPS material scope");
        String[] f=wire.split("\t",-1);
        if(f.length!=5||!"gf1".equals(f[0])||!"end\n".equals(f[4])||!f[1].matches("[1-9][0-9]*")||
                !f[2].matches("[1-9][0-9]*")||!f[3].matches("[0-9a-f]{64}"))throw new IOException("GPS declaration framing");
        try{
            long app=Long.parseLong(f[1]),size=Long.parseLong(f[2]);
            // 149 and its reserved150 rollback may never be rebuilt/relabelled.
            if(app!=code||app<151||size<20||size>12*1024*1024)throw new IOException("GPS declaration version/size");
            return new EmulatorGpsFenceMaterial(app,size,f[3],root);
        }catch(NumberFormatException bad){throw new IOException("GPS declaration bounds",bad);}
    }
    private String ancestors(){return "for p in /data /data/local /data/local/tmp; do test -d \"$p\" && test ! -L \"$p\" || exit 1; done; ";}
    String guard(){
        return "set -e; test \"$(id -u)\" = 0; "+ancestors()+
            "test -d "+root+"; test ! -L "+root+"; test \"$(stat -c %u:%g:%a "+root+")\" = 0:0:700; "+
            "test -f "+root+"/gps-fence; test ! -L "+root+"/gps-fence; "+
            "test \"$(stat -c %u:%g:%a:%h:%s "+root+"/gps-fence)\" = 0:0:500:1:"+size+"; "+
            "test \"$(sha256sum "+root+"/gps-fence | cut -d ' ' -f1)\" = "+sha+"; ";
    }
    String stage(String cache,int appUid){
        if(cache==null||!cache.matches("/data/user/0/dev\\.ody\\.pikmincontrol\\.mumu/cache/pcc-gps-fence-[0-9a-f]{32}")||appUid<10000)
            throw new IllegalArgumentException("GPS cache scope");
        return "set -e; test \"$(id -u)\" = 0; "+ancestors()+
            "test -d "+cache+"; test ! -L "+cache+"; test \"$(stat -c %u "+cache+")\" = "+appUid+"; "+
            "test -f "+cache+"/gps-fence; test ! -L "+cache+"/gps-fence; "+
            "test \"$(stat -c %u:%h:%s "+cache+"/gps-fence)\" = "+appUid+":1:"+size+"; "+
            "test ! -e "+root+"; test ! -L "+root+"; umask 077; mkdir -m 700 "+root+"; set -C; "+
            "cat "+cache+"/gps-fence > "+root+"/gps-fence; "+
            "test \"$(stat -c %u:%g:%a:%h:%s "+root+"/gps-fence)\" = 0:0:600:1:"+size+"; "+
            "test \"$(sha256sum "+root+"/gps-fence | cut -d ' ' -f1)\" = "+sha+"; chmod 500 "+root+"/gps-fence; "+
            // New fixed common fence directory only. Existing invalid state is
            // preserved, not chmod'd/cleared. Actor verifies gps.lock itself.
            "p=/data/local/tmp/pcc-emulator-gps-work; test ! -L \"$p\"; "+
            "if test ! -e \"$p\"; then mkdir -m 711 \"$p\"; fi; "+
            "test -d \"$p\"; test \"$(stat -c %u:%g:%a \"$p\")\" = 0:0:711; "+guard()+"printf gps-staged";
    }
    String launch(int gamePid,String nonce){
        if(gamePid<=0||nonce==null||!nonce.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IllegalArgumentException("GPS launch identity");
        return guard()+"test \"$(pidof com.nianticlabs.pikmin)\" = "+gamePid+"; exec "+root+"/gps-fence "+nonce;
    }
}
