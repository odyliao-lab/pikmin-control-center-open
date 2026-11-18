#include "planting_terminal_adapter.inc"
#include "planting_button_adapter.inc"
// MuMu-only owned-result adapter. No short Start hook, Init replay, speed bypass,
// accessibility, synthetic button event or screen-coordinate close.
namespace emu_planting_tx {
void *dialog{}, *init_task{}, *close_task{}, *old_result{}, *new_result{};
GcHandle dialog_root{}, init_root{}, close_root{}, old_root{}, new_root{};
long long dialog_seen{};
bool dialog_conflict{}, hook_installed{}, hook_attempted{};
std::atomic<unsigned long long> result_hook_entries{0}, result_capture_attempts{0}, result_captures{0};
std::atomic<const char *> result_wait_reason{"not-observed"};
using DialogStartAsync=void *(*)(void *,void *);
DialogStartAsync original_dialog_start_async{};

void forget_result() {
    for(auto h:{dialog_root,init_root,close_root,old_root,new_root}) if(h && gchandle_free) gchandle_free(h);
    dialog=init_task=close_task=old_result=new_result=nullptr;
    dialog_root=init_root=close_root=old_root=new_root=0;
    dialog_seen=0;dialog_conflict=false;
}
bool result_hook_ready() {return hook_installed;}
void publish_result_diagnostic(long long now) {
    const auto dest=path()+".result";
    // Counters/reasons only. No pointers, task IDs, account or coordinates.
    const std::string row="v1\t"+std::to_string(now)+"\t"+std::to_string(getpid())+"\t"+
        std::to_string(result_hook_entries.load(std::memory_order_relaxed))+"\t"+std::to_string(result_capture_attempts.load(std::memory_order_relaxed))+"\t"+
        std::to_string(result_captures.load(std::memory_order_relaxed))+"\t"+result_wait_reason.load(std::memory_order_relaxed)+"\t"+(close_attempted?"1":"0")+"\t"+name(state.phase)+"\tend\n";
    pikmin::diagnostic_snapshot::publish(dest,row);
    // Not a durable journal/closure proof; stale files cannot certify a gate.
}
void publish_safety() {
    static long long last{};
    const auto now=now_ms();if(now>=last && now-last<1000)return;last=now;
    publish_result_diagnostic(now);
    // Small fixed-size process-local provenance snapshot, never an authority
    // or a recovery journal. Separate foreign evidence survives later own STARTs.
    {
        const auto dest=path()+".diagnostic";
        const std::string row="v1\t"+std::to_string(now)+"\t"+std::to_string(getpid())+"\t"+
            std::to_string(own_starts.load())+"\t"+std::to_string(foreign_starts.load())+"\t"+
            std::to_string(last_own_start.load())+"\t"+std::to_string(last_foreign_start.load())+"\t"+
            std::to_string(foreign_start_flags.load())+"\t"+(reserve_stop?"reserve":"normal")+"\t"+
            name(state.phase)+"\t"+result_wait_reason.load()+"\tend\n";
        pikmin::diagnostic_snapshot::publish(dest,row);
    }
    const auto dest=path()+".safety";
    const std::string row="v1\t"+std::to_string(now)+"\t"+std::to_string(getpid())+"\t"+
        (hook_installed?"ready":"not-ready")+"\t"+(manual_start_clear()?"clear":"manual-review")+"\t"+
        name(state.phase)+"\t"+(hook_attempted?"attempted":"not-attempted")+"\tend\n";
    pikmin::diagnostic_snapshot::publish(dest,row);
    // Diagnostic only: not ownership, a recovery journal or Hook/RPC proof.
}
bool cleanup_ready() {
    install_planting_reward_hook();
    if(!original_reward_start){result_wait_reason="reward-hook-not-ready";return false;}
    const auto observed=emu_planting_observation::read();
    if(!runtime_metadata_ready || !emu_late::bound || emu_late::revoked || !hook_installed || !manual_start_clear() ||
       storage_failed || !observed.known || observed.active || !planting_controller ||
       (state.phase!=Phase::idle && state.phase!=Phase::stopped)) return false;
    if(state.phase==Phase::stopped && !same_session()) return false;
    void *current=emu_late::reference(planting_controller,"plantingResultTask");
    return !current || completion(current,false)==Task::success;
}
bool prepare_result() {
    void *prior=emu_late::reference(planting_controller,"plantingResultTask");
    if(prior && completion(prior,false)!=Task::success) return false;
    forget_result();
    old_result=prior;
    if(prior) {old_root=gchandle_new(prior,false);if(!old_root) return false;}
    return true;
}
void capture_dialog(void *self,void *initializing) {
    ++result_capture_attempts;
    if(state.phase!=Phase::stop_pending && !late_stop_reconciliation_ready()){result_wait_reason="capture-not-stopping";return;}
    if(!same_session()){result_wait_reason="capture-session-mismatch";return;}
    if(now_ms()<state.sent){result_wait_reason="capture-clock-invalid";return;}
    // Capture can arrive before the next Update evaluates its deadline. Hold
    // the original dialog only within the total bounded reconciliation window;
    // this is not permission to close it or reset a timer.
    if(now_ms()-state.sent>120000){result_wait_reason="capture-clock-invalid";return;}
    if(!self || !initializing){result_wait_reason="capture-object-or-task-missing";return;}
    if(!emu_late::exact(self,"Niantic.Ichigo.Game.Flowers.PlantingResultDialog","FlowerPlantingResultDialog")){result_wait_reason="capture-type-mismatch";return;}
    if(emu_late::reference(self,"inventoryManager")!=return_inventory_manager){result_wait_reason="capture-inventory-mismatch";return;}
    if(dialog) {if(dialog!=self) {dialog_conflict=true;result_wait_reason="capture-conflict";}return;}
    dialog_root=gchandle_new(self,false);init_root=gchandle_new(initializing,false);
    if(!dialog_root || !init_root) {dialog_conflict=true;result_wait_reason="capture-root-failed";return;}
    dialog=self;init_task=initializing;dialog_seen=now_ms();
    ++result_captures;result_wait_reason="captured";
}
void *hooked_dialog_start_async(void *self,void *method) {
    ++result_hook_entries;result_wait_reason="original-initializing";
    void *result=original_dialog_start_async?original_dialog_start_async(self,method):nullptr;
    capture_dialog(self,result);
    return result; // preserve the real initialization Task and MethodInfo
}
bool record_owned() {
    if(!dialog || owned_started<=0) return false;
    void *response=emu_late::reference(dialog,"result");
    if(!emu_late::exact(response,"Ichigo.Proto","FinishPlantingResponseProto")) return false;
    void *map=emu_late::reference(response,"record_");
    if(!emu_late::exact(map,"Ichigo.Proto","PlantingRecordMapProto")) return false;
    void *summary=emu_late::reference(map,"plantingRecord_");
    if(!emu_late::exact(summary,"Ichigo.Proto","PlantingRecordSummaryProto")) return false;
    long long started{},ended{};
    return task_long_for(summary,"get_PlantingStartTimeMs",started) &&
        task_long_for(summary,"get_PlantingEndTimeMs",ended) && started==owned_started && ended>=started;
}
// Read-only accounting observer. Never changes close/retry/admission behavior.
// A result dialog or a sent STOP alone is not a completed settlement receipt.
void preserve_closed_settlement() {
    if(!same_session() || !manual_start_clear() || !record_owned() ||
       account.size()!=64 || token.size()!=36 || !close_attempted)return;
    for(char c:account)if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))return;
    for(char c:token)if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')||c=='-'))return;
    void *response=emu_late::reference(dialog,"result");
    void *map=response?emu_late::reference(response,"record_"):nullptr;
    void *summary=map?emu_late::reference(map,"plantingRecord_"):nullptr;
    if(!emu_late::exact(summary,"Ichigo.Proto","PlantingRecordSummaryProto"))return;
    long long started{},ended{};
    if(!task_long_for(summary,"get_PlantingStartTimeMs",started) ||
       !task_long_for(summary,"get_PlantingEndTimeMs",ended) ||
       started!=owned_started || started<=0 || ended<started || ended>now_ms())return;
    bool ok=true;
    void *boxed=emu_late::call(summary,"get_NumFlowersPlanted",ok,nullptr,0);
    ScopedManagedRoot count_root(boxed,gchandle_new,gchandle_free);
    if(!ok || !count_root.handle || !emu_late::exact(boxed,"System","Int32") || !reward_unbox)return;
    void *value=reward_unbox(boxed);if(!value)return;
    const int count=*static_cast<int32_t *>(value);
    if(count<0 || count>10000000)return;
    const std::string directory=path().substr(0,path().find_last_of('/'));
    const std::string dest=directory+"/planting-settlement-"+token+".tsv";
    const std::string row="PCCSETTLEMENT/1\t"+account+"\t"+std::to_string(getpid())+"\t"+
        std::to_string(epoch)+"\t"+token+"\t"+std::to_string(started)+"\t"+
        std::to_string(ended)+"\t"+std::to_string(count)+"\tclosed-confirmed\tend\n";
    // Never overwrite an old or partial receipt. A partial file is preserved
    // as a gap; the observer must not block or replay the original transaction.
    int fd=open(dest.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(fd<0)return;
    size_t offset=0;
    while(offset<row.size()) {
        const auto n=write(fd,row.data()+offset,row.size()-offset);
        if(n<0 && errno==EINTR)continue;
        if(n<=0)break;
        offset+=static_cast<size_t>(n);
    }
    const bool persisted=offset==row.size() && fsync(fd)==0;
    close(fd);
    if(persisted) {
        fd=open(directory.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
        if(fd>=0){fsync(fd);close(fd);}
    }
}
bool result_button_ready(void *button) {
    // v154 FlowerPlantingResultDialog.closeButton is UnityEngine.UI.Button,
    // NOT IchigoButtonProxy. Verify Button ancestry before calling getters.
    // Unity Button.Press uses IsActive and IsInteractable (including canvas
    // group permission). Initialization completion is checked by our caller;
    // this Button has no ShowLoadingVisuals or WillReceiveTouch property.
    if(!emu_planting_button::compatible(button)) {
        result_wait_reason="button-type-mismatch";return false;
    }
    bool active{},interactable{};
    if(!task_bool_for(button,"IsActive",active)) {
        result_wait_reason="button-active-read-unavailable";return false;
    }
    if(!task_bool_for(button,"IsInteractable",interactable)) {
        result_wait_reason="button-interactable-read-unavailable";return false;
    }
    if(!active || !interactable) {result_wait_reason="button-not-ready";return false;}
    return true;
}
void poll_result(bool known,bool active) {
    if(!manual_start_clear()){result_wait_reason="manual-start-reconciliation-required";return;}
    if(state.phase!=Phase::stop_pending && !late_stop_reconciliation_ready()){result_wait_reason="not-stopping";return;}
    if(!same_session()){result_wait_reason="result-session-mismatch";return;}
    if(now_ms()<state.sent || (state.phase==Phase::stop_pending && now_ms()-state.sent>60000))return;
    if(!known || active){result_wait_reason="result-state-unready";return;}
    if(storage_failed){result_wait_reason="result-storage-failed";return;}
    if(close_attempted)return; // retain the close/Task stage, never replay
    if(!dialog){result_wait_reason="dialog-missing";return;}
    if(!init_task){result_wait_reason="init-task-missing";return;}
    if(dialog_conflict){result_wait_reason="dialog-conflict";return;}
    if(now_ms()<dialog_seen || dialog_seen<state.sent){result_wait_reason="dialog-clock-invalid";return;}
    if(completion(init_task,false)!=Task::success){result_wait_reason="init-task-unready";return;}
    if(!record_owned()){result_wait_reason="record-ownership-unproven";return;}
    // The controller's result operation must be a NEW task after this stop,
    // not the completed result of an earlier manual planting session.
    void *current=emu_late::reference(planting_controller,"plantingResultTask");
    if(!current){result_wait_reason="result-task-missing";return;}
    if(current==old_result){result_wait_reason="result-task-not-new";return;}
    if(new_result && current!=new_result){result_wait_reason="result-task-changed";return;}
    if(!new_result) {new_root=gchandle_new(current,false);if(!new_root){result_wait_reason="result-root-failed";return;}new_result=current;}
    void *button=emu_late::reference(dialog,"closeButton");
    if(!button){result_wait_reason="button-missing";return;}
    ScopedManagedRoot button_root(button,gchandle_new,gchandle_free);
    if(!button_root.handle){result_wait_reason="button-root-failed";return;}
    if(!result_button_ready(button))return;
    close_attempted=true;
    // Reboot with this pending record always REVIEWs; a failed close is not replayed.
    if(!save()) {storage_failed=true;state.phase=Phase::review;result_wait_reason="close-journal-failed";return;}
    const auto final_state=emu_planting_observation::read();
    if(!same_session() || !manual_start_clear() || !final_state.known || final_state.active || dialog_conflict ||
       now_ms()<state.sent || (state.phase==Phase::stop_pending && now_ms()-state.sent>60000) ||
       (state.phase==Phase::review && !late_stop_reconciliation_ready()) ||
       emu_late::reference(planting_controller,"plantingResultTask")!=new_result || !record_owned() ||
       emu_late::reference(dialog,"closeButton")!=button || !result_button_ready(button)) {
        state.phase=Phase::review;result_wait_reason="close-context-changed";save();return;
    }
    bool ok=true;
    close_task=emu_late::call(dialog,"OnCloseButtonClicked",ok,nullptr,0);
    if(close_task) close_root=gchandle_new(close_task,false);
    if(!ok || !close_task || !close_root) {state.phase=Phase::review;result_wait_reason="close-call-unconfirmed";save();}
    else result_wait_reason="close-task-submitted";
}
bool result_closed() {
    if(!close_attempted)return false; // preserve the pre-close blocking reason
    if(!same_session() || !manual_start_clear() || dialog_conflict || !close_task || !new_result || !record_owned()) {result_wait_reason="close-ownership-unproven";return false;}
    if(completion(close_task,false)!=Task::success){result_wait_reason="close-task-unready";return false;}
    if(completion(new_result,false)!=Task::success){result_wait_reason="controller-result-unready";return false;}
    void *current{};
    if(!read_terminal_controller_result(current)){result_wait_reason="closed-result-read-unavailable";return false;}
    if(current && current!=new_result){result_wait_reason="closed-result-task-changed";return false;}
    if(!current) {
        const auto observed=emu_planting_observation::read();
        const auto lease=independent_permit();
        const bool owned_lease=lease.valid() && lease.token==token && lease.account==account;
        if(!cleared_result_allowed(observed.known,observed.active,manual_start_clear(),
                read_planting_control_mode()=="off",reserve_stop,owned_lease)) {
            result_wait_reason="closed-cleared-state-unready";return false;
        }
        preserve_closed_settlement();
        result_wait_reason="closed-controller-task-cleared";return true;
    }
    preserve_closed_settlement();
    result_wait_reason="closed-tasks-confirmed";return true;
}
bool continue_terminal_predecessor(const std::string& row,const std::string& live) {
    using namespace planting_continuation;
    if(!terminal_original(row,live)||read_planting_control_mode()!="off"||selection::pending)return false;
    const auto t=fields(row);const auto directory=path().substr(0,path().find_last_of('/'));
    auto absent=[](const std::string& pid){struct stat s{};return lstat(("/proc/"+pid).c_str(),&s)!=0&&errno==ENOENT;};
    if(!absent(t[1]))return false;
    const auto terminal=directory+"/planting-terminal-"+t[4]+".tsv";
    std::string selected;bool exists=false;
    if(!read(path()+".selection",selected,exists))return false;
    const bool source_present=exists;
    // A crash after the verified rename may finish only from the immutable
    // terminal copy and identical retired selector; never recreate a selector.
    if(!exists&&(!read(terminal+".selection",selected,exists)||!exists))return false;
    const auto s=fields(selected);if(s.size()!=8||!uuid(s[4])||!number(s[1])||!absent(s[1]))return false;
    std::string prior,prior_selected,settlement;bool present=false;
    const auto old=directory+"/planting-unconfirmed-"+s[4]+".tsv";
    if(!read(old,prior,present)||!present||!read(old+".selection",prior_selected,present)||!present||
       !read(directory+"/planting-settlement-"+t[4]+".tsv",settlement,present)||!present||
       !terminal_selector(row,selected,prior,prior_selected,settlement,live))return false;
    for(const auto& p:{path()+".tmp",path()+".selection.tmp"}){struct stat st{};if(lstat(p.c_str(),&st)==0||errno!=ENOENT)return false;}
    const auto retired=directory+"/planting-retired-selection-"+s[4]+".tsv";
    if(!preserve(terminal,row)||!preserve(terminal+".selection",selected)||!preserve(terminal+".settlement",settlement)||
       !preserve(retired,selected))return false;
    // Copies are fsync/read verified before unlinking this redundant leaf.
    // Native Tasks/controller/quiet/OFF have been checked by the caller; no
    // canonical, original UNKNOWN or settlement is rewritten or reclassified.
    std::string current;bool found=false;
    if(!read(path(),current,found)||!found||current!=row||read_planting_control_mode()!="off"||!absent(t[1])||!absent(s[1]))return false;
    if(source_present){if(!read(path()+".selection",current,found)||!found||current!=selected||unlink((path()+".selection").c_str()))return false;}
    else if(!read(retired,current,found)||!found||current!=selected)return false;
    int dir=open(directory.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(dir<0)return false;
    bool ok=fsync(dir)==0;if(close(dir))ok=false;if(!ok)return false;
    std::string ticks;bool running=false;const auto pid=std::to_string(getpid()),boot=current_boot();
    if(!uuid(boot)||!process_ticks(pid,ticks,running)||!running)return false;
    const auto epoch=std::to_string(capability_registry.current_epoch());
    const std::string proof="PCCPLANTTERMINAL/1\t"+boot+"\t"+pid+"\t"+ticks+"\t"+epoch+"\t"+live+"\t"+t[4]+"\t"+
        pikmin::account_digest(row)+"\t"+s[4]+"\t"+pikmin::account_digest(selected)+"\t"+pikmin::account_digest(prior)+"\t"+
        pikmin::account_digest(settlement)+"\tquiet30\tend\n";
    return preserve(terminal+".ready-"+boot+"-"+pid+"-"+ticks+"-"+epoch,proof);
}
void continue_after_unknown() {
    static planting_continuation::QuietWindow quiet;
    static std::string quiet_identity;
    const auto observed=emu_planting_observation::read();
    const std::string live=nectar_account_binding();
    const auto identity=live+":"+std::to_string(capability_registry.current_epoch())+":"+read_planting_control_mode();
    if(identity!=quiet_identity){quiet={};quiet_identity=identity;}
    bool safe=(state.phase==Phase::review||state.phase==Phase::idle)&&!storage_failed&&runtime_metadata_ready&&
        emu_late::bound&&!emu_late::revoked&&manual_start_clear()&&result_hook_ready()&&
        observed.known&&!observed.active&&planting_controller&&planting_ui_clear()&&
        !command_namespace_reserved()&&live.size()==64;
    // In-process pending Tasks can arrive late. Continue observing them; only
    // terminal Tasks (including fault/reject) may be left as UNKNOWN.
    if((task&&!task_root)||(init_task&&!init_root)||(close_task&&!close_root)||(new_result&&!new_root))safe=false;
    for(void *t:{task,init_task,close_task,new_result})if(t) {
        if(!safe)break; // Never dereference an unrooted or foreign-session Task.
        bool complete=false;
        if(!task_bool_for(t,"get_IsCompleted",complete)||!complete)safe=false;
    }
    // The controller still owns a result flow until its slot is cleared. A
    // merely completed RPC is not permission to discard that flow.
    void *controller_result{};
    if(safe&&(!read_terminal_controller_result(controller_result)||controller_result))safe=false;
    if(!quiet.observe(now_ms(),safe))return;
    std::string row;bool exists=false;
    if(!planting_continuation::read(path(),row,exists)||!exists)return;
    if(continue_terminal_predecessor(row,live)) {
        previous_token=planting_continuation::fields(row)[4];forget_task();forget_result();reset_unknown_selection();
        state=State{};epoch=capability_registry.current_epoch();account=live;token.clear();
        owned_started=previous_started=0;owned_petal.clear();close_attempted=reserve_stop=false;
        planting_control_owned=false;result_wait_reason="terminal-predecessor-preserved";quiet={};return;
    }
    if(state.phase!=Phase::review||!planting_continuation::original(row,live))return;
    const auto f=planting_continuation::fields(row);
    const auto directory=path().substr(0,path().find_last_of('/'));
    bool dead=planting_continuation::dead_owner_proof(directory,row);
    if(!dead&&f[1]!=std::to_string(getpid())) {
        struct stat s{};
        if(lstat(("/proc/"+f[1]).c_str(),&s)==0||errno!=ENOENT)return;
        dead=true;
    } else if(!dead&&(!same_session()||f[2]!=std::to_string(epoch)||f[4]!=token))return;
    // A partial writer is evidence of uncertain storage, never garbage to
    // delete. Keep waiting rather than overwrite an unarchived temporary.
    for(const auto &p:{path()+".tmp",path()+".selection.tmp"}) {
        struct stat s{};if(lstat(p.c_str(),&s)==0||errno!=ENOENT)return;
    }
    const auto archive=path().substr(0,path().find_last_of('/'))+"/planting-unconfirmed-"+f[4]+".tsv";
    if(!planting_continuation::preserve(archive,row)||!preserve_unknown_selection(archive,row,dead))return;
    // No success or settlement is synthesized. The canonical original stays
    // untouched until a DIFFERENT normal admission persists its own intent.
    // A crash repeats the exact archive comparison, never the old START/STOP.
    previous_token=f[4];forget_task();forget_result();reset_unknown_selection();
    state=State{};epoch=capability_registry.current_epoch();account=live;token.clear();
    owned_started=previous_started=0;owned_petal.clear();close_attempted=reserve_stop=false;
    planting_control_owned=false;quiet={};
}
void install_result_hook() {
    if(hook_attempted || !runtime_metadata_ready || !emu_late::bound || emu_late::revoked) return;
    hook_attempted=true;
    void *klass=find_class("Niantic.Ichigo.Game.Flowers.PlantingResultDialog","FlowerPlantingResultDialog");
    void *method=klass?class_get_method_from_name(klass,"StartAsync",0):nullptr;
    void *entry=method?*static_cast<void **>(method):nullptr;
    Dl_info image{};
    // Actual v157 StartAsync is 0x164 bytes; private Start is only 20 bytes.
    // Never extend a 24-byte trampoline across that short function boundary.
    static const unsigned char expected[]{0xff,0x03,0x02,0xd1,0xfe,0x5f,0x05,0xa9,0xf6,0x57,0x06,0xa9,0xf4,0x4f,0x07,0xa9,0xd3,0xa4,0x04,0x90,0xf4,0x03,0x00,0xaa};
    if(!entry || !dladdr(entry,&image) || !image.dli_fbase || !image.dli_fname ||
       !std::strstr(image.dli_fname,"libil2cpp.so") ||
       reinterpret_cast<uintptr_t>(entry)!=reinterpret_cast<uintptr_t>(image.dli_fbase)+0x609f350 ||
       std::memcmp(entry,expected,sizeof(expected))!=0) return;
    A64HookFunction(entry,reinterpret_cast<void *>(hooked_dialog_start_async),
        reinterpret_cast<void **>(&original_dialog_start_async));
    hook_installed=original_dialog_start_async!=nullptr;
}
}
