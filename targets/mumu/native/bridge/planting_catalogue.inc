// MuMu-only read-only backend, shared by the explicit OFF-only diagnostic query
// and the separately journaled, inactive-only ordinary selection transaction.
// Included AFTER late_capture, so all metadata helpers are available.
#include "planting_catalogue_policy.h"
namespace emu_petal {
struct ManagedBackend {
    void *inventory=return_inventory_manager,*list{};GcHandle root{};
    ManagedBackend() {
        bool ok=true;
        if(ready()) list=emu_late::call(return_inventory_manager,"GetFlowerPetalList",ok,nullptr,0);
        if(ok && list) root=gchandle_new(list,false);
        if(!root) list=nullptr;
    }
    ~ManagedBackend(){if(root)gchandle_free(root);}
    ManagedBackend(const ManagedBackend &)=delete;
    long long now(){return now_ms();}
    int pid(){return getpid();}
    unsigned long long epoch(){return capability_registry.current_epoch();}
    std::string account(){std::lock_guard<std::mutex> lock(identity_mutex);return identity_hash;}
    bool ready(){return runtime_metadata_ready && emu_late::bound && !emu_late::revoked &&
        inventory && inventory==return_inventory_manager && reward_invoke && reward_unbox && gchandle_new && gchandle_free &&
        emu_late::flags && emu_late::type_code;}
    long long revision(){
        void *field=return_inventory_manager?class_get_field(object_get_class(return_inventory_manager),"latestServerSideTimestamp"):nullptr;
        if(!field || !ready() || (emu_late::flags(field)&0x10) ||
           emu_late::type_code(field_get_type(field))!=0x0a) return 0;
        int64_t value{};field_get_value(return_inventory_manager,field,&value);return value;
    }
    bool integer(void *object,const char *name,int &value){
        bool ok=true;void *boxed=emu_late::call(object,name,ok,nullptr,0);
        ScopedManagedRoot held(boxed,gchandle_new,gchandle_free);
        if(!ok || !held.handle || !emu_late::exact(boxed,"System","Int32"))return false;
        void *data=reward_unbox(boxed);if(!data)return false;value=*static_cast<int32_t *>(data);return true;
    }
    bool count(int &n){return list && integer(list,"get_Count",n);}
    bool text(void *object,std::string &out){
        if(!emu_late::exact(object,"System","String"))return false;
        const auto *s=static_cast<Il2CppStringLayout *>(object);
        if(s->length<0 || s->length>80)return false;
        out.clear();out.reserve(s->length);
        for(int i=0;i<s->length;++i){
            if(s->chars[i]<0x20 || s->chars[i]>0x7e)return false;
            out.push_back(static_cast<char>(s->chars[i]));
        }
        return true; // empty is valid only when the real managed length is zero
    }
    bool enum_field(void *proto,const char *name,int &value){
        void *field=class_get_field(object_get_class(proto),name);
        if(!field || (emu_late::flags(field)&0x10) || emu_late::type_code(field_get_type(field))!=0x11)return false;
        void *klass=class_from_type(field_get_type(field));
        void *underlying=klass?class_get_field(klass,"value__"):nullptr;
        if(!underlying || emu_late::type_code(field_get_type(underlying))!=0x08)return false;
        int32_t scalar{};field_get_value(proto,field,&scalar);value=scalar;return true;
    }
    void *static_method(const char *ns,const char *klass,const char *name,uintptr_t legacy_rva){
        // Canonical verified_v152_method maps these exact entries to the pinned
        // v154 addresses. It is not a blind historical RVA call.
        return verified_v152_method(find_class(ns,klass),name,1,legacy_rva);
    }
    bool row(int index,Row &out){
        bool ok=true;void *args[]{&index};
        void *item=emu_late::call(list,"get_Item",ok,args,1);
        ScopedManagedRoot item_root(item,gchandle_new,gchandle_free);
        if(!ok || !item_root.handle || !emu_late::exact(item,"Niantic.Ichigo.Inventory","FlowerPetalInventoryItem"))return false;
        void *id=emu_late::call(item,"get_Id",ok,nullptr,0);
        ScopedManagedRoot id_root(id,gchandle_new,gchandle_free);
        if(!ok || !id_root.handle || !emu_late::exact(id,"System","String"))return false;
        if(!text(id,out.id) || !id_valid(out.id))return false;
        void *dictionary=emu_late::reference(return_inventory_manager,"itemsById");
        ScopedManagedRoot dictionary_root(dictionary,gchandle_new,gchandle_free);
        if(!dictionary_root.handle)return false;
        void *id_args[]{id};void *predicted=emu_late::call(dictionary,"get_Item",ok,id_args,1);
        ScopedManagedRoot predicted_root(predicted,gchandle_new,gchandle_free);
        bool has_predictors{};
        if(!ok || !predicted_root.handle || !emu_planting_tx::task_bool_for(predicted,"get_HasPredictors",has_predictors) || has_predictors)return false;
        void *confirmed=emu_late::call(predicted,"get_ConfirmedValue",ok,nullptr,0);
        ScopedManagedRoot confirmed_root(confirmed,gchandle_new,gchandle_free);
        if(!ok || !confirmed_root.handle || !emu_late::exact(confirmed,"Niantic.Ichigo.Inventory","FlowerPetalInventoryItem"))return false;
        void *confirmed_id=emu_late::call(confirmed,"get_Id",ok,nullptr,0);
        ScopedManagedRoot confirmed_id_root(confirmed_id,gchandle_new,gchandle_free);
        std::string confirmed_text;
        if(!ok || !confirmed_id_root.handle || !text(confirmed_id,confirmed_text) || confirmed_text!=out.id)return false;
        void *proto=emu_late::call(confirmed,"get_Proto",ok,nullptr,0);
        ScopedManagedRoot proto_root(proto,gchandle_new,gchandle_free);
        int flower{},kind{};
        if(!ok || !proto_root.handle || !emu_late::exact(proto,"Ichigo.Proto","FlowerPetalProto") ||
           !integer(proto,"get_NumPetal",out.stock) || !enum_field(proto,"flowerType_",flower) || !enum_field(proto,"kind_",kind))return false;
        void *color_method=static_method("Niantic.Ichigo.Utils.Proto","HoneyBallProtoExtensions","ToHoneyType",0x6D3E180);
        void *color_args[]{&flower},*exception{};
        void *boxed=color_method?reward_invoke(color_method,nullptr,color_args,&exception):nullptr;
        ScopedManagedRoot color_root(boxed,gchandle_new,gchandle_free);
        void *color_underlying=!exception && color_root.handle?class_get_field(object_get_class(boxed),"value__"):nullptr;
        if(!color_underlying || (emu_late::flags(color_underlying)&0x10) ||
           emu_late::type_code(field_get_type(color_underlying))!=0x08)return false;
        void *data=reward_unbox(boxed);
        if(!data)return false;out.color=*static_cast<int32_t *>(data);
        void *text=emu_late::call(proto,"get_FlowerKind",ok,nullptr,0);
        ScopedManagedRoot text_root(text,gchandle_new,gchandle_free);
        if(!ok || !text_root.handle || !emu_late::exact(text,"System","String"))return false;
        std::string legacy_text;
        if(!this->text(text,legacy_text))return false;
        // COMMON=5 is explicit in v154 metadata. Unknown=0 is NOT promoted.
        out.ordinary=kind==5 && legacy_text.empty();
        if(kind==5 && !legacy_text.empty()){
            void *lookup=static_method("Niantic.Ichigo.ModelControllers.Flowers","FlowerKindLookup","KindToObsoleteString",0x711A138);
            void *kind_args[]{&kind};exception=nullptr;
            void *expected=lookup?reward_invoke(lookup,nullptr,kind_args,&exception):nullptr;
            ScopedManagedRoot expected_root(expected,gchandle_new,gchandle_free);
            if(exception || !expected_root.handle || !emu_late::exact(expected,"System","String"))return false;
            std::string expected_text;
            if(!this->text(expected,expected_text))return false;
            out.ordinary=expected_text==legacy_text;
        }
        return kind>=0 && flower>=1 && flower<=7 && out.color>=1 && out.color<=4 && out.stock>=0 && out.stock<=1000000;
    }
};
Snapshot read_managed_catalogue(){ManagedBackend backend;return capture(backend);}
}
