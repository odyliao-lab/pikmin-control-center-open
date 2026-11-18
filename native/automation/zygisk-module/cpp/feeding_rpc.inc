// Included after planter/probe helpers. No hooks or automatic entrypoint here.
// The background coordinator must supply a fresh policy-approved intent.
#include "feeding_pending.h"
#include "feeding_policy.h"
namespace feeding_rpc {
void *feed_class{}, *pick_class{}, *feed_ctor{}, *pick_ctor{}, *feed_ids{}, *pick_ids{};
void *set_item{}, *set_count{}, *feed_rpc{}, *pick_rpc{}, *feed_status{}, *pick_status{}, *pick_failed{};
void *task{};
void *rotation_status{};
GcHandle task_root{};
bool initialized{}, compatible{}, ok{};
feeding_pending::Intent active;
enum class Reply { absent, waiting, success, rejected, uncertain };
void *call(void *method, void *object, void **args = nullptr) {
    if (!ok || !method || !object) { ok=false; return nullptr; }
    void *exception{};
    void *value=planter_engine::invoke(method,object,args,&exception);
    if(exception)ok=false;
    return ok?value:nullptr;
}
void *get(void *object,const char *name) {
    if(!object){ok=false;return nullptr;}
    return call(class_get_method_from_name(object_get_class(object),name,0),object);
}
template<typename T> T scalar(void *value) {
    ScopedManagedRoot root(value,gchandle_new,gchandle_free);
    void *data=ok&&value?planter_engine::unbox(value):nullptr;
    if(!data){ok=false;return {};}
    return *static_cast<T *>(data);
}
void init() {
    if(initialized)return;
    initialized=true;
    feed_class=find_class("Ichigo.Proto","FeedPikminsRequestProto");
    pick_class=find_class("Ichigo.Proto","PickPikminFlowersRequestProto");
    feed_ctor=verified_v152_method(feed_class,".ctor",0,0x68D11EC);
    pick_ctor=verified_v152_method(pick_class,".ctor",0,0x68D32D4);
    feed_ids=verified_v152_method(feed_class,"get_PikminId",0,0x68D14F0);
    pick_ids=verified_v152_method(pick_class,"get_PikminId",0,0x68D368C);
    set_item=verified_v152_method(feed_class,"set_ItemId",1,0x68D1500);
    set_count=verified_v152_method(feed_class,"set_NumItems",1,0x68D15E0);
    void *rpc=find_class("Niantic.Ichigo.Rpc","RpcManager");
    feed_rpc=verified_v152_method(rpc,"SendFeedPikminsRpcAsync",3,0x725AC04);
    pick_rpc=verified_v152_method(rpc,"SendPickPikminFlowersRpcAsync",3,0x725AF0C);
    feed_status=verified_v152_method(find_class("Ichigo.Proto","FeedPikminsResponseProto"),"get_Status",0,0x68D26C8);
    void *response=find_class("Ichigo.Proto","PickPikminFlowersResponseProto");
    pick_status=verified_v152_method(response,"get_Status",0,0x68D47DC);
    pick_failed=verified_v152_method(response,"get_FailedPikminId",0,0x68D4828);
    compatible=feed_ctor&&pick_ctor&&feed_ids&&pick_ids&&set_item&&set_count&&feed_rpc&&pick_rpc&&
        feed_status&&pick_status&&pick_failed;
}
// Must be called on the existing serialized automation lane. Actual item_id
// comes from HoneyBallInventoryItem.Id, never from the UI catalogue key.
const char *send(const char *directory,const feeding_pending::Intent &intent,const std::string &item_id,
                 const feeding_policy::Config &config,const feeding_policy::Facts &facts) {
    if(task || !active.request.empty())return "pending-review";
    if(!feeding_pending::valid(intent) || intent.action!=feeding_policy::decide(config,facts) ||
        intent.nectar_before!=facts.nectar || intent.petals_before!=facts.petals ||
        intent.yield!=facts.yield || intent.cost!=facts.cost ||
        (intent.action=="feed" && intent.cost!=static_cast<int64_t>(intent.targets.empty()?1:intent.targets.size())) ||
        now_ms()<intent.epoch || now_ms()-intent.epoch>1000)return "preflight-invalid";
    if(!rpc_manager || !planter_engine::invoke || !planter_engine::unbox || !planter_engine::param ||
        !planter_engine::from_type || !gchandle_new || !gchandle_free || !object_new || !string_new)
        return "runtime-unavailable";
    {std::lock_guard<std::mutex> lock(identity_mutex);
        if(!identity_logout_hook_ready || identity_hash!=intent.account)return "account-changed";}
    init();if(!compatible)return "method-mismatch";
    const bool feed=intent.action=="feed";
    if(feed && !feeding_pending::token(item_id,256))return "item-id-invalid";
    feeding_pending::Journal journal(directory);
    feeding_pending::Intent previous;
    if(journal.load(previous)!=feeding_pending::State::absent)return "pending-review";
    ok=true;
    void *request=object_new(feed?feed_class:pick_class);
    ScopedManagedRoot request_root(request,gchandle_new,gchandle_free);
    if(!request_root.handle)return "request-root-failed";
    call(feed?feed_ctor:pick_ctor,request);
    void *list=call(feed?feed_ids:pick_ids,request);
    ScopedManagedRoot list_root(list,gchandle_new,gchandle_free);
    if(!list_root.handle)return "request-root-failed";
    void *iter{},*add{};void *klass=object_get_class(list);
    void *strings=find_class("System","String");
    for(void *m=class_get_methods(klass,&iter);m;m=class_get_methods(klass,&iter))
        if(std::strcmp(method_get_name(m),"Add")==0 && method_get_param_count(m)==1 &&
            planter_engine::from_type(planter_engine::param(m,0))==strings){add=m;break;}
    const auto ids=intent.targets.empty()?std::vector<std::string>{intent.pikmin}:intent.targets;
    for(const auto &target:ids) {
        void *id=string_new(target.c_str());
        ScopedManagedRoot id_root(id,gchandle_new,gchandle_free);
        if(!id_root.handle)return "request-root-failed";
        void *id_args[]{id};call(add,list,id_args);
    }
    if(scalar<int>(get(list,"get_Count"))!=static_cast<int>(ids.size()))ok=false;
    if(feed) {
        void *item=string_new(item_id.c_str());
        ScopedManagedRoot item_root(item,gchandle_new,gchandle_free);
        if(!item_root.handle)return "request-root-failed";
        void *args[]{item};call(set_item,request,args);
        // v152 BatchedFeedingAction.MoveNext (0x72CB084) sets NumItems=1
        // before AddRange(pikmins): this is PER TARGET, not aggregate cost.
        int one=1;void *count_args[]{&one};call(set_count,request,count_args);
    }
    if(!ok)return "request-build-failed";
    {std::lock_guard<std::mutex> lock(identity_mutex);
        if(!identity_logout_hook_ready || identity_hash!=intent.account)return "account-changed";}
    if(now_ms()<intent.epoch || now_ms()-intent.epoch>1000)return "preflight-expired";
    if(!journal.prepare(intent))return "intent-write-failed";
    active=intent; // From this point even a null/exception result blocks resend.
    void *cancel{};int retry=0; // ThrowException, not RetryWhenNoNetwork.
    void *args[]{request,&cancel,&retry};
    task=call(feed?feed_rpc:pick_rpc,rpc_manager,args);
    if(!ok || !task)return "rpc-start-uncertain";
    task_root=gchandle_new(task,false);
    if(!task_root){task=nullptr;return "task-root-failed";}
    return "sent";
}
Reply poll() {
    if(active.request.empty())return Reply::absent;
    if(!task || !task_root)return Reply::uncertain;
    {std::lock_guard<std::mutex> lock(identity_mutex);
        if(!identity_logout_hook_ready || identity_hash!=active.account)return Reply::uncertain;}
    ok=true;
    bool completed=scalar<bool>(get(task,"get_IsCompleted"));
    if(!ok)return Reply::uncertain;
    if(!completed)return Reply::waiting;
    bool faulted=scalar<bool>(get(task,"get_IsFaulted"));
    bool cancelled=scalar<bool>(get(task,"get_IsCanceled"));
    if(!ok || faulted || cancelled)return Reply::uncertain;
    void *response=get(task,"get_Result");
    ScopedManagedRoot response_root(response,gchandle_new,gchandle_free);
    const bool feed=active.action=="feed";
    void *status=call(active.action=="rotate"?rotation_status:feed?feed_status:pick_status,response);
    ScopedManagedRoot status_root(status,gchandle_new,gchandle_free);
    int result=scalar<int>(get(status,"get_Status"));
    if(!ok)return Reply::uncertain;
    if(result!=1)return Reply::rejected;
    if(active.action=="collect") {
        void *failed=call(pick_failed,response);
        ScopedManagedRoot failed_root(failed,gchandle_new,gchandle_free);
        const int count=scalar<int>(get(failed,"get_Count"));
        if(!ok || count<0)return Reply::uncertain;
        if(count!=0)return Reply::rejected;
    }
    return Reply::success; // NOT inventory confirmation; does not retire pending.
}
bool release_archived(const char *directory,const feeding_pending::Intent &intent) {
    // Only release the managed Task after the exact journal entry was retired.
    feeding_pending::Intent pending;
    feeding_pending::Journal journal(directory);
    if(feeding_pending::encode(active)!=feeding_pending::encode(intent) ||
        journal.load(pending)!=feeding_pending::State::absent)return false;
    if(task_root)gchandle_free(task_root);
    task_root=0;task=nullptr;active={};return true;
}
bool release_confirmed(const char *directory,const feeding_pending::Intent &intent) {
    return release_archived(directory,intent);
}
} // namespace feeding_rpc
