// MuMu result20 ONLY. Original result19 effect path retained. Replaces the generic return-all/stop-timer helper.
// A reason-13 overlay is collectible only during the live OWNED result close.
#include "planting_reward_policy.h"
using RewardInvoke = void *(*)(const void *, void *, void **, void **);
using RewardUnbox = void *(*)(void *);
RewardInvoke reward_invoke{};
RewardUnbox reward_unbox{};
SimpleMethod original_reward_start{};
// This canonical include precedes the late/result definitions. These are
// declarations of the SAME live adapter state, never a parallel ownership copy.
namespace emu_late {
extern int (*flags)(void *);
extern int (*type_code)(void *);
extern bool (*value_type)(void *);
}
namespace emu_planting_tx {
extern void *close_task, *new_result, *old_result;
extern GcHandle close_root, new_root;
bool record_owned();
}
namespace emu_owned_reward {
using MethodReturnType=void *(*)(void *);
MethodReturnType return_type{};
void *overlay{};
GcHandle root{};
long long seen{}, poll{}, captured_stop{};
uint64_t captured_epoch{};
std::string captured_token, consumed_token;
bool conflicted{};

void forget() {
    if(root && gchandle_free)gchandle_free(root);
    root=0;overlay=nullptr;seen=poll=captured_stop=0;
    captured_epoch=0;captured_token.clear();conflicted=false;
    // Never erase consumed_token when an overlay closes or changes.
}
pcc_owned_reward::Context context(long long now) {
    using namespace emu_planting_tx;
    const auto observed=emu_planting_observation::read();
    const auto lease=independent_permit();
    const bool owned_lease=lease.valid() && lease.token==token && lease.account==account;
    // Reserve rotation intentionally keeps mode ON. Use the SAME narrow
    // ownership/inactive gate as result_closed(), never an unconditional ON.
    const bool stop_mode=cleared_result_allowed(observed.known,observed.active,
        manual_start_clear(),read_planting_control_mode()=="off",reserve_stop,owned_lease);
    return {now,state.sent,seen,state.phase==Phase::stop_pending,same_session(),
        manual_start_clear(),!storage_failed,stop_mode,
        !command_namespace_reserved(),close_attempted,record_owned(),
        new_result && new_root && old_result!=new_result &&
            emu_late::reference(planting_controller,"plantingResultTask")==new_result,
        observed.known && !observed.active,
        root && overlay && !conflicted && captured_epoch==epoch &&
            !token.empty() && captured_token==token && captured_stop==state.sent && seen>=state.sent,
        close_task && close_root && completion(close_task,false)==Task::success,
        !token.empty() && consumed_token==token};
}
bool call(void *object,const char *name,void *&result) {
    bool ok=true;result=emu_late::call(object,name,ok,nullptr,0);return ok;
}
// Compare actual nested classes with their metadata-declared return/field
// types, not an ambiguous nested short name or an unchecked boxed integer.
bool reason13(void *object) {
    if(!return_type || !object_get_class || !class_get_method_from_name || !field_get_offset ||
       !field_get_type || !class_from_type || !emu_late::flags || !emu_late::value_type)return false;
    void *method=class_get_method_from_name(object_get_class(object),"get_CommonItemDialog",0);
    void *type=method?return_type(method):nullptr;
    void *expected=type?class_from_type(type):nullptr;
    void *common{};
    if(!expected || !call(object,"get_CommonItemDialog",common) || !common ||
       object_get_class(common)!=expected)return false;
    ScopedManagedRoot common_root(common,gchandle_new,gchandle_free);
    if(!common_root.handle)return false;
    void *field=class_get_field(expected,"reasonCase_");
    if(!field || field_get_offset(field)!=0x58 || (emu_late::flags(field)&0x10))return false;
    void *enum_type=field_get_type(field);
    void *enum_class=enum_type?class_from_type(enum_type):nullptr;
    void *boxed{};
    if(!enum_class || !emu_late::value_type(enum_class) || !call(common,"get_ReasonCase",boxed) ||
       !boxed || object_get_class(boxed)!=enum_class || !reward_unbox)return false;
    void *data=reward_unbox(boxed);
    return data && *static_cast<int32_t *>(data)==13;
}
void *button_for(void *object) {
    if(!emu_late::exact(object,"Niantic.Ichigo.Game.ItemGet","GotItemOverlay"))return nullptr;
    void *expected=find_class("Niantic.Ichigo.UI.Framework.IchigoButton","IchigoButtonProxy");
    void *field=class_get_field(object_get_class(object),"collectButton");
    if(!expected || !field || field_get_offset(field)!=0x78 || (emu_late::flags(field)&0x10))return nullptr;
    void *type=field_get_type(field);
    if(!type || emu_late::type_code(type)!=0x12 || class_from_type(type)!=expected)return nullptr;
    void *button{};field_get_value(object,field,&button);
    return button && object_get_class(button)==expected?button:nullptr;
}
bool ready(void *button) {
    bool enabled{},touch{},loading{};
    return emu_planting_tx::task_bool_for(button,"get_Interactable",enabled) &&
        emu_planting_tx::task_bool_for(button,"WillReceiveTouch",touch) &&
        emu_planting_tx::task_bool_for(button,"get_ShowLoadingVisuals",loading) && enabled && touch && !loading;
}
bool persist_attempt() {
    using namespace emu_planting_tx;
    const auto dest=path()+".reward",temp=dest+".tmp";
    // The parent close journal is already durable. Loading either journal can
    // never reconstruct roots or authority; reboot remains REVIEW, no retry.
    const std::string row="v1\t"+std::to_string(getpid())+"\t"+std::to_string(epoch)+"\t"+
        token+"\t"+std::to_string(state.sent)+"\t"+std::to_string(owned_started)+"\tattempted\tend\n";
    int fd=open(temp.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(fd<0)return false;
    struct stat st{};
    bool ok=fstat(fd,&st)==0 && S_ISREG(st.st_mode) && st.st_uid==getuid() && st.st_nlink==1 &&
        fchmod(fd,0600)==0 && write(fd,row.data(),row.size())==static_cast<ssize_t>(row.size()) && fsync(fd)==0;
    if(close(fd)!=0)ok=false;
    if(!ok || rename(temp.c_str(),dest.c_str())!=0)return false;
    const auto dir=dest.substr(0,dest.find_last_of('/'));
    fd=open(dir.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0)return false;
    ok=fsync(fd)==0;if(close(fd)!=0)ok=false;return ok;
}
}
void hooked_reward_start(void *self,void *method_info) {
    if(original_reward_start)original_reward_start(self,method_info);
    using namespace emu_owned_reward;
    const auto now=now_ms();
    if(!self || !pcc_owned_reward::authorized(context(now)))return;
    if(overlay) {if(overlay!=self)conflicted=true;return;}
    if(!emu_late::exact(self,"Niantic.Ichigo.Game.ItemGet","GotItemOverlay") || !gchandle_new || !gchandle_free)return;
    root=gchandle_new(self,false);if(!root)return;
    overlay=self;seen=now;poll=0;captured_stop=emu_planting_tx::state.sent;
    captured_epoch=emu_planting_tx::epoch;captured_token=emu_planting_tx::token;
}
void maybe_collect_planting_reward() {
    using namespace emu_owned_reward;
    if(!overlay)return;
    const auto now=now_ms();const auto before=context(now);
    if(!pcc_owned_reward::authorized(before) || !before.capture_matches || conflicted || before.consumed){forget();return;}
    if(!pcc_owned_reward::collectible(before) || now<poll || now-poll<500)return;
    poll=now;
    if(!reason13(overlay))return; // async initialization may not yet expose common
    void *button=button_for(overlay);if(!button)return;
    ScopedManagedRoot button_root(button,gchandle_new,gchandle_free);
    if(!button_root.handle || !ready(button))return;
    // Consume BEFORE journal/call. Failed durability, exceptions and uncertain
    // outcomes block this token; none becomes permission to replay InvokeClick.
    consumed_token=emu_planting_tx::token;
    if(!persist_attempt()) {
        emu_planting_tx::storage_failed=true;emu_planting_tx::state.phase=emu_planting_tx::Phase::review;
        emu_planting_tx::save();forget();return;
    }
    auto final=context(now_ms());final.consumed=false; // this one recorded attempt, not another admission
    if(!pcc_owned_reward::collectible(final) || !reason13(overlay) || button_for(overlay)!=button || !ready(button)) {
        forget();return;
    }
    forget();void *ignored{};const bool ok=call(button,"InvokeClick",ignored);
    LOGI("[PLANTING-REWARD] owned reason=13 collect %s; controller closure still requires observation",ok?"invoked":"unconfirmed");
}
// Diagnostics are change-only, finite labels: no object, account or location data.
enum class RewardHookStage { ready, metadata, binding, symbols, library, return_type,
    klass, method, entry, image, filename, rva, bytes, trampoline };
RewardHookStage reward_hook_stage=RewardHookStage::metadata;
void reward_hook_diagnostic(RewardHookStage stage) {
    static int last=-1;reward_hook_stage=stage;
    if(last==static_cast<int>(stage))return;
    last=static_cast<int>(stage);
    static const char *labels[]{"ready","metadata","binding","symbols","library","return-type",
        "class","method","entry","image","filename","rva","bytes","trampoline"};
    LOGI("[PLANTING-REWARD-HOOK] admission=%s",labels[last]);
}
void install_planting_reward_hook() {
    if(original_reward_start){reward_hook_diagnostic(RewardHookStage::ready);return;}
    if(!runtime_metadata_ready){reward_hook_diagnostic(RewardHookStage::metadata);return;}
    if(!emu_late::bound || emu_late::revoked){reward_hook_diagnostic(RewardHookStage::binding);return;}
    if(!reward_invoke || !reward_unbox){reward_hook_diagnostic(RewardHookStage::symbols);return;}
    void *lib=xdl_open("libil2cpp.so",XDL_DEFAULT);
    if(!lib){reward_hook_diagnostic(RewardHookStage::library);return;}
    emu_owned_reward::return_type=reinterpret_cast<emu_owned_reward::MethodReturnType>(xdl_sym(lib,"il2cpp_method_get_return_type",nullptr));
    if(!emu_owned_reward::return_type){reward_hook_diagnostic(RewardHookStage::return_type);return;}
    void *klass=find_class("Niantic.Ichigo.Game.ItemGet","GotItemOverlay");
    if(!klass){reward_hook_diagnostic(RewardHookStage::klass);return;}
    void *method=class_get_method_from_name(klass,"Start",0);
    if(!method){reward_hook_diagnostic(RewardHookStage::method);return;}
    void *entry=*static_cast<void **>(method);Dl_info image{};
    static const unsigned char expected[]{0xfe,0x0f,0x1d,0xf8,0xf6,0x57,0x01,0xa9,0xf4,0x4f,0x02,0xa9,0xb4,0xc4,0x04,0x90,0xf3,0x03,0x00,0xaa,0x88,0x6a,0x4e,0x39};
    if(!entry){reward_hook_diagnostic(RewardHookStage::entry);return;}
    if(!dladdr(entry,&image) || !image.dli_fbase || !image.dli_fname){reward_hook_diagnostic(RewardHookStage::image);return;}
    if(!std::strstr(image.dli_fname,"libil2cpp.so")){reward_hook_diagnostic(RewardHookStage::filename);return;}
    if(reinterpret_cast<uintptr_t>(entry)!=reinterpret_cast<uintptr_t>(image.dli_fbase)+0x5ca2754){reward_hook_diagnostic(RewardHookStage::rva);return;}
    if(std::memcmp(entry,expected,sizeof(expected))!=0){reward_hook_diagnostic(RewardHookStage::bytes);return;}
    A64HookFunction(entry,reinterpret_cast<void *>(hooked_reward_start),reinterpret_cast<void **>(&original_reward_start));
    reward_hook_diagnostic(original_reward_start?RewardHookStage::ready:RewardHookStage::trampoline);
}
