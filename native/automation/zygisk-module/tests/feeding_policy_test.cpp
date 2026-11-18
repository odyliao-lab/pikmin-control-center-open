#include "../cpp/feeding_policy.h"
#include "../cpp/feeding_observation.h"
#include <cassert>
#include <cstring>
#include <cstdio>
#include <climits>
using namespace feeding_policy;
int main() {
    Config c; c.enabled = c.allowed = true;
    Facts f; f.complete=f.account_ready=f.in_squad=f.idle=f.can_feed=true;
    f.nectar=101;f.petals=248;f.capacity=300;f.cost=1;f.yield=2;f.heart_milli=3999;f.flower_state_count=4;
    const auto base=f;
    auto check=[&](const char *expected){assert(std::strcmp(decide(c,f),expected)==0);};
    check("feed");
    f.petals=feeding_observation::confirmed_petal_stock({},"flower-s-new-kind",1,true);check("feed");
    f.petals=feeding_observation::confirmed_petal_stock({},"flower-s-new-kind",1,false);check("waiting-data");f=base;
    f.nectar=100;check("nectar-reserve");f=base;
    f.reserved_nectar=1;check("nectar-reserve");f=base;
    f.petals=249;check("petal-limit");f=base;
    f.reserved_petals=1;check("petal-limit");f=base;
    f.capacity=249;check("petal-limit");f=base;
    f.heart_milli=4000;check("friendship-reached");f=base;
    // FlowerStateFlowerCount is observation-only: even a large raw value
    // cannot turn an otherwise game-authorised candidate into a local reject.
    f.flower_state_count=5;check("feed");f.flower_state_count=6;check("feed");f=base;
    c.aggressive_pick=false;f.flower_state_count=5;check("feed");c.aggressive_pick=true;f=base;
    f.can_feed=false;check("waiting-recovery");f=base;
    f.idle=false;check("waiting-squad");f=base;
    f.in_squad=false;check("waiting-squad");f=base;
    f.account_ready=false;check("waiting-data");f=base;
    f.complete=false;check("waiting-data");f=base;
    f.priority_work=true;check("waiting-other-work");f=base;
    f.pending=true;check("pending-review");f=base;
    c.allowed=false;check("not-allowed");c.allowed=true;
    c.enabled=false;check("off");c.enabled=true;
    f.can_collect=true;f.heart_milli=4000;f.flower_state_count=5;check("collect");
    c.allowed=false;check("collect");c.allowed=true;
    c.collect=false;check("waiting-collect");c.collect=true;
    f.reserved_petals=LLONG_MAX;check("petal-limit");f=base;
    f.yield=LLONG_MAX;check("petal-limit");f=base;
    f.cost=LLONG_MAX;check("nectar-reserve");f=base;
    f.capacity=-1;check("waiting-data");f=base;
    f.heart_milli=8001;check("waiting-data");f=base;
    c.hearts=0;check("waiting-data");
    std::puts("PASS feeding native policy: 25 gates; NOT game/RPC validation");
}
