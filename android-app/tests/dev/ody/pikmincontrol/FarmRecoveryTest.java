package dev.ody.pikmincontrol;

public final class FarmRecoveryTest {
    static int checks;
    static void check(boolean b) { if (!b) throw new AssertionError("check " + checks); checks++; }
    static PlantingState state(long at, String mode, int active, int owned, String action) {
        return new PlantingState(at+"\t"+mode+"\t"+active+"\t"+owned+"\t"+action+"\t-", "11","11","11",at);
    }
    public static void main(String[] args) throws Exception {
        for (String s : new String[]{"stopped","error","stop-incomplete","running","stopping"}) check(FarmRecovery.suggested(s));
        check(!FarmRecovery.suggested("completed")); check(!FarmRecovery.suggested(""));
        FarmRecovery.Gate g = new FarmRecovery.Gate(0,1000);
        check(g.waiting(59999,false)); check(!g.waiting(60000,false)); check(!g.waiting(1,true));
        check(!g.observed(state(1000,"observe",0,0,"observe")));
        check(!g.observed(state(1100,"on",1,1,"active-owned")));
        check(!g.observed(state(1100,"observe",0,0,"controller-unavailable")));
        check(g.observed(state(1100,"observe",1,1,"observed-active")));
        check(!g.ready(state(1200,"off",0,0,"off"))); // no acknowledged off request
        g.offRequested(1200);
        check(!g.ready(state(1200,"off",0,0,"off")));
        check(!g.ready(state(1300,"off",1,0,"manual-session-preserved")));
        check(!g.ready(state(1400,"off",1,1,"stop-requested")));
        check(!g.ready(state(1500,"off",0,1,"stopped")));
        check(!g.ready(state(1500,"off",0,0,"controller-unavailable")));
        check(!g.ready(state(2000,"off",0,0,"off")));
        check(!g.ready(state(2000,"off",0,0,"off"))); // same frame is not progress
        check(!g.ready(state(3000,"off",0,0,"off")));
        check(!g.ready(state(4000,"off",0,0,"off")));
        check(g.ready(state(5000,"off",0,0,"off")));
        check(!g.ready(null)); // broken freshness resets stability
        check(!g.ready(state(6000,"off",0,0,"off")));
        check(!g.ready(state(10000,"off",0,0,"off"))); // gap resets
        check(!g.ready(state(9000,"off",0,0,"off"))); // clock reversal resets
        check(!g.ready(new PlantingState("10000\toff\t0\t0\toff\t-","12","12","11",10000)));
        check(!WorkProgress.label("error").contains("已確認"));
        for(int bits=0;bits<32;bits++) {
            boolean claimed=(bits&1)!=0, old=(bits&2)!=0, route=(bits&4)!=0, nearby=(bits&8)!=0, error=(bits&16)!=0;
            check(FarmRecovery.cleanPreflightAbort(claimed,old,route,nearby,error)==(bits==0));
        }
        check(!FarmRecovery.required("error",Boolean.TRUE,false)); // verified untouched abort can retry
        check(FarmRecovery.required("error",Boolean.TRUE,true)); // pending always wins
        String service=FarmResetSourceTest.source("FlowerFarmService");
        check(service.indexOf("recoveryRequiredAtStart=WorkStatus.farmNeedsRecovery(this)")<service.indexOf("saveLifecycle(\"running\""));
        check(service.contains("gate.confirmed(")&&service.contains("FarmRecovery.cleanPreflightAbort("));
        System.out.println("PASS FarmRecovery: " + checks + " checks");
    }
}
