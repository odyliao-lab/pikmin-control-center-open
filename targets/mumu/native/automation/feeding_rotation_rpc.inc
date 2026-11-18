// v153 only. Strongly typed managed requests; one journal/task shared with
// feed/pick, no retry. The coordinator owns the serialized automation lane.
#include "feeding_rotation.h"
#include <functional>
namespace feeding_rpc {
namespace rotation {
void *request_class{},*in_class{},*out_class{},*ctor{},*in_ctor{},*out_ctor{};
void *in_ids{},*out_ids{},*in_set{},*out_set{},*rpc{};
bool initialized{},compatible{};
void init() {
    if(initialized)return;initialized=true;
    if(!planter_engine::nested)return;
    request_class=find_class("Ichigo.Proto","ArrangePikminTroopRequestProto");
    void *iter{};
    for(void *t=request_class?planter_engine::nested(request_class,&iter):nullptr;t;t=planter_engine::nested(request_class,&iter)) {
        if(std::strcmp(class_get_name(t),"Types"))continue;
        void *child{};
        for(void *c=planter_engine::nested(t,&child);c;c=planter_engine::nested(t,&child)) {
            if(!std::strcmp(class_get_name(c),"MoveToTroopProto"))in_class=c;
            if(!std::strcmp(class_get_name(c),"MoveToEntourageProto"))out_class=c;
        }
    }
    ctor=verified_v153_method(request_class,".ctor",0,0x6a16700);
    in_ids=verified_v153_method(request_class,"get_MoveToTroop",0,0x6a16b04);
    out_ids=verified_v153_method(request_class,"get_MoveToEntourage",0,0x6a16b0c);
    in_ctor=verified_v153_method(in_class,".ctor",0,0x6a177a0);
    out_ctor=verified_v153_method(out_class,".ctor",0,0x6a186d8);
    in_set=verified_v153_method(in_class,"set_PikminId",1,0x6a179a8);
    out_set=verified_v153_method(out_class,"set_PikminId",1,0x6a188e0);
    rpc=verified_v153_method(find_class("Niantic.Ichigo.Rpc","RpcManager"),"SendArrangePikminTroopRpcAsync",3,0x73d26a8);
    rotation_status=verified_v153_method(find_class("Ichigo.Proto","ArrangePikminTroopResponseProto"),"get_Status",0,0x6a198a8);
    compatible=ctor&&in_ids&&out_ids&&in_ctor&&out_ctor&&in_set&&out_set&&rpc&&rotation_status;
}
bool fill(void *request,const std::vector<std::string> &ids,size_t start,size_t end,bool incoming) {
    void *list=call(incoming?in_ids:out_ids,request);
    ScopedManagedRoot list_root(list,gchandle_new,gchandle_free);
    if(!list_root.handle)return false;
    void *klass=incoming?in_class:out_class,*iter{},*add{};
    for(void *m=class_get_methods(object_get_class(list),&iter);m;m=class_get_methods(object_get_class(list),&iter))
        if(!std::strcmp(method_get_name(m),"Add")&&method_get_param_count(m)==1&&
            planter_engine::from_type(planter_engine::param(m,0))==klass){add=m;break;}
    if(!add)return false;
    for(size_t i=start;i<end&&ok;++i) {
        void *entry=object_new(klass);ScopedManagedRoot entry_root(entry,gchandle_new,gchandle_free);
        void *id=string_new(ids[i].c_str());ScopedManagedRoot id_root(id,gchandle_new,gchandle_free);
        if(!entry_root.handle||!id_root.handle)return false;
        call(incoming?in_ctor:out_ctor,entry);
        void *args[]{id};call(incoming?in_set:out_set,entry,args);
        void *entries[]{entry};call(add,list,entries);
    }
    return scalar<int>(get(list,"get_Count"))==static_cast<int>(end-start)&&ok;
}
}
const char *send_rotation(const char *directory,const feeding_pending::Intent &intent,
        const feeding_observation::Snapshot &s,int hearts,const std::function<bool()> &allowed) {
    if(task||!active.request.empty())return "pending-review";
    if(!feeding_pending::valid(intent)||intent.action!="rotate"||!s.fresh(now_ms(),intent.account)||!s.roster_complete)return "preflight-invalid";
    const auto half=intent.targets.size()/2;
    for(size_t i=0;i<intent.targets.size();++i) {
        const auto &list=i<half?s.squad:s.standby;
        bool found=false;
        for(const auto &p:list)if(p.id==intent.targets[i])found=i<half?feeding_rotation::outgoing(p,hearts):feeding_rotation::incoming(p,hearts);
        if(!found)return "preflight-invalid";
    }
    if(!rpc_manager||!planter_engine::invoke||!planter_engine::unbox||!planter_engine::param||!planter_engine::from_type||
        !gchandle_new||!gchandle_free||!object_new||!string_new)return "runtime-unavailable";
    rotation::init();if(!rotation::compatible)return "rotation-method-unavailable";
    feeding_pending::Journal journal(directory);feeding_pending::Intent previous;
    if(journal.load(previous)!=feeding_pending::State::absent)return "pending-review";
    ok=true;
    void *request=object_new(rotation::request_class);ScopedManagedRoot request_root(request,gchandle_new,gchandle_free);
    if(!request_root.handle)return "request-root-failed";
    call(rotation::ctor,request);
    if(!rotation::fill(request,intent.targets,0,half,false)||!rotation::fill(request,intent.targets,half,intent.targets.size(),true)||!ok)return "request-build-failed";
    {std::lock_guard<std::mutex> lock(identity_mutex);
        if(!identity_logout_hook_ready||identity_hash!=intent.account)return "account-changed";}
    if(!s.fresh(now_ms(),intent.account)||now_ms()<intent.epoch||now_ms()-intent.epoch>1000)return "preflight-expired";
    if(!allowed())return "rotation-preflight-changed";
    if(!journal.prepare(intent))return "intent-write-failed";
    active=intent;
    void *cancel{};int retry=0;void *args[]{request,&cancel,&retry};
    task=call(rotation::rpc,rpc_manager,args);
    if(!ok||!task)return "rpc-start-uncertain";
    task_root=gchandle_new(task,false);
    if(!task_root){task=nullptr;return "task-root-failed";}
    return "rotation-sent";
}
}
