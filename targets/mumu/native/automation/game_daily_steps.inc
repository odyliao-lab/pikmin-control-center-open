// Generated MuMu overlay only, AFTER late_capture. Read-only on original map thread.
#include "game_daily_steps_policy.h"
#include "game_daily_steps_publish.h"
namespace emu_daily_steps {
struct ManagedBackend {
    void *inventory=return_inventory_manager;
    int64_t now(){return now_ms();}
    Binding binding() {
        std::lock_guard<std::mutex> lock(identity_mutex);
        return {getpid(),static_cast<int64_t>(capability_registry.current_epoch()),identity_hash};
    }
    bool ready(){return runtime_metadata_ready && emu_late::bound && !emu_late::revoked &&
        inventory && inventory==return_inventory_manager && reward_invoke && reward_unbox &&
        gchandle_new && gchandle_free && emu_late::flags && emu_late::type_code &&
        emu_late::exact(inventory,"Niantic.Ichigo.Inventory","InventoryManager");}
    int64_t revision() {
        if(!ready())return 0;
        void *field=class_get_field(object_get_class(inventory),"latestServerSideTimestamp");
        if(!field || (emu_late::flags(field)&0x10) || emu_late::type_code(field_get_type(field))!=0x0a)return 0;
        int64_t value{};field_get_value(inventory,field,&value);return value;
    }
    bool text(void *object,std::string &out) {
        if(!emu_late::exact(object,"System","String"))return false;
        const auto *s=static_cast<Il2CppStringLayout *>(object);
        if(s->length<1 || s->length>80)return false;
        out.clear();
        for(int i=0;i<s->length;++i){if(s->chars[i]<0x20 || s->chars[i]>0x7e)return false;out.push_back(static_cast<char>(s->chars[i]));}
        return true;
    }
    bool long_value(void *object,const char *getter,int64_t &out) {
        bool ok=true;void *boxed=emu_late::call(object,getter,ok,nullptr,0);
        ScopedManagedRoot held(boxed,gchandle_new,gchandle_free);
        if(!ok || !held.handle || !emu_late::exact(boxed,"System","Int64"))return false;
        void *value=reward_unbox(boxed);if(!value)return false;
        out=*static_cast<int64_t *>(value);return out>=0;
    }
    bool read_confirmed(Values &out) {
        if(!ready())return false;
        bool ok=true;
        // EXACT zero-argument overload; not GetPlayerStatistics(string).
        void *item=emu_late::call(inventory,"GetPlayerStatistics",ok,nullptr,0);
        ScopedManagedRoot item_root(item,gchandle_new,gchandle_free);
        if(!ok || !item_root.handle || !emu_late::exact(item,"Niantic.Ichigo.Inventory","PlayerStatisticsInventoryItem"))return false;
        void *id=emu_late::call(item,"get_Id",ok,nullptr,0);
        ScopedManagedRoot id_root(id,gchandle_new,gchandle_free);
        std::string item_id;
        if(!ok || !id_root.handle || !text(id,item_id))return false;
        void *dictionary=emu_late::reference(inventory,"itemsById");
        ScopedManagedRoot dictionary_root(dictionary,gchandle_new,gchandle_free);
        if(!dictionary_root.handle)return false;
        void *args[]{id};void *predicted=emu_late::call(dictionary,"get_Item",ok,args,1);
        ScopedManagedRoot predicted_root(predicted,gchandle_new,gchandle_free);
        if(!ok || !predicted_root.handle)return false;
        void *has=emu_late::call(predicted,"get_HasPredictors",ok,nullptr,0);
        ScopedManagedRoot has_root(has,gchandle_new,gchandle_free);
        if(!ok || !has_root.handle || !emu_late::exact(has,"System","Boolean"))return false;
        void *has_value=reward_unbox(has);if(!has_value || *static_cast<bool *>(has_value))return false;
        void *confirmed=emu_late::call(predicted,"get_ConfirmedValue",ok,nullptr,0);
        ScopedManagedRoot confirmed_root(confirmed,gchandle_new,gchandle_free);
        if(!ok || !confirmed_root.handle || !emu_late::exact(confirmed,"Niantic.Ichigo.Inventory","PlayerStatisticsInventoryItem"))return false;
        void *confirmed_id=emu_late::call(confirmed,"get_Id",ok,nullptr,0);
        ScopedManagedRoot confirmed_id_root(confirmed_id,gchandle_new,gchandle_free);
        std::string actual_id;
        if(!ok || !confirmed_id_root.handle || !text(confirmed_id,actual_id) || actual_id!=item_id)return false;
        void *proto=emu_late::call(confirmed,"get_Proto",ok,nullptr,0);
        ScopedManagedRoot proto_root(proto,gchandle_new,gchandle_free);
        if(!ok || !proto_root.handle || !emu_late::exact(proto,"Ichigo.Proto","PlayerStatisticsProto"))return false;
        void *date=emu_late::call(proto,"get_LocalDate",ok,nullptr,0);
        ScopedManagedRoot date_root(date,gchandle_new,gchandle_free);
        return ok && date_root.handle && text(date,out.day) &&
            long_value(proto,"get_TodaysStepsWalked",out.today) &&
            long_value(proto,"get_TodaysCanonicalStepsWalked",out.canonical);
    }
};
void publish(const Snapshot &s,ManagedBackend &b) {
    static emu_counter_store::State storage;
    const std::string runtime(core_runtime_path);
    const auto slash=runtime.find_last_of('/');if(slash==std::string::npos)return;
    const auto result=emu_counter_store::publish(runtime.substr(0,slash),s,b,storage);
    static long long last=0;
    if(result.stage!=emu_counter_store::Stage::complete && result.stage!=emu_counter_store::Stage::capture &&
       (last==0 || now_ms()-last>=30000)) {
        last=now_ms();
        __android_log_print(ANDROID_LOG_WARN,"PccDailyCounter","publish stage=%s errno=%d retained=%d cleaned=%d",emu_counter_store::name(result.stage),result.error,result.evidence,result.cleaned);
    }
}
void tick() {
    static int64_t previous=0;
    const auto now=now_ms();
    if(now<=0 || (now>=previous && now-previous<1000))return;
    previous=now;
    ManagedBackend b;const auto s=capture(b);publish(s,b);
}
}
