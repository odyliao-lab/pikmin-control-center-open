package dev.ody.pikmincontrol;

final class NearbyGateway {
    private static final String KINDS = "/data/local/tmp/pikmin-nearby-kinds.txt";
    static NearbyState read() throws Exception {
        String[] rows = ProbeSnapshot.sections(root(ProbeSnapshot.command(
                "if test -e /data/local/tmp/pikmin-dispatch-mode.txt; then cat /data/local/tmp/pikmin-dispatch-mode.txt 2>/dev/null || echo __PCC_CONTROL_ERROR__; else printf off; fi",
                "if test -e /data/local/tmp/pikmin-dispatch-kinds.txt; then cat /data/local/tmp/pikmin-dispatch-kinds.txt 2>/dev/null || echo __PCC_CONTROL_ERROR__; else printf all; fi",
                "if [ -e " + KINDS + " ]; then cat " + KINDS + "; else printf 'fruit seed'; fi",
                "cat /data/user/0/com.nianticlabs.pikmin/files/nearby_dispatch_status.tsv 2>/dev/null || true",
                OwnerGameProcess.command(),
                "cat /data/user/0/com.nianticlabs.pikmin/files/nearby_gate_status.tsv 2>/dev/null || true",
                "p=$("+OwnerGameProcess.command()+"); case \"$p\" in ''|*[!0-9]*) ;; *) cat /proc/$p/stat 2>/dev/null || true ;; esac",
                "getconf CLK_TCK", OwnerGameProcess.command(),
                "if test -e /data/local/tmp/pikmin-dispatch-mode.txt; then stat -c %a /data/local/tmp/pikmin-dispatch-mode.txt 2>/dev/null || echo __PCC_CONTROL_ERROR__; else printf missing; fi",
                "if test -e /data/user/0/com.nianticlabs.pikmin/files/nearby_engine_capability.tsv; then cat /data/user/0/com.nianticlabs.pikmin/files/nearby_engine_capability.tsv 2>/dev/null || echo __PCC_CAPABILITY_READ_FAILED__; fi")), 11);
        long wall = System.currentTimeMillis(), boot = android.os.SystemClock.elapsedRealtime();
        return new NearbyState(rows[0], rows[1], rows[2], rows[3], rows[4], wall, rows[5],
                NearbyCapability.verifiedEngine(rows[10], rows[5], rows[4], rows[6], rows[7], rows[8], wall, boot),rows[9]);
    }
    static void setKinds(int mask) throws Exception {
        String value = NearbyKinds.encode(mask);
        root("printf '" + value + "\\n' > " + KINDS + ".tmp && chmod 0644 " + KINDS
                + ".tmp && mv " + KINDS + ".tmp " + KINDS);
    }
    /** Called only under the settings lease and AutomationPreferences monitor. */
    static String reconcile(boolean enabled) throws Exception {
        NearbyState state=read();
        String waiting=NearbyPreference.waiting(enabled,state);
        if(!waiting.isEmpty())return waiting;
        if(NearbyPreference.needsWrite(enabled,state))root(controlCommand(enabled));
        return "";
    }
    static String controlCommand(boolean enabled) {
        String mode=enabled?"armed":"off";
        // Do not overwrite a batch target or a farm mask, even if the earlier read raced.
        return "set -e; p=/data/local/tmp/pikmin-dispatch-mode.txt; "
            +"if test -e $p; then m=$(cat $p); else m=off; fi; case \"$m\" in off|armed) ;; *) exit 12 ;; esac; "
            +"test ! -e /data/local/tmp/pikmin-dispatch-target.txt; test ! -e /data/local/tmp/pikmin-dispatch-ready.tsv; "
            +"k=/data/local/tmp/pikmin-dispatch-kinds.txt; if test -e $k; then k=$(cat $k); else k=all; fi; case \"$k\" in all|seed|fruit|gift) ;; *) exit 13 ;; esac; "
            +"if test ! -e $p || test \"$m\" != "+mode+"; then printf '"+mode+"\\n' > $p; fi; chmod 0644 $p; test \"$(cat $p)\" = "+mode;
    }
    private static String root(String command) throws Exception {
        return GpsModuleGateway.farmRead(command);
    }
}
