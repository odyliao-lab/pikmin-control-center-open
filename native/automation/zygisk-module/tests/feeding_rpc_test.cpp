#include <string>
#include <mutex>
#include <cstring>
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include "../cpp/feeding_pending.h"
using GcHandle=uintptr_t;
static int object,boxed,count=1,failed_count=0,calls=0,request_ids=0;
static bool fault=false,exception_on_send=false;
static int rotation_in_count{},rotation_out_count{};
static std::string current_string;
static std::vector<std::string> rotation_in,rotation_out;
static std::string directory;
static void *ptr(uintptr_t n){return reinterpret_cast<void *>(n);}
static int64_t test_clock=1000;
static int64_t now_ms(){return test_clock;}
static GcHandle root_new(void *p,bool){return p?1:0;}
static void root_free(GcHandle){}
static auto gchandle_new=root_new;
static auto gchandle_free=root_free;
struct ScopedManagedRoot {GcHandle handle;ScopedManagedRoot(void *p,decltype(gchandle_new),decltype(gchandle_free)):handle(p?1:0){}};
static void *find_class(const char *,const char *name){return !std::strcmp(name,"ArrangePikminTroopRequestProto")?ptr(101):std::strcmp(name,"String")==0?ptr(2):ptr(1);}
static void *verified_v153_method(void *c,const char *,int,uintptr_t rva){return c?ptr(rva):nullptr;}
static const char *class_get_name(void *p){return p==ptr(102)?"Types":p==ptr(103)?"MoveToTroopProto":"MoveToEntourageProto";}
static void *verified_v152_method(void *,const char *,int,uintptr_t rva){return ptr(rva);}
static void *new_object(void *){return &object;}
static auto object_new=new_object;
static void *new_string(const char *s){current_string=s;return &object;}
static auto string_new=new_string;
static void *object_get_class(void *o){return o==ptr(2001)||o==ptr(2002)?o:ptr(1);}
static void *class_get_methods(void *k,void **iter){if(*iter)return nullptr;*iter=ptr(1);return k==ptr(2001)?ptr(201):k==ptr(2002)?ptr(202):ptr(9);}
static const char *method_get_name(void *){return "Add";}
static int method_get_param_count(void *){return 1;}
static void *class_get_method_from_name(void *,const char *name,int){
    if(!std::strcmp(name,"get_Count"))return ptr(10);
    if(!std::strcmp(name,"get_IsCompleted"))return ptr(11);
    if(!std::strcmp(name,"get_IsFaulted"))return ptr(12);
    if(!std::strcmp(name,"get_IsCanceled"))return ptr(13);
    if(!std::strcmp(name,"get_Result"))return ptr(14);
    if(!std::strcmp(name,"get_Status"))return ptr(15);
    return nullptr;
}
static void *rpc_manager=&object;
static std::mutex identity_mutex;
static std::string identity_hash(64,'a');
static bool identity_logout_hook_ready=true;
namespace planter_engine {
static void *parameter(void *m,uint32_t){return m==ptr(201)?ptr(103):m==ptr(202)?ptr(104):ptr(2);}
static void *nested_impl(void *c,void **iter){
    auto n=reinterpret_cast<uintptr_t>(*iter);*iter=ptr(n+1);
    if(c==ptr(101))return n==0?ptr(102):nullptr;
    if(c==ptr(102))return n==0?ptr(103):n==1?ptr(104):nullptr;
    return nullptr;
}
static auto nested=nested_impl;
static void *from(void *p){return p;}
static auto param=parameter;
static auto from_type=from;
static void *unbox_impl(void *p){return p;}
static auto unbox=unbox_impl;
static void *invoke_impl(void *m,void *receiver,void **args,void **exception){
    const auto id=reinterpret_cast<uintptr_t>(m);
    if(id==0x68D11EC || id==0x68D32D4){count=0;request_ids=0;}
    if(id==9){++count;++request_ids;}
    // Rotation calls use the reviewed Game157 identity/RVA mapping.
    if(id==0x6a16700){rotation_in_count=rotation_out_count=0;rotation_in.clear();rotation_out.clear();}
    if(id==0x6a16b04)return ptr(2001);
    if(id==0x6a16b0c)return ptr(2002);
    if(id==201){++rotation_in_count;rotation_in.push_back(current_string);}
    if(id==202){++rotation_out_count;rotation_out.push_back(current_string);}
    if(id==0x68D15E0)assert(*static_cast<int *>(args[0])==1); // per target
    if(id==0x725AC04 || id==0x725AF0C || id==0x73d26a8){
        feeding_pending::Journal journal(directory.c_str());feeding_pending::Intent pending;
        assert(journal.load(pending)==feeding_pending::State::pending);
        assert(*static_cast<int *>(args[2])==0); // no transport retry
        ++calls;if(exception_on_send){*exception=&object;return nullptr;}
        return &object;
    }
    if(id==10){boxed=receiver==ptr(2001)?rotation_in_count:receiver==ptr(2002)?rotation_out_count:count;return &boxed;}
    if(id==11 || id==15){boxed=1;return &boxed;}
    if(id==12){boxed=fault?1:0;return &boxed;}
    if(id==13){boxed=0;return &boxed;}
    if(id==0x68D4828){count=failed_count;return &object;}
    return &object;
}
static auto invoke=invoke_impl;
}
#include "../cpp/feeding_rpc.inc"
#include "../cpp/feeding_rotation_rpc.inc"
int main(){
    char temp[]="/data/local/tmp/feeding-rpc-test-XXXXXX";
    assert(mkdtemp(temp));directory=temp;
    feeding_pending::Intent i{"request-1",identity_hash,"feed","flower-5:3","pikmin-1",1000,101,240,1,1,{}};
    feeding_policy::Config c;c.enabled=c.allowed=true;
    feeding_policy::Facts f;f.complete=f.account_ready=f.in_squad=f.idle=f.can_feed=true;
    f.nectar=101;f.petals=240;f.capacity=300;f.cost=f.yield=1;f.heart_milli=0;f.flower_state_count=0;
    c.enabled=false;assert(std::string(feeding_rpc::send(temp,i,"actual-item",c,f))=="preflight-invalid");assert(calls==0);c.enabled=true;
    assert(std::string(feeding_rpc::send(temp,i,"actual-item",c,f))=="sent");assert(calls==1);
    assert(feeding_rpc::poll()==feeding_rpc::Reply::success);
    assert(std::string(feeding_rpc::send(temp,i,"actual-item",c,f))=="pending-review");assert(calls==1);
    fault=true;assert(feeding_rpc::poll()==feeding_rpc::Reply::uncertain);fault=false;
    identity_hash=std::string(64,'b');assert(feeding_rpc::poll()==feeding_rpc::Reply::uncertain);identity_hash=i.account;
    // Test harness resets only its own isolated synthetic intent, never game data.
    feeding_rpc::task=nullptr;feeding_rpc::task_root=0;feeding_rpc::active={};
    assert(std::string(feeding_rpc::send(temp,i,"actual-item",c,f))=="pending-review");assert(calls==1);
    assert(unlink((directory+"/feeding_pending.tsv").c_str())==0);
    i.action="collect";i.cost=0;f.cost=0;f.can_collect=true;count=1;
    assert(std::string(feeding_rpc::send(temp,i,"",c,f))=="sent");assert(calls==2);
    failed_count=1;assert(feeding_rpc::poll()==feeding_rpc::Reply::rejected);
    failed_count=0;assert(feeding_rpc::poll()==feeding_rpc::Reply::success);
    assert(unlink((directory+"/feeding_pending.tsv").c_str())==0);
    feeding_rpc::task=nullptr;feeding_rpc::task_root=0;feeding_rpc::active={};
    count=1;exception_on_send=true;
    assert(std::string(feeding_rpc::send(temp,i,"",c,f))=="rpc-start-uncertain");assert(calls==3);
    assert(feeding_rpc::poll()==feeding_rpc::Reply::uncertain);
    assert(std::string(feeding_rpc::send(temp,i,"",c,f))=="pending-review");assert(calls==3);
    assert(unlink((directory+"/feeding_pending.tsv").c_str())==0);
    assert(rmdir(temp)==0);
    std::puts("PASS native feeding RPC adapter mock: policy, durable-before-send, retry=0, restart and per-target failure; NOT real game RPC");
    return 0;
}
