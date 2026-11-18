package dev.ody.pikmincontrol;

/** Four display-only settings. Existing core protocols and gameplay controls are unchanged. */
final class DisplayOptionsGateway {
    static final int COUNT=4;
    private static final String BASE="/data/local/tmp/";
    private static final String GAME="/data/user/0/com.nianticlabs.pikmin/files/";
    private static final String[] KEYS={"seedlings","pikmin","expeditions","stock"};
    private static final String[][] MEMBERS={
        {"seed-preview","seed-collection","planter-display"},
        {"adult-state-preview","adult-decoration-preview"},
        {"gift-preview","expedition-display"},
        {"nectar-stock-preview"}
    };
    private static final String[][] REPORTS={
        {"seed_preview_status.tsv","seed_collection_status.tsv","planter_display_status.tsv"},
        {"adult_state_preview_status.tsv","adult_decoration_preview_status.tsv"},
        {"gift_preview_status.tsv","expedition_display_status.tsv"},
        {"nectar_stock_preview_status.tsv"}
    };
    static String[] members(int group){check(group);return MEMBERS[group].clone();}
    private static void check(int group){if(group<0||group>=COUNT)throw new IllegalArgumentException("Unknown display group");}
    /** Reconcile a persisted group intent, or migrate valid legacy values with OR once.
     * Commit the group intent before fan-out so a failed/terminated OFF cannot migrate back ON.
     * Individual renames are atomic; the set of old core files is not an atomic native snapshot.
     */
    static String configCommand(int group,Boolean enabled) {
        check(group);
        String master=BASE+"pikmin-display-group-"+KEYS[group]+".txt";
        StringBuilder out=new StringBuilder("set -eu; umask 077; pcc_display_read(){ test ! -L \"$1\" || return 1; if test -e \"$1\"; then test -f \"$1\" || return 1; pcc_display_value=$(head -c 32 \"$1\") || return 1; case \"$pcc_display_value\" in on|off) ;; *) return 1 ;; esac; else pcc_display_value=off; fi; }; ");
        // Validate every old file and the master before the first mutation, even for explicit writes.
        for(int i=0;i<MEMBERS[group].length;i++)out.append("pcc_display_read ").append(BASE).append("pikmin-").append(MEMBERS[group][i]).append(".txt; pcc_display_old").append(i).append("=$pcc_display_value; ");
        out.append("pcc_display_read ").append(master).append("; pcc_display_mode=$pcc_display_value; ");
        if(enabled!=null)out.append("pcc_display_mode=").append(enabled?"on":"off").append("; ");
        else {
            out.append("if test ! -e ").append(master).append("; then ");
            for(int i=0;i<MEMBERS[group].length;i++)out.append("if test \"$pcc_display_old").append(i).append("\" = on; then pcc_display_mode=on; fi; ");
            out.append("fi; ");
        }
        out.append("pcc_display_dirty=0; if test ! -e ").append(master).append(" || test \"$pcc_display_value\" != \"$pcc_display_mode\"; then pcc_display_dirty=1; fi; ");
        for(int i=0;i<MEMBERS[group].length;i++)out.append("if test \"$pcc_display_old").append(i).append("\" != \"$pcc_display_mode\"; then pcc_display_dirty=1; fi; ");
        out.append("if test \"$pcc_display_dirty\" = 1; then pcc_display_tmp=$(mktemp -d ").append(BASE).append("pcc-display-group.XXXXXX); trap 'rm -f -- \"$pcc_display_tmp\"/*; rmdir -- \"$pcc_display_tmp\"' EXIT; ");
        for(int i=0;i<=MEMBERS[group].length;i++)out.append("printf '%s\\n' \"$pcc_display_mode\" > \"$pcc_display_tmp/").append(i).append("\"; chown 0:0 \"$pcc_display_tmp/").append(i).append("\"; chmod 0644 \"$pcc_display_tmp/").append(i).append("\"; ");
        out.append("mv -fT \"$pcc_display_tmp/0\" ").append(master).append("; ");
        for(int i=0;i<MEMBERS[group].length;i++)out.append("mv -fT \"$pcc_display_tmp/").append(i+1).append("\" ").append(BASE).append("pikmin-").append(MEMBERS[group][i]).append(".txt; ");
        out.append("fi; ");
        out.append("pcc_display_read ").append(master).append("; test \"$pcc_display_value\" = \"$pcc_display_mode\"; ");
        for(String member:MEMBERS[group])out.append("pcc_display_read ").append(BASE).append("pikmin-").append(member).append(".txt; test \"$pcc_display_value\" = \"$pcc_display_mode\"; ");
        return out.append("printf '%s\\n' \"$pcc_display_mode\"").toString();
    }
    // Serialize normalization and explicit changes across old queued refreshes.
    static synchronized void set(int group,boolean enabled)throws Exception {
        String result=BoundedCommand.checkedRoot(configCommand(group,enabled),5,1024).trim();
        if(!result.equals(enabled?"on":"off"))throw new IllegalStateException("整組顯示設定未確認保存");
    }
    static synchronized DisplayOptionsState read(int group)throws Exception {
        check(group);String[] commands=new String[REPORTS[group].length+3];
        commands[0]=OwnerGameProcess.command();commands[1]=configCommand(group,null);
        for(int i=0;i<REPORTS[group].length;i++){
            String path=GAME+REPORTS[group][i];
            commands[i+2]="if test -e "+path+"; then test ! -L "+path+" && test -f "+path+" && head -c 1024 "+path+"; fi";
        }
        commands[commands.length-1]=OwnerGameProcess.command();
        String[] rows=ProbeSnapshot.checkedSections(BoundedCommand.root(ProbeSnapshot.command(commands),5,8192),commands.length);
        String[] reports=new String[REPORTS[group].length];System.arraycopy(rows,2,reports,0,reports.length);
        return new DisplayOptionsState(rows[1],reports,rows[0].trim(),rows[rows.length-1].trim(),System.currentTimeMillis());
    }
}
