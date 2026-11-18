package dev.ody.pikmincontrol;

/** Read-only terminal proof for the original MuMu route transaction, not start
 * authority. A route stop bit or an inactive sample alone is insufficient. */
final class EmulatorClosedPlantingRound {
    final String boot;
    final long epoch,started;
    private EmulatorClosedPlantingRound(String boot,long epoch,long started){this.boot=boot;this.epoch=epoch;this.started=started;}
    static EmulatorClosedPlantingRound read(String[] sections,String pid,String account,String token,long requested,long now) {
        if(sections==null||sections.length!=7||!uuid(token)||!account.matches("[0-9a-f]{64}")||
           !pid.matches("[1-9][0-9]{0,8}")||requested<=0||now<requested||
           !(pid+"\n").equals(sections[0])||!sections[0].equals(sections[6]))throw new IllegalArgumentException("Terminal binding");
        String boot=sections[1];
        if(!boot.endsWith("\n")||!uuid(boot.substring(0,boot.length()-1))||!boot.equals(sections[5]))throw new IllegalArgumentException("Terminal boot changed");
        CoreCapabilities c=CoreCapabilities.parse(ReadinessSample.Read.ok(sections[2]),pid,"0.20.3",900178,now);
        if(!c.ok()||!c.identityReady()||!c.domainReady()||c.sessionEpoch<=0||
           !c.live(CoreCapabilities.CAP_PLANTING_CONTROLLER)||!c.live(CoreCapabilities.CAP_LOCATION_CONTROLLER))throw new IllegalArgumentException("Terminal capabilities");
        String[] j=line(sections[3],10);
        long started=number(j[8]),sent=number(j[6]);
        if(!j[0].equals("v3")||!j[1].equals(pid)||number(j[2])!=c.sessionEpoch||!j[3].equals(account)||!j[4].equals(token)||
           !j[5].equals("stopped-confirmed")||!j[7].equals("1")||!j[9].equals("end")||
           started<=0||sent<requested||sent>now)throw new IllegalArgumentException(
               "closed[p="+j[1].equals(pid)+",e="+(number(j[2])==c.sessionEpoch)
               +",a="+j[3].equals(account)+",t="+j[4].equals(token)
               +",ph="+j[5].equals("stopped-confirmed")+",c="+j[7].equals("1")
               +",end="+j[9].equals("end")+",si="+(started<=0)
               +",sr="+(sent<requested)+",sf="+(sent>now)+"]");
        String[] p=line(sections[4],6);
        long at=number(p[0]);
        if(at<sent||at>now||now-at>2000||!p[1].equals("off")||!p[2].equals("0")||!p[3].equals("0")||
           !p[4].equals("stopped-confirmed"))throw new IllegalArgumentException("Fresh inactive unowned OFF confirmation required");
        return new EmulatorClosedPlantingRound(boot.substring(0,boot.length()-1),c.sessionEpoch,started);
    }
    private static boolean uuid(String s){return s!=null&&s.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");}
    private static long number(String s){long n=Long.parseLong(s);if(n<0||!Long.toString(n).equals(s))throw new IllegalArgumentException("Canonical terminal number");return n;}
    private static String[] line(String raw,int count){
        if(raw==null||raw.length()>2048||!raw.endsWith("\n"))throw new IllegalArgumentException("Complete bounded terminal bytes");
        String row=raw.substring(0,raw.length()-1);
        for(int i=0;i<row.length();i++){char c=row.charAt(i);if(c!='\t'&&(c<32||c>126))throw new IllegalArgumentException("Terminal framing");}
        String[] f=row.split("\t",-1);if(f.length!=count)throw new IllegalArgumentException("Terminal columns");return f;
    }
}
