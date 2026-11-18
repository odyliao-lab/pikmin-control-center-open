package dev.ody.pikmincontrol;

/** MuMu telemetry only. Reported selected ID is a native observation, NOT
 * an independently selected command argument or permission to start/rotate.
 * No policy Snapshot escapes; native mutation read-back and result/reward
 * proof remain mandatory in the execution path. */
final class EmulatorOwnedStockObservation {
    static final class Result {
        final long captured;
        final String state;
        final int stock;
        Result(long captured,String state,int stock){this.captured=captured;this.state=state;this.stock=stock;}
        String summary(){return state+(stock<0?"":":"+stock);}
        // This authorizes a controlled PAUSE only, not a new start or a low-stock
        // acceptance witness. Closure, fresh selection and the original lease
        // handoff must still be independently confirmed by the Service.
        boolean pauseRequired(){
            return state.equals("low")||state.equals("low-stop-pending")||state.equals("low-stopped")||
                state.equals("unknown-stock")||state.equals("unknown-stock-stop-pending")||state.equals("unknown-stock-stopped");
        }
    }
    static Result observe(String capabilities,String journal,String packet,String pid,String account,
            String token,long requestedAt,long now) {
        try {
            if(pid==null || !pid.matches("[1-9][0-9]{0,8}") || account==null || !account.matches("[0-9a-f]{64}") ||
               token==null || !token.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}") ||
               requestedAt<=0 || now<requestedAt)throw new IllegalArgumentException();
            CoreCapabilities caps=CoreCapabilities.parse(ReadinessSample.Read.ok(capabilities),pid,"0.20.3",900178,now);
            if(!caps.ok() || !caps.identityReady() || !caps.domainReady() || caps.sessionEpoch<=0 ||
               !caps.live(CoreCapabilities.CAP_PLANTING_CONTROLLER) || !caps.live(CoreCapabilities.CAP_LOCATION_CONTROLLER))
                throw new IllegalArgumentException();
            String[] j=line(journal,10,"v3");
            boolean stopping=j[5].equals("stop-pending")||j[5].equals("stopped-confirmed");
            boolean closed=j[5].equals("stopped-confirmed");
            if(number(j[1])!=Integer.parseInt(pid) || number(j[2])!=caps.sessionEpoch || !j[3].equals(account) ||
               !j[4].equals(token) || (!stopping&&!j[5].equals("active-confirmed")) ||
               // Column 6 is native CLOCK_REALTIME before durable START submission;
               // column 8 is the game's PlantingStartedTimeMs, not the same clock.
               // Active ownership comes from the exact native phase/PID/epoch/
               // account/token and matching packet start, not their relative order.
               // Keep native sent against App request/now; GameStarted is an exact correlation value.
               // No synthetic timestamp or mutation authority; closed phase/identity remain required.
               number(j[6])<requestedAt || number(j[6])>now ||
               !j[7].equals(closed?"1":"0") ||
               number(j[8])<=0)throw new IllegalArgumentException();
            // A reported label only: never expose it as a command choice.
            if(packet==null || packet.length()>65536)throw new IllegalArgumentException();
            int first=packet.indexOf('\n');if(first<0)throw new IllegalArgumentException();
            String[] h=packet.substring(0,first).split("\t",-1);
            if(h.length!=11)throw new IllegalArgumentException();
            EmulatorPetalRotation.Snapshot stock=EmulatorOwnedPetalReader.read(packet,Integer.parseInt(pid),
                caps.sessionEpoch,account,token,number(j[8]),h[7],now);
            if(!stock.complete)return new Result(stock.captured,stopping?(closed?"unknown-stock-stopped":"unknown-stock-stop-pending"):"unknown-stock",-1);
            for(EmulatorPetalRotation.Petal p:stock.petals)if(p.id.equals(h[7])) {
                // Still an observation, not permission to rotate. Service must
                // separately pause, close the exact native result and hand off.
                if(stopping)return new Result(stock.captured,p.stock<=EmulatorPetalRotation.RESERVE?(closed?"low-stopped":"low-stop-pending"):"stop-not-low",p.stock);
                return new Result(stock.captured,p.stock>EmulatorPetalRotation.RESERVE?"available":"low",p.stock);
            }
            return new Result(stock.captured,"selected-missing",-1);
        }catch(RuntimeException invalid){return new Result(0,"unbound-or-unavailable",-1);}
    }
    private static String[] line(String raw,int columns,String schema) {
        if(raw==null || raw.length()>2048 || !raw.endsWith("\n"))throw new IllegalArgumentException();
        String row=raw.substring(0,raw.length()-1);
        for(int i=0;i<row.length();i++){char c=row.charAt(i);if(c!='\t' && (c<0x20 || c>0x7e))throw new IllegalArgumentException();}
        String[] f=row.split("\t",-1);
        if(f.length!=columns || !f[0].equals(schema) || !f[columns-1].equals("end"))throw new IllegalArgumentException();
        return f;
    }
    private static long number(String value) {
        long n=Long.parseLong(value);
        if(n<0 || !Long.toString(n).equals(value))throw new IllegalArgumentException();
        return n;
    }
}
