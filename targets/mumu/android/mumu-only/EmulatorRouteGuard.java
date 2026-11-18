package dev.ody.pikmincontrol;

/** MuMu legacy RouteSession interlock only. Absence is NOT a global work lock. */
final class EmulatorRouteGuard {
    interface Probe { BoundedCommand.Result read(String command) throws Exception; }
    static String namespaceCommand() {
        return "pcc_rg_package=$(cmd package list packages -U --user 0 com.nianticlabs.pikmin) || exit 1; "
            +"case \"$pcc_rg_package\" in 'package:com.nianticlabs.pikmin uid:'*) ;; *) exit 1;; esac; "
            +"pcc_rg_uid=${pcc_rg_package#'package:com.nianticlabs.pikmin uid:'}; "
            +"case \"$pcc_rg_uid\" in ''|*[!0-9]*|0*) exit 1;; esac; "
            +"test \"${#pcc_rg_uid}\" -eq 5 || exit 1; "
            +"pcc_rg_parent='/data/user/0/com.nianticlabs.pikmin/files'; "
            +"test -d \"$pcc_rg_parent\" && test ! -L \"$pcc_rg_parent\" && test -r \"$pcc_rg_parent\" && test -x \"$pcc_rg_parent\" || exit 1; "
            +"pcc_rg_before=$(stat -c '%u:%d:%i' \"$pcc_rg_parent\") || exit 1; "
            +"case \"$pcc_rg_before\" in \"$pcc_rg_uid\":*) ;; *) exit 1;; esac; "
            +"pcc_rg_found=$(find \"$pcc_rg_parent\" -mindepth 1 -maxdepth 1 -name emulator-command -print) || exit 1; "
            +"test -z \"$pcc_rg_found\" && test ! -e \"$pcc_rg_parent/emulator-command\" && test ! -L \"$pcc_rg_parent/emulator-command\" || exit 1; "
            +"pcc_rg_after=$(stat -c '%u:%d:%i' \"$pcc_rg_parent\") || exit 1; "
            +"test \"$pcc_rg_before\" = \"$pcc_rg_after\" && test -d \"$pcc_rg_parent\" && test ! -L \"$pcc_rg_parent\" || exit 1; "
            +"test \"$(cmd package list packages -U --user 0 com.nianticlabs.pikmin)\" = \"$pcc_rg_package\" || exit 1; "
            +"pcc_rg_found=$(find \"$pcc_rg_parent\" -mindepth 1 -maxdepth 1 -name emulator-command -print) || exit 1; "
            +"test -z \"$pcc_rg_found\" && test ! -e \"$pcc_rg_parent/emulator-command\" && test ! -L \"$pcc_rg_parent/emulator-command\" || exit 1; "
            +"test \"$(stat -c '%u:%d:%i' \"$pcc_rg_parent\")\" = \"$pcc_rg_before\" && test -d \"$pcc_rg_parent\" && test ! -L \"$pcc_rg_parent\" || exit 1; "
            +"printf 'absent\\n'";
    }
    static void requireNoReservation(Probe probe) throws Exception {
        BoundedCommand.Result r=probe.read(namespaceCommand());
        if(r==null||!r.ok||r.timedOut||r.truncated||r.exitCode==null||r.exitCode!=0||
                !"absent\n".equals(r.text))
            throw new IllegalStateException("每日工作已保留路線控制權或狀態不明；舊路線未接管，請保留待收尾狀態");
    }
    static void requireNoReservation() throws Exception {
        requireNoReservation(command -> BoundedCommand.root(command,3,256));
    }
    static String beforeShell(String command) {
        if(command==null||command.isEmpty()||command.length()>65536||command.indexOf('\0')>=0)
            throw new IllegalArgumentException("Invalid legacy route command");
        // Recheck inside the very root invocation that would mutate owner/lease.
        // No separate preflight can replace this check. Still NOT atomic handoff.
        return "{ "+namespaceCommand()+"; } >/dev/null || exit 1; "+command;
    }
}
