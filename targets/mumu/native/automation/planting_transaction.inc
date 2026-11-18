// Generated MuMu overlay only. Replaces the canonical bool-only manager.
// An unresolved transaction survives mode changes and process restarts.
#include <fcntl.h>
#include <atomic>
#include "planting_transaction_policy.h"
#include "planting_continuation.h"
#include "planting_command_interlock.h"
// Reward helpers are defined later in the canonical translation unit.
extern void *(*reward_invoke)(const void *, void *, void **, void **);
extern void *(*reward_unbox)(void *);
namespace emu_late {
void *call(void *, const char *, bool &, void **, int);
bool exact(void *, const char *, const char *);
void *reference(void *, const char *);
}
#include "independent_planting.h"
namespace emu_planting_tx {
emu_independent::Permit independent_permit(){
    std::string live;{std::lock_guard<std::mutex> lock(identity_mutex);live=identity_hash;}
    if(!emu_late::bound||emu_late::revoked)return {};
    return emu_independent::read_permit(getpid(),capability_registry.current_epoch(),live,now_ms());
}
State state;
bool initialized{}, storage_failed{};
uint64_t epoch{};
std::string account, token, previous_token;
void *task{};
GcHandle task_root{};
bool close_attempted{};
bool reserve_stop{}; // process-local only; never reconstructed from a journal
const char *wait_reason="waiting";
long long previous_started{}, owned_started{};
std::string owned_petal;
long long resource_checked{};
// A foreign start can still be inactive while its Task/RPC is in flight.
// Latch for this process; OFF/ON or an inactive sample cannot erase it.
std::atomic<bool> manual_start_seen{false};
std::atomic<unsigned long long> own_starts{0},foreign_starts{0};
std::atomic<long long> last_own_start{0},last_foreign_start{0};
std::atomic<unsigned> foreign_start_flags{0};
thread_local void *owned_start_controller{}, *owned_start_petal{};
void note_start(void *self,void *petal,bool confirmation) {
    unsigned flags=(!self||self!=owned_start_controller?1u:0u)|(petal!=owned_start_petal?2u:0u)|(confirmation?4u:0u);
    if(flags) {
        ++foreign_starts;last_foreign_start=now_ms();foreign_start_flags=flags;
        manual_start_seen.store(true,std::memory_order_release);
    } else {++own_starts;last_own_start=now_ms();}
}
bool manual_start_clear(){return !manual_start_seen.load(std::memory_order_acquire);}
void publish_safety();
bool result_hook_ready();
bool cleanup_ready();
bool prepare_result();
bool late_stop_reconciliation_ready();
void poll_result(bool known, bool active);
bool result_closed();
void forget_result();
bool read_planting_state(long long &, bool &);
bool prepare_selection();
bool preselected_ready(const std::string &);
bool validate_selected_petal(const std::string &);
bool active_selected_petal_requires_stop(const std::string &,bool *);
bool selection_terminal();
bool preserve_unknown_selection(const std::string &,const std::string &,bool);
void reset_unknown_selection();
void continue_after_unknown();

const char *name(Phase p) {
    switch(p) {
        case Phase::idle:return "idle";
        case Phase::start_pending:return "start-pending";
        case Phase::active:return "active-confirmed";
        case Phase::stop_pending:return "stop-pending";
        case Phase::stopped:return "stopped-confirmed";
        default:return "review";
    }
}
std::string path() {
    std::string p(core_runtime_path);
    return p.substr(0,p.find_last_of('/'))+"/emulator_independent_planting_transaction.tsv";
}
bool command_namespace_reserved() {
    const std::string runtime(core_runtime_path);
    const auto slash=runtime.find_last_of('/');
    return slash==std::string::npos || emu_planting_command::namespace_reserved(runtime.substr(0,slash));
}
bool save() {
    const std::string dest=path(), temp=dest+".tmp";
    const std::string row="v3\t"+std::to_string(getpid())+"\t"+std::to_string(epoch)+"\t"+
        account+"\t"+token+"\t"+name(state.phase)+"\t"+std::to_string(state.sent)+"\t"+
        (close_attempted?"1":"0")+"\t"+std::to_string(owned_started)+"\tend\n";
    int fd=open(temp.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(fd<0) return false;
    struct stat st{};
    bool ok=fstat(fd,&st)==0 && S_ISREG(st.st_mode) && st.st_uid==getuid() && st.st_nlink==1;
    if(ok) ok=fchmod(fd,0600)==0 && write(fd,row.data(),row.size())==static_cast<ssize_t>(row.size()) && fsync(fd)==0;
    if(close(fd)!=0) ok=false;
    if(!ok || rename(temp.c_str(),dest.c_str())!=0) return false;
    const std::string dir=dest.substr(0,dest.find_last_of('/'));
    fd=open(dir.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0) return false;
    ok=fsync(fd)==0; if(close(fd)!=0) ok=false;
    return ok;
}
void initialize() {
    if(initialized) return;
    initialized=true;
    const std::string p=path();
    int fd=open(p.c_str(),O_RDONLY|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0) { if(errno!=ENOENT) state.phase=Phase::review; return; }
    struct stat st{}; char data[513]{};
    bool ok=fstat(fd,&st)==0 && S_ISREG(st.st_mode) && st.st_uid==getuid() &&
        st.st_nlink==1 && (st.st_mode&0777)==0600 && st.st_size>0 && st.st_size<512;
    ssize_t n=ok?read(fd,data,sizeof(data)-1):-1;
    if(close(fd)!=0) ok=false;
    int saved_pid{}, consumed{}, saved_close{}; unsigned long long saved_epoch{};
    long long saved_sent{}, saved_started{}; char saved_account[65]{}, saved_token[37]{}, phase[32]{};
    ok=ok && n==st.st_size && std::sscanf(data,"v3 %d %llu %64s %36s %31s %lld %d %lld end %n",
        &saved_pid,&saved_epoch,saved_account,saved_token,phase,&saved_sent,&saved_close,&saved_started,&consumed)==8 && consumed==n;
    // Only a fully-confirmed clean terminal may start a NEW lease token.
    // Any older pending/active/corrupt file is preserved for host reconciliation.
    if(ok && std::strcmp(phase,"stopped-confirmed")==0 && saved_pid>0 && saved_epoch>0 &&
       std::strlen(saved_account)==64 && std::strlen(saved_token)==36 && saved_sent>0 && saved_close==1 && saved_started>0) {
        previous_token=saved_token; state.phase=Phase::idle;
    } else state.phase=Phase::review;
}
bool same_session() {
    std::string current; {std::lock_guard<std::mutex> lock(identity_mutex);current=identity_hash;}
    return emu_late::bound && !emu_late::revoked && capability_registry.current_epoch()==epoch &&
        !account.empty() && current==account;
}
bool task_bool_for(void *target, const char *method, bool &result) {
    if(!target || !reward_invoke || !reward_unbox) return false;
    bool ok=true;
    void *boxed=emu_late::call(target,method,ok,nullptr,0);
    if(!ok || !boxed || !emu_late::exact(boxed,"System","Boolean")) return false;
    void *value=reward_unbox(boxed); if(!value) return false;
    result=*static_cast<bool *>(value); return true;
}
Task completion(void *target, bool requires_result) {
    bool completed{}, faulted{}, canceled{}, accepted{};
    if(!task_bool_for(target,"get_IsCompleted",completed)) return Task::unknown;
    if(!completed) return Task::pending;
    if(!task_bool_for(target,"get_IsFaulted",faulted) || !task_bool_for(target,"get_IsCanceled",canceled)) return Task::unknown;
    if(faulted || canceled) return Task::faulted;
    // v154 StopPlantingWithConfirmationAsync returns Task, NOT Task<bool>.
    if(!requires_result) return Task::success;
    if(!task_bool_for(target,"get_Result",accepted)) return Task::unknown;
    return accepted?Task::success:Task::rejected;
}
Task outcome() {return completion(task,state.phase==Phase::start_pending);}
bool late_stop_reconciliation_ready() {
    if(state.phase==Phase::review && state.review==Review::stop_timeout &&
       (!state.late_stop_window(now_ms()) || !same_session() || storage_failed || !manual_start_clear() ||
        !task || !task_root || owned_started<=0 || outcome()==Task::faulted || outcome()==Task::rejected)) {
        state.review=Review::unsafe;return false;
    }
    return state.late_stop_window(now_ms()) && read_planting_control_mode()=="off" &&
        !storage_failed && manual_start_clear() && task && task_root && owned_started>0 &&
        same_session() && (outcome()==Task::pending || outcome()==Task::success);
}
bool task_long_for(void *target,const char *method,long long &value) {
    bool ok=true;void *boxed=target?emu_late::call(target,method,ok,nullptr,0):nullptr;
    if(!ok || !boxed || !emu_late::exact(boxed,"System","Int64") || !reward_unbox) return false;
    void *data=reward_unbox(boxed);if(!data) return false;
    value=*static_cast<int64_t *>(data);return true;
}
bool read_planting_state(long long &started,bool &active) {
    if(!return_inventory_manager) return false;
    bool ok=true;
    void *item=emu_late::call(return_inventory_manager,"GetPlantingState",ok,nullptr,0);
    ScopedManagedRoot item_root(item,gchandle_new,gchandle_free);
    if(!ok || !item_root.handle || !emu_late::exact(item,"Niantic.Ichigo.Inventory","PlantingStateInventoryItem")) return false;
    void *proto=emu_late::call(item,"get_Proto",ok,nullptr,0);
    ScopedManagedRoot proto_root(proto,gchandle_new,gchandle_free);
    return ok && proto_root.handle && emu_late::exact(proto,"Ichigo.Proto","PlantingStateProto") &&
        task_bool_for(proto,"get_IsPlantingStarted",active) && task_long_for(proto,"get_PlantingStartedTimeMs",started) && started>=0;
}
void forget_task() {
    if(task_root && gchandle_free) gchandle_free(task_root);
    task=nullptr;task_root=0;
}
bool planting_ui_clear() {
    // v154 existing controller -> injected DialogManager -> PopUpTracker.
    // Read-only getter; never creates/hides/confirms a dialog or chooses a
    // reward. Unknown graph/type/getter stays blocked, not "no popup".
    void *manager=emu_late::reference(planting_controller,"dialogManager");
    ScopedManagedRoot manager_root(manager,gchandle_new,gchandle_free);
    if(!manager_root.handle || !emu_late::exact(manager,"Niantic.Ichigo.UI.Framework","DialogManager"))return false;
    void *tracker=emu_late::reference(manager,"popUpTracker");
    ScopedManagedRoot tracker_root(tracker,gchandle_new,gchandle_free);
    bool open{};
    return tracker_root.handle && emu_late::exact(tracker,"Niantic.Ichigo.UI","PopUpTracker") &&
        task_bool_for(tracker,"get_MostRecent",open) && !open;
}
bool begin_impl(bool start, const emu_independent::Permit &lease,const std::string &required_selected,bool for_reserve=false) {
    if(start && required_selected.empty() && command_namespace_reserved()) {
        wait_reason="command-namespace-reserved";return false;
    }
    wait_reason="controller-or-storage-unavailable";
    if(storage_failed || !planting_controller || !gchandle_new || !gchandle_free || !reward_invoke) return false;
    wait_reason="result-hook-unavailable";
    if(!result_hook_ready()) return false;
    if(start) {
        wait_reason="manual-start-reconciliation-required";
        if(!manual_start_clear()) return false;
        wait_reason="popup-or-ui-unavailable";
        if(!planting_ui_clear()) return false;
        wait_reason="independent-planting-owner-unavailable";
        if(!lease.valid() || lease.token==previous_token ||
           !planting_continuation::token_available(path().substr(0,path().find_last_of('/')),lease.token) ||
           !lease.valid()) return false;
        epoch=capability_registry.current_epoch(); token=lease.token; account=lease.account;
    }
    wait_reason="session-unavailable";
    if(!same_session()) return false;
    if(!start && !manual_start_clear()) {
        // A foreign START may still be in flight while inventory looks owned.
        // Preserve it for review instead of submitting STOP against that race.
        wait_reason="manual-start-reconciliation-required";
        state.phase=Phase::review;save();return false;
    }
    long long inventory_started{};bool inventory_active{};
    wait_reason="planting-inventory-unavailable";
    if(!read_planting_state(inventory_started,inventory_active) || inventory_active!=!start ||
       (!start && (owned_started<=0 || inventory_started!=owned_started))) return false;
    if(start) {previous_started=inventory_started;owned_started=0;}
    wait_reason="previous-result-unresolved";
    if(!start && !prepare_result()) return false;
    if(start && !(required_selected.empty()?prepare_selection():preselected_ready(required_selected))) return false;
    bool ok=true, confirmation=false;
    void *petal=start?emu_late::call(planting_controller,"get_FlowerPetalId",ok,nullptr,0):nullptr;
    wait_reason="selected-petal-unavailable";
    if(start && (!ok || !petal || utf8_string(petal).empty() ||
        !validate_selected_petal(utf8_string(petal)))) return false;
    ScopedManagedRoot selected_root(petal,gchandle_new,gchandle_free);
    if(start && !selected_root.handle) return false;
    const std::string selected=start?utf8_string(petal):std::string{};
    if(start && !required_selected.empty() && selected!=required_selected)return false;
    if(start){owned_petal=selected;resource_checked=0;}
    state.submitted(start,now_ms());
    close_attempted=false;
    reserve_stop=!start && for_reserve;
    // Durable uncertain record BEFORE the managed mutation. Never replay on failure.
    if(!save()) { storage_failed=true;state.phase=Phase::review;return false; }
    const auto fresh_lease=independent_permit();
    const auto fresh_state=emu_planting_observation::read();
    void *selected_now=start?emu_late::call(planting_controller,"get_FlowerPetalId",ok,nullptr,0):nullptr;
    if(!same_session() || !fresh_state.known || fresh_state.active==start ||
       (start && required_selected.empty() && command_namespace_reserved()) ||
       (start && (!ok || !selected_now || utf8_string(selected_now)!=selected ||
        !validate_selected_petal(selected) || !manual_start_clear() || !planting_ui_clear() ||
        read_planting_control_mode()!="on" || !fresh_lease.valid() ||
        fresh_lease.token!=token || fresh_lease.account!=account ||
        !fresh_lease.valid()))) {
        state.phase=Phase::review;save();return false;
    }
    forget_task();
    void *start_args[]{petal,&confirmation}; void *stop_args[]{&confirmation};
    if(start){owned_start_controller=planting_controller;owned_start_petal=petal;}
    task=emu_late::call(planting_controller,start?"StartPlantingWithConfirmationAsync":"StopPlantingWithConfirmationAsync",
        ok,start?start_args:stop_args,start?2:1);
    owned_start_controller=owned_start_petal=nullptr;
    if(task) task_root=gchandle_new(task,false);
    if(!ok || !task || !task_root) {state.phase=Phase::review;save();}
    // Ownership stays uncertain until Task<bool> and later state confirm start.
    return true;
}
bool begin(bool start,const emu_independent::Permit &lease){return begin_impl(start,lease,{});}
// A separate START after SELECT confirmation; no auto-choice or setter fallback.
// Not an authority boundary: caller still requires command/global-owner guards.
bool begin_preselected(const emu_independent::Permit &lease,const std::string &id){
    initialize();
    return state.phase==Phase::idle && !id.empty() && begin_impl(true,lease,id);
}
}

void maybe_manage_planting() {
    using namespace emu_planting_tx;
    initialize();
    const auto observed=emu_planting_observation::read();
    emu_planting_observation::publish(observed);
    continue_after_unknown();
    publish_safety();
    const auto lease=independent_permit();
    const std::string mode=read_planting_control_mode();
    // The future command Store reserves its namespace BEFORE ownership handoff.
    // Pending/corrupt/unknown namespace cannot reactivate the legacy starter.
    const bool command_reserved=command_namespace_reserved();
    const bool late_stop=state.phase==Phase::review && late_stop_reconciliation_ready();
    if(state.phase==Phase::start_pending || state.phase==Phase::stop_pending || late_stop) {
        const Phase before=state.phase;
        const bool same=same_session();
        if(same && (state.phase==Phase::stop_pending || late_stop)) poll_result(observed.known,observed.active);
        if(late_stop) state.reconcile_stop(now_ms(),same,observed.known,observed.active,same?outcome():Task::unknown,same && result_closed());
        else state.observe(now_ms(),same,observed.known,observed.active,same?outcome():Task::unknown,same && result_closed());
        if(state.phase!=before) {
            if(state.phase==Phase::active) {
                long long started{};bool active{};
                if(!read_planting_state(started,active) || !active || started<=previous_started)
                    state.phase=Phase::review;
                else owned_started=started;
            }
            if(!save()) {storage_failed=true;state.phase=Phase::review;}
            if(state.phase==Phase::active) planting_control_owned=true;
            if(state.phase==Phase::stopped) {
                if(!selection_terminal()) {storage_failed=true;state.phase=Phase::review;save();}
                else {planting_control_owned=false;previous_token=token;forget_task();forget_result();}
            }
        }
    }
    if(state.phase==Phase::active && !same_session()) {state.phase=Phase::review;save();}
    if(state.phase==Phase::active && observed.known && !observed.active) {
        // Exhaustion/external stop needs result reconciliation, not another start.
        state.phase=Phase::review;save();
    }
    if(state.phase==Phase::active && observed.known && observed.active && mode=="on" &&
       lease.valid() && lease.token==token &&
       (resource_checked==0 || now_ms()<resource_checked || now_ms()-resource_checked>=5000)) {
        // Read only every five seconds, never select while planting. Confirmed
        // changed selected item or stock at reserve requests our normal durable
        // stop/result transaction; it is NOT permission to restart/rotate.
        resource_checked=now_ms();
        bool at_reserve=false;
        if(active_selected_petal_requires_stop(owned_petal,&at_reserve)) begin_impl(false,lease,{},at_reserve);
    }
    if(state.phase==Phase::active && observed.known &&
       (mode=="off" || !lease.valid() || lease.token!=token)) begin(false,lease);
    if(!command_reserved && state.phase==Phase::stopped && mode=="on" && observed.known && !observed.active &&
       lease.valid() && lease.token!=previous_token) state.phase=Phase::idle;
    if(!command_reserved && state.phase==Phase::idle && mode=="on" && observed.known && !observed.active) begin(true,lease);
    const char *action=name(state.phase);
    if(state.phase==Phase::idle) action=!observed.known?"observation-unknown":
        observed.active?"manual-session-preserved":command_reserved?"command-namespace-reserved":mode=="on"?wait_reason:"off";
    write_planting_control_status(mode,!observed.known||observed.active,action,
        storage_failed?"transaction-storage-failed":observed.reason);
}
