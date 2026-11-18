#define main unused_feed_fixture_main
#include "feeding_rpc_test.cpp"
#undef main
#include "../cpp/feeding_config.h"
int main(){
    using namespace feeding_rotation;
    feeding_observation::Snapshot s;s.complete=s.roster_complete=true;s.account=identity_hash;s.started=s.captured=1000;
    s.squad={{"wilt","",1,0,6,0,0,false},{"high","",1,4000,4,0,1,false},
        {"ready","",1,5000,5,0,1,false},{"busy","",2,5000,6,0,0,false},{"low","",1,3999,3,0,2,false}};
    s.standby={{"busy-in","",2,0,3,0,2,false},{"wilt-in","",32,0,6,0,0,false},
        {"high-in","",32,4000,3,0,2,false},{"restricted","",32,0,3,0,2,true},
        {"leaf","",32,0,1,0,1,false},{"bud","",32,3000,3,0,2,false}};
    s.squad.push_back({"unknown-high","",1,8000,0,0,0,false});
    s.squad.push_back({"restricted-high","",1,8000,6,0,0,true});
    auto p=plan(s,4);assert(p.out==std::vector<std::string>({"wilt","high"}));assert(p.in==std::vector<std::string>({"leaf","bud"}));
    assert(plan(s,4,{"leaf","bud"}).empty());assert(plan(s,4,{"high"}).out.size()==1);
    s.roster_complete=false;assert(plan(s,4).empty());s.roster_complete=true;
    auto duplicate=s;duplicate.standby.push_back(s.squad[0]);assert(plan(duplicate,4).empty());
    auto none=s;none.standby.clear();assert(plan(none,4).empty());
    const std::string root=std::getenv("TMPDIR")?std::getenv("TMPDIR"):"/data/local/tmp";
    auto pattern=root+"/feeding-rotation-XXXXXX";std::vector<char> buf(pattern.begin(),pattern.end());buf.push_back(0);
    const char *temp=mkdtemp(buf.data());assert(temp);directory=temp;
    auto ids=p.out;ids.insert(ids.end(),p.in.begin(),p.in.end());
    feeding_pending::Intent i{"rotate-1",identity_hash,"rotate","squad-rotation",ids.front(),1000,0,0,0,0,ids};
    assert(feeding_pending::valid(i));feeding_pending::Intent decoded;
    assert(feeding_pending::decode(feeding_pending::encode(i),decoded)&&decoded.targets==ids);
    auto invalid=i;invalid.targets.pop_back();assert(!feeding_pending::valid(invalid));
    assert(std::string(feeding_rpc::send_rotation(temp,i,s,4,[]{return false;}))=="rotation-preflight-changed");assert(calls==0);
    assert(std::string(feeding_rpc::send_rotation(temp,i,s,4,[]{return true;}))=="rotation-sent");assert(calls==1);
    assert(rotation_in==p.in&&rotation_out==p.out);assert(feeding_rpc::poll()==feeding_rpc::Reply::success);
    assert(std::string(feeding_rpc::send_rotation(temp,i,s,4,[]{return true;}))=="pending-review");assert(calls==1);
    assert(!confirmed(s,ids));
    s.squad={s.standby[4],s.standby[5]};assert(confirmed(s,ids));
    // Unknown response stays durable; archived only by the coordinator.
    fault=true;assert(feeding_rpc::poll()==feeding_rpc::Reply::uncertain);
    feeding_pending::Journal j(temp);assert(j.load(decoded)==feeding_pending::State::pending);
    assert(j.archive_unconfirmed(i));assert(feeding_rpc::release_archived(temp,i));
    assert(unlink((directory+"/feeding-unconfirmed-rotate-1.tsv").c_str())==0);
    assert(rmdir(temp)==0);
    feeding_config::Config config;
    assert(feeding_config::parse("v3\t00000000-0000-0000-0000-000000000001\t1\t4\t100\t1000000\t1\t1\t1\t0\nend\n",config)&&config.rotate);
    assert(feeding_config::parse("v2\t00000000-0000-0000-0000-000000000001\t1\t4\t100\t1000000\t1\t1\t0\nend\n",config)&&!config.rotate);
    puts("PASS feeding rotation policy + production managed adapter: eligible, busy, petals-first, pairs, journal, no-retry (NOT real game)");
}
