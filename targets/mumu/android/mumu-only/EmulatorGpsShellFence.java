package dev.ody.pikmincontrol;

/** Same flock inode as RootGpsFence. For Root shell/watchdog scopes only;
 * never nest under an already-held actor. No creation, repair or GPS effects. */
final class EmulatorGpsShellFence {
    static String wrap(String command){
        if(command==null||command.isEmpty()||command.length()>60000||command.indexOf('\0')>=0)
            throw new IllegalArgumentException("Invalid fenced shell command");
        String check="test -d \"$pcc_gd\" && test ! -L \"$pcc_gd\" && "
            +"test \"$(stat -c %u:%g:%a:%d:%i \"$pcc_gd\")\" = \"$pcc_gdir\" && "
            +"test -f \"$pcc_gl\" && test ! -L \"$pcc_gl\" && "
            +"test \"$(stat -c %u:%g:%a:%h:%s:%d:%i \"$pcc_gl\")\" = \"$pcc_glock\" && "
            +"test \"$(stat -L -c %u:%g:%a:%h:%s:%d:%i /proc/self/fd/3 3<&3)\" = \"$pcc_glock\"";
        return "( test \"$(id -u)\" = 0 || exit 75; "
            +"for pcc_ga in /data /data/local /data/local/tmp; do test -d \"$pcc_ga\" && test ! -L \"$pcc_ga\" || exit 75; done; "
            +"pcc_gd=/data/local/tmp/pcc-emulator-gps-work; pcc_gl=$pcc_gd/gps.lock; "
            +"test -d \"$pcc_gd\" && test ! -L \"$pcc_gd\" && test -f \"$pcc_gl\" && test ! -L \"$pcc_gl\" || exit 75; "
            +"pcc_gdir=$(stat -c %u:%g:%a:%d:%i \"$pcc_gd\") || exit 75; "
            +"case \"$pcc_gdir\" in 0:0:711:*) ;; *) exit 75;; esac; "
            +"pcc_glock=$(stat -c %u:%g:%a:%h:%s:%d:%i \"$pcc_gl\") || exit 75; "
            +"case \"$pcc_glock\" in 0:0:600:1:0:*) ;; *) exit 75;; esac; "
            +"exec 3<>\"$pcc_gl\" || exit 75; "+check+" || exit 75; "
            // Android mksh closes ordinary extra descriptors on exec unless
            // explicitly passed. Keep the parent's open description locked.
            +"flock -n 3 3<&3 || exit 75; "+check+" || exit 75; "
            // Parent keeps FD3 throughout the effect. Children (notably nohup
            // watchdogs) cannot inherit and retain the parent's lock forever.
            +"( exec 3>&-; "+command+" ); pcc_gr=$?; "+check+" || exit 75; exit \"$pcc_gr\" )";
    }
}
