package dev.ody.pikmincontrol;

/** Primary-space identity only. Never chooses the first PID or controls another user. */
final class OwnerGameProcess {
    static final String ERROR="__PCC_PID_QUERY_FAILED__", ABSENT="__PCC_PID_NOT_RUNNING__";
    static final String OTHER="__PCC_PID_OTHER_USER__", AMBIGUOUS="__PCC_PID_AMBIGUOUS__";
    static final String GAME="com.nianticlabs.pikmin";
    private OwnerGameProcess() {}
    static String command(){return command("/data/user/0/"+GAME,"/proc",false);}
    /** Only the existing opt-in watchdog may interpret verified owner absence as empty. */
    static String watchdogCommand(){return command("/data/user/0/"+GAME,"/proc",true);}
    static String command(String owner,String proc,boolean allowAbsence) {
        if(owner==null||proc==null||!owner.matches("[A-Za-z0-9_./:-]+")||!proc.matches("[A-Za-z0-9_./:-]+"))
            throw new IllegalArgumentException("process paths");
        String none=allowAbsence?":":"echo "+ABSENT, other=allowAbsence?":":"echo "+OTHER;
        // Leading space keeps callers' $( + ( ... ) from becoming arithmetic $(( ... )).
        return " (pcc_owner_pid() { "
            +"pcc_uid=$(stat -c %u "+owner+" 2>/dev/null); pcc_rc=$?; "
            +"if test $pcc_rc -ne 0; then echo "+ERROR+"; return; fi; "
            +"case \"$pcc_uid\" in ''|*[!0-9]*) echo "+ERROR+"; return;; esac; "
            +"if test ${#pcc_uid} -ne 5 || test \"$pcc_uid\" -lt 10000 || test \"$pcc_uid\" -ge 100000; then echo "+ERROR+"; return; fi; "
            +"if ! command -v pidof >/dev/null 2>&1; then echo "+ERROR+"; return; fi; "
            +"pcc_pids=$(pidof "+GAME+" 2>/dev/null); pcc_rc=$?; "
            +"if test $pcc_rc -eq 1 && test -z \"$pcc_pids\"; then "+none+"; return; fi; "
            +"if test $pcc_rc -ne 0 || test -z \"$pcc_pids\"; then echo "+ERROR+"; return; fi; "
            +"pcc_count=0; pcc_matches=0; pcc_selected=; pcc_seen=' '; "
            +"for pcc_pid in $pcc_pids; do pcc_count=$((pcc_count+1)); "
            +"if test $pcc_count -gt 16; then echo "+ERROR+"; return; fi; "
            +"case \"$pcc_pid\" in ''|*[!0-9]*) echo "+ERROR+"; return;; esac; "
            +"if test ${#pcc_pid} -gt 7 || test \"$pcc_pid\" -le 0 || test \"$pcc_pid\" -gt 4194304; then echo "+ERROR+"; return; fi; "
            +"case \"$pcc_seen\" in *\" $pcc_pid \"*) echo "+ERROR+"; return;; esac; pcc_seen=\"$pcc_seen$pcc_pid \"; "
            +"pcc_uids=$(sed -n 's/^Uid:[[:space:]]*//p' "+proc+"/$pcc_pid/status 2>/dev/null); pcc_rc=$?; "
            +"if test $pcc_rc -ne 0; then echo "+ERROR+"; return; fi; "
            +"set -- $pcc_uids; if test $# -ne 4; then echo "+ERROR+"; return; fi; "
            +"for pcc_u in \"$@\"; do case \"$pcc_u\" in ''|*[!0-9]*) echo "+ERROR+"; return;; esac; done; "
            +"if test \"$1\" != \"$2\" || test \"$1\" != \"$3\" || test \"$1\" != \"$4\"; then echo "+ERROR+"; return; fi; "
            +"pcc_name=$(tr '\\000' '\\n' < "+proc+"/$pcc_pid/cmdline 2>/dev/null); pcc_rc=$?; "
            +"if test $pcc_rc -ne 0 || test \"$pcc_name\" != '"+GAME+"'; then echo "+ERROR+"; return; fi; "
            +"if test \"$1\" = \"$pcc_uid\"; then pcc_matches=$((pcc_matches+1)); pcc_selected=$pcc_pid; fi; done; "
            +"if test $pcc_matches -eq 1; then printf '%s\\n' \"$pcc_selected\"; "
            +"elif test $pcc_matches -eq 0; then "+other+"; else echo "+AMBIGUOUS+"; fi; "
            +"}; pcc_owner_pid)";
    }
    static String reason(String value) {
        String p=value==null?"":value.trim();
        if(p.isEmpty()||ABSENT.equals(p))return "等待主空間 Pikmin 啟動";
        if(OTHER.equals(p))return "只有其他使用者空間的 Pikmin；請開啟主空間遊戲";
        if(AMBIGUOUS.equals(p)||p.matches("[0-9]+(?:\\s+[0-9]+)+"))return "主空間遊戲程序不唯一，暫不操作";
        return "主空間遊戲程序核對失敗，將重試；請確認 Root 權限";
    }
}
