package dev.ody.pikmincontrol;
import android.content.Context;

/** STATUS-only fresh reservation/core read under the existing GPS actor.
 * Framed reads are NOT an atomic native-writer snapshot; no PID cache. */
final class EmulatorRouteRuntime {
    static String command(){
        String guard=EmulatorRouteGuard.namespaceCommand();
        String quoted="'"+guard.replace("'","'\\''")+"'";
        // Preserve the original guard's inner three-second timeout AND exit
        // result: ProbeSnapshot alone does not propagate each section's exit.
        return ProbeSnapshot.command("timeout 3 sh -c "+quoted+
            "; pcc_guard_exit=$?; printf '\\nguard-exit:%s\\n' \"$pcc_guard_exit\"",
            "pidof com.nianticlabs.pikmin || true",
            "cat /data/user/0/com.nianticlabs.pikmin/files/core_runtime.tsv 2>/dev/null || true",
            "cat /data/user/0/com.nianticlabs.pikmin/files/unified_runtime.tsv 2>/dev/null || true",
            "pidof com.nianticlabs.pikmin || true");
    }
    static String checkedPid(CorePair pair,BoundedCommand.Result read,long now){
        if(read==null||read.exitCode==null||read.exitCode!=0||read.text==null||read.text.indexOf('\r')>=0)
            throw new IllegalStateException("Route fresh read exit unconfirmed");
        String[] s=ProbeSnapshot.checkedSections(read,5);
        // Original guard allows only exact absent LF, no stderr/partial text.
        if(!"absent\n\nguard-exit:0\n".equals(s[0]))
            throw new IllegalStateException("Route reservation unavailable");
        return pair.runtime(s[2],s[1].trim(),s[4].trim(),now)&&
            pair.gpsRuntime(s[3],s[1].trim(),s[4].trim(),now)?s[1].trim():"";
    }
    static void require(Context context,String expected)throws Exception {
        CorePair pair=CoreUpdater.pair(context); // re-read actual signed pair
        BoundedCommand.Result result=BoundedCommand.root(command(),6,32768);
        String actual=checkedPid(pair,result,System.currentTimeMillis());
        if(expected==null||!expected.equals(actual)||actual.isEmpty())
            throw new IllegalStateException("Route game PID changed");
    }
}