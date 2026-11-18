package dev.ody.pikmincontrol;
import java.io.IOException;

/** Exact legacy copy control identity; content remains in the private migration backup. */
final class CopyModeState {
    static final String FILE="/data/user/0/com.nianticlabs.pikmin/files/gps_copy_mode.txt";
    static final String OFF_SHA="b470b84cf0cd3189e10615bffea7d5cbd9f61134ba1c2cfd513ddd45b776a5f6";
    final String raw,sha,uid,gid,mode;final boolean absent;
    CopyModeState(String raw)throws IOException {
        this.raw=raw;absent="absent".equals(raw);
        if(absent){sha="absent";uid=gid="0";mode="644";return;}
        String[] f=raw==null?new String[0]:raw.split(":",-1);
        if(f.length!=4||!f[0].matches("[a-f0-9]{64}")||!f[1].matches("[0-9]{1,9}")||!f[2].matches("[0-9]{1,9}")||!f[3].matches("[0-7]{3,4}"))throw new IOException("複製設定快照不符");
        sha=f[0];uid=f[1];gid=f[2];mode=f[3];
    }
    static String snapshotCommand(){return "set -e; f="+FILE+"; test ! -L \"$f\"; if test ! -e \"$f\"; then echo absent; else "
        +"test -f \"$f\"; test \"$(wc -c < \"$f\")\" -le 64; printf '%s:%s\\n' \"$(sha256sum \"$f\" | cut -d ' ' -f1)\" \"$(stat -c %u:%g:%a \"$f\")\"; fi";}
    String guard(){return "set -e; test ! -L "+FILE+"; "+(absent?"test ! -e "+FILE+"; ":"test -f "+FILE+"; test \"$(sha256sum "+FILE+" | cut -d ' ' -f1)\" = "+sha+"; test \"$(stat -c %u:%g:%a "+FILE+")\" = "+uid+":"+gid+":"+mode+"; ");}
    CopyModeState off()throws IOException{return new CopyModeState(OFF_SHA+":"+uid+":"+gid+":644");}
    CopyModeState selected(boolean enabled)throws IOException {
        try{return new CopyModeState(SetupBundle.sha((enabled?"on\n":"off\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))+":"+uid+":"+gid+":644");}
        catch(Exception e){throw new IOException("無法校驗複製設定",e);}
    }
    static boolean needsOff(String gps)throws IOException {
        if("enabled".equals(gps))return false;
        if("disabled".equals(gps)||"absent".equals(gps))return true;
        throw new IOException("原GPS模組狀態不明");
    }
    private static String temp(String id)throws IOException {CoreMigrationBackup.path(id);return FILE+".pcc-"+id+".tmp";}
    private static String prepareTemp(String t) {
        // The UUID is the durable transaction's own temporary file. A failed write may be retried.
        return "test ! -L "+t+"; if test -e "+t+"; then test -f "+t+"; test \"$(wc -c < "+t+")\" -le 64; fi; umask 077; ";
    }
    private String finishTemp(String t,String expected,CopyModeState prior)throws IOException {
        return "chown "+uid+":"+gid+" "+t+"; chmod "+mode+" "+t+"; /system/bin/restorecon "+t+"; "
            +"test \"$(sha256sum "+t+" | cut -d ' ' -f1)\" = "+expected+"; test \"$(stat -c %u:%g:%a "+t+")\" = "+uid+":"+gid+":"+mode+"; sync; "
            +prior.guard()+"mv -f "+t+" "+FILE+"; sync; "+guard();
    }
    String writeOff(String id)throws IOException {
        return writeSelected(id,false);
    }
    String writeSelected(String id,boolean enabled)throws IOException {
        String t=temp(id);CopyModeState target=selected(enabled);
        return guard()+prepareTemp(t)+"printf '"+(enabled?"on":"off")+"\\n' > "+t+"; "+target.finishTemp(t,target.sha,this);
    }
    String restore(String backup)throws IOException {
        return restoreFrom(backup,off());
    }
    String restoreFrom(String backup,CopyModeState written)throws IOException {
        if(!written.raw.equals(selected(false).raw)&&!written.raw.equals(selected(true).raw))throw new IOException("複製設定回復來源不符");
        if(!backup.matches("/data/adb/pcc-core-backups/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}/controls/gps_copy_mode.txt"))throw new IOException("複製備份路徑不符");
        String cmd=written.guard();
        if(absent)return cmd+"rm -f "+FILE+"; sync; ";
        String id=backup.substring("/data/adb/pcc-core-backups/".length(),"/data/adb/pcc-core-backups/".length()+36),t=temp(id);
        return cmd+"test ! -L "+backup.substring(0,backup.lastIndexOf('/'))+"; test -f "+backup+"; test ! -L "+backup+"; test \"$(sha256sum "+backup+" | cut -d ' ' -f1)\" = "+sha+"; "
            +prepareTemp(t)+"cat "+backup+" > "+t+"; "+finishTemp(t,sha,written);
    }
}
