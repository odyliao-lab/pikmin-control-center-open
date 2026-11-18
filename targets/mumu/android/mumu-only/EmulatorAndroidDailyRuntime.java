package dev.ody.pikmincontrol;

import android.content.Context;

/** One product tick, not a scheduler or a controlled-test approval API.
 * Binding/private route must be independently established by the caller.
 * Production always uses the actual retained-evidence Acceptance consumer.
 * All fresh reads remain inside the existing durable cross-process tick lock. */
final class EmulatorAndroidDailyRuntime {
    static final class Report {
        final EmulatorDailyRunner.Result result;
        final String factsReason;
        Report(EmulatorDailyRunner.Result result,String reason){this.result=result;factsReason=reason;}
    }
    static Report run(Context context,EmulatorDailyRunner.Binding held,HarvestRoute route)throws Exception{
        if(context==null||held==null||route==null)throw new IllegalArgumentException("Independent MuMu context/binding/private route required");
        EmulatorAndroidDailyFacts facts=new EmulatorAndroidDailyFacts(context,held,route,new EmulatorAndroidDailyAcceptance(context));
        EmulatorDailyRunner.Result result=EmulatorAndroidDailyTick.run(context,facts,new EmulatorAndroidDailyPort(context,route));
        return new Report(result,facts.reason());
    }
    static Report run(Context context,EmulatorDailyConfiguration configuration)throws Exception{
        if(context==null||configuration==null)throw new IllegalArgumentException("Immutable MuMu itinerary required");
        EmulatorAndroidDailyFacts facts=new EmulatorAndroidDailyFacts(context,configuration,new EmulatorAndroidDailyAcceptance(context));
        EmulatorDailyRunner.Result result=EmulatorAndroidDailyTick.run(context,facts,new EmulatorAndroidDailyPort(context,configuration));
        return new Report(result,facts.reason());
    }
    /** HOST composition seam. Acceptance, root/Android reads and effect ports
     * here are models; this overload is not an external readiness setting. */
    static Report run(EmulatorAndroidDailyTick.StoreSource stores,EmulatorDailyRunner.Binding held,
            EmulatorAndroidDailyFacts.Access access,EmulatorAndroidDailyFacts.Acceptance acceptance,
            EmulatorDailyRunner.Port port,EmulatorAndroidDailyTick.Clock clock)throws Exception{
        EmulatorAndroidDailyFacts facts=new EmulatorAndroidDailyFacts(held,access,acceptance);
        EmulatorDailyRunner.Result result=EmulatorAndroidDailyTick.run(stores,facts,port,clock);
        return new Report(result,facts.reason());
    }
}
