#include "feeding_config.h"
#include "feeding_receipt.h"
#include "feeding_completion.h"
#include "feeding_rotation.h"
#include "feeding_round.h"
namespace feeding_coordinator {
char directory[512]{},state_path[512]{};
char history_path[512]{};
std::string previous_state;
const char *control_path="/data/local/tmp/pikmin-feeding-config.tsv";
long long last_tick{};
int64_t last_rotation_probe{};
int before_flower{};
std::map<std::string,int> before_flowers;
std::map<std::string,int> before_plucks;
std::map<std::string,std::string> outcomes;
feeding_completion::Blocks blocked;
feeding_round::Round feed_round;
int64_t before_revision{};
std::string before_config_revision;
bool read_config(feeding_config::Config &config);
bool save_blocks();
bool load_blocks() {
    const std::string path=std::string(directory)+"/feeding_blocked.tsv";
    std::ifstream file(path,std::ios::binary);
    if(!file){if(access(path.c_str(),F_OK)!=0 && errno==ENOENT){blocked.clear();return true;}return false;}
    char buffer[262145];file.read(buffer,sizeof(buffer));
    if(file.bad() || file.gcount()>262144 ||
        !feeding_completion::decode_blocks(std::string(buffer,static_cast<size_t>(file.gcount())),blocked))return false;
    bool migrated=false;
    for(auto &entry:blocked)if(entry.second.until==0){entry.second.until=now_ms()+60000;migrated=true;}
    for(auto it=blocked.begin();it!=blocked.end();) {
        if(now_ms()>=it->second.until){it=blocked.erase(it);migrated=true;}
        else ++it;
    }
    return !migrated || save_blocks();
}
bool save_blocks() {
    if(blocked.size()>512)return false;
    const auto raw=feeding_completion::encode_blocks(blocked);
    return raw.size()<=262144 && planter_engine::atomic_file((std::string(directory)+"/feeding_blocked.tsv").c_str(),raw);
}
bool priority_work() {
    const auto dispatch=read_dispatch_mode();
    return flower_farm_session_active() || dispatch=="batch" || dispatch=="route" ||
        pending_expedition_task || !dispatch_confirmation_pending_id.empty() || !nearby_selections.empty() ||
        return_one_waiting || return_batch_waiting || planter_engine::pending.task ||
        planting_control_pending_task || pending_task || nectar_unresolved();
}
void status(const std::string &state) {
    feeding_config::Config config;
    const auto revision=read_config(config)?config.revision:"-";
    planter_engine::atomic_file(state_path,"v2\t"+std::to_string(now_ms())+"\t"+
        std::to_string(getpid())+"\t"+revision+"\t"+state+"\nend\n");
    if(history_path[0] && state!=previous_state) {
        std::ifstream old(history_path);std::vector<std::string> rows;std::string row;size_t bytes=0;
        while(std::getline(old,row) && (bytes+=row.size())<=262144) {
            if(std::strtoll(row.c_str(),nullptr,10)>=now_ms()-86400000LL)rows.push_back(row);
        }
        std::string history;
        for(size_t i=rows.size()>499?rows.size()-499:0;i<rows.size();++i)history+=rows[i]+"\n";
        history+=std::to_string(now_ms())+"\t"+state+"\t"+feeding_rpc::active.kind+"\n";
        if(planter_engine::atomic_file(history_path,history))previous_state=state;
    }
}
bool read_config(feeding_config::Config &config) {
    std::ifstream file(control_path,std::ios::binary);
    if(!file)return false;
    char buffer[65537];file.read(buffer,sizeof(buffer));const auto length=file.gcount();
    return !file.bad() && length<=65536 && feeding_config::parse(std::string(buffer,static_cast<size_t>(length)),config);
}
bool recovery_until(int64_t &until) {
    until=0;
    const auto path=std::string(directory)+"/feeding_recovery.tsv";
    std::ifstream file(path,std::ios::binary);
    if(!file)return access(path.c_str(),F_OK)!=0 && errno==ENOENT;
    char buffer[129];file.read(buffer,sizeof(buffer));
    if(file.bad() || file.gcount()>128)return false;
    const std::string raw(buffer,static_cast<size_t>(file.gcount()));
    std::istringstream in(raw);std::string version,end,extra;
    if(!(in>>version>>until>>end) || version!="v1" || end!="end" || until<=0 || (in>>extra))return false;
    return raw=="v1\t"+std::to_string(until)+"\nend\n";
}
bool save_recovery_until(int64_t until) {
    return planter_engine::atomic_file((std::string(directory)+"/feeding_recovery.tsv").c_str(),
        "v1\t"+std::to_string(until)+"\nend\n");
}
bool retire_unconfirmed(feeding_pending::Journal &journal,const feeding_pending::Intent &pending) {
    if(!load_blocks())return false;
    const auto now=now_ms();
    const int64_t until=pending.epoch>now ? now+60000 :
        std::max<int64_t>(pending.epoch+60000,now+5000);
    const auto ids=pending.targets.empty()?std::vector<std::string>{pending.pikmin}:pending.targets;
    for(const auto &id:ids) {
        std::string signature="unknown";
        const auto &s=feeding_probe::latest;
        if(s.fresh(now_ms(),feeding_probe::current_account()) && s.account==pending.account)
            for(const auto &p:s.squad)if(p.id==id){signature=feeding_completion::signature(p);break;}
        const auto key=std::make_pair(pending.account,id);
        auto &block=blocked[key];
        block={signature,std::max(block.until,until)};
    }
    if(!save_blocks() || !journal.archive_unconfirmed(pending))return false;
    if(!feeding_rpc::active.request.empty() && !feeding_rpc::release_archived(directory,pending))return false;
    outcomes.clear();before_flowers.clear();before_plucks.clear();before_revision=0;
    return true;
}
void tick() {
    if(!directory[0] || now_ms()-last_tick<5000)return;
    last_tick=now_ms();
    bool confirmed_snapshot=false;
    std::string confirmed_revision;
    feeding_pending::Journal journal(directory);feeding_pending::Intent pending;
    const auto pending_state=journal.load(pending);
    if(pending_state==feeding_pending::State::unreadable){
        int64_t prior=0;
        const auto epoch=now_ms();
        if(!recovery_until(prior) || !save_recovery_until(std::max<int64_t>(prior,epoch+60000)) ||
            !journal.archive_unreadable(epoch))status("recovery-storage-retry");
        else status("unconfirmed-cooldown");
        return;
    }
    if(pending_state==feeding_pending::State::pending) {
        const bool same_active=feeding_pending::encode(pending)==feeding_pending::encode(feeding_rpc::active);
        const auto reply=same_active?feeding_rpc::poll():feeding_rpc::Reply::absent;
        const bool aged=now_ms()>=pending.epoch && now_ms()-pending.epoch>=60000;
        if(!same_active || reply==feeding_rpc::Reply::uncertain || pending.epoch>now_ms()+1000 ||
            ((reply==feeding_rpc::Reply::waiting || reply==feeding_rpc::Reply::absent) && aged)) {
            feeding_probe::capture();
            status(retire_unconfirmed(journal,pending)?"unconfirmed-cooldown":"recovery-storage-retry");return;
        }
        if(reply!=feeding_rpc::Reply::success && reply!=feeding_rpc::Reply::rejected) {
            status("waiting-rpc-no-retry");return;
        }
        feeding_probe::capture();
        const auto &s=feeding_probe::latest;
        if(!s.fresh(now_ms(),feeding_probe::current_account()) || s.account!=pending.account) {
            if(aged){status(retire_unconfirmed(journal,pending)?"unconfirmed-cooldown":"recovery-storage-retry");return;}
            status("waiting-inventory-confirmation");return;
        }
        {
            if(pending.action=="rotate") {
                const bool confirmed=reply==feeding_rpc::Reply::success && s.server_revision>before_revision &&
                    feeding_rotation::confirmed(s,pending.targets);
                if(confirmed) {
                    const auto receipt=feeding_pending::encode(pending)+"confirmed\t"+std::to_string(s.captured)+"\t0\t0\n";
                    status(journal.archive_confirmed(pending,receipt,true)&&feeding_rpc::release_confirmed(directory,pending)?
                        "rotate-confirmed":"receipt-write-review");
                } else if(aged||reply==feeding_rpc::Reply::rejected)
                    status(retire_unconfirmed(journal,pending)?"unconfirmed-cooldown":"recovery-storage-retry");
                else status("waiting-inventory-confirmation");
                return;
            }
            const auto ids=pending.targets.empty()?std::vector<std::string>{pending.pikmin}:pending.targets;
            const std::map<std::string,int> empty;
            const bool confirmed_response=reply==feeding_rpc::Reply::success && s.server_revision>before_revision;
            feeding_completion::observe(pending,s,confirmed_response?before_flowers:empty,
                confirmed_response?before_plucks:empty,outcomes,now_ms());
            if(!feeding_completion::complete(pending,outcomes)){status("waiting-inventory-confirmation");return;}
            bool all=true,timeout=false;
            std::string rows;
            if(!load_blocks()){status("receipt-write-review");return;}
            for(const auto &entry:outcomes) {
                rows+="outcome\t"+entry.first+"\t"+entry.second+"\n";
                all=all && entry.second=="confirmed";
                if(entry.second=="confirmation-timeout") {
                    timeout=true;
                    for(const auto &p:s.squad)if(p.id==entry.first)
                        blocked[{s.account,p.id}]={feeding_completion::signature(p),now_ms()+60000};
                }
            }
            if(timeout && !save_blocks()){status("receipt-write-review");return;}
            int64_t nectar=-1,petals=-1;
            for(const auto &n:s.nectar)if(n.key==pending.kind){nectar=n.stock;petals=n.petals;}
            const auto receipt=feeding_pending::encode(pending)+(all?"confirmed\t":"resolved\t")+
                std::to_string(s.captured)+"\t"+std::to_string(nectar)+"\t"+std::to_string(petals)+"\n"+rows;
            if(!journal.archive_confirmed(pending,receipt,all) || !feeding_rpc::release_confirmed(directory,pending)) {
                status("receipt-write-review");return;
            }
            status(all?pending.action+"-confirmed":timeout?"batch-confirmation-timeout":"batch-squad-changed");
            // Only fully confirmed multi-kind work may continue on this same
            // observation. No extra full capture, no parallel Task, and never
            // continue a partial/rejected/uncertain request. Single-kind work
            // keeps its previous cadence. Every send below rechecks config,
            // account freshness and higher-priority ownership.
            feeding_config::Config next_config;
            if(!all || !read_config(next_config) || next_config.order.size()<2 ||
                next_config.revision!=before_config_revision) return;
            confirmed_snapshot=true;
            confirmed_revision=next_config.revision;
        }
    }
    if(!feeding_rpc::active.request.empty()) {
        const auto active=feeding_rpc::active;
        if(!journal.has_archived(active) || !feeding_rpc::release_archived(directory,active)) {
            status("recovery-storage-retry");return;
        }
        status("unconfirmed-cooldown");return;
    }
    int64_t global_until=0;
    if(!recovery_until(global_until)){status("recovery-storage-retry");return;}
    if(now_ms()<global_until){status("unconfirmed-cooldown");return;}
    feeding_config::Config config;
    if(!read_config(config)){status("config-unavailable");return;}
    if(confirmed_snapshot && config.revision!=confirmed_revision){status("config-changed");return;}
    if(!config.defaults.enabled){status("off");return;}
    if(priority_work()){status("waiting-other-work");return;}
    if(!confirmed_snapshot) feeding_probe::capture();
    const auto &snapshot=feeding_probe::latest;
    if(!snapshot.fresh(now_ms(),feeding_probe::current_account())){status("waiting-data");return;}
    if(!load_blocks()){status("receipt-write-review");return;}
    bool blocks_changed=false;
    for(const auto &p:snapshot.squad) {
        const auto it=blocked.find({snapshot.account,p.id});
        if(it!=blocked.end() && (now_ms()>=it->second.until ||
            (it->second.signature!="unknown" && it->second.signature!=feeding_completion::signature(p)))) {
            blocked.erase(it);blocks_changed=true;
        }
    }
    if(blocks_changed && !save_blocks()){status("receipt-write-review");return;}
    feed_round.sync(snapshot,config.revision);
    // Collect pre-existing/manual flowers first. For this round's members,
    // feed every usable selected kind BEFORE grouped collection, retaining
    // outstanding petals as capacity reservations. No mixed-kind ledger or
    // unverified mixed-kind pick RPC. Rotate only after collection, before a
    // repeat. A round is scheduling fairness, never proof of RPC completion.
    for(int pass=0;pass<4;++pass) {
        if(pass==3) {
            if(config.rotate && !config.order.empty()) {
                bool replaceable=false;
                for(const auto &p:snapshot.squad)
                    replaceable=replaceable||feeding_rotation::outgoing(p,config.defaults.hearts);
                if(replaceable && !(last_rotation_probe>0 && now_ms()>=last_rotation_probe &&
                    now_ms()-last_rotation_probe<30000)) {
                    last_rotation_probe=now_ms();
                    feeding_probe::capture(config.defaults.hearts);
                    // capture replaces latest in place; snapshot remains the same object.
                    if(!snapshot.fresh(now_ms(),feeding_probe::current_account())){status("waiting-data");return;}
                    std::set<std::string> cooling;
                    for(const auto &b:blocked)if(b.first.first==snapshot.account&&now_ms()<b.second.until)
                        cooling.insert(b.first.second);
                    const auto plan=feeding_rotation::plan(snapshot,config.defaults.hearts,cooling);
                    if(!plan.empty()) {
                        auto ids=plan.out;ids.insert(ids.end(),plan.in.begin(),plan.in.end());
                        const auto now=now_ms();
                        feeding_pending::Intent intent{std::to_string(getpid())+"-"+std::to_string(now),snapshot.account,
                            "rotate","squad-rotation",ids.front(),now,0,0,0,0,ids};
                        before_revision=snapshot.server_revision;
                        const auto allowed=[&]() {
                            feeding_config::Config current;
                            return read_config(current)&&current.revision==config.revision&&current.defaults.enabled&&current.rotate&&
                                !priority_work()&&snapshot.fresh(now_ms(),feeding_probe::current_account())&&
                                feeding_probe::server_revision()==snapshot.server_revision;
                        };
                        status(feeding_rpc::send_rotation(directory,intent,snapshot,config.defaults.hearts,allowed));return;
                    }
                    // Re-read the squad and collectable flowers on the next
                    // tick before repeating; a failed probe is not a send.
                    status("rotation-no-replacements");return;
                }
            }
            if(!feed_round.next()) break;
        }
        const bool collecting=pass==0 || pass==2;
        std::vector<std::string> keys=collecting?std::vector<std::string>{}:config.order;
        if(collecting)for(const auto &p:snapshot.squad)if(p.flower==5 &&
            std::find(keys.begin(),keys.end(),p.flower_key)==keys.end())keys.push_back(p.flower_key);
        for(const auto &key:keys) {
            for(const auto &n:snapshot.nectar) {
                if(n.key!=key)continue;
                auto rule=config.for_kind(key);
                std::vector<std::string> targets;
                std::map<std::string,int> flowers;
                std::map<std::string,int> flower_state_counts;
                int64_t cost=0,yield=0;
                int64_t outstanding=0;
                if(!collecting)for(const auto &ready:snapshot.squad)
                    if(ready.flower==5 && ready.flower_key==key)outstanding+=ready.next_petals;
                feeding_policy::Facts aggregate;
                for(const auto &p:snapshot.squad) {
                if((p.flower==5)!=collecting || (collecting && p.flower_key!=key) || targets.size()>=40)continue;
                if(flowers.count(p.id))continue;
                if(blocked.count({snapshot.account,p.id}))continue;
                if((pass==0 || !collecting) && feed_round.served(p.id))continue;
                feeding_policy::Facts facts;
                facts.complete=facts.account_ready=facts.in_squad=true;
                facts.idle=p.idle();facts.can_collect=p.flower==5;
                facts.can_feed=p.flower==1 || p.flower==3 || p.flower==4;
                facts.heart_milli=p.heart_milli;facts.flower_state_count=p.flower_state_count;
                facts.nectar=n.stock;facts.petals=n.petals;facts.capacity=n.capacity;
                // v152 can report next_petals=1 before feeding, then expose
                // two collectible petals after bloom. Reserve the larger
                // amount, including already-ready petals of the same kind.
                facts.cost=collecting?0:1;facts.yield=collecting?p.next_petals:std::max(2,p.next_petals);
                facts.reserved_nectar=cost;facts.reserved_petals=yield+outstanding;
                const std::string action=feeding_policy::decide(rule,facts);
                if(action!="feed" && action!="collect")continue;
                targets.push_back(p.id);flowers[p.id]=p.flower;flower_state_counts[p.id]=p.flower_state_count;
                cost+=facts.cost;yield+=facts.yield;aggregate=facts;
                }
                if(targets.empty())continue;
                aggregate.cost=cost;aggregate.yield=yield;
                aggregate.reserved_nectar=0;aggregate.reserved_petals=outstanding;
                const std::string action=collecting?"collect":"feed";
                // Configuration and higher-priority ownership can change while
                // reading inventory. Recheck the saved revision before send.
                feeding_config::Config current;
                if(!read_config(current) || current.revision!=config.revision){status("config-changed");return;}
                if(priority_work()){status("waiting-other-work");return;}
                if(!snapshot.fresh(now_ms(),feeding_probe::current_account())){status("waiting-data");return;}
                const auto now=now_ms();
                feeding_pending::Intent intent{std::to_string(getpid())+"-"+std::to_string(now),snapshot.account,action,key,targets.front(),
                    now,n.stock,n.petals,cost,yield,targets};
                outcomes.clear();before_plucks=flower_state_counts;
                before_flowers=flowers;before_flower=flowers.begin()->second;before_revision=snapshot.server_revision;
                before_config_revision=config.revision;
                const auto result=feeding_rpc::send(directory,intent,n.item_id,rule,aggregate);
                // Includes possibly-sent requests; never mark a definitive
                // preflight failure or treat this cursor as confirmation.
                if(action=="feed" && feeding_rpc::active.request==intent.request) feed_round.sent(targets);
                status(result);return;
            }
        }
    }
    status("waiting-eligible-squad");
}
void configure(const char *game_data_dir) {
    std::snprintf(directory,sizeof(directory),"%s/files",game_data_dir);
    std::snprintf(state_path,sizeof(state_path),"%s/files/feeding_state.tsv",game_data_dir);
    std::snprintf(history_path,sizeof(history_path),"%s/files/feeding_history.tsv",game_data_dir);
}
}
