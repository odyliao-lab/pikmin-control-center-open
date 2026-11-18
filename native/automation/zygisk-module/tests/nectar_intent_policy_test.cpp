#include "../cpp/nectar_intent_policy.h"
#include <cassert>
#include <memory>
#include <map>

struct Memory {
    bool exists{}, unreadable{}, fail_before_write{}, fail_after_write{};
    nectar_intent::Record record;
    int writes{};
    std::map<std::string,nectar_intent::Record> excluded;
};
struct FakeStore {
    std::shared_ptr<Memory> state;
    bool excluded(const std::string& a,const std::string& t,bool& found){found=state->excluded.count(a+t)>0;return !state->unreadable;}
    bool quarantine(const nectar_intent::Record& r){
        if(state->unreadable||state->fail_before_write||!state->exists||!nectar_intent::equal(r,state->record))return false;
        state->excluded[r.account+r.target]=r;state->exists=false;return !state->fail_after_write;
    }
    bool read(nectar_intent::Record& r,bool& exists) {
        if(state->unreadable)return false;exists=state->exists;r=state->record;return true;
    }
    bool replace(const nectar_intent::Record* expected,const nectar_intent::Record& next) {
        if(state->unreadable||state->fail_before_write||
           state->exists!=(expected!=nullptr)||
           (expected&&!nectar_intent::equal(*expected,state->record))||
           !nectar_intent::transition(expected,next))return false;
        state->record=next;state->exists=true;++state->writes;
        return !state->fail_after_write;
    }
};
using Guard=nectar_intent::Guard<FakeStore>;
int main() {
    using nectar_intent::Begin;
    const std::string account(64,'a'),target(64,'b'),other(64,'c');
    {
        auto archive=std::make_shared<Memory>();Guard g(FakeStore{archive});
        assert(g.begin(account,target,1,123,100000,1000)==Begin::admitted);
        auto original=g.record();
        assert(!g.retire_unconfirmed(60999,true));
        assert(g.retire_unconfirmed(61000,true));
        assert(nectar_intent::equal(original,archive->excluded.at(account+target)));
        assert(archive->excluded.at(account+target).phase=="pending");
        assert(!g.resolve_success(original.binding));
        assert(g.begin(account,target,2,123,200000,62000)==Begin::duplicate);
        assert(g.begin(account,other,2,123,200000,62000)==Begin::admitted);
        archive->fail_before_write=true;
        assert(!g.retire_unconfirmed(122000,true));assert(g.blocked());
        assert(g.begin(account,std::string(64,'d'),3,123,300000,123000)==Begin::blocked);
    }
    auto memory=std::make_shared<Memory>();
    Guard live(FakeStore{memory});int sends=0;
    assert(!live.blocked());
    if(live.begin(account,target,1,123,100000,1000)==Begin::admitted)++sends;
    assert(sends==1&&memory->writes==1&&live.blocked());
    auto binding=live.record().binding;
    assert(live.status(100000,1000,true).find("PENDING:0:100000:")==0);
    assert(live.status(160000,61000,true).find("PENDING_UNCONFIRMED:60000:")==0);
    assert(live.status(99999,999,true).find("PENDING_UNCONFIRMED:unknown:")==0);
    assert(live.status(100000,1000,false).find("PENDING_UNCONFIRMED:")==0); // null/fault/no Task
    assert(live.begin(account,other,1,123,160001,61001)==Begin::blocked);
    assert(!live.resolve_success(std::string(64,'d')));
    assert(memory->record.phase=="pending");

    // Crash after durable intent, both before and after an actual send:
    // a new Guard has no authority to replay or to infer a terminal result.
    Guard restarted(FakeStore{memory});
    assert(restarted.blocked());
    assert(restarted.status(160000,1,false).find(":restart")!=std::string::npos);
    assert(restarted.status(99999,1,false).find("PENDING_UNCONFIRMED:unknown:")==0);
    assert(restarted.blocked());
    if(restarted.begin(account,target,2,456,160000,1)==Begin::admitted)++sends;
    assert(sends==1&&!restarted.resolve_success(binding));
    assert(restarted.begin(other,other,9,999,999999,999999)==Begin::blocked);

    // Only the original matching Task's authoritative success resolves.
    assert(live.resolve_success(binding));
    assert(!live.blocked());
    Guard completed_restart(FakeStore{memory});
    assert(!completed_restart.blocked());
    assert(completed_restart.begin(account,target,2,456,200000,2000)==Begin::duplicate);
    assert(completed_restart.begin(account,other,2,456,200001,2001)==Begin::admitted);
    assert(!completed_restart.resolve_success(binding)); // old completion is not current

    // Before-write failure: no send; restart can start only because no intent
    // exists and the failed attempt provably never passed admission.
    auto before=std::make_shared<Memory>();before->fail_before_write=true;
    Guard failed_before(FakeStore{before});
    assert(failed_before.begin(account,target,1,123,100000,1000)==Begin::unavailable);
    assert(failed_before.blocked()&&!before->exists);

    // Rename/dir-fsync ambiguity: write may be visible but admission failed.
    auto after=std::make_shared<Memory>();after->fail_after_write=true;
    Guard failed_after(FakeStore{after});
    assert(failed_after.begin(account,target,1,123,100000,1000)==Begin::unavailable);
    assert(failed_after.blocked()&&after->exists);
    after->fail_after_write=false;
    Guard after_restart(FakeStore{after});
    assert(after_restart.blocked());

    // Failure of terminal durability never clears the in-process fence.
    auto terminal=std::make_shared<Memory>();Guard terminal_guard(FakeStore{terminal});
    assert(terminal_guard.begin(account,target,1,123,100000,1000)==Begin::admitted);
    terminal->fail_before_write=true;
    assert(!terminal_guard.resolve_success(terminal_guard.record().binding));
    assert(terminal_guard.blocked()&&terminal->record.phase=="pending");

    auto bad=std::make_shared<Memory>();bad->unreadable=true;
    Guard unreadable(FakeStore{bad});assert(unreadable.blocked());
    assert(unreadable.status(1,1,false).find(":storage")!=std::string::npos);
    nectar_intent::Record decoded;
    auto encoded=nectar_intent::encode(memory->record);
    assert(nectar_intent::decode(encoded,decoded));
    assert(!nectar_intent::decode(encoded.substr(0,encoded.size()-1),decoded));
    encoded[5]^=1;assert(!nectar_intent::decode(encoded,decoded));
    assert(!nectar_intent::decode(std::string(1025,'x'),decoded));
    assert(!nectar_intent::transition(&memory->record,memory->record));
}
