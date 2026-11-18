package dev.ody.pikmincontrol;
final class SessionIdentity {
    static String parse(String raw,String before,String after,long now) {
        return parse(raw,before,after,now,false);
    }
    static String parse(String raw,String before,String after,long now,boolean requireIdle) {
        try {
            String[] f=raw.trim().split("\t",-1);
            if(f.length!=7 || !f[0].equals("v1") || !before.matches("[1-9][0-9]*") || !before.equals(after)
                    || !f[2].equals(before) || !f[3].matches("[a-f0-9]{64}") || !f[4].equals("ready") || !f[6].equals("end")
                    || !(f[5].equals("idle")||f[5].equals("busy")) || (requireIdle&&!f[5].equals("idle")))return "";
            long at=Long.parseLong(f[1]);return at>0 && now>=at && now-at<=12000?f[3]:"";
        }catch(RuntimeException invalid){return "";}
    }
    static String read() throws Exception {
        return read(false);
    }
    static String read(boolean requireIdle) throws Exception {
        String[] p=ProbeSnapshot.sections(GpsModuleGateway.farmRead(ProbeSnapshot.command(
                "pidof com.nianticlabs.pikmin || true",
                "cat /data/user/0/com.nianticlabs.pikmin/files/automation_session.tsv 2>/dev/null || true",
                "pidof com.nianticlabs.pikmin || true")),3);
        return parse(p[1],p[0].trim(),p[2].trim(),System.currentTimeMillis(),requireIdle);
    }
}
