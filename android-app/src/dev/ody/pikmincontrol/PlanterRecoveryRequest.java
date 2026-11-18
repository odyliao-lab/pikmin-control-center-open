package dev.ody.pikmincontrol;

/** A new read-only recovery nonce bound to the exact manually confirmed pending record. */
final class PlanterRecoveryRequest {
    static final String ACCEPTED="PLANTER_RECOVERY_REQUESTED";
    private static final String FILES="/data/user/0/com.nianticlabs.pikmin/files/";
    private static final String REQUEST="/data/local/tmp/pikmin-planter-recover.txt";

    static String command(PlanterRetirement.Basis expected,String nonce,long now,long epoch,long login)throws Exception {
        if(expected==null||epoch<1||login<1||now<login
                ||!expected.pid.matches("[1-9][0-9]{0,6}")||!expected.ticks.matches("[1-9][0-9]*")
                ||!expected.boot.matches("[a-f0-9-]{36}")||!expected.account.matches("[a-f0-9]{64}")
                ||!expected.sha.matches("[a-f0-9]{64}")||expected.ledger.length()>512
                ||!expected.sha.equals(PlanterRetirement.hash(expected.ledger)))
            throw new IllegalArgumentException("育苗核對基線無效");
        // This validates every token before interpolation. Never reread a different ledger
        // to build the request after the user has agreed to the original pending record.
        String wire=PlanterRecovery.request(nonce,expected.pid,now,expected.ledger);
        String wireSha=PlanterRetirement.hash(wire),tmp=REQUEST+".tmp-"+nonce;
        String guard=guard(expected,now,epoch,login);
        return "set -eu; "+guard
            +"test ! -L '"+REQUEST+"'; test ! -e '"+tmp+"'; test ! -L '"+tmp+"'; "
            +"(umask 022; set -C; printf '%s' '"+wire+"' > '"+tmp+"'); "
            +"trap 'rm -f -- \""+tmp+"\"' EXIT; chmod 0644 '"+tmp+"'; "+guard
            +"test ! -L '"+REQUEST+"'; mv '"+tmp+"' '"+REQUEST+"'; "+guard
            +"test ! -L '"+REQUEST+"'; test \"$(sha256sum '"+REQUEST+"'|cut -d' ' -f1)\" = "+wireSha+"; "
            +"printf '"+ACCEPTED+"'";
    }

    private static String guard(PlanterRetirement.Basis p,long asked,long epoch,long login) {
        return p.guard()
            +"pcc_now=$(date +%s%3N); case \"$pcc_now\" in ''|*[!0-9]*) exit 1;; esac; "
            +"test \"$pcc_now\" -ge "+asked+"; test \"$((pcc_now-"+asked+"))\" -le 90000; "
            +"test ! -L '"+FILES+"automation_session.tsv'; test -f '"+FILES+"automation_session.tsv'; "
            +"awk -F '\\t' -v now=\"$pcc_now\" 'NF==7 && $1==\"v1\" && $2~/^[0-9]+$/ && $2>0 && $3=="+p.pid
            +" && $4==\""+p.account+"\" && $5==\"ready\" && ($6==\"idle\" || $6==\"busy\") && $7==\"end\""
            +" && now>=$2 && now-$2<=12000 {ok=1} END{exit !(NR==1 && ok)}' '"+FILES+"automation_session.tsv'; "
            +"test ! -L '"+FILES+"core_capabilities.tsv'; test -f '"+FILES+"core_capabilities.tsv'; "
            +"awk -F '\\t' -v now=\"$pcc_now\" 'NF==23 && $1==\"v1\" && $2~/^[0-9]+$/ && $2>0 && $3=="+p.pid
            +" && $15=="+epoch+" && $16==\"ready\" && $17=="+login+" && $23==\"end\""
            +" && now>=$2 && now-$2<=12000 {ok=1} END{exit !(NR==1 && ok)}' '"+FILES+"core_capabilities.tsv'; "
            +"pcc_package=$(dumpsys package com.nianticlabs.pikmin); "
            +"test \"$(printf '%s\\n' \"$pcc_package\" | sed -n 's/^[[:space:]]*versionName=//p')\" = '157.0'; "
            +"test ! -L '"+FILES+"planter_pending.tsv'; test -f '"+FILES+"planter_pending.tsv'; "
            +"test \"$(sha256sum '"+FILES+"planter_pending.tsv'|cut -d' ' -f1)\" = "+p.sha+"; ";
    }

    static boolean accepted(BoundedCommand.Result result) {
        return result!=null&&result.ok&&!result.timedOut&&!result.truncated&&result.exitCode!=null
            &&result.exitCode==0&&ACCEPTED.equals(result.text);
    }
}
