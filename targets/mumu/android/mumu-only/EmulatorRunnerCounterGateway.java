package dev.ody.pikmincontrol;

/** Android counter input for the daily runner. The held Binding is not inferred
 * from any packet. This supplies a nursery snapshot, NEVER readiness or work
 * authority: APK/core byte identity, ownership, resources and location remain
 * the execution gate's responsibility. Not installed or scheduled by this class. */
final class EmulatorRunnerCounterGateway {
    private static final String ROOT="/data/user/0/com.nianticlabs.pikmin/files";
    static EmulatorGameDailySteps.Observation read(android.content.Context context,
            EmulatorDailyRunner.Binding held,CorePair pair)throws Exception {
        return capture(context,held,pair).observation;
    }
    static final class Captured {
        final String packet;
        final EmulatorGameDailySteps.Observation observation;
        private Captured(String packet,EmulatorGameDailySteps.Observation observation){this.packet=packet;this.observation=observation;}
    }
    /** Preserve the actual framed counter section; never reconstruct from parsed values. */
    static Captured capture(android.content.Context context,
            EmulatorDailyRunner.Binding held,CorePair pair)throws Exception {
        EmulatorRuntime.require();
        requirePair(held,pair);
        String pid=Integer.toString(held.pid);
        if(!pid.equals(CoreUpdater.runtimePid(context,pair)))bad();
        String identity="pidof com.nianticlabs.pikmin && cat /proc/sys/kernel/random/boot_id";
        String raw=GpsModuleGateway.farmRead(ProbeSnapshot.command(identity,
                bounded("automation_session.tsv",2048),bounded("core_capabilities.tsv",32768),
                passiveScalarRead(EmulatorGameDailyStepsGateway.command()),identity));
        Captured result=decodeCaptured(raw,held,pair,System.currentTimeMillis());
        if(!pid.equals(CoreUpdater.runtimePid(context,pair)))bad();
        return result;
    }
    static EmulatorGameDailySteps.Observation decode(String raw,EmulatorDailyRunner.Binding held,
            CorePair pair,long now) {
        return decodeCaptured(raw,held,pair,now).observation;
    }
    static Captured decodeCaptured(String raw,EmulatorDailyRunner.Binding held,CorePair pair,long now) {
        requirePair(held,pair);
        if(raw==null || raw.length()>40000 || raw.indexOf('\r')>=0)bad();
        String[] p=EmulatorOwnedPetalGateway.sections(raw,5);
        String pid=Integer.toString(held.pid),identity=pid+"\n"+held.boot+"\n";
        if(!identity.equals(p[0]) || !identity.equals(p[4]))bad();
        // SessionIdentity intentionally permits idle or busy. A read-only
        // counter must not infer execution authority from either state.
        String account=SessionIdentity.parse(p[1],pid,pid,now);
        CoreCapabilities caps=CoreCapabilities.parse(ReadinessSample.Read.ok(p[2]),pid,pair.version,pair.code,now);
        if(!held.account.equals(account) || !caps.ok() || !caps.identityReady() ||
                !caps.domainReady() || caps.sessionEpoch!=held.sessionEpoch)bad();
        return new Captured(p[3],EmulatorGameDailySteps.read(p[3],held,now));
    }
    private static void requirePair(EmulatorDailyRunner.Binding held,CorePair pair) {
        if(held==null || pair==null || !pair.unified || !held.coreSha.equals(pair.sha) ||
                !("0.10.22".equals(pair.version)&&pair.code==900126 ||
                  "0.10.23".equals(pair.version)&&pair.code==900127 ||
                  "0.20.3".equals(pair.version)&&pair.code==900178))bad();
    }
    /** Immutable bounded read with held FD/path identity. No request publication. */
    static String bounded(String leaf,int max) {
        if(!(leaf.equals("automation_session.tsv") && max==2048 ||
                leaf.equals("core_capabilities.tsv") && max==32768))bad();
        String path=ROOT+"/"+leaf;
        String once="set -e; test -d "+ROOT+" && test ! -L "+ROOT+" && g=$(stat -c %u "+ROOT+") && "
            +"case \"$g\" in ''|*[!0-9]*) exit 1;; esac && test \"$g\" -ge 10000 && "
            +"test -f "+path+" && test ! -L "+path+" && exec 3<"+path+" && "
            +"IFS=' ' read -r held_pid held_stat < /proc/self/stat && "
            +"case \"$held_pid\" in ''|*[!0-9]*) exit 1;; esac && p=/proc/$held_pid/fd/3 && "
            // Existing automation_session/core_capabilities producers publish
            // 0644, unlike the separate private daily-counter leaf (0600).
            +"test \"$(stat -Lc %u:%g:%a:%h $p)\" = \"$g:$g:644:1\" && n=$(stat -Lc %s $p) && "
            +"case \"$n\" in ''|*[!0-9]*) exit 1;; esac && test \"$n\" -gt 0 && test \"$n\" -le "+max+" && "
            +"b=$(stat -Lc %d:%i:%s:%Y:%Z:%u:%g:%a:%h $p) && raw=$(cat <&3) && "
            +"test \"$n\" -eq $(( ${#raw} + 1 )) && test \"$(stat -Lc %d:%i:%s:%Y:%Z:%u:%g:%a:%h $p)\" = \"$b\" && "
            +"test ! -L "+path+" && test -f "+path+" && "
            +"test \"$(stat -c %d:%i:%s:%Y:%Z:%u:%g:%a:%h "+path+")\" = \"$b\" && printf '%s\\n' \"$raw\"";
        // Atomic heartbeat publication may invalidate a held FD while reading.
        // Retry only this passive scalar read, never an effect or whole work.
        // Every attempt keeps ALL original guards; discard failed stdout.
        // The enclosing farmRead deadline and later identity/freshness remain.
        return passiveScalarRead(once);
    }
    /** Only internally generated passive scalar commands. Never wrap effects,
     * framing, or a complete work operation. Failed stdout is not reused. */
    static String passiveScalarRead(String once) {
        if(once==null || !once.startsWith("set -e; test -d /data/user/0/com.nianticlabs.pikmin/files ") ||
                !once.endsWith("printf '%s\\n' \"$raw\""))bad();
        return "set +e; for pcc_read_try in 1 2 3; do pcc_read_wire=$( ("+once+") ); pcc_read_rc=$?; "
            +"if test \"$pcc_read_rc\" -eq 0; then printf '%s\\n' \"$pcc_read_wire\"; exit 0; fi; done; exit 1";
    }
    private static void bad(){throw new IllegalStateException("Fresh independently held runner account/session/boot counter required");}
}
