package dev.ody.pikmincontrol;

/** Final time-of-action request/ACK check; a previously fresh Java Proof is not a lease. */
final class PlanterRetirementBoundary {
    private static final String REQUEST="/data/local/tmp/pikmin-planter-recover.txt";
    private static final String ACK="/data/user/0/com.nianticlabs.pikmin/files/planter_recovery.tsv";

    static String guard(PlanterRetirement.Proof proof) {
        if(proof==null||!proof.pid.matches("[1-9][0-9]{0,6}")
                ||!proof.requestNonce.matches("[A-Za-z0-9_-]{1,64}")||proof.ledger.length()>512)
            throw new IllegalArgumentException("育苗封存核對識別無效");
        String[] ledger=proof.ledger.trim().split("\\s+",-1);
        if(ledger.length!=4||!ledger[0].matches("[1-9][0-9]{0,18}")
                ||!ledger[1].equals("plant")||!ledger[2].matches("[A-Za-z0-9_:-]{1,256}")
                ||!ledger[3].matches("[0-7]"))throw new IllegalArgumentException("育苗封存原件無效");
        long sent=Long.parseLong(ledger[0]);
        return "test ! -L '"+REQUEST+"'; test -f '"+REQUEST+"'; "
            +"test ! -L '"+ACK+"'; test -f '"+ACK+"'; "
            +"test \"$(stat -c %s '"+REQUEST+"')\" -le 1024; test \"$(stat -c %s '"+ACK+"')\" -le 512; "
            +"pcc_boundary_now=$(date +%s%3N); case \"$pcc_boundary_now\" in ''|*[!0-9]*) exit 1;; esac; "
            +"awk -F '\\t' -v now=\"$pcc_boundary_now\" '"
            +"FILENAME==ARGV[1] {requests++; if(NF==8 && $1==\"v1\" && $2==\""+proof.requestNonce
            +"\" && $3=="+proof.pid+" && $4~/^[0-9]+$/ && $4>="+sent+" && now>=$4 && now-$4<=90000"
            +" && $5==\""+ledger[0]+"\" && $6==\"plant\" && $7==\""+ledger[2]+"\" && $8==\""+ledger[3]
            +"\") {request_ok=1; asked=$4} next} "
            +"FILENAME==ARGV[2] {acks++; if(NF==5 && $1==\"v1\" && $2~/^[0-9]+$/ && $2>0 && now>=$2 && now-$2<=60000"
            +" && $3=="+proof.pid+" && $4==\""+proof.requestNonce+"\" && $5==\"result-unknown\") {ack_ok=1; acknowledged=$2} next} "
            +"END {exit !(requests==1 && acks==1 && request_ok && ack_ok && acknowledged>=asked)}' '"+REQUEST+"' '"+ACK+"'; ";
    }
}
