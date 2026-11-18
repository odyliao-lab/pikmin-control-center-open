#include "../cpp/auto_petal_policy.h"
#include <cassert>
#include <iostream>
int main(){
    using namespace auto_petals;Config c;c.revision="12345678-1234-1234-1234-123456789abc";c.enabled=true;c.order={"a","b","c"};
    Config parsed;assert(parse(encode(c),parsed));assert(parsed.order==c.order);
    auto empty=c;empty.order.clear();
    assert(parse(encode(empty),parsed)&&parsed.enabled&&parsed.order.empty());
    assert(choose(parsed,"a",{{"a",0}},true).action==Action::unknown);
    RecoveryWindow recovery;
    for(long long now=1000;now<11000;now+=2000)assert(!recovery.ready(true,1,now));
    assert(recovery.ready(true,1,11000));
    assert(!recovery.ready(false,1,12000));
    assert(!recovery.ready(true,1,13000));
    assert(!recovery.ready(true,1,27000)); // No elapsed-only recovery across a paused game.
    assert(!recovery.ready(true,2,29000)); // Same account can log in with a new epoch.
    for(long long now=31000;now<39000;now+=2000)assert(!recovery.ready(true,2,now));
    assert(recovery.ready(true,2,39000));
    assert(!recovery.ready(true,2,38000));
    assert(!parse(encode(c)+"extra",parsed));assert(!parse("v1\tx\ton\t50\nend\n",parsed));
    auto duplicated=c;duplicated.order.push_back("a");assert(!parse(encode(duplicated),parsed));
    assert(choose(c,"a",{{"a",56},{"b",80},{"c",90}},true).action==Action::keep);
    auto next=choose(c,"a",{{"a",55},{"b",80},{"c",90}},true);assert(next.action==Action::change&&next.next=="b");
    next=choose(c,"a",{{"a",55},{"b",30},{"c",90}},true);assert(next.next=="c");
    assert(choose(c,"a",{{"a",55},{"c",90}},true).action==Action::unknown);
    assert(choose(c,"a",{{"a",55},{"b",80}},false).action==Action::unknown);
    assert(choose(c,"x",{{"x",1},{"a",90}},true).action==Action::outside);
    assert(choose(c,"c",{{"a",100},{"b",100},{"c",50}},true).action==Action::exhausted);
    c.enabled=false;assert(choose(c,"a",{{"a",1},{"b",90}},true).action==Action::unknown);
    c.enabled=true;c.reserve=1000000;assert(choose(c,"a",{{"a",1000000},{"b",1000000},{"c",1000000}},true).action==Action::exhausted);
    assert(receipt(true,true,true,100,100,"b","b",true,10,11));
    assert(!receipt(true,true,true,100,100,"b","b",true,10,10));
    assert(!receipt(true,true,true,100,101,"b","b",true,10,11));
    assert(!receipt(false,true,true,100,100,"b","b",true,10,11));
    assert(!receipt(true,false,true,100,100,"b","b",true,10,11));
    assert(!receipt(true,true,false,100,100,"b","b",true,10,11));
    assert(!receipt(true,true,true,100,100,"b","a",true,10,11));
    assert(!receipt(true,true,true,100,100,"STOP","a",false,10,10));
    assert(!receipt(true,true,true,100,0,"STOP","",false,10,10));
    assert(receipt(true,true,true,100,0,"STOP","",false,10,10,true));
    assert(!receipt(true,true,true,100,101,"STOP","",false,10,10,true));
    assert(!receipt(true,true,true,100,0,"STOP","",true,10,10,true));
    assert(!receipt(true,false,true,100,0,"STOP","",false,10,10,true));
    assert(!ready_to_change(true,false,false,false,100,5000));
    assert(!ready_to_change(true,true,true,false,100,5000));
    assert(!ready_to_change(true,true,false,true,100,5000));
    assert(!ready_to_change(true,true,false,false,100,4099));
    assert(ready_to_change(true,true,false,false,100,4100));
    std::cout<<"PASS auto petal policy: explicit order, reserve, unknown, no wrap, canonical config\n";
}
