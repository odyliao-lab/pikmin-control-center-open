package dev.ody.pikmincontrol;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Passive owned-session stock packet. Never grants start/stop/replay authority.
 * Caller must independently supply the live lease, account, epoch and start time.
 * Unknown is not an empty inventory; missing/stale packets are rejected. */
final class EmulatorOwnedPetalReader {
    static EmulatorPetalRotation.Snapshot read(String wire,int pid,long epoch,String account,
            String token,long started,String selected,long now) {
        if(wire==null || wire.length()>65536 || pid<=0 || epoch<=0 || started<=0 || now<=0 ||
           account==null || !account.matches("[0-9a-f]{64}") || token==null ||
           !token.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}") ||
           selected==null || !selected.matches("[A-Za-z0-9._:-]{1,80}"))bad();
        for(int i=0;i<wire.length();i++) {
            char c=wire.charAt(i);if(c!='\t' && c!='\n' && (c<0x20 || c>0x7e))bad();
        }
        String[] lines=wire.split("\n",-1);
        if(lines.length<3 || !lines[lines.length-1].isEmpty())bad();
        String[] h=lines[0].split("\t",-1);
        if(h.length!=11 || !h[0].equals("v1") || number(h[2])!=pid || number(h[3])!=epoch ||
           !h[4].equals(account) || !h[5].equals(token) || number(h[6])!=started || !h[7].equals(selected))bad();
        long captured=number(h[1]),revision=number(h[9]),count=number(h[10]);
        boolean complete=h[8].equals("complete");
        if(!complete && !h[8].equals("unknown"))bad();
        if(captured<=0 || captured>now || now-captured>8000 || count>512 ||
           (complete ? revision<=0 : revision!=0 || count!=0) || lines.length!=count+3 ||
           !lines[lines.length-2].equals("end\t"+count))bad();
        Set<String> ids=new HashSet<>();Set<Integer> ordinaryColors=new HashSet<>();
        ArrayList<EmulatorPetalRotation.Petal> petals=new ArrayList<>();
        for(int i=1;i<=count;i++) {
            String[] f=lines[i].split("\t",-1);
            if(f.length!=5 || !f[0].equals("r") || !f[1].matches("[A-Za-z0-9._:-]{1,80}") || !ids.add(f[1]))bad();
            long color=number(f[2]),stock=number(f[3]);
            if(color<1 || color>4 || stock>1000000 || !(f[4].equals("0") || f[4].equals("1")))bad();
            boolean ordinary=f[4].equals("1");
            if(ordinary && !ordinaryColors.add((int)color))bad();
            petals.add(new EmulatorPetalRotation.Petal(f[1],(int)color,(int)stock,ordinary));
        }
        return new EmulatorPetalRotation.Snapshot(captured,pid,account,complete,petals);
    }
    private static long number(String wire) {
        try {long n=Long.parseLong(wire);if(n<0 || !Long.toString(n).equals(wire))bad();return n;}
        catch(NumberFormatException invalid){throw new IllegalArgumentException("Owned petal number",invalid);}
    }
    private static void bad(){throw new IllegalArgumentException("Complete fresh independently bound owned petal packet required");}
}
