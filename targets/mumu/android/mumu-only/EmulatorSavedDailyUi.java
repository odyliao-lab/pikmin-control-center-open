package dev.ody.pikmincontrol;

import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

/** Same finite mask/recovery/denial journal, with the new independent run owner. */
final class EmulatorSavedDailyUi implements AutoCloseable,EmulatorDailyUiRecovery.Port {
    private final EmulatorSavedDailyRuntime runtime;
    private EmulatorSavedDailyRuntime.Identity game;
    private EmulatorDailyUiMatcher matcher;private EmulatorDailyUiStore store;private EmulatorDailyUiRecovery recovery;
    private boolean closed;private String identity,displayLogical,displayPhysical;
    EmulatorSavedDailyUi(EmulatorSavedDailyRuntime runtime){this.runtime=runtime;}
    void tick()throws Exception{
        if(!active())return;
        EmulatorSavedDailyRuntime.Identity current=runtime.game();String key=EmulatorDailyRunner.digest(current.key());
        if(!runtime.active()&&!current.key().equals(runtime.store.get("plant.binding","")))return;
        if(identity!=null&&!identity.equals(key)){
            EmulatorSavedDailyRuntime.Identity previous=game;
            if(previous==null||!previous.account.equals(current.account)||previous.pair.code!=current.pair.code||
               !previous.pair.version.equals(current.pair.version)||!previous.boot.equals(current.boot)||previous.pid==current.pid)
                throw new IOException("UI owner/account change requires review");
            // Do not kill or detach a live actor. The old game must really have
            // ended, and the fresh new game must be clear before any new input.
            if(!EmulatorSavedDailyRuntime.root("set -eu; "+current.sessionGuard()+"test ! -e /proc/"+previous.pid+"; echo OLD_UI_OWNER_ENDED").trim().equals("OLD_UI_OWNER_ENDED"))
                throw new IOException("Old UI game owner still present");
            boolean changed=false;game=current;String oldLogical=displayLogical,oldPhysical=displayPhysical;
            displayLogical=displayPhysical=null;
            try{
                EmulatorDailyUiRecovery.Frame clear=capture();long age=monotonic()-clear.monotonic;
                if(age<0||age>3000||!EmulatorDailyUiRecovery.ownerChangeAllowed(true,true,true,clear.scene))
                    throw new IOException("New UI game needs a clear main scene; old input retained");
                store.retireOnClear(key,clear); // Old intents/results stay byte-exact, including UNKNOWN.
                store.close();store=null;recovery=null;matcher=null;identity=null;changed=true;
            }finally{if(!changed){game=previous;displayLogical=oldLogical;displayPhysical=oldPhysical;}}
        }
        game=current;
        if(recovery==null){
            try(InputStream in=runtime.app.getAssets().open("daily-ui-masks.tsv")){matcher=new EmulatorDailyUiMatcher(in);}
            Path root=runtime.app.getFilesDir().toPath().resolve("daily-ui");
            if(!Files.exists(root)){Files.createDirectory(root);Files.setPosixFilePermissions(root,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));EmulatorAndroidDailyStore.syncDirectory(root.getParent());}
            store=EmulatorDailyUiStore.open(root.resolve(key),key,EmulatorAndroidDailyStore::syncDirectory);identity=key;recovery=new EmulatorDailyUiRecovery(this,store);
        }
        recovery.tick();
    }
    public boolean active()throws Exception{return !closed&&(runtime.active()||runtime.plantDrainPending());}
    public long monotonic(){return SystemClock.elapsedRealtime();}
    public boolean navigationAllowed(){return !DispatchBatchService.isRunning()&&!GpsWorkLock.heldBy(runtime.app,GpsWorkLock.BATCH);}
    private String ownerGuard()throws Exception{
        if(game==null||!active())throw new IOException("Saved UI not active");
        return "set -eu; "+runtime.store.guard(runtime.active())+game.guard()+
            "now=$(date +%s%3N); awk -F '\\t' -v now=\"$now\" 'NF==23 && $3=="+game.pid+" && $4==\"0.20.3\" && $5==900178 && $15=="+game.epoch+" && $16==\"ready\" && now>=$2 && now-$2<=12000 {ok=1} END{exit !ok}' "+EmulatorSavedDailyRuntime.FILES+"core_capabilities.tsv; "+
            "";
    }
    // MuMu's WindowManager/task Activity dumps have crashed system_server. Its windows-only
    // dump omits mCurrentFocus on this build, so retain an actual input-focus
    // guard via InputDispatcher, not just a top Activity or a visible window.
    // The same dump can append an ANR-era Input Dispatcher snapshot. Only the
    // unique CURRENT section may supply focus; historical focus never authorizes input.
    static String focusQuery(){return "dumpsys input | awk '/^Input Dispatcher State:$/ {current=1;sections++;s=0;next} /^[^ ]/ {current=0;s=0} current && /^  FocusedDisplayId: [0-9]+$/ {display=$2;displays++;next} current && /^  FocusedWindows:$/ {s=1;windows++;next} s && /^  [^ ]/ {s=0} current && s && /^    displayId=[0-9]+, name=/ {id=$0;sub(/^    displayId=/,\"\",id);sub(/,.*/,\"\",id);if(id==display){v=$0;n++}} END {if(sections!=1||windows!=1||displays!=1||n!=1)exit 1;print v}'";}
    // Legacy ticket actors still use default-display capture/input. Preserve
    // their display0 restriction; only this saved actor binds explicit IDs.
    static String focusGuard(){return logicalFocusGuard("0");}
    private static String logicalFocusGuard(String logical){return "case \"$("+focusQuery()+")\" in \"    displayId="+logical+", name=\"*com.nianticlabs.pikmin/com.nianticproject.ichigo.IchigoUnityPlayerActivity*) ;; *) exit 1;;esac; ";}
    static String viewportProgram(){return "/^  mViewports=\\[/ {views++;count=split($0,entries,/DisplayViewport[{]/);for(i=2;i<=count;i++){entry=entries[i];sub(/[}].*$/,\"\",entry);id=entry;sub(/^.*displayId=/,\"\",id);sub(/,.*/,\"\",id);if(id!=target)continue;matches++;if(entry!~/^type=INTERNAL, valid=true, isActive=true, /||index(entry,\", orientation=0, logicalFrame=Rect(0, 0 - 1080, 1920), physicalFrame=Rect(0, 0 - 1080, 1920), deviceWidth=1080, deviceHeight=1920\")==0){bad=1;exit 1}physical=entry;sub(/^.*uniqueId=/,\"\",physical);sub(/,.*/,\"\",physical);q=sprintf(\"%c\",39);if(index(physical,q\"local:\")!=1||substr(physical,length(physical),1)!=q){bad=1;exit 1}physical=substr(physical,8,length(physical)-8);if(physical!~/^[0-9]+$/||length(physical)>19){bad=1;exit 1}}} END {if(bad||views!=1||matches!=1)exit 1;print physical}";}
    static String viewportQuery(String logical)throws IOException{
        if(logical==null||!logical.matches("0|[1-9][0-9]{0,5}"))throw new IOException("Invalid UI display");
        return "dumpsys display | awk -v target="+logical+" '"+viewportProgram()+"'";
    }
    static String focusedLogical(String focus)throws IOException{
        if(focus==null||!focus.matches("    displayId=(0|[1-9][0-9]{0,5}), name='[^'\\r\\n]{1,96} com[.]nianticlabs[.]pikmin/com[.]nianticproject[.]ichigo[.]IchigoUnityPlayerActivity'\\n?"))
            throw new IOException("Current game input focus required");
        return focus.substring(14,focus.indexOf(','));
    }
    private void bindDisplay()throws Exception{
        if(displayLogical!=null)return;
        String logical=focusedLogical(EmulatorSavedDailyRuntime.root(ownerGuard()+focusQuery()));
        String physical=EmulatorSavedDailyRuntime.root(ownerGuard()+logicalFocusGuard(logical)+viewportQuery(logical)).trim();
        if(!physical.matches("[1-9][0-9]{0,18}"))throw new IOException("Physical display unavailable");
        displayLogical=logical;displayPhysical=physical;
    }
    private String guard()throws Exception{
        bindDisplay();
        // Pin both identities for this UI actor; a display replacement cannot
        // redirect a captured frame's input. Only proven-dead owner retirement
        // above may bind a different display, with the original clear-scene gate.
        return ownerGuard()+"focused=$("+focusQuery()+"); case \"$focused\" in \"    displayId="+displayLogical+", name=\"*com.nianticlabs.pikmin/com.nianticproject.ichigo.IchigoUnityPlayerActivity*) ;; *) exit 1;;esac; "+
            "test \"$("+viewportQuery(displayLogical)+")\" = "+displayPhysical+"; ";
    }
    public EmulatorDailyUiRecovery.Frame capture()throws Exception{
        long begin=monotonic();byte[] bytes=binary(guard()+"date +%s%3N; exec screencap -d "+displayPhysical);
        if(bytes.length!=8294430||bytes[13]!=10)throw new IOException("Full raw/clock required");
        String time=new String(bytes,0,13,StandardCharsets.US_ASCII);if(!time.matches("[0-9]{13}"))throw new IOException("UI clock invalid");
        byte[] raw=java.util.Arrays.copyOfRange(bytes,14,bytes.length);
        return new EmulatorDailyUiRecovery.Frame(EmulatorSavedUiDrain.scene(matcher.classify(new EmulatorDailyUiMatcher.Raw(raw)),runtime.active()),raw,Long.parseLong(time),begin);
    }
    public void tap(EmulatorDailyUiRecovery.Frame frame)throws Exception{
        if(!frame.scene.clickable()||frame.scene.navigation()&&!navigationAllowed())throw new IOException("Not a permitted UI action");
        if(EmulatorSavedUiDrain.scene(frame.scene,runtime.active())!=frame.scene)throw new IOException("Not an owned result drain scene");
        if(frame.scene.reward()){
            if(!navigationAllowed())throw new IOException("Reward waits for batch work");
            EmulatorDailyUiRecovery.Frame fresh=capture();
            if(fresh.scene!=frame.scene||monotonic()-fresh.monotonic>3000||!navigationAllowed())throw new IOException("Reward selection changed; original intent retained");
            frame=fresh;
        }
        String cmd=guard()+"now=$(date +%s%3N); test \"$now\" -ge "+frame.clock+"; test \"$((now-"+frame.clock+"))\" -le 3000; input -d "+displayLogical+" tap "+frame.scene.x+" "+frame.scene.y+"; printf UI_SENT";
        if(!EmulatorSavedDailyRuntime.root(cmd).equals("UI_SENT"))throw new IOException("UI input unknown; no replay");
    }
    private byte[] binary(String command)throws Exception{
        Process p=new ProcessBuilder("su","-mm","-c","timeout 3 sh -c '"+command.replace("'","'\\''")+"'").start();
        FutureTask<byte[]> out=new FutureTask<>(()->EmulatorDailyUiMatcher.read(p.getInputStream(),8294430));
        FutureTask<byte[]> err=new FutureTask<>(()->EmulatorDailyUiMatcher.read(p.getErrorStream(),4096));
        Thread a=new Thread(out,"saved-ui-output"),b=new Thread(err,"saved-ui-error");a.setDaemon(true);b.setDaemon(true);a.start();b.start();
        try{if(!p.waitFor(5,TimeUnit.SECONDS)||p.exitValue()!=0)throw new IOException("UI capture unavailable");byte[] data=out.get(250,TimeUnit.MILLISECONDS);if(err.get(250,TimeUnit.MILLISECONDS).length!=0)throw new IOException("UI capture stderr");return data;}finally{p.destroyForcibly();}
    }
    public void close(){closed=true;if(store!=null)try{store.close();}catch(Exception ignored){}}
}
