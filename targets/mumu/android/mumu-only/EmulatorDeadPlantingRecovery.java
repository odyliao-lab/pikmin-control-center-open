package dev.ody.pikmincontrol;

import android.content.Context;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Explicit dead-owner recovery. UNKNOWN is archived, never changed to success.
 * No game START/STOP RPC is replayed. The current, proven inactive game is
 * terminated before retiring journals, so its cached review state cannot run.
 * A separate normal Bootstrap and normal daily START are required afterwards. */
final class EmulatorDeadPlantingRecovery {
    static final String BASE=EmulatorSavedDailyRuntime.FILES;
    static final String TX="emulator_independent_planting_transaction.tsv";
    static final String[] LEAVES={TX,TX+".selection",TX+".tmp",TX+".selection.tmp"};
    static final String PREFIX="UNKNOWN_ARCHIVED_DEAD_OWNER:";
    // Fixed stage labels only: never expose a token, account, path or shell output.
    private static volatile String stage="idle";
    static String diagnostic(){return stage;}
    private static String root(String s)throws Exception{return EmulatorSavedDailyRuntime.root(s);}
    private static String sha(String s){return EmulatorDailyRunner.digest(s);}
    private static String q(String s){if(s.indexOf('\'')>=0||s.indexOf('\0')>=0)throw new IllegalArgumentException();return "'"+s+"'";}
    private static String encoded(String s){return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
    private static void require(boolean ok,String message)throws IOException{if(!ok)throw new IOException(message);}
    static boolean uuid(String s){try{return UUID.fromString(s).toString().equals(s);}catch(Exception e){return false;}}
    static String[] binding(String s)throws IOException {
        String[] b=s.split(":",-1);
        require(b.length==5&&uuid(b[0])&&b[1].matches("[1-9][0-9]{0,9}")&&b[2].matches("[1-9][0-9]{0,18}")&&
            b[3].matches("[1-9][0-9]{0,18}")&&b[4].matches("[a-f0-9]{64}"),"舊種花身分格式不符");
        return b;
    }
    static void transaction(String wire,String held,String token)throws IOException {
        String[] b=binding(held),t=wire.split("\t",-1);
        require(uuid(token)&&t.length==10&&t[0].equals("v3")&&t[1].equals(b[1])&&t[2].equals(b[3])&&t[3].equals(b[4])&&t[4].equals(token)&&
            Arrays.asList("start-pending","active-confirmed","stop-pending","review").contains(t[5])&&
            t[6].matches("[1-9][0-9]{0,18}")&&t[7].matches("[01]")&&t[8].matches("0|[1-9][0-9]{0,18}")&&t[9].equals("end\n"),"舊種花原件不符；不封存");
    }
    /** A prepared successor can be stopped before native accepts it. Its
     * predecessor must be the exact closure already committed by this run.
     * This proves neither success nor failure of the prepared intent: the
     * existing dead-owner, fresh inactive, backup and quiesce transaction still
     * archives it as UNKNOWN. Never renew its permit or replay its START. */
    static void preparedAfterClosed(String wire,String held,String token,String priorClosure)throws IOException {
        String[] b=binding(held),t=wire.split("\t",-1);
        require(uuid(token)&&t.length==10&&t[0].equals("v3")&&t[1].equals(b[1])&&t[2].equals(b[3])&&t[3].equals(b[4])&&
            uuid(t[4])&&!t[4].equals(token)&&t[5].equals("stopped-confirmed")&&t[7].equals("1")&&
            t[6].matches("[1-9][0-9]{0,18}")&&t[8].matches("0|[1-9][0-9]{0,18}")&&t[9].equals("end\n")&&
            priorClosure!=null&&!priorClosure.isEmpty()&&priorClosure.equals(wire.trim()),"未接受意圖的前筆結算原件不符；不封存");
    }
    static boolean terminalPredecessor(String wire,String liveAccount,String token){
        String[] f=wire.split("\t",-1);
        return f.length==10&&f[0].equals("v3")&&f[1].matches("[1-9][0-9]{0,9}")&&f[2].matches("[1-9][0-9]{0,18}")&&
            f[3].equals(liveAccount)&&uuid(f[4])&&!f[4].equals(token)&&f[5].equals("stopped-confirmed")&&
            f[6].matches("[1-9][0-9]{0,18}")&&f[7].equals("1")&&f[8].matches("[1-9][0-9]{0,18}")&&f[9].equals("end\n");
    }
    /** Native alone certifies the original terminal-Task/controller/30s quiet
     * gate. An App-only intent remains UNKNOWN; terminal history remains true. */
    static String terminalPredecessorGuard(String wire,String held,String token,String proof)throws Exception {
        String[] b=binding(held),t=wire.split("\t",-1),p=proof.split("\t",-1);
        require(terminalPredecessor(wire,b[4],token)&&p.length==14&&p[0].equals("PCCPLANTTERMINAL/1")&&
            p[1].equals(b[0])&&p[2].equals(b[1])&&p[3].equals(b[2])&&p[4].equals(b[3])&&p[5].equals(b[4])&&
            p[6].equals(t[4])&&p[7].equals(sha(wire))&&uuid(p[8])&&!p[8].equals(t[4])&&
            p[9].matches("[a-f0-9]{64}")&&p[10].matches("[a-f0-9]{64}")&&p[11].matches("[a-f0-9]{64}")&&
            p[12].equals("quiet30")&&p[13].equals("end\n"),"原生terminal接續原件不符");
        String terminal=BASE+"planting-terminal-"+t[4]+".tsv",old=BASE+"planting-unconfirmed-"+p[8]+".tsv";
        String proofPath=terminal+".ready-"+b[0]+"-"+b[1]+"-"+b[2]+"-"+b[3];
        StringBuilder cmd=new StringBuilder();
        for(String[] item:new String[][]{{BASE+TX,p[7]},{terminal,p[7]},{terminal+".selection",p[9]},
                {terminal+".settlement",p[11]},{BASE+"planting-settlement-"+t[4]+".tsv",p[11]},
                {BASE+"planting-retired-selection-"+p[8]+".tsv",p[9]},{old,p[10]},{old+".selection",p[9]},{proofPath,sha(proof)}})
            cmd.append("p=").append(q(item[0])).append("; test -f $p; test ! -L $p; test \"$(stat -c %u:%g:%a:%h $p)\" = \"$(stat -c %u "+BASE+"):$(stat -c %u "+BASE+"):600:1\"; test \"$(sha256sum $p | cut -d' ' -f1)\" = ").append(item[1]).append("; ");
        cmd.append("test ! -e ").append(q(BASE+TX+".selection")).append("; test ! -L ").append(q(BASE+TX+".selection")).append("; ");
        cmd.append("test \"$(cat /data/local/tmp/pikmin-planting-mode.txt)\" = off; ");
        for(String leaf:new String[]{TX+".tmp",TX+".selection.tmp","emulator-command"})
            cmd.append("test ! -e ").append(q(BASE+leaf)).append("; test ! -L ").append(q(BASE+leaf)).append("; ");
        return cmd.toString();
    }
    static String deadAppOnlyGuard(String held,String account)throws Exception {
        require(binding(held)[4].equals(account),"未接納App意圖帳號不同");
        return deadOwner(held);
    }
    private static void archiveTransaction(String wire,EmulatorSavedDailyStore s,String held,String token)throws IOException {
        try{transaction(wire,held,token);return;}catch(IOException original){
            String[] t=wire.split("\t",-1);
            require(t.length==10&&uuid(t[4]),"原件格式不符");
            preparedAfterClosed(wire,held,token,s.get("plant.closed."+t[4],""));
        }
    }
    static void selection(String wire,String held,String token)throws IOException {
        selection(wire,held,token,false);
    }
    static void selection(String wire,String held,String token,boolean foreignOwnerAbsent)throws IOException {
        String[] b=binding(held),f=wire.split("\t",-1);
        // Native keeps the last CLOSED selector when the next planting reuses
        // the already selected petal. It need not carry the next START token.
        // Only that terminal selector may differ; a pending/confirmed selector
        // must still belong to the exact old transaction. Never replay either.
        require(uuid(token)&&f.length==8&&f[0].equals("v1")&&f[1].matches("[1-9][0-9]{0,9}")&&
            (f[1].equals(b[1])||(f[5].equals("closed")&&foreignOwnerAbsent))&&f[2].equals(b[3])&&f[3].equals(b[4])&&
            uuid(f[4])&&Arrays.asList("pending","confirmed","closed").contains(f[5])&&
            (f[4].equals(token)||f[5].equals("closed"))&&f[6].matches("[A-Za-z0-9._:-]{1,80}")&&f[7].equals("end\n"),
            "換花原件身分不符");
    }
    private static String selectionGuard(String wire,String held,String token)throws Exception {
        String[] f=wire.split("\t",-1),b=binding(held);
        // Native can reuse an already selected flower across game restarts.
        // Only a terminal selector with a freshly absent old PID is archival
        // evidence. Keep account/epoch checks; never adopt or replay it.
        boolean foreign=f.length==8&&!f[1].equals(b[1]);
        selection(wire,held,token,foreign);
        String guard=foreign?"test ! -e /proc/"+f[1]+"; ":"";
        root(identityGuard()+guard+"echo SELECTOR_OWNER_CHECKED");
        return guard;
    }
    /** Stopped recovery can preserve a same-incarnation predecessor selector.
     * Both exact native UNKNOWN originals must already exist; this never
     * retires a live selector or infers a successful setter/planting result. */
    static void stoppedArchivedSelector(String current,String held,String token,String selected,String prior,String priorSelected)throws IOException {
        transaction(current,held,token);
        String[] b=binding(held),t=current.split("\t",-1),f=selected.split("\t",-1);
        require(t[5].equals("review")&&selected.equals(priorSelected)&&f.length==8&&
            f[1].equals(b[1])&&f[2].equals(b[3])&&f[3].equals(b[4])&&uuid(f[4])&&
            !f[4].equals(token)&&f[5].equals("confirmed"),"前筆換花UNKNOWN原件不符");
        selection(selected,held,f[4]);transaction(prior,held,f[4]);
        String[] old=prior.split("\t",-1);
        require(old[5].equals("review")&&Long.parseLong(old[6])<Long.parseLong(t[6]),"前筆種花先後次序不符");
    }
    private static String stoppedSelectionGuard(String selected,String held,String token)throws Exception {
        String[] f=selected.split("\t",-1),b=binding(held);
        if(f.length!=8||!f[1].equals(b[1])||f[4].equals(token)||!f[5].equals("confirmed"))
            return selectionGuard(selected,held,token);
        require(uuid(f[4]),"前筆token格式不符");String dead=deadOwner(held);
        String current=decode(nativeRead(TX)),prior=archivedRead("planting-unconfirmed-"+f[4]+".tsv",dead);
        String priorSelected=archivedRead("planting-unconfirmed-"+f[4]+".tsv.selection",dead);
        stoppedArchivedSelector(current,held,token,selected,prior,priorSelected);
        require(current.equals(archivedRead("planting-unconfirmed-"+token+".tsv",dead)),"目前UNKNOWN原件不符");
        String guard=stoppedPredecessorGuard(current,held,token,selected,prior,priorSelected);
        root(identityGuard()+guard+"echo STOPPED_PREDECESSOR_ORIGINALS");return guard;
    }
    /** Stable evidence survives the atomic move of the two mutable leaves.
     * originalsGuard and fileGuard protect those leaves until retirement;
     * this continuing guard references only immutable native UNKNOWN copies. */
    static String stoppedPredecessorGuard(String current,String held,String token,String selected,String prior,String priorSelected)throws Exception {
        stoppedArchivedSelector(current,held,token,selected,prior,priorSelected);
        String[] f=selected.split("\t",-1);StringBuilder guard=new StringBuilder(deadOwner(held));
        for(String[] item:new String[][]{{"planting-unconfirmed-"+token+".tsv",current},
                {"planting-unconfirmed-"+f[4]+".tsv",prior},{"planting-unconfirmed-"+f[4]+".tsv.selection",priorSelected}}){
            String path=BASE+item[0];guard.append("p=").append(q(path)).append("; test -f $p; test ! -L $p; test \"$(stat -c %u:%g:%a:%h $p)\" = \"$(stat -c %u "+BASE+"):$(stat -c %u "+BASE+"):600:1\"; test \"$(sha256sum $p | cut -d' ' -f1)\" = ").append(sha(item[1])).append("; ");
        }
        return guard.toString();
    }

    /** An unchanged selector from a dead predecessor is not this run's setter.
     * Native has already preserved BOTH predecessor originals, and has reached
     * its 30s quiet/terminal-task/controller-clear gate for the current UNKNOWN.
     * Retire only that redundant predecessor leaf; never the current intent. */
    static void archivedSelector(String current,String held,String token,String selected,String prior,String priorSelected)throws IOException {
        transaction(current,held,token);
        String[] b=binding(held),t=current.split("\t",-1),f=selected.split("\t",-1),p=prior.split("\t",-1);
        require(t[5].equals("review")&&selected.equals(priorSelected)&&f.length==8&&f[0].equals("v1")&&
            f[1].matches("[1-9][0-9]{0,9}")&&!f[1].equals(b[1])&&f[2].matches("[1-9][0-9]{0,18}")&&
            f[3].equals(b[4])&&uuid(f[4])&&!f[4].equals(token)&&Arrays.asList("confirmed","closed").contains(f[5])&&
            f[6].matches("[A-Za-z0-9._:-]{1,80}")&&f[7].equals("end\n")&&
            p.length==10&&p[0].equals("v3")&&p[1].equals(f[1])&&p[2].equals(f[2])&&p[3].equals(f[3])&&p[4].equals(f[4])&&
            Arrays.asList("start-pending","active-confirmed","stop-pending","review").contains(p[5])&&
            p[6].matches("[1-9][0-9]{0,18}")&&p[7].matches("[01]")&&p[8].matches("0|[1-9][0-9]{0,18}")&&p[9].equals("end\n"),
            "舊換花缺少精確已保全原件；不退休");
    }
    private static String archivedRead(String leaf,String guard)throws Exception {
        require(leaf.matches("planting-unconfirmed-[a-f0-9-]{36}\\.tsv(\\.selection)?"),"封存名稱不符");
        return decode(root(identityGuard()+guard+"p="+q(BASE+leaf)+"; test -f $p; test ! -L $p; test \"$(stat -c %u:%g:%a:%h $p)\" = \"$(stat -c %u "+BASE+"):$(stat -c %u "+BASE+"):600:1\"; test \"$(stat -c %s $p)\" -le 4096; base64 $p").replaceAll("\\s",""));
    }
    static boolean retireArchivedSelector(String held,String token,String guard)throws Exception {
        String[] b=binding(held);require(uuid(token),"目前種花token不符");
        String currentArchive="planting-unconfirmed-"+token+".tsv";
        // Absence is an ordinary wait. A native partial archive is required;
        // this App never substitutes its own claim that game Tasks are idle.
        String present=root(identityGuard()+guard+"if test ! -e "+q(BASE+currentArchive)+" && test ! -L "+q(BASE+currentArchive)+"; then echo NONE; elif test ! -e "+q(BASE+TX+".selection")+" && test ! -L "+q(BASE+TX+".selection")+"; then echo NONE; else echo CHECK; fi;").trim();
        if(present.equals("NONE"))return false;require(present.equals("CHECK"),"換花盤點不符");
        String current=decode(nativeRead(TX)),selected=decode(nativeRead(TX+".selection"));
        String[] f=selected.split("\t",-1);
        require(f.length==8&&uuid(f[4]),"舊換花格式不符");
        if(f[1].equals(b[1])||f[4].equals(token))return false; // original current-owner rules still apply
        String oldArchive="planting-unconfirmed-"+f[4]+".tsv";
        String prior=archivedRead(oldArchive,guard),priorSelected=archivedRead(oldArchive+".selection",guard);
        archivedSelector(current,held,token,selected,prior,priorSelected);
        require(current.equals(archivedRead(currentArchive,guard)),"目前UNKNOWN保全原件不同");
        String command=archivedSelectorCommand(held,token,guard,current,selected,prior,priorSelected);
        require(root(command).trim().equals("SELECTOR_RETIRED_UNKNOWN"),"舊換花保全退休未確認");
        return true;
    }
    static String archivedSelectorCommand(String held,String token,String guard,String current,String selected,String prior,String priorSelected)throws Exception {
        archivedSelector(current,held,token,selected,prior,priorSelected);
        String[] b=binding(held),f=selected.split("\t",-1);
        String currentArchive="planting-unconfirmed-"+token+".tsv",oldArchive="planting-unconfirmed-"+f[4]+".tsv";
        String retired=BASE+"planting-retired-selection-"+f[4]+".tsv";
        StringBuilder command=new StringBuilder(identityGuard()+guard);
        for(String[] item:new String[][]{{TX,current},{TX+".selection",selected},{currentArchive,current},{oldArchive,prior},{oldArchive+".selection",priorSelected}})
            command.append("p=").append(q(BASE+item[0])).append("; test -f $p; test ! -L $p; test \"$(stat -c %u:%g:%a:%h $p)\" = \"$(stat -c %u "+BASE+"):$(stat -c %u "+BASE+"):600:1\"; test \"$(sha256sum $p | cut -d' ' -f1)\" = ").append(sha(item[1])).append("; ");
        command.append("test ! -e /proc/").append(f[1]).append("; now=$(date +%s%3N); ")
            .append("awk -F '\\t' -v p=").append(b[1]).append(" -v now=$now 'NR==1 && NF==6 && $1==\"v1\" && $3==p && $4==\"inactive\" && $5==\"inactive\" && $6==\"end\" && now>=$2 && now-$2<=10000 {ok=1} END {exit !ok}' ").append(BASE).append("emulator_planting_observation.tsv; ")
            .append("awk -F '\\t' -v p=").append(b[1]).append(" -v now=$now 'NR==1 && NF==8 && $1==\"v1\" && $3==p && $4==\"ready\" && $5==\"clear\" && $6==\"review\" && $7==\"attempted\" && $8==\"end\" && now>=$2 && now-$2<=10000 {ok=1} END {exit !ok}' ").append(BASE).append(TX).append(".safety; ")
            .append("test ! -L /data/local/tmp/pikmin-planting-mode.txt; test \"$(cat /data/local/tmp/pikmin-planting-mode.txt)\" = off; ");
        for(String leaf:new String[]{TX+".tmp",TX+".selection.tmp","emulator-command"})command.append("test ! -e ").append(q(BASE+leaf)).append("; test ! -L ").append(q(BASE+leaf)).append("; ");
        command.append("test ! -e ").append(q(retired)).append("; test ! -L ").append(q(retired)).append("; mv -n ").append(q(BASE+TX+".selection")).append(" ").append(q(retired)).append("; sync; test ! -e ").append(q(BASE+TX+".selection")).append("; test ! -L ").append(q(retired)).append("; test \"$(sha256sum ").append(q(retired)).append(" | cut -d' ' -f1)\" = ").append(sha(selected)).append("; echo SELECTOR_RETIRED_UNKNOWN;");
        return command.toString();
    }
    static boolean inactive(String observation,String safety,String diagnostic,int pid,long now){
        try{
            String p=Integer.toString(pid);String[] o=observation.split("\t",-1),s=safety.split("\t",-1),d=diagnostic.split("\t",-1);
            return pid>0&&o.length==6&&s.length==8&&d.length==12&&o[0].equals("v1")&&s[0].equals("v1")&&d[0].equals("v1")&&
                o[2].equals(p)&&s[2].equals(p)&&d[2].equals(p)&&fresh(o[1],now)&&fresh(s[1],now)&&fresh(d[1],now)&&
                o[3].equals("inactive")&&o[4].equals("inactive")&&o[5].equals("end\n")&&s[3].equals("ready")&&s[4].equals("clear")&&
                Arrays.asList("review","idle").contains(s[5])&&s[6].equals("attempted")&&s[7].equals("end\n")&&
                d[3].equals("0")&&d[4].equals("0")&&d[5].equals("0")&&d[6].equals("0")&&d[7].equals("0")&&
                Arrays.asList("review","idle").contains(d[9])&&d[11].equals("end\n");
        }catch(Exception e){return false;}
    }
    private static boolean fresh(String time,long now){long t=Long.parseLong(time);return t>0&&now>=t&&now-t<=10000;}
    private static String identityGuard(){return "set -eu; test \"$(getprop ro.product.cpu.abi)\" = x86_64; test \"$(getprop ro.build.version.sdk)\" = 35; test \"$(getprop ro.dalvik.vm.native.bridge)\" = libnb.so; pm path dev.ody.pikmincontrol.mumu >/dev/null; pm path com.nianticlabs.pikmin >/dev/null; test ! -L "+BASE+"; ";}
    private static String noGame(){return "test -z \"$(pidof com.nianticlabs.pikmin || true)\"; ";}
    private static String deadOwner(String held)throws Exception {
        String[] b=binding(held);
        return "if test \"$(cat /proc/sys/kernel/random/boot_id)\" = "+b[0]+" && test -e /proc/"+b[1]+"/stat; then test \"$(awk '{print $22}' /proc/"+b[1]+"/stat)\" != "+b[2]+"; fi; ";
    }
    private static String offGuard(){
        return "for f in /data/local/tmp/pikmin-dispatch-mode.txt /data/local/tmp/pikmin-return-mode.txt /data/local/tmp/pikmin-planter-mode.txt /data/local/tmp/pikmin-planting-mode.txt /data/local/tmp/pikmin-flower-farm-mode.txt; do test -f $f; test ! -L $f; test \"$(cat $f)\" = off; done; "+
            "f=/data/local/tmp/pikmin-feeding-config.tsv; test ! -L $f; awk -F '\\t' 'NR==1 && $1==\"v3\" && $3==0 && $7==0 && $9==0 {ok=1} END {exit !ok}' $f; "+
            "for f in "+CoreUpdater.BASE+"/nectar_control_mode.txt "+BASE+"nectar_rpc_mode.txt; do test ! -L $f; case $(cat $f) in off|diag) ;; *) exit 1;; esac; done; "+
            "test ! -e "+BASE+"emulator-command; test ! -L "+BASE+"emulator-command; ";
    }
    /** A normal VM restart retires a stopped provider instead of pausing it.
     * Accept that separate terminal state only with no retained GPS ownership,
     * the exact retired incarnation dead, and no current overlay process.
     * This is fresh quiescence evidence, never a successful route receipt. */
    static String retiredGpsGuard(String provider,String retired)throws IOException {
        String[] f=provider.split(":",-1);
        require(provider.equals(retired)&&f.length==3&&uuid(f[0])&&
            f[1].matches("[1-9][0-9]{0,9}")&&f[2].matches("[1-9][0-9]{0,18}"),"退休GPS身分不符");
        return "if test \"$(cat /proc/sys/kernel/random/boot_id)\" = "+f[0]+" && test -e /proc/"+f[1]+"/stat; then test \"$(awk '{print $22}' /proc/"+f[1]+"/stat)\" != "+f[2]+"; fi; "+
            "command -v pidof >/dev/null; pcc_retired_gps=''; pcc_retired_rc=0; pcc_retired_gps=$(pidof "+EmulatorSavedJoystickProtocol.PACKAGE+":overlay) || pcc_retired_rc=$?; test $pcc_retired_rc = 1; test -z \"$pcc_retired_gps\"; ";
    }
    private static void stoppedGps(Context c,EmulatorSavedDailyStore s)throws Exception {
        if(s.get("gps.phase","").equals("PAUSED"))return;
        require(s.get("gps.phase","").equals("RETIRED")&&
            !GpsWorkLock.heldBy(c,EmulatorSavedJoystickPort.OWNER),"GPS尚未確認暫停或退休");
        // Existing explicit GPS UNKNOWN archives retain their stricter checks.
        EmulatorSavedDailyRuntime.requireGpsRetirementReady(c,s);
        root(identityGuard()+s.guard(false)+retiredGpsGuard(s.get("gps.provider",""),s.get("gps.retired-provider",""))+"echo RETIRED_GPS_QUIET");
    }
    private static void idle(Context c,EmulatorSavedDailyStore s)throws Exception {
        idle(c,s,false);
    }
    private static void idle(Context c,EmulatorSavedDailyStore s,boolean drainingObserver)throws Exception {
        String entered=stage;stage=entered+"-run";
        require(s!=null&&s.current()&&s.confirmedStop()&&!s.enabled(),"請先正常停止每日排程");
        stage=entered+"-work";
        require((drainingObserver||!EmulatorSavedDailyService.isRunning())&&!DispatchBatchService.isRunning()&&!FlowerFarmService.isRunning()&&
            !NearbyWorkState.pending(c)&&!RouteSession.pending(c)&&!WorkStatus.stoppingAll&&!SetupDeployment.pending(c)&&
            !CoreUpdater.pending(c)&&!UpdateInstaller.pending(c)&&!CoreInstallWizard.preparing(c),"仍有工作或維護未收尾");
        stage=entered+"-pending";
        for(String lane:new String[]{"gps","gps-bootstrap","batch","batch-close"})require(s.get(lane+".pending","").isEmpty(),"GPS或批次未決；原件保留");
        stage=entered+"-gps";stoppedGps(c,s);
        stage=entered+"-locks";
        require(!GpsWorkLock.heldBy(c,GpsWorkLock.BATCH)&&!GpsWorkLock.heldBy(c,GpsWorkLock.FLOWER_FARM),"其他工作持有定位");
        stage=entered+"-checkpoints";
        for(String owner:new String[]{GpsWorkLock.BATCH,GpsWorkLock.FLOWER_FARM}){
            if(CheckpointStore.present(c,owner)){
                WorkCheckpoint cp=CheckpointStore.read(c,owner);
                require(!cp.uncertain||CheckpointStore.archivedReviewed(c,cp),"批次有未確認操作");
            }
            require(!WorkProgress.active(WorkStatus.state(c,owner))&&!"stop-incomplete".equals(WorkStatus.state(c,owner)),"批次尚未收尾");
        }
        stage=entered+"-batch";
        if(!s.get("batch.key","").isEmpty()&&!EmulatorSavedDailyRuntime.stoppedBatchUnknownReviewed(c,s)){
            EmulatorDailyRunner.Binding b=EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(s.get("batch.binding","")));
            EmulatorDailyBatchOutcome o=EmulatorDailyBatchOutcome.readExact(EmulatorAndroidDailyStore.files(c),b,s.get("batch.key",""),false,EmulatorAndroidDailyStore::syncDirectory);
            require(o!=null&&!o.needsReview(),"已接受批次尚有未確認結果");
        }
        stage=entered;
    }
    private static String nativeRead(String leaf)throws Exception {
        require(Arrays.asList(LEAVES).contains(leaf)||Arrays.asList(TX+".safety",TX+".diagnostic","emulator_planting_observation.tsv").contains(leaf),"非允許的種花檔案");
        return root(identityGuard()+"p="+q(BASE+leaf)+"; test -f $p; test ! -L $p; test \"$(stat -c %h $p)\" = 1; test \"$(stat -c %s $p)\" -le 8192; base64 $p").replaceAll("\\s","");
    }
    private static String decode(String b){return new String(Base64.getDecoder().decode(b),StandardCharsets.UTF_8);}
    private static EmulatorSavedDailyRuntime.Identity observe(Context c,EmulatorSavedDailyStore s)throws Exception {
        String sample=stage;stage=sample+"-core";EmulatorSavedDailyRuntime.Identity i=new EmulatorSavedDailyRuntime(c,s).game();
        stage=sample+"-native";
        String o=decode(nativeRead("emulator_planting_observation.tsv")),safe=decode(nativeRead(TX+".safety")),d=decode(nativeRead(TX+".diagnostic"));
        stage=sample+"-inactive";
        require(inactive(o,safe,d,i.pid,System.currentTimeMillis()),"需要同程序新鮮非種花、無手動或自動在途證據");
        stage=sample+"-off";
        root(identityGuard()+i.guard()+deadOwner(s.get("plant.binding",""))+offGuard());
        stage=sample+"-account";
        require(binding(s.get("plant.binding",""))[4].equals(i.account),"帳號已不同");return i;
    }
    private static String observationGuard(int pid){
        String p=Integer.toString(pid);
        // Recheck in the mutation command, not merely the earlier Java sample.
        return "now=$(date +%s%3N); awk -F '\\t' -v p="+p+" -v now=$now 'NR==1 && NF==6 && $1==\"v1\" && $3==p && $4==\"inactive\" && $5==\"inactive\" && $6==\"end\" && now>=$2 && now-$2<=10000 {ok=1} END {exit !ok}' "+BASE+"emulator_planting_observation.tsv; "+
            "awk -F '\\t' -v p="+p+" -v now=$now 'NR==1 && NF==8 && $1==\"v1\" && $3==p && $4==\"ready\" && $5==\"clear\" && ($6==\"review\" || $6==\"idle\") && now>=$2 && now-$2<=10000 {ok=1} END {exit !ok}' "+BASE+TX+".safety; "+
            "awk -F '\\t' -v p="+p+" -v now=$now 'NR==1 && NF==12 && $3==p && $4==0 && $5==0 && $6==0 && $7==0 && $8==0 && ($10==\"review\" || $10==\"idle\") && now>=$2 && now-$2<=10000 {ok=1} END {exit !ok}' "+BASE+TX+".diagnostic; ";
    }
    private static String manifest()throws Exception {
        StringBuilder cmd=new StringBuilder(identityGuard()+"owner=$(stat -c %u "+BASE+"); test $owner -ge 10000; ");
        for(String leaf:LEAVES)cmd.append("p=").append(q(BASE+leaf)).append("; test ! -L $p; if test -e $p; then test -f $p; test \"$(stat -c %u $p)\" = $owner; test \"$(stat -c %h $p)\" = 1; test \"$(stat -c %a $p)\" = 600; test \"$(stat -c %s $p)\" -le 8192; printf '").append(leaf).append("\\t'; sha256sum $p | cut -d' ' -f1 | tr '\\n' '\\t'; stat -c '%u:%g:%a:%h:%s' $p; fi; ");
        String raw=root(cmd.toString());validateManifest(raw);return raw;
    }
    static void validateManifest(String raw)throws IOException {
        require(raw!=null&&raw.length()<4096,"封存清單無效");Set<String> names=new HashSet<>();
        for(String row:raw.trim().split("\n")){
            String[] f=row.split("\t",-1);require(f.length==3&&Arrays.asList(LEAVES).contains(f[0])&&names.add(f[0])&&f[1].matches("[a-f0-9]{64}")&&f[2].matches("[1-9][0-9]{4,}:[1-9][0-9]{4,}:600:1:[0-9]{1,4}"),"封存檔案身分無效");
        }
        require(names.contains(TX),"缺少原交易");
    }
    private static String fileGuard(String path,String hash,String meta){
        return "test -f "+q(path)+"; test ! -L "+q(path)+"; test \"$(sha256sum "+q(path)+" | cut -d' ' -f1)\" = "+hash+"; test \"$(stat -c '%u:%g:%a:%h:%s' "+q(path)+")\" = "+q(meta)+"; ";
    }
    private static String originalsGuard(String manifest){
        StringBuilder out=new StringBuilder();Set<String> names=new HashSet<>();
        for(String row:manifest.trim().split("\n")){String[] f=row.split("\t");out.append(fileGuard(BASE+f[0],f[1],f[2]));names.add(f[0]);}
        for(String leaf:LEAVES)if(!names.contains(leaf))out.append("test ! -e ").append(q(BASE+leaf)).append("; test ! -L ").append(q(BASE+leaf)).append("; ");
        return out.toString();
    }
    private static Path directory(EmulatorSavedDailyStore s)throws Exception {
        Path root=EmulatorAndroidDailyStore.files(s.app).resolve("saved-daily");
        require(s.wire.equals(EmulatorDailyBatchOutcome.readPrivate(root.resolve("config.tsv"),256)),"原排程已改變");
        Path p=root.resolve("plant-unknown-"+s.run);if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS)){Files.createDirectory(p);Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rwx------"));EmulatorAndroidDailyStore.syncDirectory(root);}
        require(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(p),"私人封存目錄不符");return p;
    }
    static void writeExact(Path p,String content)throws Exception {
        writeExact(p,content,f->Files.setPosixFilePermissions(f,PosixFilePermissions.fromString("rw-------")),EmulatorAndroidDailyStore::syncDirectory);
    }
    static void writeExact(Path p,String content,EmulatorDailyRouteWork.PrivateMode mode,EmulatorAndroidDailyStore.DirectorySync sync)throws Exception {
        byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
        if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS))try(FileChannel out=FileChannel.open(p,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            mode.apply(p);ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())out.write(b);out.force(true);
        }
        require(content.equals(EmulatorDailyBatchOutcome.readPrivate(p,262144)),"封存回讀不符；不覆蓋");
        try(FileChannel f=FileChannel.open(p,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){f.force(true);}sync.sync(p.getParent());
    }
    static boolean archiveNeedsGame(String maintenance){return maintenance!=null&&maintenance.isEmpty();}
    static boolean archiveNeedsGame(Context c){
        try{EmulatorSavedDailyStore s=EmulatorSavedDailyStore.open(c);
            return s==null||archiveNeedsGame(s.get("plant.archive-maintenance",""));
        }catch(Exception uncertain){return false;} // Never launch into uncertain archival continuation.
    }
    static String recover(Context c)throws Exception {
        // Match normal nearby cleanup's NearbyWorkState -> GPS lock order.
        // Other daily starts/owner changes share GPS; HC remains independent.
        synchronized(NearbyWorkState.class){synchronized(GpsWorkLock.class){
            stage="idle-proof";EmulatorSavedDailyStore s=EmulatorSavedDailyStore.open(c);idle(c,s,true);requireLiveMaintenance(s);
            String intent=s.get("plant.pending","");
            if(intent.isEmpty()){require(!s.get("plant.unknown-archive","").isEmpty(),"沒有可封存的舊種花");return "舊種花已保留為UNKNOWN。請登入並載入核心後，按開始每日排程。";}
            require(uuid(intent)&&s.get("intent."+intent,"").equals("NATIVE_ORDINARY_PLANTING"),"原種花意圖不符");
            stage="private-anchor";Path local=directory(s);String remote=BASE+"plant-unknown-"+s.run;
            String held=s.get("plant.binding",""),token=s.get("plant.token","");binding(held);require(uuid(token),"原token不符");
            String manifest,request;
            Path requestFile=local.resolve("request.tsv");
            if(Files.exists(requestFile,LinkOption.NOFOLLOW_LINKS)){
                request=EmulatorDailyBatchOutcome.readPrivate(requestFile,4096);manifest=EmulatorDailyBatchOutcome.readPrivate(local.resolve("manifest.tsv"),4096);validateManifest(manifest);
                String[] r=request.split("\t",-1);require(r.length==9&&r[0].equals("dead-plant-v1")&&r[1].equals(s.run)&&r[2].equals(intent)&&r[3].equals(token)&&r[4].equals(held)&&r[5].equals(CoreUpdater.pair(c).sha)&&r[6].equals(sha(manifest))&&r[7].equals("UNKNOWN")&&r[8].equals("end\n"),"封存原件已改變");
            }else{
                stage="inactive-first";EmulatorSavedDailyRuntime.Identity first=observe(c,s);Thread.sleep(1200);stage="inactive-second";EmulatorSavedDailyRuntime.Identity second=observe(c,s);
                require(first.key().equals(second.key()),"觀察期間遊戲已變更");
                stage="original-proof";String original=decode(nativeRead(TX));archiveTransaction(original,s,held,token);manifest=manifest();
                require(manifest.startsWith(TX+"\t"+sha(original)+"\t"),"原交易於觀察期間變更");
                if(manifest.contains("\n"+TX+".selection\t")){
                    stoppedSelectionGuard(decode(nativeRead(TX+".selection")),held,token);
                }
                stage="local-backup";writeExact(local.resolve("config.tsv"),s.wire);writeExact(local.resolve("stop.tsv"),EmulatorDailyBatchOutcome.readPrivate(local.getParent().resolve(s.run+".stop"),256));
                Path prefs=EmulatorAndroidDailyStore.files(c).getParent().resolve("shared_prefs/saved_daily_run_"+s.run+".xml");
                writeExact(local.resolve("original-run.xml"),EmulatorDailyBatchOutcome.readPrivate(prefs,262144));
                writeExact(local.resolve("manifest.tsv"),manifest);
                request="dead-plant-v1\t"+s.run+"\t"+intent+"\t"+token+"\t"+held+"\t"+second.pair.sha+"\t"+sha(manifest)+"\tUNKNOWN\tend\n";
                writeExact(requestFile,request);
            }
            String selectorGuard="";
            if(manifest.contains("\n"+TX+".selection\t")){
                String selected=root(identityGuard()+"if test -f "+q(BASE+TX+".selection")+"; then cat "+q(BASE+TX+".selection")+"; fi;");
                if(!selected.isEmpty())selectorGuard=stoppedSelectionGuard(selected,held,token);
            }
            String digest=sha(request),common=identityGuard()+s.guard(false)+deadOwner(held)+offGuard()+selectorGuard;
            // PREPARE is idempotent. Existing files must compare, never replace.
            stage="remote-prepare";root(common+"umask 077; if test ! -e "+q(remote)+"; then mkdir "+q(remote)+"; fi; test -d "+q(remote)+"; test ! -L "+q(remote)+"; test \"$(stat -c %u:%a "+q(remote)+")\" = 0:700; "+
                "p="+q(remote+"/request.tsv")+"; test ! -L $p; if test ! -e $p; then printf %s "+q(encoded(request))+" | base64 -d > $p; fi; test \"$(sha256sum $p | cut -d' ' -f1)\" = "+digest+"; sync;");
            // A stopped observer must not keep this run alive forever merely
            // because its original owner is gone. Fence restarts only after
            // the exact dead-owner request and original backups are durable.
            stage="archive-pause-observer";s.put("plant.archive-maintenance",digest);EmulatorSavedDailyService.pauseForPlantingMaintenance(s,digest);
            long waitUntil=android.os.SystemClock.elapsedRealtime()+5000;while(EmulatorSavedDailyService.isRunning()&&android.os.SystemClock.elapsedRealtime()<waitUntil)Thread.sleep(50);
            idle(c,s);
            stage="quiesced-read";String quiesced=root(common+"if test -f "+q(remote+"/quiesced.tsv")+" && test ! -L "+q(remote+"/quiesced.tsv")+"; then cat "+q(remote+"/quiesced.tsv")+"; fi;");
            if(quiesced.isEmpty()){
                stage="quiesce-proof";EmulatorSavedDailyRuntime.Identity live=observe(c,s);require(live.pair.sha.equals(CoreUpdater.pair(c).sha),"核心配對已變更");
                idle(c,s);archiveTransaction(decode(nativeRead(TX)),s,held,token);
                String quiet="quiesced-v1\t"+digest+"\t"+live.key()+"\t"+System.currentTimeMillis()+"\tend\n";
                // The fresh guarded force-stop invalidates all process-local
                // review/task state. This is NOT a planting STOP result.
                root(common+live.guard()+observationGuard(live.pid)+originalsGuard(manifest)+
                    "am force-stop com.nianticlabs.pikmin; "+noGame()+"test ! -e "+q(remote+"/quiesced.tsv")+"; test ! -L "+q(remote+"/quiesced.tsv")+"; printf %s "+q(encoded(quiet))+" | base64 -d > "+q(remote+"/quiesced.tsv")+"; sync;");
                quiesced=root(identityGuard()+"cat "+q(remote+"/quiesced.tsv"));
            }
            String[] quiet=quiesced.split("\t",-1);require(quiet.length==5&&quiet[0].equals("quiesced-v1")&&quiet[1].equals(digest)&&quiet[4].trim().equals("end"),"停止程序證據不完整");binding(quiet[2]);
            // Never resume half a retirement while any new game is running.
            root(common+noGame());
            StringBuilder retire=new StringBuilder(common+noGame()+"test ! -L "+q(remote+"/original")+"; test ! -L "+q(remote+"/retired")+"; mkdir -p "+q(remote+"/original")+" "+q(remote+"/retired")+"; ");
            for(String row:manifest.trim().split("\n")){
                String[] f=row.split("\t");String source=BASE+f[0],copy=remote+"/original/"+f[0],moved=remote+"/retired/"+f[0];
                retire.append("test ! -L ").append(q(copy)).append("; if test ! -e ").append(q(copy)).append("; then ").append(fileGuard(source,f[1],f[2])).append("cp -p ").append(q(source)).append(" ").append(q(copy)).append("; fi; ").append(fileGuard(copy,f[1],f[2]));
            }
            // Every original is copied and verified before the first rename.
            retire.append("sync; ").append(noGame());
            for(String row:manifest.trim().split("\n")){
                String[] f=row.split("\t");String source=BASE+f[0],moved=remote+"/retired/"+f[0];
                retire.append("if test -e ").append(q(source)).append("; then ").append(fileGuard(source,f[1],f[2])).append("test ! -e ").append(q(moved)).append("; test ! -L ").append(q(moved)).append("; mv ").append(q(source)).append(" ").append(q(moved)).append("; fi; ").append(fileGuard(moved,f[1],f[2]));
            }
            for(String leaf:LEAVES)retire.append("test ! -e ").append(q(BASE+leaf)).append("; test ! -L ").append(q(BASE+leaf)).append("; ");
            retire.append("sync; ");
            retire.append("p=").append(q(remote+"/retired.tsv")).append("; test ! -L $p; if test ! -e $p; then printf %s ").append(q(encoded(request))).append(" | base64 -d > $p; fi; test \"$(sha256sum $p | cut -d' ' -f1)\" = ").append(digest).append("; sync; echo RETIRED_UNKNOWN;");
            stage="retire-originals";require(root(retire.toString()).trim().equals("RETIRED_UNKNOWN"),"封存回讀未確認");
            writeExact(local.resolve("quiesced.tsv"),quiesced);writeExact(local.resolve("retired.tsv"),request);
            idle(c,s);require(intent.equals(s.get("plant.pending",""))&&token.equals(s.get("plant.token",""))&&held.equals(s.get("plant.binding","")),"原run已變更");
            stage="retired-final-proof";root(common+noGame());
            // All original bytes are retired and independently verified, the
            // current game is quiesced and every other stopped lane is idle.
            // Record UNKNOWN and STOPPED in ONE durable editor commit. A crash
            // must not leave an empty queue stuck in DRAINING. STOP/config and
            // original token/binding remain for requireStartReady's proof.
            idle(c,s);root(common+noGame());require(s.needsDrain()&&maintenanceQueue(s.journal.getAll()),"仍有其他未決；不結束原run");
            stage="commit-unknown";require(s.journal.edit().putString("result."+intent,PREFIX+digest).putString("plant.unknown-archive",digest)
                .putString("plant.unknown-intent",intent).remove("plant.pending").putString("run.lifecycle",EmulatorSavedDailyStore.STOPPED)
                .putString("run.closure","owned-controls-closed; gameplay-ledgers-preserved").commit(),"UNKNOWN與停止收尾尚未持久確認");
            require(s.replaceable()&&s.confirmedStop(),"UNKNOWN停止收尾回讀不符");
            return "舊種花已核驗封存為UNKNOWN，未重送或補成功。遊戲已安全關閉；請重新登入、載入核心，再按開始每日排程。";
        }}
    }
    /** Explicit maintenance for one narrowly identified expired result. This
     * abandons unverified process-local Tasks as UNKNOWN; it does not certify
     * their completion or replay a game mutation. Dead-owner recovery below
     * remains a separate transaction, with its original guards unchanged. */
    // own_starts is a process-lifetime count, not the current in-flight count.
    // Bind its latest own START to this exact active span; prior completed
    // cycles must not prevent an explicit UNKNOWN maintenance stop. Foreign
    // STARTs, later own STARTs and all original ownership/quiet guards reject.
    static boolean liveInactive(String observation,String safety,String diagnostic,String tx,String held,String token,int pid,long now){
        try{
            transaction(tx,held,token);String[] t=tx.split("\t",-1),o=observation.split("\t",-1),s=safety.split("\t",-1),d=diagnostic.split("\t",-1);
            String p=Integer.toString(pid);long sent=Long.parseLong(t[6]),started=Long.parseLong(t[8]);
            return pid>0&&binding(held)[1].equals(p)&&t[5].equals("review")&&t[7].equals("0")&&started>0&&sent>=started&&now>=sent&&now-sent>=120000&&
                o.length==6&&s.length==8&&d.length==12&&o[0].equals("v1")&&s[0].equals("v1")&&d[0].equals("v1")&&
                o[2].equals(p)&&s[2].equals(p)&&d[2].equals(p)&&fresh(o[1],now)&&fresh(s[1],now)&&fresh(d[1],now)&&
                o[3].equals("inactive")&&o[4].equals("inactive")&&o[5].equals("end\n")&&
                s[3].equals("ready")&&s[4].equals("clear")&&s[5].equals("review")&&s[6].equals("attempted")&&s[7].equals("end\n")&&
                d[3].matches("[1-9][0-9]{0,9}")&&Long.parseLong(d[3])<=0xffffffffL&&d[4].equals("0")&&Long.parseLong(d[5])>=started&&(Long.parseLong(d[5])<=sent||Long.parseLong(d[5])-sent<=60000)&&now>=Long.parseLong(d[5])&&now-Long.parseLong(d[5])>=120000&&d[6].equals("0")&&d[7].equals("0")&&
                Arrays.asList("reserve","normal").contains(d[8])&&d[9].equals("review")&&d[10].equals("capture-not-stopping")&&d[11].equals("end\n");
        }catch(Exception invalid){return false;}
    }
    private static Path liveDirectory(EmulatorSavedDailyStore s)throws Exception {
        Path p=directory(s).resolve("live-maintenance");
        if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS)){Files.createDirectory(p);Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rwx------"));EmulatorAndroidDailyStore.syncDirectory(p.getParent());}
        require(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(p),"維護私人目錄不符");return p;
    }
    private static void requireLiveMaintenance(EmulatorSavedDailyStore s)throws Exception {
        String marker=s.get("plant.maintenance","");if(marker.isEmpty())return;
        String request=EmulatorDailyBatchOutcome.readPrivate(liveDirectory(s).resolve("request.tsv"),4096);
        String[] r=request.split("\t",-1);require(r.length==9&&r[0].equals("live-plant-v1")&&r[1].equals(s.run)&&
            r[2].equals(s.get("plant.pending",s.get("plant.unknown-intent","")))&&r[3].equals(s.get("plant.token",""))&&r[4].equals(s.get("plant.binding",""))&&
            r[5].equals(CoreUpdater.pair(s.app).sha)&&r[7].equals("UNKNOWN")&&r[8].equals("end\n")&&sha(request).equals(marker),"維護原件不符");
        String manifest=EmulatorDailyBatchOutcome.readPrivate(liveDirectory(s).resolve("manifest.tsv"),4096);validateManifest(manifest);require(sha(manifest).equals(r[6]),"維護清單已變更");
    }
    private static EmulatorSavedDailyRuntime.Identity observeLive(Context c,EmulatorSavedDailyStore s,String tx)throws Exception {
        EmulatorSavedDailyRuntime.Identity i=new EmulatorSavedDailyRuntime(c,s).game();
        require(i.key().equals(s.get("plant.binding","")),"目前不是原種花程序；改用已失聯封存入口");
        require(liveInactive(decode(nativeRead("emulator_planting_observation.tsv")),decode(nativeRead(TX+".safety")),decode(nativeRead(TX+".diagnostic")),tx,
            s.get("plant.binding",""),s.get("plant.token",""),i.pid,System.currentTimeMillis()),"需要原程序新鮮非種花、無外來START及逾期review證據");
        root(identityGuard()+i.guard()+offGuard()+"for p in "+BASE+"feeding_pending.tsv "+BASE+"planter_pending.tsv; do test ! -e $p; test ! -L $p; done; ");return i;
    }
    static boolean maintenanceQueue(Map<String,?> values){
        Object plant=values.get("plant.pending");if(!(plant instanceof String)||!uuid((String)plant))return false;
        for(Map.Entry<String,?> e:values.entrySet())if(e.getKey().endsWith(".pending")&&!e.getKey().equals("plant.pending")&&
            (!(e.getValue() instanceof String)||!((String)e.getValue()).isEmpty()))return false;
        return true;
    }
    static String liveObservationGuard(int pid,long started,long sent){
        // All read-only proofs are repeated in the same command as force-stop.
        if(pid<=0||started<=0||sent<started)throw new IllegalArgumentException("Exact live owner and original submission required");
        return observationGuard(pid).replace("$4==0 && $5==0 && $6==0 && $7==0 && $8==0", "$4 ~ /^[1-9][0-9]{0,9}$/ && $4<=4294967295 && $5==0 && $6>="+started+" && ($6<="+sent+" || $6-"+sent+"<=60000) && now>=$6 && now-$6>=120000 && $7==0 && $8==0")
            .replace("NR==1 && NF==12 && $3==p", "NR==1 && NF==12 && $1==\"v1\" && $12==\"end\" && $3==p")
            .replace("($6==\"review\" || $6==\"idle\")", "$6==\"review\" && $7==\"attempted\" && $8==\"end\"")
            .replace("($10==\"review\" || $10==\"idle\")", "$10==\"review\" && $11==\"capture-not-stopping\"");
    }
    static String quiesceLive(Context c)throws Exception {
        synchronized(NearbyWorkState.class){synchronized(GpsWorkLock.class){
            stage="live-idle";EmulatorSavedDailyStore s=EmulatorSavedDailyStore.open(c);idle(c,s,true);
            require(EmulatorSavedDailyStore.DRAINING.equals(s.lifecycle()),"僅處理正常STOP後的收尾中工作");
            String intent=s.get("plant.pending",""),held=s.get("plant.binding",""),token=s.get("plant.token","");
            require(uuid(intent)&&s.get("intent."+intent,"").equals("NATIVE_ORDINARY_PLANTING"),"原意圖不符");binding(held);require(uuid(token),"原token不符");
            Path local=liveDirectory(s);String remote=BASE+"plant-maintenance-"+s.run;
            if(!s.get("plant.maintenance","").isEmpty()){
                requireLiveMaintenance(s);
                // If the original owner is already absent, never terminate a
                // newer process or replay an uncertain maintenance command.
                try{root(identityGuard()+deadOwner(held));return "原程序已退出；原件保留。請正常登入載入核心，再使用封存已失聯種花。";}catch(Exception originalStillLive){}
            }
            String tx=decode(nativeRead(TX));stage="live-inactive-first";EmulatorSavedDailyRuntime.Identity first=observeLive(c,s,tx);
            long observedAt=Long.parseLong(decode(nativeRead("emulator_planting_observation.tsv")).split("\t",-1)[1]);
            Thread.sleep(2500);stage="live-inactive-second";EmulatorSavedDailyRuntime.Identity second=observeLive(c,s,tx);require(first.key().equals(second.key()),"維護觀察期間程序已變更");
            require(Long.parseLong(decode(nativeRead("emulator_planting_observation.tsv")).split("\t",-1)[1])>observedAt,"原生觀察未前進");
            String manifest=manifest();require(manifest.startsWith(TX+"\t"+sha(tx)+"\t"),"維護原件變更");
            if(manifest.contains("\n"+TX+".selection\t"))selectionGuard(decode(nativeRead(TX+".selection")),held,token);
            String request="live-plant-v1\t"+s.run+"\t"+intent+"\t"+token+"\t"+held+"\t"+second.pair.sha+"\t"+sha(manifest)+"\tUNKNOWN\tend\n",digest=sha(request);
            stage="live-backup";
            // Preserve every original before stopping either observer or game.
            if(!Files.exists(local.resolve("request.tsv"),LinkOption.NOFOLLOW_LINKS)){
                // Resolve only the trusted Android Context anchor. s.root can
                // retain the OS /data/user/0 alias, which the strict reader
                // correctly rejects; descendant NOFOLLOW checks stay intact.
                Path saved=EmulatorAndroidDailyStore.files(c).resolve("saved-daily");
                writeExact(local.resolve("config.tsv"),s.wire);writeExact(local.resolve("stop.tsv"),EmulatorDailyBatchOutcome.readPrivate(saved.resolve(s.run+".stop"),256));
                writeExact(local.resolve("original-run.xml"),EmulatorDailyBatchOutcome.readPrivate(EmulatorAndroidDailyStore.files(c).getParent().resolve("shared_prefs/saved_daily_run_"+s.run+".xml"),262144));
                for(String row:manifest.trim().split("\n")){String leaf=row.split("\t")[0];writeExact(local.resolve(leaf),decode(nativeRead(leaf)));}
                writeExact(local.resolve("manifest.tsv"),manifest);writeExact(local.resolve("request.tsv"),request);
            }
            require(request.equals(EmulatorDailyBatchOutcome.readPrivate(local.resolve("request.tsv"),4096)),"維護request已變更");
            String common=identityGuard()+s.guard(false)+offGuard()+originalsGuard(manifest);
            stage="live-remote-backup";StringBuilder backup=new StringBuilder(common+"umask 077; if test ! -e "+q(remote)+"; then mkdir "+q(remote)+"; fi; test ! -L "+q(remote)+"; test \"$(stat -c %u:%a "+q(remote)+")\" = 0:700; ");
            for(String row:manifest.trim().split("\n")){String[] f=row.split("\t");String dest=remote+"/"+f[0];backup.append("test ! -L ").append(q(dest)).append("; if test ! -e ").append(q(dest)).append("; then cp -p ").append(q(BASE+f[0])).append(" ").append(q(dest)).append("; fi; ").append(fileGuard(dest,f[1],f[2]));}
            backup.append("p=").append(q(remote+"/request.tsv")).append("; test ! -L $p; if test ! -e $p; then printf %s ").append(q(encoded(request))).append(" | base64 -d > $p; fi; test \"$(sha256sum $p | cut -d' ' -f1)\" = ").append(digest).append("; sync;");root(backup.toString());
            s.put("plant.maintenance",digest);requireLiveMaintenance(s);
            stage="live-pause-observer";EmulatorSavedDailyService.pauseForPlantingMaintenance(s,digest);
            long until=android.os.SystemClock.elapsedRealtime()+5000;while(EmulatorSavedDailyService.isRunning()&&android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(50);
            idle(c,s);stage="live-final-proof";EmulatorSavedDailyRuntime.Identity live=observeLive(c,s,tx);
            String quiet="live-quiesced-v1\t"+digest+"\t"+live.key()+"\t"+System.currentTimeMillis()+"\tend\n";
            // An explicit UNKNOWN maintenance stop, never a successful STOP
            // receipt. The native original stays in place for dead-owner review.
            root(common+live.guard()+liveObservationGuard(live.pid,Long.parseLong(tx.split("\t",-1)[8]),Long.parseLong(tx.split("\t",-1)[6]))+
                "for p in "+BASE+"feeding_pending.tsv "+BASE+"planter_pending.tsv; do test ! -e $p; test ! -L $p; done; "+
                "am force-stop com.nianticlabs.pikmin; "+noGame()+"p="+q(remote+"/quiesced.tsv")+"; test ! -e $p; test ! -L $p; printf %s "+q(encoded(quiet))+" | base64 -d > $p; sync;");
            writeExact(local.resolve("quiesced.tsv"),root(identityGuard()+noGame()+"cat "+q(remote+"/quiesced.tsv")));
            return "已完成維護停止，原種花結果仍是UNKNOWN，原件未移除。請重新登入、正常載入核心，再按封存已失聯種花；未重送遊戲動作。";
        }}
    }
    static void requireStartReady(Context c,EmulatorSavedDailyStore prior)throws Exception {
        String digest=prior.get("plant.unknown-archive","");if(digest.isEmpty())return;
        Path local=directory(prior);String request=EmulatorDailyBatchOutcome.readPrivate(local.resolve("retired.tsv"),4096);
        require(digest.equals(sha(request))&&prior.get("result."+prior.get("plant.unknown-intent",""),"").equals(PREFIX+digest),"UNKNOWN封存收據不完整");
        String[] f=request.split("\t",-1);require(f.length==9&&f[1].equals(prior.run)&&f[5].equals(CoreUpdater.pair(c).sha),"封存與目前核心不符");
        EmulatorSavedDailyRuntime.Identity live=observe(c,prior);
        String[] quiet=EmulatorDailyBatchOutcome.readPrivate(local.resolve("quiesced.tsv"),4096).split("\t",-1);
        require(quiet.length==5&&quiet[1].equals(digest)&&!quiet[2].equals(live.key()),"請重新登入並正常載入核心");
        root(identityGuard()+live.guard()+offGuard()+"test \"$(sha256sum "+q(BASE+"plant-unknown-"+prior.run+"/retired.tsv")+" | cut -d' ' -f1)\" = "+digest+"; ");
        for(String leaf:LEAVES)root(identityGuard()+live.guard()+"test ! -e "+q(BASE+leaf)+"; test ! -L "+q(BASE+leaf)+";");
    }
}
