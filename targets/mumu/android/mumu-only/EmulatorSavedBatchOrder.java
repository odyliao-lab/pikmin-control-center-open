package dev.ody.pikmincontrol;
import java.util.*;
/** Scheduling order only. Retains every original candidate object, timestamp,
 * binding and unknown-location fact; never grants dispatch authority. */
final class EmulatorSavedBatchOrder {
    static EmulatorDailyBatchScan.Snapshot balanced(EmulatorDailyBatchScan.Snapshot scan){
        if(scan==null||scan.candidates.size()>EmulatorDailyBatchScan.MAX_TASKS)throw new IllegalArgumentException("Complete bounded scan required");
        String[] kinds={"seed","gift","fruit"};List<List<DispatchProbeGateway.Candidate>> groups=new ArrayList<>();
        for(int n=0;n<kinds.length;n++)groups.add(new ArrayList<>());
        for(DispatchProbeGateway.Candidate c:scan.candidates){int index=Arrays.asList(kinds).indexOf(c.kind);if(index<0)throw new IllegalArgumentException("Known candidate kind required");groups.get(index).add(c);}
        List<DispatchProbeGateway.Candidate> all=new ArrayList<>(),located=new ArrayList<>();
        for(int n=0;all.size()<scan.candidates.size();n++)for(List<DispatchProbeGateway.Candidate> group:groups)if(n<group.size())all.add(group.get(n));
        for(DispatchProbeGateway.Candidate c:all)if(!scan.unlocated.contains(c.taskId))located.add(c);
        return new EmulatorDailyBatchScan.Snapshot(scan.binding,scan.scanMs,scan.inventoryCount,all,located,scan.unlocated);
    }
}
