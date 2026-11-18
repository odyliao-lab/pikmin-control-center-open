package dev.ody.pikmincontrol;
import java.nio.charset.StandardCharsets;
public final class CopyModeStateTest {
    static int n;static void check(boolean b){if(!b)throw new AssertionError("copy state "+n);n++;}
    public static void main(String[] args)throws Exception {
        String hash=SetupBundle.sha("on\n".getBytes(StandardCharsets.UTF_8));
        CopyModeState original=new CopyModeState(hash+":10001:10001:600"),absent=new CopyModeState("absent");
        if(args.length==1) {
            if(args[0].equals("--write-command"))System.out.print(original.writeOff("11111111-1111-1111-1111-111111111111"));
            else if(args[0].equals("--restore-command"))System.out.print(original.restore("/data/adb/pcc-core-backups/11111111-1111-1111-1111-111111111111/controls/gps_copy_mode.txt"));
            else if(args[0].equals("--write-on-command"))System.out.print(new CopyModeState(CopyModeState.OFF_SHA+":10001:10001:600").writeSelected("11111111-1111-1111-1111-111111111111",true));
            else if(args[0].equals("--restore-on-command")) {
                CopyModeState off=new CopyModeState(CopyModeState.OFF_SHA+":10001:10001:600");
                System.out.print(off.restoreFrom("/data/adb/pcc-core-backups/11111111-1111-1111-1111-111111111111/controls/gps_copy_mode.txt",off.selected(true)));
            }
            else throw new IllegalArgumentException("Unknown fixture command");
            return;
        }
        check(original.sha.equals(hash));check(original.uid.equals("10001"));check(original.mode.equals("600"));check(!original.absent);check(absent.absent);
        check(CopyModeState.OFF_SHA.equals(SetupBundle.sha("off\n".getBytes(StandardCharsets.UTF_8))));
        String id="11111111-1111-1111-1111-111111111111",write=original.writeOff(id);
        check(write.startsWith(original.guard()));check(write.contains("chown 10001:10001"));check(write.contains("chmod 644"));
        check(absent.off().uid.equals("0"));check(original.off().mode.equals("644"));
        String backup="/data/adb/pcc-core-backups/11111111-1111-1111-1111-111111111111/controls/gps_copy_mode.txt";
        check(original.restore(backup).startsWith(original.off().guard()));check(original.restore(backup).contains("chmod 600"));
        check(original.restore(backup).contains(hash));check(!original.restore(backup).contains("rm -f"));
        check(absent.restore(backup).contains("rm -f "+CopyModeState.FILE));
        for(String bad:new String[]{null,"","on",hash+":1:1:999",hash+":1;id:1:644",hash+":1:1:644\n","absent\n"}) {
            boolean rejected=false;try{new CopyModeState(bad);}catch(Exception e){rejected=true;}check(rejected);
        }
        for(String bad:new String[]{"/","/data/adb/pcc-core-backups/x/controls/gps_copy_mode.txt",backup+";id"}) {
            boolean rejected=false;try{original.restore(bad);}catch(Exception e){rejected=true;}check(rejected);
        }
        check(CopyModeState.needsOff("disabled"));check(CopyModeState.needsOff("absent"));check(!CopyModeState.needsOff("enabled"));
        check(write.contains("/system/bin/restorecon"));check(write.contains("mv -f "+CopyModeState.FILE+".pcc-"+id+".tmp "+CopyModeState.FILE));
        check(write.indexOf("chmod 644")<write.indexOf("mv -f"));
        check(write.indexOf("restorecon")<write.indexOf("mv -f"));
        check(write.lastIndexOf(original.guard())<write.indexOf("mv -f"));
        check(!write.contains("> "+CopyModeState.FILE+";"));check(!original.restore(backup).contains("> "+CopyModeState.FILE+";"));
        boolean rejected=false;try{original.writeOff("bad;id");}catch(Exception e){rejected=true;}check(rejected);
        check(original.selected(true).sha.equals(hash));check(original.selected(false).raw.equals(original.off().raw));
        check(absent.selected(true).uid.equals("0"));check(absent.selected(false).mode.equals("644"));
        check(original.writeSelected(id,true).contains("printf 'on\\n'"));
        check(original.restoreFrom(backup,original.selected(true)).startsWith(original.selected(true).guard()));
        check(absent.restoreFrom(backup,absent.selected(true)).contains("rm -f "+CopyModeState.FILE));
        for(String bad:new String[]{"absent",hash+":9:9:644",hash+":10001:10001:600"}) {
            rejected=false;try{original.restoreFrom(backup,new CopyModeState(bad));}catch(Exception e){rejected=true;}check(rejected);
        }
        System.out.println("PASS CopyModeState: "+n+" exact identity, off hash and guarded restore command checks; NOT Android file/SELinux or transaction validation");
    }
}
