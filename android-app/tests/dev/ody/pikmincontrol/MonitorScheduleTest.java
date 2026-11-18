package dev.ody.pikmincontrol;
public final class MonitorScheduleTest {
    static void eq(long a,long b){if(a!=b)throw new AssertionError(a+" != "+b);}
    public static void main(String[] args){
        MonitorSchedule s=new MonitorSchedule();
        eq(s.next(MonitorSchedule.Result.WAITING,false,false,true),15000);
        eq(s.next(MonitorSchedule.Result.WAITING,false,false,true),30000);
        eq(s.next(MonitorSchedule.Result.FAILED,false,false,true),60000);
        eq(s.next(MonitorSchedule.Result.FAILED,false,true,true),15000);
        eq(s.next(MonitorSchedule.Result.HEALTHY,false,false,true),45000);
        eq(s.next(MonitorSchedule.Result.WAITING,false,false,true),15000);
        eq(s.next(MonitorSchedule.Result.HEALTHY,true,false,true),15000);
        eq(s.next(MonitorSchedule.Result.BUSY,false,false,true),15000);
        eq(s.next(MonitorSchedule.Result.FAILED,false,false,false),15000);
        eq(s.next(MonitorSchedule.Result.FAILED,false,false,true),15000);
        System.out.println("PASS settings monitor cadence: bounded backoff, visible/STOP priority, recovery reset and rollback");
    }
}
