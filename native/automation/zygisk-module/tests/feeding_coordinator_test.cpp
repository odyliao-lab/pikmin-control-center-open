// Exercise the production coordinator and RPC adapter together; mock only the
// managed game boundary and inventory reads. Never use real control paths.
#define main rpc_adapter_fixture_main
#include "feeding_rpc_test.cpp"
#undef main
#include <fstream>
#include <map>
#include <functional>
#include "../cpp/feeding_observation.h"
static std::string dispatch_mode="off";
static std::string read_dispatch_mode(){return dispatch_mode;}
static bool flower_farm_session_active(){return false;}
static void *pending_expedition_task{},*planting_control_pending_task{},*pending_task{};
static std::string dispatch_confirmation_pending_id;
static std::map<std::string,int> nearby_selections;
static bool return_one_waiting{},return_batch_waiting{};
static bool nectar_unknown{};
static bool nectar_unresolved(){return nectar_unknown;}
namespace planter_engine {
static struct {void *task{};} pending;
static bool atomic_file(const char *path,const std::string &data){
    if(std::string(path).find("feeding_blocked.tsv")!=std::string::npos ||
        std::string(path).find("feeding_recovery.tsv")!=std::string::npos){std::ofstream f(path);f<<data;return f.good();}
    return true;
}
}
namespace feeding_probe {
static feeding_observation::Snapshot latest;
static int captures{};
static std::function<void()> after_capture;
static std::string current_account(){return identity_hash;}
static void capture(int =0){++captures;latest.started=latest.captured=now_ms();if(after_capture)after_capture();}
static int64_t server_revision(){return latest.server_revision;}
}
#include "../cpp/feeding_coordinator.inc"
int main() {
    const std::string root=std::getenv("TMPDIR")?std::getenv("TMPDIR"):"/data/local/tmp";
    std::string pattern=root+"/feeding-coordinator-test-XXXXXX";
    std::vector<char> temp_buf(pattern.begin(),pattern.end());temp_buf.push_back('\0');
    char *temp=temp_buf.data();assert(mkdtemp(temp));directory=temp;
    const auto config_file=directory+"/config.tsv";
    {std::ofstream f(config_file);f<<"v1\t00000000-0000-0000-0000-000000000001\t1\t4\t100\t250\t1\t1\nrule\tflower-5:3\t-1\t-1\nend\n";}
    feeding_coordinator::control_path=config_file.c_str();
    std::snprintf(feeding_coordinator::directory,sizeof(feeding_coordinator::directory),"%s",temp);
    std::snprintf(feeding_coordinator::state_path,sizeof(feeding_coordinator::state_path),"%s/state.tsv",temp);
    auto &s=feeding_probe::latest;s.complete=true;s.account=identity_hash;s.server_revision=10;
    s.nectar.push_back({"flower-5:3","real-item",101,240,300,3,true});
    s.squad.push_back({"pikmin-1","",1,0,1,0,1,false});
    nectar_unknown=true;test_clock=4000;feeding_coordinator::tick();assert(calls==0);
    nectar_unknown=false;
    test_clock=5000;dispatch_mode="batch";feeding_coordinator::tick();assert(calls==0);
    test_clock=10000;dispatch_mode="off";s.squad[0].restricted=true;feeding_coordinator::tick();assert(calls==0);
    test_clock=15000;s.squad[0].restricted=false;feeding_coordinator::tick();assert(calls==1);
    const auto feed_request=feeding_rpc::active.request;
    // Response alone and stale server revision cannot retire or send again.
    test_clock=20000;feeding_coordinator::tick();assert(calls==1&&!feeding_rpc::active.request.empty());
    s.server_revision=11;s.nectar[0].stock=100;s.squad[0].flower=5;s.squad[0].flower_key="flower-5:3";
    nectar_unknown=true; // Existing feeding confirmation remains observable while nectar blocks new work.
    test_clock=25000;feeding_coordinator::tick();assert(calls==1&&feeding_rpc::active.request.empty());
    nectar_unknown=false;
    test_clock=30000;feeding_coordinator::tick();assert(calls==2&&feeding_rpc::active.action=="collect");
    const auto pick_request=feeding_rpc::active.request;
    s.server_revision=12;s.nectar[0].petals=241;s.squad[0].flower=4;s.squad[0].flower_state_count=1;
    test_clock=35000;feeding_coordinator::tick();assert(calls==2&&feeding_rpc::active.request.empty());
    // Reserve reached: another feed cannot be sent after confirmed collect.
    test_clock=40000;feeding_coordinator::tick();assert(calls==2);
    // One RPC contains every affordable eligible member, not concurrent
    // singleton requests. Busy/high-heart targets do not consume reservations.
    s.nectar[0].stock=103;s.nectar[0].petals=246;
    s.squad={{"pikmin-1","",1,0,1,0,1,false},{"pikmin-2","",1,0,1,0,1,false},
        {"pikmin-3","",1,0,1,0,2,false},{"busy","",1,0,1,0,1,true},
        {"heart","",1,4000,1,0,1,false}};
    test_clock=45000;feeding_coordinator::tick();assert(calls==3&&request_ids==2);
    assert(feeding_rpc::active.cost==2&&feeding_rpc::active.yield==4);
    const auto batch_request=feeding_rpc::active.request;
    // A partial target transition cannot retire the batch or send it again.
    s.server_revision++;s.squad[0].flower=5;s.squad[0].flower_key="flower-5:3";
    test_clock=50000;feeding_coordinator::tick();assert(calls==3&&!feeding_rpc::active.request.empty());
    s.squad[1].flower=5;s.squad[1].flower_key="flower-5:3";
    test_clock=55000;feeding_coordinator::tick();assert(calls==3&&feeding_rpc::active.request.empty());
    // Empty whitelist still collects manually-fed/high-heart ready flowers.
    {std::ofstream f(config_file);f<<"v1\t00000000-0000-0000-0000-000000000002\t1\t4\t100\t250\t1\t0\nend\n";}
    s.squad[0].heart_milli=8000;
    test_clock=60000;feeding_coordinator::tick();assert(calls==4&&request_ids==2&&feeding_rpc::active.action=="collect");
    const auto batch_pick=feeding_rpc::active.request;
    s.server_revision++;s.squad[0].flower=s.squad[1].flower=4;
    test_clock=65000;feeding_coordinator::tick();assert(calls==4&&feeding_rpc::active.request.empty());
    test_clock=70000;feeding_coordinator::tick();assert(calls==4);
    {std::ofstream f(config_file);f<<"v1\t00000000-0000-0000-0000-000000000003\t1\t4\t100\t250\t0\t1\nrule\tflower-5:3\t-1\t-1\nend\n";}
    s.nectar[0].petals=249;s.squad[0].heart_milli=0;
    test_clock=75000;feeding_coordinator::tick();assert(calls==4); // 1 reported, reserve 2
    s.nectar[0].petals=247;s.squad[0].flower=5;s.squad[0].next_petals=2;
    test_clock=80000;feeding_coordinator::tick();assert(calls==4); // 2 ready + 2 predicted won't fit
    {std::ofstream f(config_file);f<<"v1\t00000000-0000-0000-0000-000000000004\t1\t4\t100\t250\t1\t0\nend\n";}
    s.nectar[0].petals=200;s.squad={{"left","flower-5:3",1,0,5,1,1,false},{"stays","flower-5:3",1,0,5,1,1,false}};
    test_clock=85000;feeding_coordinator::tick();assert(calls==5);
    const auto changed_request=feeding_rpc::active;
    s.squad.erase(s.squad.begin());s.squad[0].flower_state_count=2;s.server_revision++;
    test_clock=90000;feeding_coordinator::tick();assert(feeding_rpc::active.request.empty());
    assert(!feeding_pending::Journal(temp).prepare(changed_request));
    // Still-flowering is accepted when its authoritative pluck count increased.
    test_clock=95000;feeding_coordinator::tick();assert(calls==6);
    const auto timeout_request=feeding_rpc::active.request;
    failed_count=1; // completed partial rejection must not become an eternal wait
    test_clock=125000;feeding_coordinator::tick();assert(feeding_rpc::active.request.empty());
    failed_count=0;
    feeding_coordinator::blocked.clear(); // reload from disk, not just memory
    test_clock=130000;feeding_coordinator::tick();assert(calls==6);
    s.squad.push_back({"new","flower-5:3",1,0,5,0,1,false});
    test_clock=135000;feeding_coordinator::tick();assert(calls==7&&request_ids==1);
    const auto new_request=feeding_rpc::active.request;
    s.squad[1].flower_state_count++;s.server_revision++;
    test_clock=140000;feeding_coordinator::tick();assert(feeding_rpc::active.request.empty());
    // Simulate a game-process death after send. The old exact intent is
    // preserved, only its target cools down, and another ready Pikmin proceeds.
    s.squad={{"recovery-1","flower-5:3",1,0,5,0,1,false}};
    test_clock=145000;feeding_coordinator::tick();assert(calls==8);
    const auto lost=feeding_rpc::active;
    feeding_rpc::active={};feeding_rpc::task=nullptr;feeding_rpc::task_root=0;
    s.squad.push_back({"recovery-2","flower-5:3",1,0,5,0,1,false});
    test_clock=150000;feeding_coordinator::tick();assert(calls==8);
    assert(feeding_pending::Journal(temp).load(feeding_rpc::active)==feeding_pending::State::absent);
    assert(access((directory+"/feeding-unconfirmed-"+lost.request+".tsv").c_str(),F_OK)==0);
    test_clock=155000;feeding_coordinator::tick();assert(calls==9&&request_ids==1&&
        feeding_rpc::active.pikmin=="recovery-2");
    const auto other=feeding_rpc::active;
    s.squad[1].flower=4;s.squad[1].flower_state_count++;s.server_revision++;
    test_clock=160000;feeding_coordinator::tick();assert(feeding_rpc::active.request.empty());
    test_clock=165000;feeding_coordinator::tick();assert(calls==9);
    test_clock=210000;feeding_coordinator::tick();assert(calls==10&&feeding_rpc::active.pikmin=="recovery-1");
    const auto retried=feeding_rpc::active;
    s.squad[0].flower=4;s.squad[0].flower_state_count++;s.server_revision++;
    test_clock=215000;feeding_coordinator::tick();assert(feeding_rpc::active.request.empty());
    assert(unlink((directory+"/feeding-unconfirmed-"+lost.request+".tsv").c_str())==0);
    assert(unlink((directory+"/feeding-confirmed-"+other.request+".tsv").c_str())==0);
    assert(unlink((directory+"/feeding-confirmed-"+retried.request+".tsv").c_str())==0);
    // A torn journal has no reliable target identity. Preserve raw bytes and
    // hold all new sends for one bounded minute, rather than pause forever.
    {std::ofstream f(directory+"/feeding_pending.tsv");f<<"v1";}
    s.squad.push_back({"after-corrupt","flower-5:3",1,0,5,0,1,false});
    test_clock=220000;feeding_coordinator::tick();assert(calls==10);
    assert(access((directory+"/feeding-unreadable-"+std::to_string(getpid())+"-220000.tsv").c_str(),F_OK)==0);
    test_clock=225000;feeding_coordinator::tick();assert(calls==10);
    test_clock=280000;feeding_coordinator::tick();assert(calls==11&&feeding_rpc::active.pikmin=="after-corrupt");
    const auto after_corrupt=feeding_rpc::active;
    s.squad.back().flower=4;s.squad.back().flower_state_count++;s.server_revision++;
    test_clock=285000;feeding_coordinator::tick();assert(feeding_rpc::active.request.empty());
    assert(unlink((directory+"/feeding-confirmed-"+after_corrupt.request+".tsv").c_str())==0);
    assert(unlink((directory+"/feeding-unreadable-"+std::to_string(getpid())+"-220000.tsv").c_str())==0);
    assert(unlink((directory+"/feeding_recovery.tsv").c_str())==0);
    assert(unlink((directory+"/feeding-resolved-"+changed_request.request+".tsv").c_str())==0);
    assert(unlink((directory+"/feeding-resolved-"+timeout_request+".tsv").c_str())==0);
    assert(unlink((directory+"/feeding-confirmed-"+new_request+".tsv").c_str())==0);
    assert(unlink((directory+"/feeding_blocked.tsv").c_str())==0);
    assert(unlink((directory+"/feeding-confirmed-"+batch_request+".tsv").c_str())==0);
    assert(unlink((directory+"/feeding-confirmed-"+batch_pick+".tsv").c_str())==0);
    assert(unlink((directory+"/feeding-confirmed-"+feed_request+".tsv").c_str())==0);
    assert(unlink((directory+"/feeding-confirmed-"+pick_request+".tsv").c_str())==0);
    // Production rotation orchestration: no ordinary feed candidate remains.
    {std::ofstream f(config_file);f<<"v3\t00000000-0000-0000-0000-000000000002\t1\t4\t100\t250\t1\t1\t1\t1\nrule\tflower-5:3\t-1\t-1\nend\n";}
    s.roster_complete=true;s.squad={{"wilt","",1,0,6,0,0,false}};
    s.standby={{"new-bud","",32,0,3,0,2,false}};
    test_clock=300000;feeding_coordinator::tick();assert(calls==12&&feeding_rpc::active.action=="rotate");
    auto swap=feeding_rpc::active;
    test_clock=305000;feeding_coordinator::tick();assert(!feeding_rpc::active.request.empty()&&calls==12);
    s.squad={{"new-bud","",1,0,3,0,2,false}};
    test_clock=310000;feeding_coordinator::tick();assert(!feeding_rpc::active.request.empty()); // fresh response, old inventory revision
    ++s.server_revision;test_clock=315000;feeding_coordinator::tick();assert(feeding_rpc::active.request.empty());
    assert(unlink((directory+"/feeding-confirmed-"+swap.request+".tsv").c_str())==0);
    s.squad={{"wilt-again","",1,0,6,0,0,false}};
    s.standby={{"second","",32,0,3,0,2,false}};
    test_clock=340000;feeding_coordinator::tick();assert(calls==13);
    auto uncertain=feeding_rpc::active;
    test_clock=345000;feeding_coordinator::tick();assert(calls==13&&!feeding_rpc::active.request.empty());
    test_clock=400000;feeding_coordinator::tick();assert(calls==13&&feeding_rpc::active.request.empty());
    s.standby.clear();test_clock=405000;feeding_coordinator::tick();assert(calls==13); // no replacement: no automatic resend
    assert(unlink((directory+"/feeding-unconfirmed-"+uncertain.request+".tsv").c_str())==0);
    assert(unlink((directory+"/feeding_blocked.tsv").c_str())==0);
    // Fairness is a bounded scheduler, never confirmation or a resend ledger.
    {
        feeding_round::Round round;
        s.squad={{"a","",1,0,3,0,2,false},{"b","",1,0,3,0,2,false}};
        round.sync(s,"r1");round.sent({"a"});assert(round.served("a")&&!round.served("b"));
        s.squad.erase(s.squad.begin());round.sync(s,"r1");assert(!round.served("a"));
        round.sent({"b"});round.sync(s,"r2");assert(!round.served("b"));
        round.sent({"b"});auto other=s;other.account=std::string(64,'b');round.sync(other,"r2");assert(!round.served("b"));
        round.sent({"b"});assert(round.next()&&!round.next());
        std::vector<std::string> many;for(int i=0;i<80;++i)many.push_back("id"+std::to_string(i));
        round.sent(many);assert(round.served("id39")&&!round.served("id40"));
    }
    int scenario=0;
    const auto tick=[&](){test_clock+=5000;feeding_coordinator::tick();};
    const auto settle=[&](bool pause_after=false,bool all_confirmed=true) {
        const auto sent=feeding_rpc::active;assert(!sent.request.empty());
        for(auto &p:s.squad)if(std::find(sent.targets.begin(),sent.targets.end(),p.id)!=sent.targets.end()) {
            if(sent.action=="feed"){p.flower=5;p.flower_key=sent.kind;}
            else {p.flower=4;++p.flower_state_count;}
        }
        for(auto &n:s.nectar)if(n.key==sent.kind) {
            if(sent.action=="feed")n.stock-=sent.cost;else n.petals+=sent.yield;
        }
        ++s.server_revision;
        if(pause_after)dispatch_mode="batch"; // exercise preemption, not a test-only coordinator switch
        const auto captured=feeding_probe::captures;
        tick();
        assert(feeding_probe::captures==captured+1); // confirmation + continuation share one capture
        if(pause_after){assert(feeding_rpc::active.request.empty());dispatch_mode="off";}
        assert(unlink((directory+(all_confirmed?"/feeding-confirmed-":"/feeding-resolved-")+sent.request+".tsv").c_str())==0);
    };
    const auto setup30=[&](int petals,int stock,bool second) {
        ++scenario;
        std::ofstream f(config_file);
        f<<"v3\t00000000-0000-0000-0000-00000000010"<<scenario<<"\t1\t4\t100\t1200\t1\t1\t0\t"<<(second?2:1)
            <<"\nrule\tflower-5:3\t-1\t-1\n";
        if(second)f<<"rule\tflower-6:3\t-1\t-1\n";
        f<<"end\n";
        s.nectar={{"flower-5:3","item-a",stock,petals,1200,3,true},
            {"flower-6:3","item-b",500,0,1200,3,false}};
        s.squad.clear();for(int i=0;i<30;++i)s.squad.push_back({"fair-"+std::to_string(i),"",1,0,3,0,2,false});
        s.squad.push_back({"busy","",1,0,3,0,2,true});
        s.squad.push_back({"high-heart","",1,5000,3,0,2,false});
        s.standby.clear();
    };
    for(const auto petals: {1190,1195}) {
        const int first=(1200-petals)/2;
        setup30(petals,500,true);tick();
        assert(feeding_rpc::active.kind=="flower-5:3" && feeding_rpc::active.targets.size()==static_cast<size_t>(first));
        const auto first_ids=feeding_rpc::active.targets;
        settle();assert(feeding_rpc::active.action=="feed"&&feeding_rpc::active.kind=="flower-6:3");
        assert(feeding_rpc::active.targets.size()==static_cast<size_t>(30-first));
        for(const auto &id:first_ids)assert(std::find(feeding_rpc::active.targets.begin(),feeding_rpc::active.targets.end(),id)==feeding_rpc::active.targets.end());
        const auto second_ids=feeding_rpc::active.targets;
        settle();assert(feeding_rpc::active.action=="collect"&&feeding_rpc::active.targets==first_ids);
        settle();assert(feeding_rpc::active.action=="collect"&&feeding_rpc::active.targets==second_ids);
        settle();
        // Everybody eligible has now had a feed and collection; a new round can begin.
        assert(feeding_rpc::active.action=="feed"&&feeding_rpc::active.targets.size()==30);settle(true);
    }
    // Original starvation counterexample: first kind only has 5 nectar above
    // reserve, and its petals are NOT full. Previously the same first 5 could
    // be fed with the next kind before the remaining 25 ever got a turn.
    setup30(0,105,true);tick();assert(feeding_rpc::active.targets.size()==5);settle();
    assert(feeding_rpc::active.action=="feed"&&feeding_rpc::active.kind=="flower-6:3"&&feeding_rpc::active.targets.size()==25);
    assert(feeding_rpc::active.targets.front()=="fair-5");settle(true);
    // No selected second kind: do not widen whitelist or overfill to finish.
    setup30(1190,500,false);tick();assert(feeding_rpc::active.targets.size()==5);settle();tick();settle();
    const auto bounded_calls=calls;tick();assert(calls==bounded_calls&&feeding_rpc::active.request.empty());
    // A new member with no usable capacity must not block eligible repeats.
    s.squad[5].restricted=true;s.nectar[0].petals=1180;
    tick();assert(feeding_rpc::active.action=="feed"&&feeding_rpc::active.targets.size()==10);settle();
    setup30(1200,500,true);
    s.nectar[1].capacity=-1;
    auto no_send=calls;tick();assert(calls==no_send); // unknown capacity is not zero or unlimited
    s.nectar[1].capacity=1200;s.nectar[1].stock=100;
    tick();assert(calls==no_send); // all selected kinds unusable: wait without using reserve
    s.nectar[1].stock=500;
    rpc_manager=nullptr;tick();assert(calls==no_send);
    assert(!feeding_coordinator::feed_round.served("fair-0")); // definitive preflight failure is not served
    rpc_manager=&object;tick();assert(calls==no_send+1&&feeding_rpc::active.targets.size()==30);settle(true);
    // A completed round must rotate a high-heart squad member into an
    // eligible standby Pikmin before repeating the same member's feed.
    {std::ofstream f(config_file);f<<"v3\t00000000-0000-0000-0000-000000000099\t1\t4\t100\t1200\t1\t1\t1\t1\n"
        "rule\tflower-5:3\t-1\t-1\nend\n";}
    s.roster_complete=true;
    s.nectar={{"flower-5:3","item-a",500,0,1200,3,true}};
    s.squad={{"round-current","",1,0,3,0,2,false},{"round-high","",1,5000,4,0,2,false}};
    s.standby={{"round-standby","",32,0,3,0,2,false}};
    feeding_coordinator::last_rotation_probe=0;
    tick();assert(feeding_rpc::active.action=="feed"&&feeding_rpc::active.targets==std::vector<std::string>{"round-current"});settle();
    tick();assert(feeding_rpc::active.action=="collect");settle();
    tick();assert(feeding_rpc::active.action=="rotate"&&feeding_rpc::active.targets.size()==2);
    assert(feeding_rpc::active.targets[0]=="round-high"&&feeding_rpc::active.targets[1]=="round-standby");
    const auto round_swap=feeding_rpc::active;
    s.squad[1]=s.standby.front();s.squad[1].status=1;s.standby.clear();++s.server_revision;
    tick();assert(feeding_rpc::active.request.empty());
    assert(unlink((directory+"/feeding-confirmed-"+round_swap.request+".tsv").c_str())==0);
    tick();assert(feeding_rpc::active.action=="feed"&&
        std::find(feeding_rpc::active.targets.begin(),feeding_rpc::active.targets.end(),"round-standby")!=feeding_rpc::active.targets.end());
    settle();
    tick();assert(feeding_rpc::active.action=="collect");settle();
    s.squad.push_back({"round-high-again","",1,5000,4,0,2,false});
    feeding_coordinator::last_rotation_probe=0;
    const auto before_no_replacement=calls;
    tick();assert(calls==before_no_replacement&&feeding_rpc::active.request.empty());
    tick();assert(feeding_rpc::active.action=="feed"&&feeding_rpc::active.targets.size()==2);
    assert(std::find(feeding_rpc::active.targets.begin(),feeding_rpc::active.targets.end(),"round-high-again")==
        feeding_rpc::active.targets.end());
    settle();
    // The user's 40-member, three-red-heart example: feed 5/10/25 first,
    // then collect 5/10/25. Four selected kinds, but 80 nectar is not usable
    // with a reserve of 100. Confirmed continuation never captures twice.
    int examples=0;
    const auto write40=[&](bool enabled=true) {
        std::ofstream f(config_file);
        f<<"v3\t00000000-0000-0000-0000-0000000002"<<(10+examples)<<"\t"<<(enabled?1:0)
            <<"\t4\t100\t1000\t1\t1\t0\t4\n"
            "rule\tflower-5:1\t-1\t-1\nrule\tflower-5:2\t-1\t-1\n"
            "rule\tflower-5:4\t-1\t-1\nrule\tflower-6:1\t-1\t-1\nend\n";
    };
    const auto setup40=[&]() {
        assert(feeding_rpc::active.request.empty());++examples;write40();
        s.complete=true;s.account=identity_hash;s.standby.clear();s.squad.clear();
        for(int i=0;i<40;++i)s.squad.push_back({"example-"+std::to_string(i),"",1,3000,3,0,2,false});
        s.nectar={{"flower-5:1","white-item",200,990,1000,1,true},
            {"flower-5:2","red-item",200,980,1000,2,true},
            {"flower-5:4","yellow-item",200,950,1000,4,true},
            {"flower-6:1","other-item",80,0,1000,1,false}};
    };
    const auto finish40=[&]() {
        for(int i=0;!feeding_rpc::active.request.empty()&&i<12;++i)settle();
        assert(feeding_rpc::active.request.empty());
    };
    setup40();
    const auto example_calls=calls,example_captures=feeding_probe::captures;
    tick();
    std::set<std::string> all_fed;
    const char *expected_kinds[]={"flower-5:1","flower-5:2","flower-5:4"};
    const size_t expected_counts[]={5,10,25};
    for(int stage=0;stage<6;++stage) {
        const auto &intent=feeding_rpc::active;
        assert(intent.action==(stage<3?"feed":"collect"));
        assert(intent.kind==expected_kinds[stage%3]&&intent.targets.size()==expected_counts[stage%3]);
        if(stage<3)for(const auto &id:intent.targets)assert(all_fed.insert(id).second);
        const auto captures_before=feeding_probe::captures,calls_before=calls;
        test_clock+=1000;feeding_coordinator::tick();
        assert(calls==calls_before&&feeding_probe::captures==captures_before); // unchanged 5s gate
        settle();
    }
    assert(all_fed.size()==40&&calls==example_calls+6);
    assert(feeding_probe::captures==example_captures+7); // initial + six confirmations
    assert(feeding_rpc::active.request.empty());
    assert(s.nectar[0].stock==195&&s.nectar[1].stock==190&&s.nectar[2].stock==175&&s.nectar[3].stock==80);
    for(int i=0;i<3;++i)assert(s.nectar[i].petals==1000);
    // One confirmation cannot start the next kind with stale data, stale
    // revision, another account, changed configuration, OFF, or priority work.
    setup40();tick();
    auto held=feeding_rpc::active.request;auto no_more=calls;
    tick();assert(calls==no_more&&feeding_rpc::active.request==held); // RPC success, old revision
    feeding_probe::after_capture=[&](){s.started-=1001;};
    tick();assert(calls==no_more&&feeding_rpc::active.request==held);
    feeding_probe::after_capture=[&](){identity_hash=std::string(64,'b');};
    tick();assert(calls==no_more&&feeding_rpc::active.request==held);
    identity_hash=s.account;feeding_probe::after_capture={};
    settle(true);assert(calls==no_more); // priority acquired during confirmation
    tick();assert(feeding_rpc::active.action=="feed"&&feeding_rpc::active.kind=="flower-5:2");
    write40(false);no_more=calls;settle();assert(calls==no_more&&feeding_rpc::active.request.empty());
    write40();tick();finish40();
    setup40();tick();
    ++examples;write40();no_more=calls;settle();
    assert(calls==no_more&&feeding_rpc::active.request.empty()); // revision changed: no same-tick continuation
    tick();assert(feeding_rpc::active.action=="collect"); // old ready petals are no longer this round's deferred work
    finish40();
    // A receipt failure must retain the durable pending, not silently advance
    // to the next kind. This only mutates the fixture's dedicated tmp folder.
    setup40();tick();const auto receipt_hold=feeding_rpc::active;
    const auto obstruct=directory+"/feeding-confirmed-"+receipt_hold.request+".tsv";
    assert(mkdir(obstruct.c_str(),0700)==0);
    for(auto &p:s.squad)if(std::find(receipt_hold.targets.begin(),receipt_hold.targets.end(),p.id)!=receipt_hold.targets.end()) {
        p.flower=5;p.flower_key=receipt_hold.kind;
    }
    s.nectar[0].stock-=receipt_hold.cost;++s.server_revision;no_more=calls;
    tick();assert(calls==no_more&&feeding_rpc::active.request==receipt_hold.request);
    assert(rmdir(obstruct.c_str())==0);
    tick();assert(calls==no_more+1&&feeding_rpc::active.kind=="flower-5:2");
    assert(unlink(obstruct.c_str())==0);finish40();
    setup40();tick();no_more=calls;
    s.squad.erase(s.squad.begin());settle(false,false);
    assert(calls==no_more&&feeding_rpc::active.request.empty()); // partial outcome cannot chain
    tick();finish40();
    // An exception in the next kind may have consumed nectar: keep its exact
    // intent, archive unconfirmed + cooldown, and do not replay those targets.
    setup40();tick();exception_on_send=true;settle();exception_on_send=false;
    const auto uncertain_next=feeding_rpc::active;
    assert(uncertain_next.kind=="flower-5:2");no_more=calls;
    tick();assert(calls==no_more&&feeding_rpc::active.request.empty());
    assert(unlink((directory+"/feeding-unconfirmed-"+uncertain_next.request+".tsv").c_str())==0);
    tick();assert(feeding_rpc::active.action=="feed"); // other IDs may still use an affordable red kind
    for(const auto &id:feeding_rpc::active.targets)
        assert(std::find(uncertain_next.targets.begin(),uncertain_next.targets.end(),id)==uncertain_next.targets.end());
    for(int i=0;!feeding_rpc::active.request.empty()&&i<12;++i) {
        if(feeding_rpc::active.action=="feed")for(const auto &id:feeding_rpc::active.targets)
            assert(std::find(uncertain_next.targets.begin(),uncertain_next.targets.end(),id)==uncertain_next.targets.end());
        settle();
    }
    assert(feeding_rpc::active.request.empty());
    assert(unlink((directory+"/feeding_blocked.tsv").c_str())==0);
    assert(unlink(config_file.c_str())==0);assert(rmdir(temp)==0);
    std::puts("PASS production coordinator mock: legacy safety/rotation, deferred multi-kind collection, 40-member 5/10/25 then grouped collection, reserve/capacity, one capture/5s, same-tick confirmed continuation, stale/account/config/OFF/priority/receipt gates; NOT live feeding");
}
