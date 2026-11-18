// Exercises the included production engine with deterministic managed-call fakes.
// All journal IO is real POSIX IO in a private temporary directory. No game/API.
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <cerrno>
#include <fstream>
#include <map>
#include <set>
#include <string>
#include <vector>
#include <memory>
#include <sys/stat.h>
#include <unistd.h>
#include "../../../native/automation/zygisk-module/cpp/managed_gc.h"
#include "../../../native/automation/zygisk-module/cpp/planter_policy.h"
#include "../native/automation/planter_recovery.h"
#include "../../../native/automation/zygisk-module/cpp/capability_state.h"
using pikmin::GcHandle;
using pikmin::ScopedManagedRoot;
#define LOGI(...) ((void)0)
#define LOGE(...) ((void)0)
static int64_t mono=1000,wall=100000;
static std::string owner="account-a";
static bool runtime_metadata_ready=true;
static int manager;
static void *return_inventory_manager=&manager,*rpc_manager=&manager;
static pcc_cap::Registry capability_registry;
long long now_ms(){return wall;}
long long steady_ms(){return mono;}
std::string nectar_account_binding(){return owner;}
struct Obj { std::string kind,text; int integer{};bool boolean{},complete{},fault{},cancel{};Obj *result{};std::vector<Obj*> items; };
static std::vector<std::unique_ptr<Obj>> objects;
Obj *obj(const std::string &kind){objects.emplace_back(new Obj);objects.back()->kind=kind;return objects.back().get();}
static Obj *rpc_task;
static bool start_null{},start_throws{},root_task_fails{};
static int sends{},roots{},frees{},polls{};
static std::string missing;
static int string_class;
static bool metadata_missing;
GcHandle root_new(void *v,bool){if(root_task_fails&&v==rpc_task)return 0;++roots;return static_cast<GcHandle>(roots);}
void root_free(GcHandle){++frees;}
static pikmin::GcHandleNew gchandle_new=root_new;
static pikmin::GcHandleFree gchandle_free=root_free;
void *object_get_class(void *o){return o;}
void *class_get_method_from_name(void *,const char *n,int){return missing==n?nullptr:const_cast<char*>(n);}
void *class_get_methods(void *,void **it){if(*it)return nullptr;*it=&manager;return const_cast<char*>("Add");}
const char *method_get_name(void *m){return static_cast<const char*>(m);}
uint32_t method_get_param_count(void *){return 1;}
const char *class_get_name(void *){return "unused";}
void *find_class(const char *,const char *name){return std::strcmp(name,"String")==0?&string_class:&manager;}
void *verified_v152_method(void *,const char *,int,uintptr_t){return metadata_missing?nullptr:&manager;}
void *object_new(void *){auto *v=obj("request");v->items.push_back(obj("list"));return v;}
void *string_new(const char *s){auto *v=obj("string");v->text=s;return v;}
std::string read_string(void *v){return v?static_cast<Obj*>(v)->text:std::string{};}
std::string describe_seed(void *){return "seed";}
void *xdl_sym(void *,const char *,void *){return nullptr;}
void *fake_nested(void *,void **){return nullptr;}
void *fake_param(void *,uint32_t){return &string_class;}
void *fake_from_type(void *p){return p;}
void *fake_unbox(void *v){auto *o=static_cast<Obj*>(v);return o->kind=="bool"?static_cast<void*>(&o->boolean):static_cast<void*>(&o->integer);}
void *fake_invoke(void *m,void *v,void **args,void **ex){
    const std::string n=static_cast<char*>(m);auto *o=static_cast<Obj*>(v);
    if(n=="SendPull") {++sends;if(start_throws)*ex=&manager;return start_null?nullptr:rpc_task;}
    if(n==".ctor")return nullptr;
    if(n=="get_SeedId")return o->items[0];
    if(n=="Add"){o->items.push_back(static_cast<Obj*>(args[0]));return nullptr;}
    if(n=="get_Count"){auto *b=obj("int");b->integer=static_cast<int>(o->items.size());return b;}
    if(n=="get_Item")return o->items.at(*static_cast<int*>(args[0]));
    if(n=="get_IsCompleted"||n=="get_IsFaulted"||n=="get_IsCanceled"){
        ++polls;auto *b=obj("bool");b->boolean=n=="get_IsCompleted"?o->complete:n=="get_IsFaulted"?o->fault:o->cancel;return b;
    }
    if(n=="get_Result")return o->result;
    if(n=="get_Status")return o->kind=="response"?o->result:o;
    if(n=="get_Pikmin")return o->items[0];
    assert(false && "unexpected managed call");return nullptr;
}
#include "../native/automation/planter_automation.inc"
static std::string temp;
static std::string read_file(const std::string &p){std::ifstream f(p,std::ios::binary);return {std::istreambuf_iterator<char>(f),{}};}
static void write_file(const std::string &p,const std::string &v){std::ofstream f(p,std::ios::binary);f<<v;assert(f.good());}
static std::string mode_path;
static void setup(){
    namespace e=planter_engine;
    char dir[]="/data/local/tmp/planter-flow-XXXXXX";assert(mkdtemp(dir));temp=dir;mode_path=temp+"/mode";
    e::control_path=mode_path.c_str();write_file(mode_path,"auto\n");
    std::snprintf(e::pending_path,sizeof(e::pending_path),"%s/planter_pending.tsv",temp.c_str());
    std::snprintf(e::history_path,sizeof(e::history_path),"%s/history.tsv",temp.c_str());
    std::snprintf(e::state_path,sizeof(e::state_path),"%s/status.tsv",temp.c_str());
    std::snprintf(e::recovery_state_path,sizeof(e::recovery_state_path),"%s/recovery.tsv",temp.c_str());
    e::invoke=fake_invoke;e::unbox=fake_unbox;e::nested=fake_nested;e::param=fake_param;e::from_type=fake_from_type;
    e::pull_method=const_cast<char*>("SendPull");e::pull_class=&manager;e::initialized=true;e::compatible=true;
    auto epoch=capability_registry.on_login(wall);capability_registry.on_identity_ready(epoch);
    capability_registry.capture(pcc_cap::cap_preparer,epoch);
}
static planter_engine::Snapshot snap(){
    planter_engine::Snapshot s;s.complete=true;s.epoch=capability_registry.current_epoch();s.account=owner;s.manager=&manager;
    s.max_slots=2;s.capacity=300;s.pikmins.insert("old-pikmin");s.slots.push_back({0,0,0,"seed-1"});
    planter_engine::Seed seed;seed.id="seed-1";seed.planted=100;seed.required=1000;seed.steps=1000;seed.ready=true;s.seeds.emplace(seed.id,seed);return s;
}
static void prepare_task(){
    rpc_task=obj("task");rpc_task->result=obj("response");rpc_task->result->result=obj("int");rpc_task->result->result->integer=1;
    auto *ids=obj("list");auto *new_id=obj("string");new_id->text="new-pikmin";ids->items.push_back(new_id);rpc_task->result->items.push_back(ids);
}
static void start(){auto s=snap();planter_engine::start("pluck",s.seeds.at("seed-1"),s.slots[0],s);}
static void adopt(){
    namespace e=planter_engine;assert(!e::recover(snap()));mono+=30001;assert(e::recover(snap()));
    assert(access(e::pending_path,F_OK)!=0);assert(!e::pending.watch.active);
}
int main(){
    namespace e=planter_engine;setup();
    // An early missing method does not permanently latch initialization off.
    e::initialized=false;e::option_class=&manager;metadata_missing=true;e::init();
    assert(!e::initialized&&!e::compatible);metadata_missing=false;e::init();
    assert(e::initialized&&e::compatible);e::pull_method=const_cast<char*>("SendPull");
    // Restart: malformed/oversized/binary records are archived byte-for-byte, not parsed or replayed.
    const std::string legacy=std::string(8192,'x')+std::string("\0legacy\n",8);
    write_file(e::pending_path,legacy);write_file(std::string(e::pending_path)+".recovered","older-proof");
    adopt();assert(sends==0);assert(read_file(std::string(e::pending_path)+".recovered")=="older-proof");
    // start-null and root-failure retain unknown original, then permit a new decision only after delay.
    prepare_task();start_null=true;start();start_null=false;assert(sends==1&&!e::pending.task);adopt();
    prepare_task();root_task_fails=true;start();root_task_fails=false;assert(!e::pending.task&&!e::pending.root);adopt();
    prepare_task();start_throws=true;start();start_throws=false;assert(!e::pending.task);adopt();
    // A live never-completing task is rooted until the bounded observer retires it.
    prepare_task();start();assert(e::pending.task&&e::pending.root);int before=frees;
    wall-=999999;mono+=29999;e::observe_pending(snap());assert(e::pending.task&&frees==before);
    mono++;e::observe_pending(snap());assert(!e::pending.task&&frees==before+1);int old_polls=polls;
    rpc_task->complete=true;adopt();assert(polls==old_polls); // late completion cannot mutate the new lifecycle
    // Fault, cancellation, malformed getter, and explicit rejection never become confirmations.
    for(int scenario=0;scenario<4;++scenario){
        prepare_task();start();rpc_task->complete=true;
        if(scenario==0)rpc_task->fault=true;
        if(scenario==1)rpc_task->cancel=true;
        if(scenario==2)missing="get_IsFaulted";
        if(scenario==3)rpc_task->result->result->integer=2;
        e::observe_pending(snap());missing.clear();assert(!e::pending.watch.active&&!e::archive_confirmed);adopt();
    }
    // Success without inventory change is also bounded; wall-clock changes cannot extend it.
    prepare_task();start();rpc_task->complete=true;e::observe_pending(snap());assert(e::pending.watch.responded);
    wall+=999999999;mono+=30000;e::observe_pending(snap());assert(!e::pending.watch.active&&!e::archive_confirmed);adopt();
    // Confirm only matching current-session authoritative state, never a changed account.
    prepare_task();start();owner="account-b";e::observe_pending(snap());assert(!e::archive_confirmed);adopt();
    prepare_task();start();rpc_task->complete=true;auto s=snap();s.seeds.clear();s.slots[0].seed.clear();s.pikmins.insert("new-pikmin");
    e::observe_pending(s);assert(e::archive_confirmed&&!e::pending.task);assert(!e::recover(s));mono+=5001;assert(e::recover(s));
    // Incomplete snapshots and a session transition never satisfy adoption.
    e::adoption.wait(mono,true);s=snap();s.complete=false;assert(!e::recover(s));mono+=30001;assert(!e::recover(s));
    assert(!e::recover(snap()));assert(e::recover(snap()));
    // Storage failure cannot become a permanent latch: preserve a FIFO without reading it,
    // adopt fresh state after backoff, send once, and observe only the newly rooted Task.
    assert(mkfifo(e::pending_path,0600)==0);
    assert(!e::recover(snap()));mono+=30001;assert(e::recover(snap()));
    int fallback_sent=sends;prepare_task();start();
    assert(sends==fallback_sent+1&&e::pending.watch.active&&!e::pending.journaled);
    struct stat original{};assert(lstat(e::pending_path,&original)==0&&S_ISFIFO(original.st_mode));
    mono+=30000;e::observe_pending(snap());assert(!e::pending.watch.active&&!e::archive_confirmed);
    assert(!e::recover(snap()));mono+=30001;assert(e::recover(snap()));
    assert(lstat(e::pending_path,&original)==0&&S_ISFIFO(original.st_mode));
    assert(unlink(e::pending_path)==0); // Test fixture cleanup only; production never removes unsafe entries.
    // Explicit off prevents constructing or sending a new mutation from an otherwise valid snapshot.
    write_file(mode_path,"off\n");int sent=sends;prepare_task();start();assert(sends==sent);
    // Actual store failure retries: an unsafe directory becomes usable without manual recovery.
    std::string store_dir=temp+"/retry";assert(mkdir(store_dir.c_str(),0700)==0);
    pikmin::planter::PendingStore store(store_dir);std::string retained;
    assert(chmod(store_dir.c_str(),0777)==0);assert(!store.create("intent\n"));
    assert(store.archive(false,mono,retained)==pikmin::planter::PendingStore::Archive::retry);
    assert(chmod(store_dir.c_str(),0700)==0);assert(store.create("intent\n"));
    assert(store.archive(false,mono,retained)==pikmin::planter::PendingStore::Archive::retained);
    assert(read_file(store_dir+"/"+retained)=="intent\n");
    // FIFO and symlink cannot block archive or be followed. Existing record is never overwritten.
    const auto ledger=store_dir+"/planter_pending.tsv";assert(mkfifo(ledger.c_str(),0600)==0);
    assert(store.archive(false,mono,retained)==pikmin::planter::PendingStore::Archive::retry);assert(!store.create("replace"));assert(unlink(ledger.c_str())==0);
    assert(symlink("outside",ledger.c_str())==0);assert(store.archive(false,mono,retained)==pikmin::planter::PendingStore::Archive::retry);assert(unlink(ledger.c_str())==0);
    assert(store.create("one"));assert(!store.create("two"));assert(store.archive(false,mono,retained)==pikmin::planter::PendingStore::Archive::retained);
    const auto first=retained;assert(store.create("two"));assert(store.archive(false,mono,retained)==pikmin::planter::PendingStore::Archive::retained);
    assert(first!=retained&&read_file(store_dir+"/"+first)=="one"&&read_file(store_dir+"/"+retained)=="two");
    std::printf("PASS production planter flow: bounded task/inventory, faults, restart archival, session, off, real store; sends=%d roots=%d frees=%d dir=%s\n",sends,roots,frees,temp.c_str());
}
