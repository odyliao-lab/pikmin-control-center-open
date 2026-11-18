#include "../cpp/nearby_safety.h"
#include <cassert>
#include <cstdio>
#include <limits>
#include <string>
#include <initializer_list>
using namespace pikmin;
int main() {
    for (int64_t ms : {-2000, -1, 0, 300001, 519535566, 519560925, 519568240}) assert(!nearby_duration_safe(ms));
    for (int64_t ms : {1, 2000, 120000, 120001, 180000, 299999, 300000}) assert(nearby_duration_safe(ms));
    NearbyLocationGate gate;
    NearbyFix taiwan{true,25,121,25,121,10000};
    gate.observe(taiwan,10000,10000); assert(!gate.ready(10000));
    gate.observe(taiwan,11000,11000); gate.observe(taiwan,14000,14000);
    assert(!gate.ready(14000)); // repeated cached fix cannot open gate
    taiwan.raw_wall_ms=15000; gate.observe(taiwan,15000,15000);
    taiwan.raw_wall_ms=16000; gate.observe(taiwan,16000,16000); assert(gate.ready(16000));
    const auto before_jump=gate.epoch();
    NearbyFix japan{true,35,139,28,127,17000};
    gate.observe(japan,17000,17000); assert(!gate.ready(17000) && gate.epoch()!=before_jump);
    for (double intermediate : {30.,32.,34.}) {
        japan.game_lat=intermediate; japan.raw_wall_ms+=1000;
        gate.observe(japan,japan.raw_wall_ms,japan.raw_wall_ms); assert(!gate.ready(japan.raw_wall_ms));
    }
    japan.game_lat=35; japan.game_lng=139;
    for (int i=0;i<4;++i) { japan.raw_wall_ms+=1000; gate.observe(japan,japan.raw_wall_ms,japan.raw_wall_ms); }
    assert(gate.ready(japan.raw_wall_ms)); // catches up, 3 distinct fixes + >=3 seconds
    assert(!gate.ready(japan.raw_wall_ms+2001)); // stopped heartbeat
    auto epoch=gate.epoch();
    japan.raw_lat=japan.game_lat=36; japan.raw_wall_ms+=1000;
    gate.observe(japan,japan.raw_wall_ms,japan.raw_wall_ms);
    assert(!gate.ready(japan.raw_wall_ms) && gate.epoch()!=epoch); // instant full jump still settles
    gate.observe(japan,japan.raw_wall_ms+6000,japan.raw_wall_ms+6000); assert(!gate.ready(japan.raw_wall_ms+6000));
    assert(std::string(gate.reason())=="stale-game-location");
    japan.raw_wall_ms=999999; gate.observe(japan,40000,40000); assert(!gate.ready(40000));
    japan.valid=false; gate.observe(japan,41000,41000); assert(!gate.ready(41000));
    japan.valid=true; japan.raw_lat=std::numeric_limits<double>::quiet_NaN();
    gate.observe(japan,42000,42000); assert(!gate.ready(42000));
    NearbyFix origin{true,0,0,0,0,1000}; NearbyLocationGate zero;
    for (int i=0;i<4;++i) { origin.raw_wall_ms+=1000; zero.observe(origin,origin.raw_wall_ms,origin.raw_wall_ms); }
    assert(zero.ready(5000)); // equator/prime meridian are valid coordinates
    epoch=zero.epoch(); zero.observe(origin,4000,6000); assert(!zero.ready(6000) && epoch!=zero.epoch());
    // Synthetic sample with distinct Unix, boot, and monotonic clocks.
    // Monotonic can differ from boot time after suspend; never use either
    // Unix or monotonic for raw-fix age, nor calibrate stale input to 'now'.
    NearbyLocationGate boot;
    NearbyFix actual{true,-12.12500,47.25000,-12.12500,47.25000,45000};
    for (int i=0; i<4; ++i) {
        actual.raw_wall_ms=835828+i*1000;
        boot.observe(actual,837000+i*1000,780000+i*1000);
    }
    assert(boot.ready(783000));
    boot.observe(actual,901000,783001); // one minute suspended, stale raw fix
    assert(!boot.ready(783001));
    boot.observe(actual,-1,783002); assert(!boot.ready(783002));
    boot.observe(actual,2000000000000LL,783003); assert(!boot.ready(783003));
    boot.observe(actual,780000,783004); assert(!boot.ready(783004)); // wrong clock/future
    // 18km/h continuous route: no repeated epoch resets after passing 8m.
    NearbyLocationGate walking;
    NearbyFix walk{true,0,0,0,0,1000};
    for (int i=0;i<120;++i) {
        walk.raw_wall_ms=1000+i*1000;
        walk.raw_lat=walk.game_lat=i*5.0/111194.9266;
        walking.observe(walk,walk.raw_wall_ms,walk.raw_wall_ms);
        if(i>=3) assert(walking.ready(walk.raw_wall_ms));
        assert(walking.epoch()==0);
    }
    // A loop corner/reversal is valid: do not require a fixed heading.
    for(int i=1;i<=10;++i) {
        walk.raw_wall_ms+=1000; walk.raw_lat=walk.game_lat=(119-i)*5.0/111194.9266;
        walking.observe(walk,walk.raw_wall_ms,walk.raw_wall_ms);
        assert(walking.ready(walk.raw_wall_ms));
    }
    epoch=walking.epoch();
    walk.raw_wall_ms+=1000; walk.raw_lat=walk.game_lat=10;
    walking.observe(walk,walk.raw_wall_ms,walk.raw_wall_ms);
    assert(!walking.ready(walk.raw_wall_ms)&&walking.epoch()!=epoch);
    // Continual overspeed cannot re-arm even when raw and processed agree.
    for(int i=1;i<=8;++i) {
        walk.raw_wall_ms+=1000; walk.raw_lat=walk.game_lat=10+i*30.0/111194.9266;
        walking.observe(walk,walk.raw_wall_ms,walk.raw_wall_ms);
        assert(!walking.ready(walk.raw_wall_ms));
    }
    // Duplicate timestamps cannot hide raw jumps or count toward readiness.
    epoch=walking.epoch(); walk.raw_lat=walk.game_lat=11;
    walking.observe(walk,walk.raw_wall_ms,walk.raw_wall_ms+1);
    assert(!walking.ready(walk.raw_wall_ms+1)&&walking.epoch()!=epoch);
    // A sustained 18km/h route can leave the processed game point ~35m
    // behind the raw point. Both must actually move, at bounded speed.
    NearbyLocationGate delayed;
    NearbyFix lagged{true,35.0/111194.9266,0,0,0,1000};
    for(int i=0;i<6;++i) {
        lagged.raw_wall_ms=1000+i*1000;
        lagged.raw_lat=(35.0+i*5.0)/111194.9266;
        lagged.game_lat=i*5.0/111194.9266;
        delayed.observe(lagged,lagged.raw_wall_ms,lagged.raw_wall_ms);
        if(i>=3) assert(delayed.ready(lagged.raw_wall_ms));
    }
    lagged.raw_wall_ms+=1000; lagged.raw_lat+=5.0/111194.9266;
    delayed.observe(lagged,lagged.raw_wall_ms,lagged.raw_wall_ms);
    assert(!delayed.ready(lagged.raw_wall_ms)); // game point stopped moving
    lagged.raw_wall_ms+=1000; lagged.game_lat=lagged.raw_lat-45.0/111194.9266;
    delayed.observe(lagged,lagged.raw_wall_ms,lagged.raw_wall_ms);
    assert(!delayed.ready(lagged.raw_wall_ms)); // excessive mismatch
    // A jump beyond the bounded lag remains blocked.
    walk.raw_wall_ms+=1000; walk.game_lat=walk.raw_lat-50.0/111194.9266;
    walking.observe(walk,walk.raw_wall_ms,walk.raw_wall_ms);
    assert(!walking.ready(walk.raw_wall_ms));
    assert(std::string(walking.reason())=="game-location-catching-up");
    // Raw fixes at 1Hz, game interpolation at 4Hz: the actual Libra failure.
    // Include turning/reversing and all of 17/18/19km/h, not only one fix/tick.
    constexpr double metre = 1.0/111194.9266;
    for (double kmh : {17.0,18.0,19.0}) {
        NearbyLocationGate smooth;
        const double speed=kmh/3.6;
        for (int i=0;i<160;++i) {
            const int64_t at=10000+i*250, raw_at=10000+(i/4)*1000;
            const double raw_seconds=(raw_at-10000)/1000.0;
            const double game_seconds=(at-10000)/1000.0;
            const auto position=[&](double seconds) {return (seconds<=20?seconds:40-seconds)*speed;};
            NearbyFix f{true,(20+position(raw_seconds))*metre,0,position(game_seconds)*metre,0,raw_at};
            smooth.observe(f,at,at);
            assert(smooth.epoch()==0);
            if(i>=12)assert(smooth.ready(at));
        }
        auto e=smooth.epoch();
        NearbyFix jump{true,10,0,10,0,50000};
        smooth.observe(jump,50000,50000);
        assert(!smooth.ready(50000)&&smooth.epoch()!=e);
    }
    // Interpolation never manufactures the three distinct raw fixes.
    NearbyLocationGate cached;
    NearbyFix cache{true,20*metre,0,0,0,10000};
    for(int i=0;i<17;++i){cache.game_lat=i*0.5*metre;cached.observe(cache,10000+i*250,10000+i*250);
        assert(cached.samples()==1&&!cached.ready(10000+i*250)&&cached.epoch()==0);}
    auto e=cached.epoch();cached.observe(cache,15001,15001);
    assert(!cached.ready(15001)&&cached.epoch()!=e); // stale raw still blocks
    // Same raw timestamp + changed raw coordinates is not interpolation.
    NearbyLocationGate conflict;
    cache={true,20*metre,0,0,0,10000};conflict.observe(cache,10000,10000);
    cache.raw_lat+=2*metre;conflict.observe(cache,10250,10250);
    assert(conflict.epoch()!=0&&!conflict.ready(10250));
    // A processed jump is rejected even when raw is cached and lag is <40m.
    NearbyLocationGate game_jump;
    cache={true,20*metre,0,0,0,10000};game_jump.observe(cache,10000,10000);
    cache.game_lat=10*metre;game_jump.observe(cache,10250,10250);
    assert(game_jump.epoch()!=0&&!game_jump.ready(10250));
    // A 1ms raw timestamp refresh must not divide a smooth game step by 1ms.
    NearbyLocationGate fine_time;
    cache={true,20*metre,0,0,0,10000};fine_time.observe(cache,10000,10000);
    cache.raw_wall_ms++;cache.game_lat=1.25*metre;fine_time.observe(cache,10250,10250);
    assert(fine_time.epoch()==0);
    // High-frequency drift cannot replenish the jitter allowance every frame.
    NearbyLocationGate jitter;
    cache={true,20*metre,0,0,0,10000};jitter.observe(cache,10000,10000);
    for(int i=1;i<=3;++i){cache.game_lat=i*1.9*metre;jitter.observe(cache,10000+i*100,10000+i*100);}
    assert(jitter.epoch()!=0&&!jitter.ready(10300));
    puts("PASS nearby safety: 17/18/19kmh raw/interpolation clocks, corners, cached and 1ms fixes, bounded jitter, duration/lag/stale/jump/overspeed protections");
}
