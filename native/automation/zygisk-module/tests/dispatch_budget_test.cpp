#include "../cpp/dispatch_budget.h"
#include <cassert>
#include <cstdio>
int main(){
    pikmin::DispatchBudget b;b.begin(100);
    assert(b.capacity(100));b.used=1;assert(!b.capacity(104));
    b.begin(200);b.used=4;assert(!b.capacity(200));
    b.hints["far"]={100,900000,0,0,6};
    assert(!b.due("far",1000,0,false));
    assert(b.due("far",1000,25,false));
    assert(b.due("far",1000,0,true));
    assert(b.due("far",30100,0,false));
    assert(b.due("new",1000,0,false));
    b.hints["near"]={100,300001,0,0,1};
    assert(b.due("near",2100,0,false));
    b.prune({"near"});assert(b.hints.size()==1);
    puts("PASS dispatch count/time budget and cache invalidation");
}
