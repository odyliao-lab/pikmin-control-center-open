package dev.ody.pikmincontrol;

/** Fixed commands only: UI ack/navigation, never arbitrary coordinates or game RPC. */
final class EmulatorDailyUiCommands {
    private static final String GAME="com.nianticlabs.pikmin",ACTIVITY="com.nianticproject.ichigo.IchigoUnityPlayerActivity";
    final String guard;
    EmulatorDailyUiCommands(EmulatorDailyRunner.Binding b,String ticks,String base,String ticketSha,String configSha,String ticketId){
        if(b==null||ticks==null||!ticks.matches("[1-9][0-9]*")||base==null||!base.matches("/data/(?:user/0|data)/dev\\.ody\\.pikmincontrol\\.mumu/files")||!sha(ticketSha)||!sha(configSha)||ticketId==null||!ticketId.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw new IllegalArgumentException("Exact private MuMu UI identity required");
        guard="set -eu\n"+
            "test \"$(id -u)\" = 0\n"+
            "test \"$(getprop ro.build.version.sdk)\" = 35 && test \"$(getprop ro.product.cpu.abi)\" = x86_64 && test \"$(getprop ro.dalvik.vm.native.bridge)\" = libnb.so\n"+
            "test \"$(cat /proc/sys/kernel/random/boot_id)\" = "+b.boot+"\n"+
            "test \"$(pidof "+GAME+")\" = "+b.pid+" && test \"$(awk '{print $22}' /proc/"+b.pid+"/stat)\" = "+ticks+"\n"+
            "test ! -e '"+base+"/daily-schedule/"+ticketId+".stop' && test ! -L '"+base+"/daily-schedule/"+ticketId+".stop'\n"+
            "test ! -e '"+base+"/daily-schedule/ticket.tsv.tmp' && test ! -L '"+base+"/daily-schedule/ticket.tsv.tmp' && test ! -e '"+base+"/daily-configuration/config.tsv.tmp' && test ! -L '"+base+"/daily-configuration/config.tsv.tmp'\n"+
            held(base+"/daily-schedule/ticket.tsv",ticketSha)+held(base+"/daily-configuration/config.tsv",configSha)+
            "now=$(date +%s%3N)\n"+
            "awk -F '\\t' -v now=\"$now\" 'NF==23 && $1==\"v1\" && $3=="+b.pid+" && $4==\"0.20.3\" && $5==900178 && $6==\"157.0\" && $15=="+b.sessionEpoch+" && $16==\"ready\" && $23==\"end\" && now>=$2 && now-$2<=12000 {ok=1} END{exit !ok}' /data/user/0/"+GAME+"/files/core_capabilities.tsv\n"+
            "awk -F '\\t' -v now=\"$now\" 'NF==7 && $1==\"v1\" && $3=="+b.pid+" && $4==\""+b.account+"\" && $5==\"ready\" && $7==\"end\" && now>=$2 && now-$2<=12000 {ok=1} END{exit !ok}' /data/user/0/"+GAME+"/files/automation_session.tsv\n"+
            EmulatorSavedDailyUi.focusGuard()+"\n";
    }
    String capture(){return guard+"date +%s%3N\nexec screencap\n";}
    String tap(EmulatorDailyUiRecovery.Frame held){
        if(held==null||!held.scene.clickable()||held.clock<=0)throw new IllegalArgumentException("Whitelisted UI frame required");
        return guard+"now=$(date +%s%3N);test \"$now\" -ge "+held.clock+" && test \"$((now-"+held.clock+"))\" -le 3000 || exit 4\ninput tap "+held.scene.x+" "+held.scene.y+"\nprintf 'UI_INPUT_SENT\\n'\n";
    }
    private static String held(String p,String sha){return "test -f '"+p+"' && test ! -L '"+p+"' && test \"$(sha256sum '"+p+"'|cut -d' ' -f1)\" = "+sha+"\n";}
    private static boolean sha(String s){return s!=null&&s.matches("[a-f0-9]{64}");}
}
