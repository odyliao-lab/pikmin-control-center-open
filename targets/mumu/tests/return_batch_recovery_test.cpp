// Runs the actual MuMu function with deterministic observations and a counted RPC.
// The runner extracts that function without rewriting it. No game files/API used.
#include <cassert>
#include <cstdint>
#include <climits>
#include <string>
#include <cstdio>
#include <cstring>
#include "return_policy.h"
#define LOGI(...) ((void)0)
#define LOGE(...) ((void)0)
std::string mode="all",last_return_batch_mode="all",return_batch_pending_id,return_batch_pending_reward;
bool return_batch_waiting=false,return_batch_stopped=false,return_all_empty_reported=false,complete=true,seen=true;
bool excluded=false;
bool task_mutation_allowed(const std::string&){return !excluded;}
uint64_t return_batch_pending_epoch=7;
std::string return_batch_pending_account="account-a",account="account-a";
struct Ticket {bool valid=true;uint64_t epoch=7;};
struct Registry {Ticket t;Ticket begin_scan(){return t;}} capability_registry;
std::string nectar_account_binding(){return account;}
int return_batch_baseline_count=0,return_batch_completed=0,posts=0,observations=0,limit=2;
long long return_batch_dispatched_ms=0,clock_ms=100000;
void *return_action_manager=reinterpret_cast<void*>(1),*return_inventory_manager=reinterpret_cast<void*>(2);
alignas(void*) unsigned char list[40]{},items[64]{};
std::string last_status;
std::string read_return_mode(){return mode;}
void *task_list(void*,void*){++observations;return list;}
void *complete_task(void*,void*,bool,void*){++posts;return nullptr;}
void *item_id(void*,void*){return const_cast<char*>("new-task");}
void *task_proto(void*,void*){return reinterpret_cast<void*>(3);}
int64_t finish_time(void*,void*){return 0;}
auto original_get_pikmin_task_list=task_list;
auto original_complete_pikmin_task=complete_task;
auto get_inventory_item_id=item_id;
auto get_pikmin_task_proto=task_proto;
auto get_task_finish_time_ms=finish_time;
int (*gchandle_new)(void*,bool)=nullptr;
bool complete_return_list(void*,int){return complete;}
bool task_list_contains_id(void*,int,const std::string& id){assert(id=="old-task");return seen;}
long long now_ms(){return clock_ms;}
bool return_discard_postcard(){return false;}
void write_return_status(const char *s,int,int,bool,bool){last_status=s;}
int return_batch_limit(){return limit;}
bool return_proto_ready(void*,long long){return true;}
std::string utf8_string(void* p){return static_cast<char*>(p);}
std::string describe_return_reward(void*){return "opaque-reward";}
void append_return_trace(const char*,void*,void*){}
#include "return_batch_under_test.inc"
void reset(){
 mode=last_return_batch_mode="all";return_batch_waiting=true;return_batch_stopped=false;
 return_batch_pending_id="old-task";return_batch_pending_reward="opaque-original";
 return_batch_dispatched_ms=1000;return_batch_completed=0;posts=observations=0;
 seen=complete=true;clock_ms=100000;last_status.clear();
 account=return_batch_pending_account="account-a";return_batch_pending_epoch=7;capability_registry.t={true,7};
 void *a=items,*t=reinterpret_cast<void*>(4);int count=1;
 std::memcpy(list+0x10,&a,sizeof a);std::memcpy(list+0x18,&count,sizeof count);std::memcpy(items+0x20,&t,sizeof t);
}
int main(){
 reset();maybe_dispatch_return_batch();assert(return_batch_stopped&&return_batch_waiting&&posts==0);
 int before=observations;maybe_dispatch_return_batch();assert(observations==before+1&&posts==0&&return_batch_pending_id=="old-task");
 complete=false;seen=false;maybe_dispatch_return_batch();assert(return_batch_waiting&&return_batch_stopped&&posts==0);
 complete=true;maybe_dispatch_return_batch();assert(!return_batch_waiting&&!return_batch_stopped&&return_batch_completed==1&&posts==0&&last_status=="batch-confirmed");
 maybe_dispatch_return_batch();assert(posts==1&&return_batch_pending_id=="new-task");
 reset();maybe_dispatch_return_batch();mode="off";maybe_dispatch_return_batch();assert(return_batch_waiting&&return_batch_stopped&&posts==0&&return_batch_pending_id=="old-task");
 mode="batch";maybe_dispatch_return_batch();assert(return_batch_waiting&&return_batch_stopped&&posts==0&&return_batch_pending_id=="old-task");
 seen=false;maybe_dispatch_return_batch();assert(!return_batch_waiting&&return_batch_completed==1&&posts==0);
 return_batch_completed=limit;maybe_dispatch_return_batch();assert(return_batch_stopped&&posts==0&&last_status=="batch-paused-limit");
 before=observations;maybe_dispatch_return_batch();assert(observations==before&&posts==0);
 reset();clock_ms=50000;maybe_dispatch_return_batch();assert(return_batch_waiting&&!return_batch_stopped&&posts==0);
 reset();maybe_dispatch_return_batch();seen=false;account="account-b";maybe_dispatch_return_batch();assert(return_batch_waiting&&posts==0&&return_batch_completed==0);
 account="account-a";capability_registry.t.epoch=8;maybe_dispatch_return_batch();assert(return_batch_waiting&&posts==0&&return_batch_completed==0);
 capability_registry.t={false,7};maybe_dispatch_return_batch();assert(return_batch_waiting&&posts==0);
 capability_registry.t={true,7};maybe_dispatch_return_batch();assert(!return_batch_waiting&&posts==0&&return_batch_completed==1);
 reset();return_batch_waiting=false;return_all_empty_reported=false;excluded=true;maybe_dispatch_return_batch();assert(posts==0&&!return_batch_waiting&&last_status=="excluded-prior-unknown");
 excluded=false;maybe_dispatch_return_batch();assert(posts==1&&return_batch_waiting);
 std::puts("PASS actual return batch: timeout, late exact observation, incomplete snapshot, OFF/ON, mode change, limit; no retry of unknown");
}
