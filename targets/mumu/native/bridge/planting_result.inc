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
    const auto dest=path()+".result",temp=dest+".tmp";
    // Counters/reasons only. No pointers, task IDs, account or coordinates.
    const std::string row="v1\t"+std::to_string(now)+"\t"+std::to_string(getpid())+"\t"+
        std::to_string(result_hook_entries.load(std::memory_order_relaxed))+"\t"+std::to_string(result_capture_attempts.load(std::memory_order_relaxed))+"\t"+
        std::to_string(result_captures.load(std::memory_order_relaxed))+"\t"+result_wait_reason.load(std::memory_order_relaxed)+"\t"+(close_attempted?"1":"0")+"\t"+name(state.phase)+"\tend\n";
    int fd=open(temp.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(fd<0)return;
    struct stat st{};
    bool ok=fstat(fd,&st)==0 && S_ISREG(st.st_mode) && st.st_uid==getuid() && st.st_nlink==1 &&
        write(fd,row.data(),row.size())==static_cast<ssize_t>(row.size()) && fsync(fd)==0 && fchmod(fd,0644)==0;
    if(close(fd)!=0)ok=false;
    if(ok)rename(temp.c_str(),dest.c_str());
    // Not a durable journal/closure proof; stale files cannot certify a gate.
}
void publish_safety() {
    static long long last{};
    const auto now=now_ms();if(now>=last && now-last<1000)return;last=now;
    publish_result_diagnostic(now);
    const auto dest=path()+".safety",temp=dest+".tmp";
    const std::string row="v1\t"+std::to_string(now)+"\t"+std::to_string(getpid())+"\t"+
        (hook_installed?"ready":"not-ready")+"\t"+(manual_start_clear()?"clear":"manual-review")+"\t"+
        name(state.phase)+"\t"+(hook_attempted?"attempted":"not-attempted")+"\tend\n";
    int fd=open(temp.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(fd<0)return;
    struct stat st{};
    bool ok=fstat(fd,&st)==0 && S_ISREG(st.st_mode) && st.st_uid==getuid() && st.st_nlink==1 &&
        write(fd,row.data(),row.size())==static_cast<ssize_t>(row.size()) && fsync(fd)==0 && fchmod(fd,0644)==0;
    if(close(fd)!=0)ok=false;
    if(ok)rename(temp.c_str(),dest.c_str());
    // Diagnostic only: not ownership, a recovery journal or Hook/RPC proof.
}
bool cleanup_ready() {
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
bool result_button_ready(void *button) {
    // v154 FlowerPlantingResultDialog.closeButton is UnityEngine.UI.Button,
    // NOT IchigoButtonProxy. Check the actual managed type before getters.
    // Unity Button.Press uses IsActive and IsInteractable (including canvas
    // group permission). Initialization completion is checked by our caller;
    // this Button has no ShowLoadingVisuals or WillReceiveTouch property.
    if(!emu_late::exact(button,"UnityEngine.UI","Button")) {
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
    if(!same_session() || !final_state.known || final_state.active || dialog_conflict ||
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
    if(!same_session() || dialog_conflict || !close_task || !new_result || !record_owned()) {result_wait_reason="close-ownership-unproven";return false;}
    if(completion(close_task,false)!=Task::success){result_wait_reason="close-task-unready";return false;}
    if(completion(new_result,false)!=Task::success){result_wait_reason="controller-result-unready";return false;}
    if(emu_late::reference(planting_controller,"plantingResultTask")!=new_result){result_wait_reason="closed-result-task-changed";return false;}
    result_wait_reason="closed-tasks-confirmed";return true;
}
void install_result_hook() {
    if(hook_attempted || !runtime_metadata_ready || !emu_late::bound || emu_late::revoked) return;
    hook_attempted=true;
    void *klass=find_class("Niantic.Ichigo.Game.Flowers.PlantingResultDialog","FlowerPlantingResultDialog");
    void *method=klass?class_get_method_from_name(klass,"StartAsync",0):nullptr;
    void *entry=method?*static_cast<void **>(method):nullptr;
    Dl_info image{};
    // Actual v154 StartAsync is 0x164 bytes; private Start is only 20 bytes.
    // Never extend a 24-byte trampoline across that short function boundary.
    static const unsigned char expected[]{0xff,0x03,0x02,0xd1,0xfe,0x5f,0x05,0xa9,
        0xf6,0x57,0x06,0xa9,0xf4,0x4f,0x07,0xa9,0x13,0x9f,0x04,0x90,0xf4,0x03,0x00,0xaa};
    if(!entry || !dladdr(entry,&image) || !image.dli_fbase || !image.dli_fname ||
       !std::strstr(image.dli_fname,"libil2cpp.so") ||
       reinterpret_cast<uintptr_t>(entry)!=reinterpret_cast<uintptr_t>(image.dli_fbase)+0x5F68408 ||
       std::memcmp(entry,expected,sizeof(expected))!=0) return;
    A64HookFunction(entry,reinterpret_cast<void *>(hooked_dialog_start_async),
        reinterpret_cast<void **>(&original_dialog_start_async));
    hook_installed=original_dialog_start_async!=nullptr;
}
}
