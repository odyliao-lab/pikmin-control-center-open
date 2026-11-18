package dev.ody.pikmincontrol;

/** Passive pre-effect wait only. START has already been confirmed, but WALK
 * is still unsent. An aged positive observation may wait for a new complete
 * frame; it never authorizes movement. No heartbeat, nested fence or effects.
 * The original require/fresh/progress gates apply to the returned frame. */
final class EmulatorDailyWalkBaseline {
    static final long LIMIT_MS=2500;
    interface Read { RouteObservation read()throws Exception; }
    interface Clock { long now(); }
    interface Sleep { void waitMs(long ms)throws Exception; }
    interface Diagnostic { void observed(RouteObservation frame,long wall,long elapsed,long cost); }

    static RouteObservation await(Read read,Clock wall,Clock elapsed,Sleep sleep,Diagnostic diagnostic,
            String pid,String account,long requested)throws Exception {
        if(read==null||wall==null||elapsed==null||sleep==null||pid==null||account==null||
           account.isEmpty()||requested<=0)throw new IllegalArgumentException("Held pre-WALK context required");
        long started=elapsed.now(),deadline=started+LIMIT_MS,previous=started;
        if(started<0||deadline<started)throw new IllegalArgumentException("Bounded pre-WALK clock required");
        while(true){
            long before=elapsed.now();
            if(before<previous||before>=deadline)throw new IllegalStateException("Daily pre-WALK observation deadline");
            RouteObservation frame=read.read(); // No failed read replay or command/effect port.
            long now=elapsed.now(),at=wall.now();
            if(now<before||now>=deadline)throw new IllegalStateException("Daily pre-WALK observation deadline");
            if(diagnostic!=null)diagnostic.observed(frame,at,now,now-before);
            if(ready(frame,pid,account,requested,at,now))return frame;
            previous=now;
            sleep.waitMs(Math.min(250,deadline-now));
        }
    }

    static boolean ready(RouteObservation frame,String pid,String account,long requested,long wall,long elapsed){
        if(frame==null||!account.equals(frame.account)||!pid.equals(frame.location.pid))
            throw new IllegalStateException("Daily pre-WALK identity changed");
        FarmLocation f=frame.location;PlantingState p=frame.planting;
        if(f.provider==null||!f.provider.known||!f.provider.mock||!f.provider.own||
           f.at<=0||f.at>wall||f.rawAt<=0||f.rawAt>elapsed||f.provider.fixMs<=0||f.provider.fixMs>elapsed)
            throw new IllegalStateException("Daily pre-WALK provider or clock changed");
        // A fresh OFF/unowned or pre-request frame is NOT transient lateness.
        if(!p.planting||!p.owned||!"on".equals(p.mode)||p.at<=requested||p.at>wall)
            throw new IllegalStateException("Daily pre-WALK planting confirmation lost");
        if(!f.valid&&!java.util.Arrays.asList("等待遊戲定位心跳更新","等待 GPS JoyStick 新鮮定位","等待遊戲收到新定位").contains(f.readiness))
            throw new IllegalStateException("Daily pre-WALK location malformed");
        if(!p.valid&&wall-p.at<=2000)
            throw new IllegalStateException("Daily pre-WALK planting frame malformed");
        if(!f.valid||!f.fresh(wall,elapsed)||!p.valid||wall-p.at>2000)return false;
        frame.require(pid,account,requested); // unchanged complete original gate before the ONE WALK
        return true;
    }
}
