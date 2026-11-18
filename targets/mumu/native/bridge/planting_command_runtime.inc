// Compile-only payload integration until an independently verified
// OwnerEnvironment/global handoff is supplied. No default authority provider.
#include "planting_command_backend.inc"
#include "planting_command_owner.h"
#include <memory>
#ifdef PCC_CANARY_DAILY_PAIR
#include "planting_command_paired_owner.h"
extern "C" void pcc_emulator_stop_pair_observation();
#endif
namespace emu_planting_runtime {
using namespace emu_planting_command;
class Runtime {
    FileStore store_;
    OwnerAuthority authority_;
    NativePort port_;
public:
    Runtime(const std::string &root,OwnerEnvironment &environment):
        store_(root),authority_(store_,environment,root),port_(store_,authority_){}
    bool enter(){Grant actual;return store_.is_open()&&authority_.enter()&&authority_.read(actual);}
    UpdateTick tick(long lane){return port_.update(store_,lane);}
};
// All lifecycle calls belong to the observed Map Update lane. The atomics
// reject another thread before touching process-local dispatch evidence.
std::atomic<long> map_lane{0};
std::atomic_flag in_callback=ATOMIC_FLAG_INIT;
std::unique_ptr<Runtime> runtime;
#ifdef PCC_CANARY_DAILY_PAIR
std::unique_ptr<PairedOwnerEnvironment> paired_environment;
class GameOwnershipSource;
std::unique_ptr<GameOwnershipSource> game_ownership;
class GameConsumerSource;
std::unique_ptr<GameConsumerSource> game_consumer;
WriterExclusionSource *game_writers{};
#endif
bool attempted{},revoked{};
std::atomic<bool> logout_seen{false};

struct CallbackRelease {
    ~CallbackRelease(){in_callback.clear(std::memory_order_release);}
};
bool on_lane(){const auto lane=map_lane.load(std::memory_order_acquire);return lane>0&&lane==::syscall(SYS_gettid);}
#ifdef PCC_CANARY_DAILY_PAIR
#include "planting_command_game_owner.inc"
#include "planting_command_game_consumer.inc"
#endif

// The future provider must outlive this process-local Runtime and independently
// prove installed APK / loaded core bytes and exclusion of competing writers.
// No JNI/file-driven attachment endpoint; not called by the current payload.
__attribute__((used,noinline)) bool connect(OwnerEnvironment &environment){
    if(!on_lane()||in_callback.test_and_set(std::memory_order_acquire))return false;
    CallbackRelease release;
    if(attempted||revoked||logout_seen.load(std::memory_order_acquire)||!emu_late::bound||emu_late::revoked)return false;
    attempted=true;
    const std::string path(core_runtime_path),suffix="/core_runtime.tsv";
    if(path.size()<=suffix.size()||path.substr(path.size()-suffix.size())!=suffix)return false;
    const auto root=path.substr(0,path.size()-suffix.size())+"/emulator-command";
    auto candidate=std::make_unique<Runtime>(root,environment);
    if(!candidate->enter())return false;
    runtime=std::move(candidate);return true;
}
#ifdef PCC_CANARY_DAILY_PAIR
// Typed activation route: ownership/account remains an independent handoff;
// actual core/APK bytes can no longer be substituted by its flags or strings.
// No default ownership source, file/JNI activation endpoint, or automatic caller.
__attribute__((used,noinline)) bool connect_paired(OwnershipSource &ownership){
    if(!on_lane()||attempted||revoked||logout_seen.load(std::memory_order_acquire))return false;
    auto environment=std::make_unique<PairedOwnerEnvironment>(ownership);
    RuntimePair checked;if(!environment->read(checked)||!connect(*environment))return false;
    paired_environment=std::move(environment);return true;
}
__attribute__((used,noinline)) bool connect_game(WriterExclusionSource &writers){
    if(!on_lane()||attempted||revoked||logout_seen.load(std::memory_order_acquire))return false;
    if(game_writers&&game_writers!=&writers)return false;
    if(!game_consumer)game_consumer=std::make_unique<GameConsumerSource>();
    if(!game_ownership){game_ownership=std::make_unique<GameOwnershipSource>(writers,*game_consumer);game_writers=&writers;}
    if(!connect_paired(*game_ownership))return false;
    return true;
}
#endif

void revoke(){
    // Logout may arrive from another callback. Do not destroy state/locks on
    // that thread; the next Map Update observes the latch and releases them.
    logout_seen.store(true,std::memory_order_release);
#ifdef PCC_CANARY_DAILY_PAIR
    pcc_emulator_stop_pair_observation();
#endif
}
UpdateTick map_update(void *self){
    if(!self)return UpdateTick::blocked;
    const long here=::syscall(SYS_gettid);long empty=0;
    map_lane.compare_exchange_strong(empty,here,std::memory_order_acq_rel);
    if(!on_lane())return UpdateTick::wrong_lane;
    if(in_callback.test_and_set(std::memory_order_acquire))return UpdateTick::busy;
    CallbackRelease release;
    if(logout_seen.load(std::memory_order_acquire)||emu_late::revoked){revoked=true;runtime.reset();
#ifdef PCC_CANARY_DAILY_PAIR
        paired_environment.reset();
        game_ownership.reset();
        game_consumer.reset();
        game_writers=nullptr;
#endif
        return UpdateTick::halted;}
    if(revoked)return UpdateTick::halted;
    if(!runtime||!emu_late::bound)return UpdateTick::blocked;
    return runtime->tick(here);
}
}
