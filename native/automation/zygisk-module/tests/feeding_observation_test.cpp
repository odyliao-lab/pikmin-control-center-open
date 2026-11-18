#include "../cpp/feeding_observation.h"
#include <cassert>
#include <cstdio>
int main() {
    using feeding_observation::confirmed_petal_stock;
    const std::map<std::pair<std::string,int>,int> petals={
        {{"flower-s-carnation",1},12},{{"flower-s-carnation",2},0},
        {{"flower-s-rose",3},300}};
    assert(confirmed_petal_stock(petals,"flower-s-carnation",1,true)==12);
    assert(confirmed_petal_stock(petals,"flower-s-carnation",2,true)==0);
    assert(confirmed_petal_stock(petals,"flower-s-carnation",3,true)==0);
    assert(confirmed_petal_stock(petals,"flower-s-new-kind",1,true)==0);
    assert(confirmed_petal_stock({},"flower-s-new-kind",4,true)==0);
    for(int color=1;color<=4;++color){
        assert(confirmed_petal_stock(petals,"flower-s-new-kind",color,true)==0);
        assert(confirmed_petal_stock(petals,"flower-s-new-kind",color,false)==-1);
        assert(confirmed_petal_stock(petals,"flower-s-carnation",color,false)==-1);
    }
    assert(confirmed_petal_stock({},"flower-s-new-kind",1,false)==-1);
    assert(confirmed_petal_stock(petals,"",1,true)==-1);
    assert(confirmed_petal_stock(petals,"flower-s-new-kind",0,true)==-1);
    assert(confirmed_petal_stock(petals,"flower-s-new-kind",5,true)==-1);
    feeding_observation::Snapshot s;s.account="account-a";s.started=1000;s.captured=1100;s.complete=true;
    assert(s.fresh(1100,"account-a"));assert(s.fresh(2000,"account-a"));
    assert(!s.fresh(2001,"account-a"));assert(!s.fresh(1099,"account-a"));
    assert(!s.fresh(1100,"account-b"));s.complete=false;assert(!s.fresh(1100,"account-a"));s.complete=true;
    s.captured=999;assert(!s.fresh(1100,"account-a"));s.captured=1100;
    s.account="";assert(!s.fresh(1100,""));
    feeding_observation::Pikmin p;p.status=1;p.restricted=false;assert(p.idle());
    p.restricted=true;assert(!p.idle());p.restricted=false;
    p.status=2;assert(!p.idle());p.status=32;assert(!p.idle());p.status=0;assert(!p.idle());
    std::puts("PASS feeding observation: confirmed missing petals zero, failed reads unknown, freshness/account/idle; NOT live getter validation");
}
