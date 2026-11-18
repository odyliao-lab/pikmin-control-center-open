package dev.ody.pikmincontrol;

/** MuMu passive native report bridge. No query requests/control writes/managed
 * mutations. Route/daily caller must supply its independently held live binding.
 * Does not convert a stock snapshot into authority to rotate or replay. */
final class EmulatorOwnedPetalGateway {
    private static final String ROOT="/data/user/0/com.nianticlabs.pikmin/files";
    static EmulatorPetalRotation.Snapshot read(String pid,long epoch,String account,String token,long started,String selected)throws Exception {
        requirePid(pid);
        String raw=GpsModuleGateway.farmRead(ProbeSnapshot.command(
            "pidof com.nianticlabs.pikmin",command(),"pidof com.nianticlabs.pikmin"));
        String[] sections=sections(raw,3);
        return decode(sections[0],sections[1],sections[2],pid,epoch,account,token,started,selected,System.currentTimeMillis());
    }
    /** Insert this read-only section into the existing route probe rather than
     * issuing another root round trip. It deliberately has no caller arguments. */
    static String command() {
        String path=ROOT+"/emulator_planting_transaction.tsv.petals";
        // Open the exact private leaf and compare held metadata before/after.
        // A concurrent atomic publication is rejected instead of mixing frames.
        return "g=$(stat -c %u "+ROOT+") && case \"$g\" in ''|*[!0-9]*) exit 1;; esac; "
            +"test \"$g\" -ge 10000 && test ! -L "+path+" && test -f "+path+" && "
            +"test \"$(stat -c %u:%g:%a:%h "+path+")\" = \"$g:$g:600:1\" && "
            // ProbeSnapshot runs this in a subshell. $$ remains the parent PID
            // in Android mksh; read /proc/self/stat with a shell builtin instead.
            +"exec 3<"+path+" && IFS=' ' read -r held_pid held_stat < /proc/self/stat && "
            +"case \"$held_pid\" in ''|*[!0-9]*) exit 1;; esac; "
            +"p=/proc/$held_pid/fd/3 && b=$(stat -Lc %d:%i:%s:%Y:%Z $p) && "
            +"test \"$(stat -Lc %s $p)\" -le 32768 && raw=$(cat <&3) && "
            +"test \"$(stat -Lc %s $p)\" -eq $(( ${#raw} + 1 )) && "
            +"test \"$(stat -Lc %d:%i:%s:%Y:%Z $p)\" = \"$b\" && "
            +"test \"$(stat -c %d:%i:%s:%Y:%Z "+path+")\" = \"$b\" && printf '%s\\n' \"$raw\"";
    }
    /** Decode already-framed coalesced sections. No transport, control writes,
     * or authority inference. All expected bindings still come from the caller. */
    static EmulatorPetalRotation.Snapshot decode(String before,String packet,String after,
            String pid,long epoch,String account,String token,long started,String selected,long now) {
        requirePid(pid);
        if(!(pid+"\n").equals(before)||!(pid+"\n").equals(after))
            throw new IllegalStateException("Game changed during stock read");
        return EmulatorOwnedPetalReader.read(packet,Integer.parseInt(pid),epoch,account,token,started,selected,now);
    }
    /** Coalesced callers must use this before ProbeSnapshot can normalize CR.
     * A missing stock section stays missing, not an empty inventory. */
    static String[] sections(String raw,int count) {
        if(raw==null || count<1 || count>64 || raw.indexOf('\r')>=0)throw new IllegalStateException("Noncanonical stock transport framing");
        java.util.regex.Matcher markers=java.util.regex.Pattern.compile("\n__PCC_READ_([0-9]+|END)__\n").matcher(raw);
        int i=0;
        while(markers.find()) {
            String expected=i<count ? Integer.toString(i) : "END";
            if(i>count || !expected.equals(markers.group(1)))throw new IllegalStateException("Unexpected stock probe section");
            i++;
        }
        if(i!=count+1)throw new IllegalStateException("Incomplete stock probe sections");
        return ProbeSnapshot.sections(raw,count);
    }
    private static void requirePid(String pid) {
        if(pid==null || !pid.matches("[1-9][0-9]{0,8}"))throw new IllegalArgumentException("Bound game PID required");
    }
}
