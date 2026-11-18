// Isolated MuMu command bridge. Include AFTER transaction, selection and result
// backends. Optional compile-only payload wiring exercises real dependencies;
// an independent OwnerEnvironment/global handoff is still required to activate.
#include "planting_command_ledger.h"
#include "planting_command_store.h"
#include "planting_command_authority.h"
#include "planting_stop_snapshot.h"
#include <atomic>
#include <sys/syscall.h>
namespace emu_planting_command {
enum class UpdateTick { blocked, pending, terminal, report_published, review, storage_failed, halted, wrong_lane, busy };
class NativePort final:public Port {
    Store &store_;Authority &authority_;
    std::string dispatched_,published_snapshot_;
    std::atomic<long> update_thread_{0};
    std::atomic_flag updating_=ATOMIC_FLAG_INIT;
    bool update_failed_{};
    bool grant(Grant &g){
        if(!authority_.read(g))return false;
        const auto now=now_ms();
        return g.pid==getpid()&&g.epoch>0&&uuid(g.boot)&&digest(g.account)&&
            uuid(g.owner)&&uuid(g.token)&&uuid(g.operation)&&g.sampled>=1000000000000LL&&
            digest(g.core_sha)&&digest(g.apk_sha)&&
            now>=g.sampled&&now-g.sampled<=5000&&g.global_lock&&g.namespace_lock&&
            g.legacy_quiescent&&g.core_paired&&g.apk_paired;
    }
public:
    NativePort(Store &store,Authority &authority):store_(store),authority_(authority){}
    // Keep THIS Port alive across callbacks: dispatch/publication evidence is
    // intentionally process-local. Caller supplies the independently captured
    // Update callback TID and holds its complete ownership critical section.
    // This is not a hook installer, authority implementation or lane discovery.
    UpdateTick update(FileStore &incoming,long expected_update_tid){
        if(expected_update_tid<=0||expected_update_tid>999999999||
           ::syscall(SYS_gettid)!=expected_update_tid)return UpdateTick::wrong_lane;
        long unbound=0;
        update_thread_.compare_exchange_strong(unbound,expected_update_tid);
        if(update_thread_.load()!=expected_update_tid)return UpdateTick::wrong_lane;
        if(updating_.test_and_set(std::memory_order_acquire))return UpdateTick::busy;
        struct Release {std::atomic_flag &flag;~Release(){flag.clear(std::memory_order_release);}} release{updating_};
        if(update_failed_)return UpdateTick::halted;
        auto failure=[&](UpdateTick reason){update_failed_=true;return reason;};
        if(static_cast<Store *>(&incoming)!=&store_)return failure(UpdateTick::storage_failed);
        bool present=false;Ledger entry;
        auto read=[&]{return store_.read(present,entry)&&(!present||valid(entry));};
        auto observe_pending=[&]()->UpdateTick{
            const auto result=observe();
            if(result==Confirmation::storage_failed)return failure(UpdateTick::storage_failed);
            if(result==Confirmation::review)return failure(UpdateTick::review);
            if(!read())return failure(UpdateTick::storage_failed);
            if(!present)return failure(UpdateTick::storage_failed);
            if(entry.phase==ReceiptPhase::review)return failure(UpdateTick::review);
            return entry.phase==ReceiptPhase::accepted?UpdateTick::pending:UpdateTick::terminal;
        };
        if(!read())return failure(UpdateTick::storage_failed);
        if(present&&entry.phase==ReceiptPhase::review)return failure(UpdateTick::review);
        // Observe the unresolved durable operation BEFORE considering a new
        // request. Neither expiry nor a newer file can replace/replay it.
        if(present&&entry.phase==ReceiptPhase::accepted){
            const auto result=observe_pending();if(result!=UpdateTick::terminal)return result;
        }
        const auto consumed=poll_request(incoming);
        if(consumed==Consume::storage_failed)return failure(UpdateTick::storage_failed);
        if(consumed==Consume::review)return failure(UpdateTick::review);
        if(!read())return failure(UpdateTick::storage_failed);
        if(present&&entry.phase==ReceiptPhase::review)return failure(UpdateTick::review);
        if(present&&entry.phase==ReceiptPhase::accepted){
            const auto result=observe_pending();if(result!=UpdateTick::terminal)return result;
        }
        // The regular native manager/result hook still progresses owned STOP.
        // Never call it a second time here, never close from a saved receipt.
        if(present&&entry.phase==ReceiptPhase::started){
            StopSnapshot actual;
            if(read_stop_snapshot(actual)){
                if(!publish_stop_snapshot(incoming))return failure(UpdateTick::storage_failed);
                return UpdateTick::report_published;
            }
        }
        return consumed==Consume::blocked?UpdateTick::blocked:UpdateTick::terminal;
    }
    // Must run under the caller's full ownership critical section, on the same
    // Update lane as transaction/result hooks. No file adoption/deletion/replay.
    Consume poll_request(FileStore &incoming){
        if(static_cast<Store *>(&incoming)!=&store_)return Consume::storage_failed;
        const auto begin=now_ms();Grant first,last;
        if(!grant(first))return Consume::blocked;
        bool present=false,rechecked=false;std::string wire,after;
        if(!incoming.read_request(present,wire))return Consume::storage_failed;
        if(!present)return Consume::blocked;
        if(!grant(last)||!same_grant(first,last)||last.sampled<first.sampled||
           !incoming.read_request(rechecked,after)||!rechecked||after!=wire||
           now_ms()<begin||now_ms()-begin>1000)return Consume::blocked;
        // consume() persists/readbacks intent then rechecks actual fresh context
        // before send. Exact duplicates are observe-only even after send expiry.
        return consume(wire,store_,*this);
    }
    bool read_stop_snapshot(StopSnapshot &out){
        using namespace emu_planting_tx;
        const auto begin=now_ms();bool present=false;Ledger entry,after;Grant first,last;Context facts;
        if(!store_.read(present,entry)||!present||!valid(entry)||entry.phase!=ReceiptPhase::started||
           entry.command.action!=Action::start||dispatched_!=command_wire(entry.command)||
           !grant(first)||!context(entry.command,facts))return false;
        const auto &c=entry.command;
        if(!facts.known||!facts.trusted_session||facts.pid!=c.pid||facts.epoch!=c.epoch||
           facts.boot!=c.boot||facts.account!=c.account||facts.owner!=c.owner||facts.token!=c.token||
           facts.operation!=c.operation||facts.selected!=c.petal||owned_petal!=c.petal||
           first.pid!=facts.pid||first.epoch!=facts.epoch||first.boot!=facts.boot||
           first.account!=facts.account||first.owner!=facts.owner||first.token!=facts.token||
           first.operation!=facts.operation)return false;
        StopSnapshot s;
        if(facts.owned_stop_pending&&facts.owned&&facts.owned_started==entry.native_started)
            s.phase=NativeStopPhase::pending;
        else if(facts.result_closed&&!facts.active&&!facts.owned&&facts.closed_started==entry.native_started)
            s.phase=NativeStopPhase::closed;
        else return false;
        s.pid=facts.pid;s.epoch=facts.epoch;s.boot=facts.boot;s.account=facts.account;
        s.core_sha=first.core_sha;s.apk_sha=first.apk_sha;s.owner=facts.owner;s.token=facts.token;
        s.start_operation=c.operation;s.petal=c.petal;s.started=entry.native_started;
        if(!store_.read(present,after)||!present||encode(after)!=encode(entry)||
           !grant(last)||!same_grant(first,last)||last.sampled<first.sampled)return false;
        s.captured=now_ms();
        if(s.captured<begin||s.captured-begin>1000||s.captured<facts.now||
           s.captured-first.sampled>5000||s.captured-last.sampled>5000||stop_snapshot_wire(s).empty())return false;
        out=s;return true;
    }
    bool publish_stop_snapshot(FileStore &target){
        if(static_cast<Store *>(&target)!=&store_)return false;
        const auto begin=now_ms();StopSnapshot snapshot;
        if(!read_stop_snapshot(snapshot))return false;
        bool present=false;StopSnapshot prior;
        if(!target.read_stop_report(present,prior))return false;
        const auto previous=present?stop_snapshot_wire(prior):std::string{};
        // History sets cannot prove an arbitrary old token/operation pairing.
        // Cross-round replacement also requires our exact last published frame.
        if(present&&!same_stop_round(prior,snapshot)&&previous!=published_snapshot_)return false;
        const bool saved=target.publish_stop_report(snapshot,previous,[&](const StopSnapshot &expected){
            StopSnapshot fresh;
            return read_stop_snapshot(fresh)&&same_stop_round(expected,fresh)&&fresh.phase==expected.phase&&
                fresh.captured>=expected.captured&&now_ms()>=begin&&now_ms()-begin<=1000;
        });
        if(saved)published_snapshot_=stop_snapshot_wire(snapshot);
        return saved;
    }
    bool context(const Command &c,Context &out) override {
        using namespace emu_planting_tx;
        initialize();Grant first,last;
        if(!grant(first)||!runtime_metadata_ready||!emu_late::bound||emu_late::revoked||
           storage_failed||!manual_start_clear()||!planting_controller||!result_hook_ready()||
           !command_namespace_reserved()||capability_registry.current_epoch()!=first.epoch)return false;
        if(!emu_late::exact(planting_controller,"Niantic.Ichigo.Game.Flowers","FlowerPlantingController"))return false;
        std::string live_account;
        {std::lock_guard<std::mutex> lock(identity_mutex);live_account=identity_hash;}
        if(live_account!=first.account)return false;
        const auto observed=emu_planting_observation::read();
        long long inventory_started{};bool inventory_active{};
        std::string selected,after;
        if(!observed.known||!read_planting_state(inventory_started,inventory_active)||
           inventory_active!=observed.active||!selection::selected(selected)||
           !selection::command_selection_clear())return false;
        Context facts;facts.pid=getpid();facts.epoch=first.epoch;facts.boot=first.boot;
        facts.account=live_account;facts.owner=first.owner;facts.token=first.token;
        facts.operation=first.operation;facts.selected=selected;facts.known=true;facts.active=inventory_active;
        const bool bound=epoch==first.epoch&&account==first.account&&token==first.token&&same_session();
        facts.owned=bound&&planting_control_owned&&owned_started>0&&
            inventory_started==owned_started&&(state.phase==Phase::active||state.phase==Phase::stop_pending);
        if(facts.owned)facts.owned_started=owned_started;
        facts.owned_stop_pending=facts.owned&&state.phase==Phase::stop_pending&&owned_petal==c.petal;
        // Native stopped is reached only after the real close/new-result Tasks
        // plus two inactive samples. Restart initializes it to idle, NOT stopped,
        // so a saved scalar journal cannot reconstruct this in-process proof.
        facts.result_closed=bound&&state.phase==Phase::stopped&&close_attempted&&
            owned_started>0&&!planting_control_owned&&!inventory_active&&previous_token==token;
        if(facts.result_closed)facts.closed_started=owned_started;
        const auto lease=route_lease();
        const bool new_terminal=state.phase==Phase::stopped&&close_attempted&&!planting_control_owned&&
            same_session()&&previous_token==token&&first.token!=previous_token&&owned_started>0;
        facts.ready_to_start=!inventory_active&&!planting_control_owned&&
            (state.phase==Phase::idle||new_terminal)&&lease.valid()&&lease.token==first.token&&
            lease.account==first.account&&lease.phase!="prepare"&&read_planting_control_mode()=="on"&&
            route_location_gate.ready(nearby_elapsed_ms())&&cleanup_ready()&&
            selection::command_resources(c.petal,first.epoch,first.account);
        long long final_started{};bool final_active{};
        const auto final_observed=emu_planting_observation::read();
        if(!final_observed.known||final_observed.active!=observed.active||
           !read_planting_state(final_started,final_active)||final_started!=inventory_started||
           final_active!=inventory_active||!selection::selected(after)||after!=selected||
           !grant(last)||!same_grant(first,last)||
           capability_registry.current_epoch()!=first.epoch||!emu_late::bound||emu_late::revoked||
           !manual_start_clear())return false;
        {std::lock_guard<std::mutex> lock(identity_mutex);if(identity_hash!=live_account)return false;}
        facts.now=now_ms();facts.trusted_session=true;out=facts;return true;
    }
    void send(const Command &c) override {
        using namespace emu_planting_tx;
        bool present=false;Ledger entry;Context fresh;
        if(!store_.read(present,entry)||!present||!valid(entry)||entry.phase!=ReceiptPhase::accepted||
           command_wire(entry.command)!=command_wire(c)||!context(c,fresh)||!matches(c,fresh)||
           fresh.now<entry.written)return;
        const auto lease=route_lease();
        if(!lease.valid()||lease.token!=fresh.token||lease.account!=fresh.account)return;
        dispatched_.clear();
        if(c.action!=Action::stop){
            if(state.phase==Phase::stopped){
                if(!fresh.ready_to_start||entry.tokens.size()<2||token==fresh.token)return;
                state={}; // only accepted NEW round after actual closed terminal
            }
            if(state.phase!=Phase::idle)return;
            // Values come from fresh independent facts, never directly from c.
            epoch=fresh.epoch;account=fresh.account;token=fresh.token;
        }else {
            if(owned_petal!=c.petal||owned_started!=c.started)return;
            // Link this durable host intent to the already-issued autonomous
            // owned STOP. No second RPC, no reconstructed/adopted foreign round.
            // Actual result closure is still required by observe()/confirm().
            if((state.phase==Phase::stop_pending&&fresh.owned_stop_pending)||
               (state.phase==Phase::stopped&&fresh.result_closed)){
                dispatched_=command_wire(c);return;
            }
            if(state.phase!=Phase::active||!fresh.owned)return;
        }
        bool sent=c.action==Action::select?select_petal(c.petal):
            c.action==Action::start?begin_preselected(lease,c.petal):begin(false,lease);
        if(sent)dispatched_=command_wire(c);
    }
    Confirmation observe(){
        using namespace emu_planting_tx;
        bool present=false;Ledger entry;
        if(!store_.read(present,entry))return Confirmation::storage_failed;
        if(!present||!valid(entry))return Confirmation::rejected;
        if(entry.phase==ReceiptPhase::review)return Confirmation::review;
        if(entry.phase!=ReceiptPhase::accepted)return Confirmation::rejected;
        const auto &c=entry.command;
        auto phase=ReceiptPhase::accepted;int64_t started=0;
        if(dispatched_==command_wire(c)){
            if(c.action==Action::select&&state.phase==Phase::idle&&same_session()&&
               token==c.token&&preselected_ready(c.petal))phase=ReceiptPhase::selected;
            if(c.action==Action::start&&state.phase==Phase::active&&owned_petal==c.petal){
                phase=ReceiptPhase::started;started=owned_started;
            }
            if(c.action==Action::stop&&state.phase==Phase::stopped&&owned_petal==c.petal&&
               close_attempted&&owned_started==c.started){phase=ReceiptPhase::stopped;started=owned_started;}
        }
        // Without an in-process dispatch/proof this only checks the deadline;
        // never confirm/replay a persisted accepted request after reconstruction.
        return confirm(store_,*this,c,phase,started);
    }
};
}
