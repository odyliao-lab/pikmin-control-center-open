// MuMu generated overlay AFTER existing counter/catalogue code. Original map
// thread only. Read confirmed items, never construct a service or send RPC.
#include "daily_resources_policy.h"
#include "daily_resources_publish.h"
namespace emu_daily_resources {
struct ManagedBackend : emu_daily_steps::ManagedBackend {
    int64_t began=now_ms();
    bool in_budget(){const auto n=now();return n>=began && n-began<=200;}
    bool integer(void *object,const char *getter,int &out) {
        bool ok=true;void *boxed=emu_late::call(object,getter,ok,nullptr,0);
        ScopedManagedRoot root(boxed,gchandle_new,gchandle_free);
        if(!ok || !root.handle || !emu_late::exact(boxed,"System","Int32"))return false;
        void *p=reward_unbox(boxed);if(!p)return false;out=*static_cast<int32_t *>(p);return true;
    }
    bool capacity_proto(void *object) {
        // CapacityProto is nested, not a top-level class named CapacityProto.
        // Resolve the exact declared nesting with the existing metadata API.
        if(!object || !planter_engine::nested)return false;
        void *outer=find_class("Ichigo.Proto","ItemCapacityProto");
        if(!outer)return false;
        void *it{};int examined=0;
        for(void *types=planter_engine::nested(outer,&it);types && examined++<32;types=planter_engine::nested(outer,&it)) {
            if(std::strcmp(class_get_name(types),"Types")!=0)continue;
            void *sub{};int children=0;
            for(void *klass=planter_engine::nested(types,&sub);klass && children++<32;klass=planter_engine::nested(types,&sub))
                if(std::strcmp(class_get_name(klass),"CapacityProto")==0)return object_get_class(object)==klass;
        }
        return false;
    }
    template<class Consumer> bool confirmed(void *item,const char *klass,Consumer consume) {
        bool ok=true;
        if(!emu_late::exact(item,"Niantic.Ichigo.Inventory",klass))return false;
        void *id=emu_late::call(item,"get_Id",ok,nullptr,0);
        ScopedManagedRoot id_root(id,gchandle_new,gchandle_free);
        std::string expected;
        if(!ok || !id_root.handle || !text(id,expected))return false;
        void *dictionary=emu_late::reference(inventory,"itemsById");
        ScopedManagedRoot dictionary_root(dictionary,gchandle_new,gchandle_free);
        if(!dictionary_root.handle)return false;
        void *args[]{id};void *predicted=emu_late::call(dictionary,"get_Item",ok,args,1);
        ScopedManagedRoot predicted_root(predicted,gchandle_new,gchandle_free);
        if(!ok || !predicted_root.handle)return false;
        void *has=emu_late::call(predicted,"get_HasPredictors",ok,nullptr,0);
        ScopedManagedRoot has_root(has,gchandle_new,gchandle_free);
        if(!ok || !has_root.handle || !emu_late::exact(has,"System","Boolean"))return false;
        void *flag=reward_unbox(has);if(!flag || *static_cast<bool *>(flag))return false;
        void *value=emu_late::call(predicted,"get_ConfirmedValue",ok,nullptr,0);
        ScopedManagedRoot value_root(value,gchandle_new,gchandle_free);
        if(!ok || !value_root.handle || !emu_late::exact(value,"Niantic.Ichigo.Inventory",klass))return false;
        void *actual_id=emu_late::call(value,"get_Id",ok,nullptr,0);
        ScopedManagedRoot actual_root(actual_id,gchandle_new,gchandle_free);
        std::string actual;
        return ok && actual_root.handle && text(actual_id,actual) && actual==expected && consume(value,expected);
    }
    bool count_confirmed(const char *getter,const char *klass,int64_t &out) {
        bool ok=true;void *list=emu_late::call(inventory,getter,ok,nullptr,0);
        ScopedManagedRoot list_root(list,gchandle_new,gchandle_free);
        int count=-1;
        if(!ok || !list_root.handle || !integer(list,"get_Count",count) || count<0 || count>10000)return false;
        std::set<std::string> ids;
        for(int i=0;i<count;i++) {
            if(!in_budget() || !ready())return false;
            void *args[]{&i};void *item=emu_late::call(list,"get_Item",ok,args,1);
            ScopedManagedRoot item_root(item,gchandle_new,gchandle_free);
            if(!ok || !item_root.handle || !confirmed(item,klass,[&](void *,const std::string &id){return ids.insert(id).second;}))return false;
        }
        int after=-1;
        if(!integer(list,"get_Count",after) || after!=count)return false;
        out=count;return true;
    }
    bool capacities(Values &out) {
        bool ok=true;void *list=emu_late::call(inventory,"GetItemCapacityList",ok,nullptr,0);
        ScopedManagedRoot list_root(list,gchandle_new,gchandle_free);
        int count=-1,index=0;
        if(!ok || !list_root.handle || !integer(list,"get_Count",count) || count!=1)return false;
        void *args[]{&index};void *item=emu_late::call(list,"get_Item",ok,args,1);
        ScopedManagedRoot item_root(item,gchandle_new,gchandle_free);
        if(!ok || !item_root.handle)return false;
        return confirmed(item,"ItemCapacityInventoryItem",[&](void *value,const std::string &){
            void *proto=emu_late::call(value,"get_Proto",ok,nullptr,0);
            ScopedManagedRoot proto_root(proto,gchandle_new,gchandle_free);
            if(!ok || !proto_root.handle || !emu_late::exact(proto,"Ichigo.Proto","ItemCapacityProto"))return false;
            int pm=-1,sm=-1;
            void *pikmin=emu_late::call(proto,"get_Pikmin",ok,nullptr,0);
            ScopedManagedRoot pikmin_root(pikmin,gchandle_new,gchandle_free);
            void *seed=emu_late::call(proto,"get_Seedling",ok,nullptr,0);
            ScopedManagedRoot seed_root(seed,gchandle_new,gchandle_free);
            if(!ok || !pikmin_root.handle || !seed_root.handle ||
               !capacity_proto(pikmin) || !capacity_proto(seed) ||
               !integer(pikmin,"get_CurrentCount",pm) || !integer(seed,"get_CurrentCount",sm) || pm<=0 || sm<=0)return false;
            out.pikmin_max=pm;out.seed_max=sm;return true;
        });
    }
    bool read_confirmed(Values &out) {
        if(!ready() || !in_budget() || !capacities(out) ||
           !count_confirmed("GetPikminList","PikminInventoryItem",out.pikmin_used) ||
           !count_confirmed("GetPikminSeedList","PikminSeedInventoryItem",out.seed_used))return false;
        const auto petals=emu_petal::read_managed_catalogue();
        const auto id=binding();
        if(!emu_petal::coherent(petals,now(),id.pid,id.epoch,id.account) || petals.revision!=revision())return false;
        out.ordinary.fill(0);
        for(const auto &row:petals.rows)if(row.ordinary)out.ordinary[row.color-1]=row.stock;
        return in_budget() && out.valid();
    }
};
void tick() {
    static int64_t previous=0;
    const auto now=now_ms();if(now<=0 || (now>=previous && now-previous<5000))return;
    previous=now;
    ManagedBackend b;const auto s=emu_daily_resources::capture(b);
    static emu_resource_store::State storage;
    const std::string runtime(core_runtime_path);
    const auto slash=runtime.find_last_of('/');if(slash==std::string::npos)return;
    emu_resource_store::publish(runtime.substr(0,slash),s,b,storage);
}
}
