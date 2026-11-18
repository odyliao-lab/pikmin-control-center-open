#include "../cpp/route_policy.h"
#include <cassert>
#include <iostream>
#include <string>

namespace {
double lat(double metres) { return metres/111194.9266; }
pikmin::NearbyFix fix(double raw, double game, long long at) {
    return {true,lat(raw),0,lat(game),0,at};
}
}

int main(){
    const std::string token="12345678-1234-1234-1234-123456789abc";
    const std::string account(64,'a');
    assert(pikmin::RouteLease::parse("v2 9 20000 "+token+" walk "+account+"\n",9,10000).account==account);
    assert(!pikmin::RouteLease::parse("v1 9 20000 "+token+" walk\n",9,10000).valid());
    assert(!pikmin::RouteLease::parse("v2 9 20000 "+token+" walk bad\n",9,10000).valid());
    for(auto prefix:{"v2 8 20000 ","v2 9 9999 ","v2 9 30000 "})assert(!pikmin::RouteLease::parse(std::string(prefix)+token+" walk "+account,9,10000).valid());
    for(auto raw:{"v1 9 20000 "+token+" walk extra", "v1 8 20000 "+token+" walk", "v1 9 9999 "+token+" walk", "v1 9 30000 "+token+" walk", "v1 9 20000 "+token+" armed"})
        assert(!pikmin::RouteLease::parse(raw,9,10000).valid());

    pikmin::RouteLocationGate steady; steady.begin_session();
    // Begin with the game 25m behind, then let it enter the retained raw trail.
    for(int i=0;i<=20;++i) {
        steady.observe(fix(25+i*5,i*5,1000LL*(i+1)),1000LL*(i+1));
        if(i>=6) assert(steady.ready(1000LL*(i+1)));
    }
    assert(std::string(steady.reason())=="ready");
    // A moving 25m processed lag stays eligible; it is not a teleport.
    assert(steady.over_40_samples()==0);

    // Raw keeps walking while the game's processed point pauses, then catches
    // up in one hop. Preserve the trail and re-open after two fresh fixes.
    pikmin::RouteLocationGate stepped; stepped.begin_session();
    stepped.observe(fix(0,0,1000),1000);
    stepped.observe(fix(5,5,2000),2000); assert(stepped.ready(2000));
    const auto before_catchup=stepped.epoch();
    stepped.observe(fix(10,5,3000),3000);
    stepped.observe(fix(15,5,4000),4000);
    stepped.observe(fix(20,5,5000),5000);
    assert(!stepped.ready(5000)); assert(stepped.epoch()!=before_catchup);
    assert(stepped.catchup_events()==1 && std::string(stepped.reason())=="route-catching-up");
    stepped.observe(fix(25,20,6000),6000);
    assert(!stepped.ready(6000) && stepped.recoveries()==1);
    stepped.observe(fix(30,25,7000),7000);
    assert(stepped.ready(7000) && stepped.recovered_window());

    // A pause longer than the six-second grace prunes old trail, but recovery
    // still takes only two current matched fixes.
    pikmin::RouteLocationGate prolonged; prolonged.begin_session();
    prolonged.observe(fix(0,0,1000),1000); prolonged.observe(fix(5,5,2000),2000);
    for(int i=3;i<=10;++i) { prolonged.observe(fix(i*5,5,1000LL*i),1000LL*i); assert(!prolonged.ready(1000LL*i)); }
    prolonged.observe(fix(55,50,11000),11000); assert(!prolonged.ready(11000));
    prolonged.observe(fix(60,55,12000),12000); assert(prolonged.ready(12000));

    // 40m is diagnostic only in route mode; 80m remains a hard ceiling.
    pikmin::RouteLocationGate lag; lag.begin_session();
    lag.observe(fix(0,0,1000),1000); lag.observe(fix(5,5,2000),2000);
    lag.observe(fix(50,5,9000),9000); // 45m lag, bounded raw speed over 7s
    assert(!lag.ready(9000)); assert(lag.over_40_samples()==1 && lag.over_80_events()==0);
    auto lag_epoch=lag.epoch();
    lag.observe(fix(90,5,16000),16000); // 85m lag
    assert(!lag.ready(16000)); assert(lag.over_80_events()==1 && lag.epoch()!=lag_epoch);
    assert(std::string(lag.reason())=="route-lag-over-80m");

    // Eight seconds is inclusive. Older input and implausible raw jumps remain blocked.
    pikmin::RouteLocationGate boundaries; boundaries.begin_session();
    boundaries.observe(fix(0,0,1000),9000);
    boundaries.observe(fix(5,5,2000),10000); assert(boundaries.ready(10000));
    boundaries.observe(fix(10,10,3000),11001); // age 8001
    assert(!boundaries.ready(11001) && boundaries.stale_events()>0);

    pikmin::RouteLocationGate jump; jump.begin_session();
    jump.observe(fix(0,0,1000),1000); auto jump_epoch=jump.epoch();
    jump.observe(fix(30,30,2000),2000);
    assert(!jump.ready(2000) && jump.epoch()!=jump_epoch);
    assert(std::string(jump.reason())=="route-discontinuous");

    pikmin::RouteLocationGate duplicate; duplicate.begin_session();
    duplicate.observe(fix(0,0,1000),1000);
    duplicate.observe(fix(5,0,2000),2000);
    const auto game_catchup_epoch=duplicate.epoch();
    duplicate.observe(fix(5,5,2000),2200); // processed point may catch up independently
    assert(duplicate.epoch()==game_catchup_epoch);
    duplicate.observe(fix(5,5,2000),2500); // identical snapshot is harmless
    const auto duplicate_epoch=duplicate.epoch();
    duplicate.observe(fix(10,5,2000),2600); // raw movement without clock advance
    assert(!duplicate.ready(2600) && duplicate.epoch()!=duplicate_epoch);
    assert(std::string(duplicate.reason())=="route-discontinuous");

    std::cout<<"PASS route lease and balanced route gate: 80m/8s, bounded raw path, catch-up preservation and one-second recovery\n";
}
