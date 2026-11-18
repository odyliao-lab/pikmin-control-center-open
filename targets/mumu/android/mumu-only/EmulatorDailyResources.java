package dev.ody.pikmincontrol;

import android.content.Context;

/** Mandatory MuMu daily resource observation, independent of the optional
 * phone ResourceGuard preference. This new passive protocol requires its own
 * native producer; absence is UNKNOWN, never zero or unlimited. No mutations. */
final class EmulatorDailyResources {
    static final String LEAF="emulator_daily_resources.tsv";
    static final int MAX_BYTES=2048;
    final EmulatorDailyRunner.Binding binding;
    final long captured,revision,pikminUsed,pikminMax,seedUsed,seedMax;
    private final long[] ordinary;
    private EmulatorDailyResources(EmulatorDailyRunner.Binding b,long[] n) {
        binding=b;captured=n[0];revision=n[3];pikminUsed=n[4];pikminMax=n[5];seedUsed=n[6];seedMax=n[7];
        ordinary=new long[]{n[8],n[9],n[10],n[11]};
    }
    String blocker(boolean seeds,boolean planting,boolean nursery) {
        if(seeds && seedMax-seedUsed<=5)return "seed-capacity-reserve";
        if(nursery && pikminMax-pikminUsed<=10)return "pikmin-capacity-reserve";
        if(planting) {
            for(long stock:ordinary)if(stock>EmulatorPetalRotation.RESERVE)return "";
            return "no-ordinary-petals-above-reserve";
        }
        return "";
    }
    String batchPreflight(String kind) {
        if(!java.util.Arrays.asList("seed","fruit","gift").contains(kind))bad();
        return "seed".equals(kind)?blocker(true,false,false):"";
    }
    /** Advisory only. Never grants admission or toggles a lane. Keep overflow
     * visible and reject stale samples rather than reporting an empty bag. */
    String capacitySummary(long now) {
        if(!fresh(now))return "容量未確認（快照過期）；不視為空間充足";
        return capacityLine("花苗",seedUsed,seedMax,5,15,"花苗派遣")+"\n"+
            capacityLine("皮克敏",pikminUsed,pikminMax,10,30,"育苗拔出／補種")+
            "\n容量提醒不停止GPS／步數／種花；水果與禮物盒仍各依原生條件判定。";
    }
    static String capacityLine(String label,long used,long max,long reserve,long warning,String affected) {
        if(used<0||max<=0||used>1000000||max>1000000||reserve<0||warning<reserve)
            return label+"：容量未確認";
        long free=max-used;
        String state=free<=reserve?"保留空間不足，"+affected+"可能等待":
            free<=warning?"接近保留量，請預留空間":"尚有空間";
        return label+" "+used+"/"+max+"（"+(free<0?"超額 "+(-free):"剩餘 "+free)+"）："+state;
    }
    static EmulatorDailyResources read(Context c,EmulatorDailyRunner.Binding held)throws Exception {
        return read(new SampleSource(){
            public String[] next()throws Exception {
                EmulatorDailyRouteWork.requireIdentity(c,held);
                return EmulatorOwnedPetalGateway.sections(EmulatorSavedDailyRead.resources(),7);
            }
            public void recheck()throws Exception {EmulatorDailyRouteWork.requireIdentity(c,held);}
            public long now(){return System.currentTimeMillis();}
            public void awaitNext()throws Exception {Thread.sleep(500);}
        },held);
    }
    interface SampleSource {
        String[] next()throws Exception;
        void recheck()throws Exception;
        long now();
        void awaitNext()throws Exception;
    }
    /** Only an otherwise valid expired resource may trigger a new complete read.
     * Inventory predictors can defer the passive five-second publication.
     * Never retry identity/transport/malformed data, combine frames, enlarge TTL,
     * or reuse the rejected packet. Three reads bound this pre-effect wait. */
    static EmulatorDailyResources read(SampleSource source,EmulatorDailyRunner.Binding held)throws Exception {
        if(source==null||held==null)bad();
        for(int attempt=0;attempt<3;attempt++){
            try{
                EmulatorDailyResources result=decode(source.next(),held,source.now());
                source.recheck();
                result.requireFresh(source.now());
                return result;
            }catch(Expired expired){
                if(attempt==2)throw expired;
                source.awaitNext();
            }
        }
        throw new IllegalStateException("Bounded fresh resource reads exhausted");
    }
    private static final class Expired extends IllegalArgumentException {
        Expired(){super("Complete same-account/session daily resource expired; bounded fresh read required");}
    }
    private void requireFresh(long now){
        if(captured<=0||now<captured)bad();
        if(now-captured>12000)throw new Expired();
    }
    static void require(Context c,EmulatorDailyRunner.Binding held,boolean seeds,boolean planting,boolean nursery)throws Exception {
        String reason=read(c,held).blocker(seeds,planting,nursery);
        if(!reason.isEmpty())throw new IllegalStateException("Daily resource gate: "+reason);
    }
    boolean fresh(long now){return captured>0 && now>=captured && now-captured<=12000;}
    static EmulatorDailyResources decode(String[] p,EmulatorDailyRunner.Binding b,long now) {
        if(b==null||p==null||p.length!=7||now<=0)bad();
        String pid=Integer.toString(b.pid),identity=pid+"\n"+b.boot+"\n";
        if(!identity.equals(p[0])||!identity.equals(p[6])||
            !b.account.equals(SessionIdentity.parse(p[1],pid,pid,now))||
            !b.account.equals(SessionIdentity.parse(p[5],pid,pid,now)))bad();
        caps(p[2],b,now);caps(p[4],b,now);
        return packet(p[3],b,now);
    }
    static EmulatorDailyResources packet(String wire,EmulatorDailyRunner.Binding b,long now) {
        if(b==null||wire==null||wire.length()>MAX_BYTES||!wire.endsWith("\n"))bad();
        String row=wire.substring(0,wire.length()-1);
        for(int i=0;i<row.length();i++){char c=row.charAt(i);if(c!='\t'&&(c<32||c>126))bad();}
        String[] f=row.split("\t",-1);
        if(f.length!=16||!f[0].equals("v1")||!f[4].equals(b.account)||!f[14].equals("complete")||!f[15].equals("end"))bad();
        // time, PID, session epoch, revision, counts/capacities, then ordinary
        // white/yellow/red/blue stocks. No selected-special-petal substitution.
        long[] n=new long[12];int[] columns={1,2,3,5,6,7,8,9,10,11,12,13};
        for(int i=0;i<n.length;i++)n[i]=num(f[columns[i]]);
        // Confirmed returns may overflow an already-full bag. Preserve the
        // actual bounded counts; blocker() still denies further seed admission.
        if(n[1]!=b.pid||n[2]!=b.sessionEpoch||n[3]<=0||n[5]<=0||n[7]<=0)bad();
        for(int i=4;i<n.length;i++)if(n[i]>1000000)bad();
        EmulatorDailyResources result=new EmulatorDailyResources(b,n);
        result.requireFresh(now);return result;
    }
    private static void caps(String raw,EmulatorDailyRunner.Binding b,long now) {
        CoreCapabilities c=EmulatorDailyCore.capabilities(raw,b,now);
        if(!c.ok()||!c.identityReady()||!c.domainReady()||c.sessionEpoch!=b.sessionEpoch)bad();
    }
    static String command() {
        String identity="pidof com.nianticlabs.pikmin && cat /proc/sys/kernel/random/boot_id";
        return ProbeSnapshot.command(identity,EmulatorRunnerCounterGateway.bounded("automation_session.tsv",2048),
            EmulatorRunnerCounterGateway.bounded("core_capabilities.tsv",32768),bounded(),
            EmulatorRunnerCounterGateway.bounded("core_capabilities.tsv",32768),
            EmulatorRunnerCounterGateway.bounded("automation_session.tsv",2048),identity);
    }
    private static String bounded() {
        // Same immutable 0600 single-LF leaf contract as the passive counter;
        // replace only its literal path, never permit caller-controlled leaves.
        return EmulatorRunnerCounterGateway.passiveScalarRead(EmulatorGameDailyStepsGateway.command().replace("/emulator_game_daily_steps.tsv","/"+LEAF));
    }
    private static long num(String s){long n=Long.parseLong(s);if(n<0||!Long.toString(n).equals(s))bad();return n;}
    private static void bad(){throw new IllegalArgumentException("Complete fresh same-account/session daily resources required; missing is unknown");}
}
