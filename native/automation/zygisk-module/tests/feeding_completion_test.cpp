#include "../cpp/feeding_completion.h"
#include <cassert>
#include <cstdio>
int main() {
    using namespace feeding_completion;
    feeding_pending::Intent i{"r",std::string(64,'a'),"collect","flower-5:1","one",1000,100,0,0,3,{"one","two","three"}};
    feeding_observation::Snapshot s;
    s.squad={{"one","flower-5:1",1,0,5,2,1,false},{"two","flower-5:1",1,0,5,1,1,false}};
    std::map<std::string,int> flowers{{"one",5},{"two",5},{"three",5}},plucks{{"one",1},{"two",1},{"three",1}};
    std::map<std::string,std::string> outcomes;
    observe(i,s,flowers,plucks,outcomes,2000);
    assert(outcomes["one"]=="confirmed"&&outcomes["three"]=="squad-left"&&!complete(i,outcomes));
    s.squad.erase(s.squad.begin());observe(i,s,flowers,plucks,outcomes,31000);
    assert(outcomes["one"]=="confirmed"&&outcomes["two"]=="confirmation-timeout"&&complete(i,outcomes));
    outcomes["foreign"]="confirmed";assert(!complete(i,outcomes));
    Blocks blocks{{{i.account,"two"},{signature(s.squad[0]),90000}}},parsed;
    assert(decode_blocks(encode_blocks(blocks),parsed)&&parsed.size()==1&&
        parsed.begin()->second.signature==signature(s.squad[0])&&parsed.begin()->second.until==90000);
    assert(!decode_blocks(encode_blocks(blocks)+"extra",parsed));
    assert(!decode_blocks("",parsed));
    const auto legacy="v1\nblock\t"+i.account+"\ttwo\t"+signature(s.squad[0])+"\nend\n";
    assert(decode_blocks(legacy,parsed)&&parsed.begin()->second.until==0);
    std::puts("PASS feeding completion: sticky confirmed, squad-left, bounded timeout, counter progression and durable block framing; NOT device validation");
}
