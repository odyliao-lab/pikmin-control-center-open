#include "diagnostic_snapshot_file.h"
#include "async_dispatch_projection.h"
#include "dispatch_projection_file.h"
#include "atomic_projection.h"
#include "hack.h"
#include "log.h"
#include "xdl.h"
#include "And64InlineHook.hpp"
#include "managed_gc.h"
#include "seed_preview.h"
#include "dispatch_safety.h"
#include "nearby_policy.h"
#include "nearby_safety.h"
#include "async_tsv_log.h"
#include "dispatch_budget.h"
#include <algorithm>
#include "route_policy.h"
#include "session_identity.h"
#include "nectar_intent_store.h"
#include "task_exclusion.h"
#include <mutex>
#include <set>
#include "return_policy.h"
#include "planter_policy.h"
#include "planter_recovery.h"
#include "capability_state.h"
#include "batch_target_probe.h"
#include "bootstrap_retry.h"
#include <atomic>

#include <cinttypes>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <cerrno>
#include <cstdlib>
#include <dlfcn.h>
#include <fstream>
#include <ifaddrs.h>
#include <map>
#include <net/if.h>
#include <string>
#include <vector>
#include <sys/stat.h>
#include <thread>
#include <time.h>
#include <unistd.h>

#ifdef PCC_UNIFIED
void pcc_gps_install_after_login();
#endif

namespace {

// Read-only capability diagnostics (core_capabilities.tsv). Every call below is
// a short lock/assign/unlock on the registry's own mutex; nothing here is a
// business gate. See docs/handoffs/2026-09-16-phase-b-native-sync-design.md.
pcc_cap::Registry capability_registry;

// Pikmin Bloom 157.0 / arm64-v8a. Regenerated from the signature-verified APK's
// libil2cpp.so plus global-metadata.dat; nonmatching binaries fail closed.
constexpr off_t kExpectedIl2CppSize = 257043464;
constexpr uintptr_t kRegisterMapObjectRva = 0x5b87ed0;
constexpr uintptr_t kMapObjectManagerUpdateRva = 0x5b89bc4;
constexpr uintptr_t kFlowerModelUpdatedRva = 0x5b9a9c4;
constexpr uintptr_t kRpcManagerConstructorRva = 0x73f3114;
constexpr uintptr_t kRpcManagerLoggedInRva = 0x73f2a10;
constexpr uintptr_t kPlantingInitRva = 0x6088f44;
constexpr uintptr_t kPlantingStartRva = 0x6089fcc;
constexpr uintptr_t kPlantingStateUpdatedRva = 0x608a808;
constexpr uintptr_t kLocationControllerAwakeRva = 0x7327ef8;
constexpr uintptr_t kRequestConstructorRva = 0x7348a94;
constexpr uintptr_t kRequestSetMapObjectIdRva = 0x7348c8c;
constexpr uintptr_t kRequestSetIncludeFailureRva = 0x7348d6c;
constexpr uintptr_t kSendClaimRva = 0x73e1554;
constexpr uintptr_t kTaskIsCompletedRva = 0xcb6f674;
constexpr uintptr_t kTaskIsFaultedRva = 0xcb75088;
constexpr uintptr_t kBlockerServiceInitializeRva = 0x63025c0;

constexpr size_t kInitialMapObjectProtoOffset = 0x68;
constexpr size_t kInteractionSettingsOffset = 0xA8;
constexpr size_t kLocationControllerOffset = 0x10;
constexpr size_t kPlantingSenderOffset = 0x128;
constexpr size_t kPlantingLocationControllerOffset = 0x48;
constexpr size_t kChangeSenderItemOffset = 0x20;
constexpr size_t kMapObjectIdOffset = 0x18;
constexpr size_t kMapObjectPointOffset = 0x20;
constexpr size_t kMapObjectPayloadOffset = 0x30;
constexpr size_t kPointLatitudeOffset = 0x18;
constexpr size_t kPointLongitudeOffset = 0x20;
constexpr size_t kFlowerStateOffset = 0x18;
constexpr size_t kFlowerRemainingOffset = 0x30;
constexpr size_t kFlowerWiltingTimeOffset = 0x38;
constexpr size_t kFlowerBloomedTimeOffset = 0x40;
constexpr size_t kFlowerRewardReceivedOffset = 0x48;

using ObjectGetClass = void *(*)(void *);
using ObjectGetVirtualMethod = void *(*)(void *, void *);
using ClassGetName = const char *(*)(void *);
using DomainGet = void *(*)();
using DomainGetAssemblies = const void **(*)(void *, size_t *);
using AssemblyGetImage = void *(*)(const void *);
using ClassFromName = void *(*)(void *, const char *, const char *);
using ClassGetMethodFromName = void *(*)(void *, const char *, int);
using ClassGetMethods = void *(*)(void *, void **);
using ClassGetField = void *(*)(void *, const char *);
using FieldGetOffset = size_t (*)(void *);
using FieldGetValue = void (*)(void *, void *, void *);
using FieldGetType = void *(*)(void *);
using ClassFromType = void *(*)(void *);
using ClassValueSize = int32_t (*)(void *, uint32_t *);
using SendExpeditionRpc = void *(*)(void *, void *, void *, int, void *);
using MethodGetName = const char *(*)(void *);
using MethodGetParamCount = uint32_t (*)(void *);
using ObjectNew = void *(*)(void *);
using ArrayNew = void *(*)(void *, size_t);
using StringNew = void *(*)(const char *);
using pikmin::GcHandle;
using pikmin::GcHandleNew;
using pikmin::GcHandleFree;
using pikmin::ScopedManagedRoot;
using GcWriteBarrier = void (*)(void *, void **, void *);
using RestrictPikminActions = bool (*)(void *, int64_t, void *, void *);
using TaskBool = bool (*)(void *, void *);
using RegisterMapObject = void (*)(void *, void *, int, void *);
using SimpleMethod = void (*)(void *, void *);
using GetFlowerProto = void *(*)(void *, void *);
using ProtoMethod = void (*)(void *, void *, void *);
using RpcManagerLoggedIn = void (*)(void *, void *, void *, void *, void *);
using RequestConstructor = void (*)(void *, void *);
using RequestSetString = void (*)(void *, void *, void *);
using RequestSetBool = void (*)(void *, bool, void *);
using SendClaim = void *(*)(void *, void *, void *, int, void *);
using CompletePikminTask = void *(*)(void *, void *, bool, void *);
using PreparePikminTasks = void *(*)(void *, void *, void *);
using ShouldPrepareCompletion = bool (*)(void *, void *, void *);
using GetInventoryItemId = void *(*)(void *, void *);
using GetPikminTaskList = void *(*)(void *, void *);
using GetPikminTaskProto = void *(*)(void *, void *);
using GetTaskFinishTimeMs = int64_t (*)(void *, void *);
using GetTaskLong = int64_t (*)(void *, void *);
using GetTaskDouble = double (*)(void *, void *);
using GetTaskVariant = void *(*)(void *, void *);
using GetTaskInt = int (*)(void *, void *);
using GetExpeditionDurationMs = int64_t (*)(void *, void *);
using GetExpeditionDataByIndex = void *(*)(void *, int, void *, void *);
using GetExpeditionBool = bool (*)(void *, void *);
using ExpeditionDataStoreConstructor = void (*)(void *, void *);
using GetEnumerable = void *(*)(void *, void *);
using EnumeratorMoveNext = bool (*)(void *, void *);
using EnumeratorCurrent = void *(*)(void *, void *);
using PickFastestPikmins = void *(*)(void *, void *, void *, void *, void *);
using SetExpeditionPikmins = void (*)(void *, void *, void *);
using StartExpedition = void *(*)(void *, void *);
using GetManagedString = void *(*)(void *, void *);
using StartPlantingWithConfirmation = void *(*)(void *, void *, bool, void *);
using StopPlantingWithConfirmation = void *(*)(void *, bool, void *);
using NoArgTask = void *(*)(void *, void *);
using StaticNoArgTask = void *(*)(void *);
// Shape shared by any zero-arg managed getter that returns an object
// reference (System.Threading.Tasks.Task.Exception, Exception.Message, ...).
using ObjectGetter = void *(*)(void *, void *);
using PikminTaskPreparerConstructor = void (*)(void *, void *, void *, void *, void *);
using BlockerServiceInitialize = void (*)(void *, void *);

struct Il2CppStringLayout {
    void *klass;
    void *monitor;
    int32_t length;
    char16_t chars[1];
};

struct FlowerRecord {
    double latitude{};
    double longitude{};
    int state{};
    long long wilting_ms{};
    bool received{};
    bool attempted{};
};

ObjectGetClass object_get_class{};
ObjectGetVirtualMethod object_get_virtual_method{};
ClassGetName class_get_name{};
DomainGet domain_get{};
DomainGetAssemblies domain_get_assemblies{};
AssemblyGetImage assembly_get_image{};
ClassFromName class_from_name{};
ClassGetMethodFromName class_get_method_from_name{};
ClassGetMethods class_get_methods{};
ClassGetField class_get_field{};
FieldGetOffset field_get_offset{};
FieldGetValue field_get_value{};
FieldGetType field_get_type{};
ClassFromType class_from_type{};
ClassValueSize class_value_size{};
SendExpeditionRpc original_send_expedition_rpc{};
void *dispatch_request_class{};
void *dispatch_point_class{};
void *dispatch_string_class{};
char dispatch_wire_path[512]{};
thread_local bool module_start_call{};
MethodGetName method_get_name{};
MethodGetParamCount method_get_param_count{};
ObjectNew object_new{};
ArrayNew array_new{};
StringNew string_new{};
GcHandleNew gchandle_new{};
GcHandleFree gchandle_free{};
GcWriteBarrier gc_write_barrier{};
void *available_pikmin_class{};
void *available_proto_class{};
void *available_list_method{};
void *available_proto_method{};
void *available_status_method{};
void *available_restriction_method{};
void *available_clock_instance_method{};
void *available_clock_time_method{};
void *selected_pikmins_method{};
void *gift_allows_method{};
RegisterMapObject original_register_map_object{};
SimpleMethod original_map_update{};
GetFlowerProto original_get_flower_proto{};
ProtoMethod original_construct_proto_object{};
ProtoMethod original_update_proto_object{};
SimpleMethod original_flower_model_updated{};
SimpleMethod original_rpc_manager_constructor{};
RpcManagerLoggedIn original_rpc_manager_logged_in{};
SimpleMethod original_planting_init{};
StartPlantingWithConfirmation original_planting_start{};
ProtoMethod original_planting_state_updated{};
ObjectGetter original_maybe_show_speed_warning{};
SimpleMethod original_location_controller_awake{};
RequestConstructor request_constructor{};
RequestSetString request_set_map_object_id{};
RequestSetBool request_set_include_failure{};
SendClaim send_claim{};
CompletePikminTask original_complete_pikmin_task{};
PreparePikminTasks original_prepare_pikmin_tasks{};
ShouldPrepareCompletion original_should_prepare_completion{};
GetInventoryItemId get_inventory_item_id{};
GetPikminTaskList original_get_pikmin_task_list{};
GetPikminTaskProto get_pikmin_task_proto{};
GetTaskFinishTimeMs get_task_finish_time_ms{};
GetTaskInt get_task_completion_requirement{};
void *task_completion_requirement_method{};
GetTaskVariant get_task_finish_location{};
GetTaskVariant get_task_pikmin_id{};
GetTaskVariant get_task_carry{};
GetTaskVariant get_task_expedition{};
GetTaskVariant get_task_gift{};
GetTaskVariant get_task_poi_challenge{};
GetTaskVariant get_expedition_fruit{};
GetTaskVariant get_expedition_seed{};
GetTaskVariant get_expedition_gift{};
GetTaskVariant get_expedition_bloomed_poi{};
GetTaskVariant get_expedition_postcard{};
GetTaskVariant get_expedition_postcard_with_items{};
GetTaskVariant get_bloomed_poi_reward_v2{};
GetTaskVariant get_poi_reward_fruit{};
GetTaskVariant get_carry_resource{};
GetTaskVariant get_carry_challenge_info{};
GetTaskVariant get_carry_pikmin_seed{};
GetTaskInt get_seed_type{};
GetTaskVariant get_seed_pikmin{};
GetTaskInt get_pikmin_type{};
GetTaskInt get_pikmin_category{};
GetTaskInt get_pikmin_full_asset{};
GetTaskInt get_gift_item_case{};
GetTaskVariant get_gift_deco{};
GetTaskVariant get_gift_rare_deco{};
GetTaskVariant get_deco_pikmin_type{};
GetTaskInt get_deco_box_type{};
GetTaskInt get_rare_deco_category{};
GetTaskInt get_task_case{};
GetTaskInt get_expedition_target_case{};
GetTaskLong get_expedition_expiration_time_ms{};
GetTaskVariant get_expedition_spawn_point{};
GetTaskDouble get_point_lat_degrees{};
GetTaskDouble get_point_lng_degrees{};
GetTaskInt get_resource_type{};
GetTaskInt get_resource_num_pikmins{};
GetTaskInt get_resource_honey_flower_kind{};
GetTaskInt get_resource_weight_grams{};
GetTaskVariant get_resource_flower_kind{};
GetTaskVariant get_resource_honey_balls{};
GetTaskInt get_honey_num_balls{};
GetTaskInt get_honey_type{};
GetTaskInt get_honey_flower_kind{};
GetTaskVariant get_honey_flower_kind_text{};
GetTaskVariant get_expedition_item_key{};
GetExpeditionDurationMs original_get_expedition_total_duration_ms{};
GetExpeditionDataByIndex get_expedition_data_by_index{};
GetExpeditionBool get_expedition_can_try_start{};
// Two more candidates from the INVALID_ARGUMENT investigation
// (HANDOFF_2026-08-31_RPC_DIAGNOSTICS.md): CanTryStart alone may not be the
// full precondition set the server checks, and the picked team's cached
// carrying-power/duration fields may be stale immediately after a GPS
// teleport if the game's own UI flow normally forces a recompute that this
// module's tighter selection-to-start window skips.
GetExpeditionBool get_expedition_has_enough_carrying_power{};
SimpleMethod invalidate_expedition_cached_values{};
ExpeditionDataStoreConstructor original_expedition_data_store_constructor{};
GetEnumerable get_pikmin_item_collection{};
PickFastestPikmins pick_fastest_pikmins{};
SetExpeditionPikmins set_expedition_pikmins{};
StartExpedition start_expedition{};
GetManagedString get_planting_flower_petal_id{};
StartPlantingWithConfirmation start_planting_with_confirmation{};
StopPlantingWithConfirmation stop_planting_with_confirmation{};
NoArgTask close_planting_result_dialog{};
StaticNoArgTask get_completed_task{};
SimpleMethod original_planting_result_dialog_start{};
PikminTaskPreparerConstructor original_pikmin_task_preparer_constructor{};
BlockerServiceInitialize original_blocker_service_initialize{};
TaskBool task_is_completed{};
TaskBool task_is_faulted{};
void *request_class{};
void *game_domain{};
std::atomic<bool> runtime_metadata_ready{};
void *rpc_manager{};
void *planting_controller{};
void *interaction_settings{};
void *location_controller{};
void *return_preparer{};
void *return_inventory_manager{};
void *return_action_manager{};
void *expedition_data_store{};
char flower_log_path[512]{};
char mode_path[512]{};
char target_path[512]{};
char status_path[512]{};
char claim_log_path[512]{};
char system_gps_path[512]{};
// service.sh rewrites the system GPS file every two seconds, so anything older
// than this means its loop is not running and the contents cannot be trusted.
constexpr long long kSystemGpsMaxAgeSeconds = 15;
constexpr int kMaxPikminTaskListCount = 512;
long long last_stale_gps_age_logged = -1;
char return_trace_path[512]{};
char return_history_path[512]{};
char return_mode_path[512]{};
char return_postcard_policy_path[512]{};
char return_status_path[512]{};
char return_candidates_path[512]{};
char return_batch_limit_path[512]{};
char compatibility_path[512]{};
char core_runtime_path[512]{};
char dispatch_candidates_path[512]{};
char dispatch_availability_path[512]{};
char dispatch_status_path[512]{};
char dispatch_mode_path[512]{};
// Optional restriction for armed mode. Missing/unknown means all existing
// kinds remain eligible; the flower-farm controller explicitly writes farm.
char dispatch_kinds_path[512]{};
char nearby_kinds_path[512]{};
char nearby_status_path[512]{};
char nearby_gate_status_path[512]{};
char nearby_engine_capability_path[512]{};
char route_dispatch_diagnostics_path[512]{};
pikmin::NearbyLocationGate nearby_location_gate;
pikmin::RouteLocationGate route_location_gate;
char route_status_path[512]{};
std::string route_gate_token, route_stop_token;
bool route_stop_started{}, route_session_owned{};
void *route_close_task{};
GcHandle route_close_handle{};
pikmin::NearbyFix nearby_fix;
struct NearbySelection {
    std::string kind;
    std::vector<std::string> team;
    long long since{};
    uint64_t location_epoch{};
};
std::map<std::string, NearbySelection> nearby_selections;
pikmin::DispatchBudget nearby_work;
std::map<std::string, std::pair<std::string, long long>> nearby_block_history;
struct RouteDispatchDiagnostics {
    std::string token;
    std::set<std::string> candidates;
    std::set<std::string> evaluated;
    std::set<std::string> selected;
    std::set<std::string> started;
    std::set<std::string> duration_blocked;
    std::set<std::string> recovered_started;
    std::string last_block;
    bool collecting{};

    void begin(const std::string &next_token) {
        collecting = true;
        if (token == next_token) { last_block.clear(); return; }
        token = next_token;
        candidates.clear(); evaluated.clear(); selected.clear(); started.clear();
        duration_blocked.clear(); recovered_started.clear();
        last_block.clear();
    }
    void stop() { collecting = false; }
};
RouteDispatchDiagnostics route_dispatch_diag;
char dispatch_target_path[512]{};
char dispatch_ready_path[512]{};
char dispatch_history_path[512]{};
char dispatch_diagnostics_path[512]{};
// Exact values passed to ExpeditionItemData.SetPikmins. This is a short-lived
// diagnostic capture for comparing the native selection with the game's UI.
char dispatch_selection_diagnostics_path[512]{};
// Forensic log for start_expedition() RPC faults -- see
// append_rpc_fault_diagnostics() and read_task_exception_message() below.
// Kept separate from dispatch_history.tsv so it never touches that file's
// existing column contract (the Java side parses it with a fixed split).
char dispatch_rpc_fault_path[512]{};
// Forensic log for a batch target that fails the dispatch gate without ever
// reaching start_expedition() -- see the "batch target blocked" LOGI site.
// Persisted (not just logged) because a full batch run can take many
// minutes and this needs to be readable after the fact, not only via a
// live logcat capture.
char dispatch_gate_block_path[512]{};
// Per-tick heartbeat while a batch target is set -- see the call site in
// write_dispatch_candidates() and append_dispatch_tick_trace() below.
char dispatch_tick_trace_path[512]{};
// The short-lived single-target projection never replaces the complete list.
char dispatch_target_candidate_path[512]{};
// This is deliberately a separate control plane from nectar collection and
// expedition dispatch. Missing/unknown values mean observe only: the module
// must never stop a planting session that the player started manually.
char planting_control_mode_path[512]{};
char planting_control_status_path[512]{};
// A separate, explicit flower-farm session flag.  It never changes the
// game's speed calculation; it merely allows the native UI-warning hook
// below to suppress its own modal while Control Center is moving a route.
char flower_farm_mode_path[512]{};
std::string dispatch_confirmation_pending_id;
long long dispatch_confirmation_started_observed_ms{};
std::string dispatch_last_gift_skip_id;
// Batch dispatch runs on the game's update thread.  Do not block that thread
// after SetPikmins(); instead, remember the exact target and re-check it on a
// later live inventory tick before StartExpeditionAsync().
constexpr long long kBatchSelectionSettleMs = 1500;
constexpr long long kNearbySelectionSettleMs = 1000;
std::string batch_selection_settling_id;
std::string batch_selection_settling_kind;
long long batch_selection_settling_started_ms{};
int batch_selection_settling_picked_count{};
std::vector<std::string> batch_selection_settling_team;
pikmin::DispatchReservations dispatch_reservations;
std::map<std::string, std::string> last_gift_availability;
// Batch mode deliberately owns one confirmation lock: its Java controller
// must not advance to the next GPS point until this exact task is settled.
// Armed mode is different.  A moving GPS provider can leave nearby targets
// behind before a sequential confirmation finishes, so it may start a small
// bounded group from one live task-list scan.  Keep every one of those calls
// rooted and independently visible until the inventory confirms it.
constexpr size_t kArmedMaxInFlight = 3;
constexpr size_t kArmedMaxStartsPerScan = 3;
struct ArmedDispatchInFlight {
    void *task{};
    GcHandle task_handle{};
    std::string kind;
    long long started_ms{};
    long long ms_since_previous_attempt{-1};
    bool rpc_result_recorded{};
    bool seen_this_scan{};
};
std::map<std::string, ArmedDispatchInFlight> armed_dispatches;
// Batch mode still has only one Task to watch.  This is deliberately not
// shared with armed_dispatches so batch cannot accidentally parallelise.
void *pending_expedition_task{};
GcHandle pending_expedition_task_handle{};
std::string pending_expedition_task_id;
std::string pending_expedition_task_kind;
long long pending_expedition_task_started_ms{};
// When the previous start_expedition() attempt (of any outcome) began, and
// how many faults have landed back-to-back -- both feed
// append_rpc_fault_diagnostics() so a rate-limit or streak pattern in the
// fault rate is visible without guessing at the game's internal reason.
long long last_expedition_attempt_started_ms{};
int consecutive_expedition_rpc_faults{};
long long pending_expedition_ms_since_previous_attempt = -1;
std::string observed_expedition_task_id;
int64_t observed_expedition_duration_ms{};
std::map<std::string, FlowerRecord> flowers;
std::map<std::string, std::string> last_flower_state;
long long last_tick_ms{};
long long last_claim_ms{};
long long last_status_ms{};
long long last_task_list_trace_ms{};
long long last_return_dry_run_ms{};
long long last_return_scheduler_ms{};
long long last_expedition_duration_observed_ms{};
bool test_once_sent{};
bool target_loaded{};
bool return_one_dispatched{};
bool return_one_waiting{};
int return_one_baseline_count{};
bool return_batch_waiting{};
bool return_batch_stopped{};
uint64_t return_batch_pending_epoch{};
std::string return_batch_pending_account;
bool return_all_empty_reported{};
int return_batch_baseline_count{};
int return_batch_completed{};
long long return_batch_dispatched_ms{};
std::string last_return_batch_mode;
std::string return_batch_pending_id;
std::string return_batch_pending_reward;
bool return_reward_variant_metadata_logged[4]{};
bool return_expedition_target_metadata_logged[6]{};
bool return_bloomed_poi_reward_metadata_logged{};
bool return_bloomed_poi_fruit_metadata_logged{};
bool return_bloomed_poi_fruit_entry_metadata_logged{};
bool dispatch_enumerable_metadata_logged{};
bool gift_pikmin_metadata_logged{};
bool gift_target_metadata_logged{};
// First step for the flower-farm controller: enumerate the actual v152
// PlantingController methods once, without invoking any of them.  Starting or
// stopping planting will not be attempted until this passive probe identifies
// the game's real method contract.
bool planting_controller_metadata_logged{};
bool planting_control_owned{};
bool planting_control_start_attempted{};
bool planting_control_stop_attempted{};
std::string planting_control_last_mode = "observe";
std::string planting_control_last_action = "observe";
void *planting_control_pending_task{};
GcHandle planting_control_pending_task_handle{};
void *planting_result_dialog{};
long long planting_result_dialog_seen_ms{};
long long planting_result_stop_requested_ms{};
long long planting_result_close_after_ms{};
bool planting_result_auto_close_pending{};
bool planting_result_hook_installed{};
bool speed_warning_hook_installed{};
// Metadata-only, zero-invocation-risk probe for whether ExpeditionItemData
// exposes a preparation/validation method (Lock*, Prepare*, Validate*, ...)
// that the game's own UI might call before StartExpeditionAsync and that
// this module currently skips -- see the INVALID_ARGUMENT investigation in
// HANDOFF_2026-08-31_RPC_DIAGNOSTICS.md. Logged once per process, from the
// real runtime class via object_get_class() rather than the static
// find_class() lookup, so it reflects the exact instance in play.
bool expedition_item_metadata_logged{};
bool expedition_utils_metadata_logged{};
void *pending_task{};
GcHandle pending_task_handle{};
std::string pending_id;
double pending_gps_lat{};
double pending_gps_lng{};
double pending_distance{};
std::string last_result = "none";

long long now_ms();
long long steady_ms();
long long nearby_elapsed_ms();
std::mutex identity_mutex;
std::string identity_hash;
std::set<std::string> revoked_route_tokens;
bool identity_logout_hook_ready{};
#if defined(PCC_UNIFIED)
// Independent display context; never used for transaction admission.
std::mutex display_context_mutex;
void *display_inventory{};
uint64_t display_inventory_epoch{};
#endif

std::string pending_intent_binding;
nectar_intent::Guard<nectar_intent::FileStore>& nectar_intent_guard() {
    // First use is after start() has initialized status_path.
    static auto *guard = new nectar_intent::Guard<nectar_intent::FileStore>(
            nectar_intent::FileStore(std::string(status_path).substr(
                    0, std::string(status_path).find_last_of('/'))));
    return *guard;
}
bool nectar_unresolved() {
    return !status_path[0] || nectar_intent_guard().blocked();
}
std::string nectar_account_binding() {
    std::lock_guard<std::mutex> lock(identity_mutex);
    return identity_logout_hook_ready ? identity_hash : std::string{};
}
#if defined(PCC_UNIFIED)
void clear_display_inventory() {
    std::lock_guard<std::mutex> lock(display_context_mutex);
    display_inventory=nullptr;display_inventory_epoch=0;
}
void capture_display_inventory(void *inventory,uint64_t epoch) {
    if(!inventory)return;
    capability_registry.capture_current(pcc_cap::cap_preparer,epoch,[&] {
        std::lock_guard<std::mutex> lock(display_context_mutex);
        display_inventory=inventory;display_inventory_epoch=epoch;
    });
}
seed_preview::InsightContext display_context() {
    const auto epoch=capability_registry.current_epoch();
    const auto account=nectar_account_binding();
    if(account.empty())return {};
    void *inventory=nullptr;
    {std::lock_guard<std::mutex> lock(display_context_mutex);
     if(display_inventory_epoch==epoch)inventory=display_inventory;}
    if(!inventory||epoch!=capability_registry.current_epoch()||account!=nectar_account_binding())return {};
    return {account+":"+std::to_string(epoch),inventory};
}
#endif
bool task_mutation_allowed(const std::string &id) {
    const std::string runtime(core_runtime_path);const auto slash=runtime.find_last_of('/');
    return slash!=std::string::npos&&task_exclusion::allowed(runtime.substr(0,slash),nectar_account_binding(),id);
}

char identity_path[512]{};
using LoggedOut = void (*)(void *, void *);
LoggedOut original_logged_out{};
pikmin::RouteLease raw_route_lease() {
    char raw[202]{}; FILE *f=std::fopen("/data/local/tmp/pikmin-route-lease.tsv","r");
    if(!f)return {};size_t n=std::fread(raw,1,201,f);std::fclose(f);
    return pikmin::RouteLease::parse(std::string(raw,n),getpid(),now_ms());
}
pikmin::RouteLease route_lease() {
    auto lease=raw_route_lease();
    std::lock_guard<std::mutex> lock(identity_mutex);
    return identity_logout_hook_ready&&!identity_hash.empty()&&lease.account==identity_hash&&!revoked_route_tokens.count(lease.token)?lease:pikmin::RouteLease{};
}
void write_identity(long long now) {
    std::lock_guard<std::mutex> lock(identity_mutex);
    if(!identity_path[0])return;
    const bool ready=identity_logout_hook_ready&&!identity_hash.empty();
    const std::string tmp=std::string(identity_path)+".tmp";
    if(FILE *f=std::fopen(tmp.c_str(),"w")){
        // Busy is conservative: this producer does not certify all outstanding RPCs idle.
        bool ok=std::fprintf(f,"v1\t%lld\t%d\t%s\t%s\tbusy\tend\n",now,getpid(),ready?identity_hash.c_str():"-",ready?"ready":"unknown")>0;
        bool closed=std::fclose(f)==0;if(ok&&closed&&chmod(tmp.c_str(),0644)==0)std::rename(tmp.c_str(),identity_path);
    }
}
void invalidate_identity() {
    auto lease=raw_route_lease();
    {std::lock_guard<std::mutex> lock(identity_mutex);identity_hash.clear();if(lease.valid())revoked_route_tokens.insert(lease.token);}
    write_identity(now_ms());
}
namespace emu_late { extern bool revoked; void clear(); }
void hooked_logged_out(void *self,void *method) {
    emu_late::revoked=true; emu_late::clear();
    capability_registry.on_logout(); // Diagnostic epoch first: an in-flight scan can no longer commit.
    invalidate_identity(); // Before native logout can enter another account.
    if(original_logged_out)original_logged_out(self,method);
}
bool dispatch_location_ready(bool route) {return route ? route_lease().phase=="walk"&&route_location_gate.ready(nearby_elapsed_ms()) : nearby_location_gate.ready(steady_ms());}
uint64_t dispatch_location_epoch(bool route) {return route ? route_location_gate.epoch() : nearby_location_gate.epoch();}
void update_nearby_location(long long wall);
void *find_class(const char *namespaze, const char *name);
void log_task_variant_metadata(void *proto);
bool current_location(double &latitude, double &longitude);
double distance_metres(double lat1, double lng1, double lat2, double lng2);
std::string read_dispatch_mode();
std::string read_dispatch_kind_filter();
unsigned read_nearby_selection();
std::string read_planting_control_mode();
bool flower_farm_session_active();
std::string read_dispatch_target();
bool dispatch_target_is_ready(const std::string &target_id, long long observed_ms);
void append_dispatch_history(const char *event, const char *kind, const std::string &task_id,
                             int64_t duration_ms, int picked_count);
void append_dispatch_selection_diagnostics(const char *kind, const std::string &task_id,
                                           const char *phase, int picker_count, int id_count,
                                           int64_t duration_ms, bool can_try_start,
                                           bool carrying_power, const std::string &ids);
void log_class_methods(const char *label, void *proto_class);
void append_dispatch_gate_block(const std::string &task_id, const char *kind, bool lock_held,
                                bool requested, bool has_data, bool has_picked, int picked_count,
                                double distance, double allowed_distance);
void append_dispatch_tick_trace(const std::string &batch_target, bool target_seen_raw,
                                int raw_count, int filtered_count);
void maybe_return_tasks();

std::string utf8_string(void *value) {
    if (!value) return {};
    const auto *text = static_cast<Il2CppStringLayout *>(value);
    if (text->length < 1 || text->length > 256) return {};
    std::string result;
    result.reserve(static_cast<size_t>(text->length));
    for (int index = 0; index < text->length; ++index) {
        const char16_t c = text->chars[index];
        if (c < 0x80) result.push_back(static_cast<char>(c));
        else result.push_back('?');
    }
    return result;
}

void append_return_trace(const char *event, void *self, void *task_id) {
    const std::string id = utf8_string(task_id);
    LOGI("[RETURN-DIAG] %s self=%p taskId=%s", event, self, id.c_str());
    static auto *writer = new pikmin::AsyncTsvLog;
    const auto time=now_ms();char pointer[32];std::snprintf(pointer,sizeof(pointer),"%p",self);
    const std::string row=std::to_string(time)+"\t"+event+"\t"+pointer+"\t"+id+"\n";
    if(!writer->append(return_trace_path,row,time))LOGE("[RETURN-LOG] trace queue overflow");
}

// Append-only, controller-visible history. The status file below intentionally
// holds only the latest state; this file is the audit record shown in the APK.
void append_return_history(const char *event, int task_count, int completed,
                           bool waiting, bool discard_postcard) {
    static auto *writer = new pikmin::AsyncTsvLog;
    const auto time=now_ms();
    const std::string row=std::to_string(time)+"\t"+event+"\t"+return_batch_pending_id+"\t"+
        std::to_string(task_count)+"\t"+std::to_string(completed)+"\t"+(waiting?"1":"0")+"\t"+
        (discard_postcard?"discard":"keep")+"\t"+last_return_batch_mode+"\t"+return_batch_pending_reward+"\n";
    if(!writer->append(return_history_path,row,time))LOGE("[RETURN-LOG] history queue overflow");
}

bool task_list_contains_id(void *items, int count, const std::string &target_id) {
    if (!items || target_id.empty()) return false;
    for (int index = 0; index < count; ++index) {
        void *task = *reinterpret_cast<void **>(static_cast<uint8_t *>(items) + 0x20 + index * sizeof(void *));
        if (!task || !get_inventory_item_id) continue;
        void *task_id = get_inventory_item_id(task, nullptr);
        if (utf8_string(task_id) == target_id) return true;
    }
    return false;
}

const char *fruit_type_name(int type) {
    switch (type) {
        case 3: return "strawberry"; case 4: return "orange"; case 5: return "cherry";
        case 6: return "melon"; case 8: return "peach"; case 27: return "lemon";
        case 28: return "blueberry"; case 31: return "watermelon"; case 32: return "lime";
        case 33: return "muscat-one"; case 34: return "kiwi"; case 35: return "green-apple";
        case 36: return "muscat"; case 37: return "kumquat"; case 38: return "banana";
        case 39: return "grapefruit"; case 40: return "apple"; case 41: return "prune";
        case 42: return "grapes"; case 43: return "happy-fruit"; case 44: return "cut-kiwi";
        case 45: return "fig"; case 46: return "loquat"; case 47: return "mangosteen";
        default: return "unknown-fruit";
    }
}

const char *flower_kind_name(int kind) {
    switch (kind) {
        case 1: return "sunflower"; case 2: return "tulip"; case 3: return "pansy";
        case 4: return "rose"; case 5: return "common"; case 6: return "poinsettia";
        case 7: return "camellia"; case 8: return "plumblossom"; case 9: return "narcissus";
        case 10: return "cherryblossom"; case 11: return "nemophila"; case 12: return "carnation";
        case 13: return "callalily"; case 14: return "hydrangea"; case 15: return "lilium";
        case 16: return "hibiscus"; case 17: return "plumeria"; case 18: return "clusteramaryllis";
        case 19: return "cosmos"; case 20: return "cyclamen"; case 21: return "anemone";
        case 22: return "dianthus"; case 23: return "gentian"; case 24: return "chrysanthemum";
        case 25: return "hellebore"; case 26: return "cattleya"; case 27: return "hyacinth";
        case 28: return "sweetpea"; case 29: return "convallaria"; case 30: return "peony";
        case 31: return "nymphaea"; case 32: return "morningglory"; case 33: return "bougainvillea";
        case 34: return "dahlia"; case 35: return "clematis"; case 36: return "snowdrop";
        case 37: return "freesia"; case 38: return "canola"; case 39: return "rose2025";
        case 40: return "iris"; case 41: return "strelitzia"; case 42: return "celosia";
        case 43: return "marigold"; case 44: return "salvia"; case 45: return "primula";
        case 46: return "mothorchid"; case 47: return "snapdragon"; case 48: return "petunia";
        case 49: return "tulip2026"; case 50: return "forgetmenot"; case 51: return "poppy";
        case 52: return "bellflower"; case 53: return "epiphyllum"; case 54: return "canna";
        case 55: return "lisianthus"; case 56: return "thistle"; case 57: return "geranium";
        case 58: return "oxalis"; default: return "unknown-flower";
    }
}

const char *honey_type_name(int type) {
    switch (type) {
        case 1: return "white";
        case 2: return "red";
        case 3: return "blue";
        case 4: return "yellow";
        case 5: return "happy";
        default: return "unknown";
    }
}

// ResourceProto.WeightGrams is task weight, not the awarded nectar amount.
// The actual reward is the repeated HoneyBallProto.NumBalls payload.
std::string describe_resource_honey(void *resource) {
    if (!resource || !get_resource_honey_balls || !get_honey_num_balls || !get_honey_type)
        return {};
    void *repeated = get_resource_honey_balls(resource, nullptr);
    if (!repeated) return {};
    auto *repeated_bytes = static_cast<uint8_t *>(repeated);
    void *items = *reinterpret_cast<void **>(repeated_bytes + 0x10);
    const int count = *reinterpret_cast<int *>(repeated_bytes + 0x18);
    if (!items || count < 1 || count > 16) return {};
    std::string result;
    for (int index = 0; index < count; ++index) {
        void *honey = *reinterpret_cast<void **>(static_cast<uint8_t *>(items) + 0x20 + index * sizeof(void *));
        if (!honey) continue;
        std::string flower = get_honey_flower_kind_text
                ? utf8_string(get_honey_flower_kind_text(honey, nullptr)) : "";
        if (flower.empty() && get_honey_flower_kind) {
            const int kind = get_honey_flower_kind(honey, nullptr);
            if (kind > 0) flower = flower_kind_name(kind);
        }
        if (flower.empty()) flower = "unknown-flower";
        if (!result.empty()) result += ";";
        result += flower;
        result += ":" + std::string(honey_type_name(get_honey_type(honey, nullptr)));
        result += ":" + std::to_string(get_honey_num_balls(honey, nullptr));
    }
    return result;
}

// All reward attributes below are read through v151 public protobuf getters.
// This avoids relying on private object offsets for the direct Fruit target.
std::string describe_resource(void *resource) {
    if (!resource || !get_resource_type || !get_resource_num_pikmins || !get_resource_weight_grams)
        return "unknown-fruit";
    const int type = get_resource_type(resource, nullptr);
    const int pikmins = get_resource_num_pikmins(resource, nullptr);
    std::string flower = get_resource_flower_kind
            ? utf8_string(get_resource_flower_kind(resource, nullptr)) : "";
    if (flower.empty() && get_resource_honey_flower_kind) {
        const int kind = get_resource_honey_flower_kind(resource, nullptr);
        if (kind > 0) flower = flower_kind_name(kind);
    }
    std::string result = std::string(fruit_type_name(type)) + " x" + std::to_string(pikmins);
    const std::string honey = describe_resource_honey(resource);
    if (!honey.empty()) result += " honey=" + honey;
    else if (!flower.empty()) result += " flower=" + flower;
    return result;
}

// Seed and gift targets contain their reward description before completion.
// Record only public protobuf fields so the history can be displayed without
// guessing from the post-completion inventory delta.
std::string describe_seed(void *seed) {
    if (!seed) return "seed:unavailable";
    const int seed_type = get_seed_type ? get_seed_type(seed, nullptr) : 0;
    void *pikmin = get_seed_pikmin ? get_seed_pikmin(seed, nullptr) : nullptr;
    const int pikmin_type = pikmin && get_pikmin_type ? get_pikmin_type(pikmin, nullptr) : 0;
    const int category = pikmin && get_pikmin_category ? get_pikmin_category(pikmin, nullptr) : 0;
    const int asset = pikmin && get_pikmin_full_asset ? get_pikmin_full_asset(pikmin, nullptr) : 0;
    return "seed:" + std::to_string(seed_type) + ":pikmin:" + std::to_string(pikmin_type)
            + ":category:" + std::to_string(category) + ":asset:" + std::to_string(asset);
}

std::string describe_gift(void *gift) {
    if (!gift) return "gift:unavailable";
    const int item_case = get_gift_item_case ? get_gift_item_case(gift, nullptr) : 0;
    void *deco = get_gift_deco ? get_gift_deco(gift, nullptr) : nullptr;
    if (deco) {
        void *pikmin = get_deco_pikmin_type ? get_deco_pikmin_type(deco, nullptr) : nullptr;
        const int pikmin_type = pikmin && get_pikmin_type ? get_pikmin_type(pikmin, nullptr) : 0;
        const int category = pikmin && get_pikmin_category ? get_pikmin_category(pikmin, nullptr) : 0;
        const int box_type = get_deco_box_type ? get_deco_box_type(deco, nullptr) : 0;
        return "gift:deco:pikmin:" + std::to_string(pikmin_type) + ":category:"
                + std::to_string(category) + ":box:" + std::to_string(box_type);
    }
    void *rare_deco = get_gift_rare_deco ? get_gift_rare_deco(gift, nullptr) : nullptr;
    if (rare_deco) {
        const int category = get_rare_deco_category ? get_rare_deco_category(rare_deco, nullptr) : 0;
        return "gift:rare:category:" + std::to_string(category);
    }
    return "gift:itemcase:" + std::to_string(item_case);
}

std::string describe_return_reward(void *task) {
    if (!task || !get_pikmin_task_proto) return "task-reward-unavailable";
    void *proto = get_pikmin_task_proto(task, nullptr);
    if (!proto) return "task-proto-unavailable";
    log_task_variant_metadata(proto);
    void *carry = proto && get_task_carry ? get_task_carry(proto, nullptr) : nullptr;
    if (carry) {
        void *seed = get_carry_pikmin_seed ? get_carry_pikmin_seed(carry, nullptr) : nullptr;
        if (seed) return "carry:" + describe_seed(seed);
        if (get_carry_resource) {
            void *resource = get_carry_resource(carry, nullptr);
            if (resource) {
                const bool mushroom = get_carry_challenge_info && get_carry_challenge_info(carry, nullptr);
                return std::string(mushroom ? "carry:mushroom-fruit:" : "carry:fruit:") + describe_resource(resource);
            }
        }
        return "carry:unknown";
    }
    void *expedition = get_task_expedition ? get_task_expedition(proto, nullptr) : nullptr;
    if (!expedition) {
        if (get_task_gift && get_task_gift(proto, nullptr)) return "gift-task:reward-not-in-task-proto";
        if (get_task_poi_challenge && get_task_poi_challenge(proto, nullptr)) return "poi-challenge:reward-in-completion-response";
        const int task_case = get_task_case ? get_task_case(proto, nullptr) : -1;
        return "taskcase:" + std::to_string(task_case) + ":unparsed";
    }
    if (get_expedition_seed) {
        void *seed = get_expedition_seed(expedition, nullptr);
        if (seed) return "expedition:" + describe_seed(seed);
    }
    if (get_expedition_fruit) {
        void *fruit = get_expedition_fruit(expedition, nullptr);
        if (fruit) return "expedition:fruit:" + describe_resource(fruit);
    }
    if (get_expedition_gift) {
        void *gift = get_expedition_gift(expedition, nullptr);
        if (gift) return "expedition:" + describe_gift(gift);
    }
    if (get_expedition_postcard && get_expedition_postcard(expedition, nullptr))
        return "expedition:postcard";
    if (get_expedition_postcard_with_items && get_expedition_postcard_with_items(expedition, nullptr))
        return "expedition:postcard-with-items:reward-not-in-task-proto";
    if (!get_expedition_bloomed_poi || !get_bloomed_poi_reward_v2 || !get_poi_reward_fruit)
        return "expedition:target-unavailable";
    void *bloomed = get_expedition_bloomed_poi(expedition, nullptr);
    if (!bloomed) {
        const int target_case = get_expedition_target_case ? get_expedition_target_case(expedition, nullptr) : -1;
        return "expedition:targetcase:" + std::to_string(target_case) + ":unparsed";
    }
    void *reward = get_bloomed_poi_reward_v2(bloomed, nullptr);
    void *repeated = reward ? get_poi_reward_fruit(reward, nullptr) : nullptr;
    if (!repeated) return "bloomed-poi:no-fruit";
    auto *repeated_bytes = static_cast<uint8_t *>(repeated);
    void *items = *reinterpret_cast<void **>(repeated_bytes + 0x10);
    const int count = *reinterpret_cast<int *>(repeated_bytes + 0x18);
    if (!items || count < 1 || count > 32) return "bloomed-poi:fruit-list-empty";
    std::string result = "bloomed-poi:";
    for (int index = 0; index < count; ++index) {
        void *resource = *reinterpret_cast<void **>(static_cast<uint8_t *>(items) + 0x20 + index * sizeof(void *));
        if (!resource) continue;
        if (result.size() > 12) result += ",";
        result += describe_resource(resource);
    }
    return result == "bloomed-poi:" ? "bloomed-poi:fruit-list-empty" : result;
}

// Compact status: confirmation requires the exact pending task to disappear
// from a complete live inventory snapshot, not an unrelated count decrease.
void write_return_status(const char *event, int task_count, int completed, bool waiting, bool discard_postcard) {
    FILE *file = std::fopen(return_status_path, "w");
    if (!file) return;
    std::fprintf(file, "%lld\t%s\t%d\t%d\t%d\t%s\n", now_ms(), event, task_count,
                 completed, waiting ? 1 : 0, discard_postcard ? "discard" : "keep");
    std::fclose(file);
    chmod(return_status_path, 0644);
    append_return_history(event, task_count, completed, waiting, discard_postcard);
}

void write_compatibility_status(bool compatible, off_t observed_size) {
    FILE *file = std::fopen(compatibility_path, "w");
    if (!file) return;
    std::fprintf(file, "v157\t%s\t%lld\t%lld\n", compatible ? "compatible" : "incompatible",
                 static_cast<long long>(kExpectedIl2CppSize), static_cast<long long>(observed_size));
    std::fclose(file);
    chmod(compatibility_path, 0644);
}

// Conservative phase-one expedition probe.  It intentionally reuses only
// protobuf accessors that are already exercised by the return-reward engine:
// task Proto, FinishTimeMs, Expedition and TargetCase.  No new game method is
// resolved or hooked here, so the first live validation is limited to safely
// identifying unstarted Seed/Fruit candidates.
int count_enumerable_items(void *enumerable) {
    if (!enumerable || !object_get_class || !class_get_method_from_name) return 0;
    void *enumerable_class = object_get_class(enumerable);
    void *get_enumerator_method = enumerable_class
            ? class_get_method_from_name(enumerable_class, "GetEnumerator", 0) : nullptr;
    // Compiler-generated iterator types implement GetEnumerator explicitly.
    // Resolve it through the non-generic IEnumerable interface so that an
    // iterator in its initial (-2) state is cloned into a usable enumerator.
    if (!get_enumerator_method && object_get_virtual_method) {
        void *ienumerable = find_class("System.Collections", "IEnumerable");
        void *interface_get_enumerator = ienumerable
                ? class_get_method_from_name(ienumerable, "GetEnumerator", 0) : nullptr;
        get_enumerator_method = interface_get_enumerator
                ? object_get_virtual_method(enumerable, interface_get_enumerator) : nullptr;
    }
    auto get_enumerator = get_enumerator_method
            ? reinterpret_cast<GetEnumerable>(*reinterpret_cast<void **>(get_enumerator_method)) : nullptr;
    // Iterator blocks generated by IL2CPP can expose IEnumerator only via an
    // explicit interface implementation, so no public GetEnumerator appears
    // in the concrete class metadata. In that case the returned object itself
    // is already the IEnumerator.
    void *enumerator = get_enumerator ? get_enumerator(enumerable, get_enumerator_method) : enumerable;
    void *enumerator_class = enumerator && object_get_class ? object_get_class(enumerator) : nullptr;
    void *move_next_method = enumerator_class
            ? class_get_method_from_name(enumerator_class, "MoveNext", 0) : nullptr;
    if (!move_next_method && enumerator_class) {
        move_next_method = class_get_method_from_name(enumerator_class,
                "System.Collections.IEnumerator.MoveNext", 0);
    }
    if (!move_next_method && object_get_virtual_method) {
        void *ienumerator = find_class("System.Collections", "IEnumerator");
        void *interface_move_next = ienumerator
                ? class_get_method_from_name(ienumerator, "MoveNext", 0) : nullptr;
        move_next_method = interface_move_next
                ? object_get_virtual_method(enumerator, interface_move_next) : nullptr;
    }
    auto move_next = move_next_method
            ? reinterpret_cast<EnumeratorMoveNext>(*reinterpret_cast<void **>(move_next_method)) : nullptr;
    if (!move_next) {
        LOGI("[DISPATCH-OBSERVE] iterator unresolved object=%p getEnumerator=%p enumerator=%p moveNext=%p",
             enumerable, get_enumerator_method, enumerator, move_next_method);
        return 0;
    }
    int count{};
    while (count < 100 && move_next(enumerator, move_next_method)) ++count;
    LOGI("[DISPATCH-OBSERVE] iterator counted object=%p enumerator=%p count=%d", enumerable, enumerator, count);
    return count;
}

// The game's SetPikmins API accepts IEnumerable<string>.  A managed string[]
// implements that interface, so it avoids fabricating a generic List<T> and
// keeps the hand-off entirely inside the game's managed runtime.
bool wire_field_matches(void *klass, const char *name, size_t expected);

void *verified_v153_method(void *klass, const char *name, int args, uintptr_t expected_rva) {
    // Historical helper name; callers now supply reviewed v157 RVAs.
    void *method = klass ? class_get_method_from_name(klass, name, args) : nullptr;
    void *entry = method ? *reinterpret_cast<void **>(method) : nullptr;
    Dl_info info{};
    if (!entry || !dladdr(entry, &info) ||
        reinterpret_cast<uintptr_t>(entry) - reinterpret_cast<uintptr_t>(info.dli_fbase) != expected_rva) {
        LOGE("[DISPATCH-AVAILABLE] method mismatch %s; availability fails closed", name);
        return nullptr;
    }
    return method;
}

// The exact v153 binary-size gate is installed before metadata initialization.
// For managed calls whose implementation RVA is not hooked directly, resolve
// the class/name/arity from that already-gated runtime instead of carrying a
// stale v152 RVA. The returned entry must still belong to libil2cpp.
void *resolved_v153_method(void *klass, const char *name, int args, uintptr_t) {
    void *method = klass ? class_get_method_from_name(klass, name, args) : nullptr;
    void *entry = method ? *reinterpret_cast<void **>(method) : nullptr;
    Dl_info info{};
    if (!entry || !dladdr(entry, &info) || !info.dli_fbase) {
        LOGE("[NECTAR] v153 method unavailable: %s/%d", name, args);
        return nullptr;
    }
    return method;
}

// Source compatibility for feature fragments that still carry historical
// RVA arguments. They are resolved against the v153-gated runtime above.
void *verified_v152_method(void *klass, const char *name, int args, uintptr_t old_rva) {
    return resolved_v153_method(klass, name, args, old_rva);
}

// Match ExpeditionPikminSelectionTools.GetIsBusyReason before the fastest
// picker. Allows() only applies expedition restrictions, not busy status.
// Use a concrete GetPikminList snapshot: no new generic enumerator calls.
void *available_expedition_pikmins(void *task_key, const std::string &owner,
                                 void *expedition_data, bool is_gift, const std::string &designated,
                                 std::set<std::string> &eligible_ids,
                                 std::map<std::string, int> &statuses,
                                 std::map<std::string, std::string> &assigned_tasks) {
    eligible_ids.clear();
    if (is_gift && (!expedition_data || !gift_allows_method)) return nullptr;
    if (!return_inventory_manager || !available_list_method || !available_proto_method ||
        !available_status_method || !available_restriction_method || !available_clock_instance_method ||
        !available_clock_time_method || !available_pikmin_class || !available_proto_class ||
        !gchandle_new || !gchandle_free || !gc_write_barrier || !array_new) return nullptr;
    auto get_list = reinterpret_cast<GetEnumerable>(*reinterpret_cast<void **>(available_list_method));
    void *list = get_list(return_inventory_manager, available_list_method);
    ScopedManagedRoot list_root(list, gchandle_new, gchandle_free);
    if (!list || !list_root.handle) return nullptr;
    void *klass = object_get_class(list);
    if (!wire_field_matches(klass, "_items", 0x10) || !wire_field_matches(klass, "_size", 0x18)) return nullptr;
    auto *bytes = static_cast<uint8_t *>(list);
    void *storage = *reinterpret_cast<void **>(bytes + 0x10);
    const int count = *reinterpret_cast<int *>(bytes + 0x18);
    if (!storage || count < 0 || count > 10000) return nullptr;
    const size_t capacity = *reinterpret_cast<size_t *>(static_cast<uint8_t *>(storage) + 0x18);
    if (capacity < static_cast<size_t>(count) || capacity > 16384) return nullptr;
    auto get_clock = reinterpret_cast<StaticNoArgTask>(*reinterpret_cast<void **>(available_clock_instance_method));
    void *clock = get_clock(available_clock_instance_method);
    if (!clock) return nullptr;
    auto get_time = reinterpret_cast<GetTaskLong>(*reinterpret_cast<void **>(available_clock_time_method));
    const int64_t time_ms = get_time(clock, available_clock_time_method);
    if (time_ms <= 0) return nullptr;
    auto get_proto = reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(available_proto_method));
    auto get_status = reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(available_status_method));
    auto restrict_actions = reinterpret_cast<RestrictPikminActions>(*reinterpret_cast<void **>(available_restriction_method));
    auto **items = reinterpret_cast<void **>(static_cast<uint8_t *>(storage) + 0x20);
    std::vector<void *> eligible;
    int busy{}, restricted{}, reserved{}, gift_filtered{};
    for (int index = 0; index < count; ++index) {
        void *pikmin = items[index];
        if (!pikmin || object_get_class(pikmin) != available_pikmin_class) return nullptr;
        void *proto = get_proto(pikmin, available_proto_method);
        if (!proto || object_get_class(proto) != available_proto_class) return nullptr;
        const int status = get_status(proto, available_status_method);
        const std::string id = utf8_string(get_inventory_item_id ? get_inventory_item_id(pikmin, nullptr) : nullptr);
        if (id.empty() || statuses.count(id)) return nullptr;
        statuses[id] = status;
        // This is the live projected inventory, not claimed as server state.
        if (status == 2 && wire_field_matches(available_proto_class, "status_", 0xC8)) {
            void *assigned = *reinterpret_cast<void **>(static_cast<uint8_t *>(proto) + 0xC8);
            if (assigned && wire_field_matches(object_get_class(assigned), "taskId_", 0x18))
                assigned_tasks[id] = utf8_string(*reinterpret_cast<void **>(static_cast<uint8_t *>(assigned) + 0x18));
        }
        dispatch_reservations.observe(id, status);
        // v152 PikminProto.StatusOneofCase: Available=1, Entourage=32.
        if (!pikmin::dispatch_status_available(status)) { ++busy; continue; }
        if (restrict_actions(proto, time_ms, task_key, available_restriction_method)) { ++restricted; continue; }
        if (!dispatch_reservations.permits(id, owner)) { ++reserved; continue; }
        if (is_gift) {
            // The fastest-team helper must receive an already eligible candidate set.
            // Never let an unrelated idle Pikmin compete for a personal gift.
            if (designated.empty() || id != designated) { ++gift_filtered; continue; }
            using AllowsPikmin = bool (*)(void *, void *, void *);
            auto allows = reinterpret_cast<AllowsPikmin>(*reinterpret_cast<void **>(gift_allows_method));
            if (!pikmin::gift_candidate_allowed(designated, id,
                    allows(expedition_data, pikmin, gift_allows_method))) { ++gift_filtered; continue; }
        }
        eligible.push_back(pikmin);
        eligible_ids.insert(id);
    }
    void *array = array_new(available_pikmin_class, eligible.size());
    if (!array) return nullptr;
    ScopedManagedRoot array_root(array, gchandle_new, gchandle_free);
    if (!array_root.handle) return nullptr;
    auto **values = reinterpret_cast<void **>(static_cast<uint8_t *>(array) + 0x20);
    for (size_t index = 0; index < eligible.size(); ++index) gc_write_barrier(array, &values[index], eligible[index]);
    LOGI("[DISPATCH-AVAILABLE] task=%s total=%d eligible=%zu busy=%d restricted=%d reserved=%d held=%zu giftFiltered=%d",
         utf8_string(task_key).c_str(), count, eligible.size(), busy, restricted, reserved, dispatch_reservations.size(), gift_filtered);
    return array;
}

void *picked_pikmin_ids_array(void *enumerable, std::string *selected_ids, int *selected_count) {
    if (selected_ids) selected_ids->clear();
    if (selected_count) *selected_count = 0;
    if (!enumerable || !object_get_class || !class_get_method_from_name ||
        !object_get_virtual_method || !get_inventory_item_id || !array_new) return nullptr;
    void *ienumerable = find_class("System.Collections", "IEnumerable");
    void *ienumerator = find_class("System.Collections", "IEnumerator");
    void *string_class = find_class("System", "String");
    void *get_method = ienumerable ? class_get_method_from_name(ienumerable, "GetEnumerator", 0) : nullptr;
    void *move_method = ienumerator ? class_get_method_from_name(ienumerator, "MoveNext", 0) : nullptr;
    void *current_method = ienumerator ? class_get_method_from_name(ienumerator, "get_Current", 0) : nullptr;
    void *get_impl = get_method ? object_get_virtual_method(enumerable, get_method) : nullptr;
    auto get_enumerator = get_impl ? reinterpret_cast<GetEnumerable>(*reinterpret_cast<void **>(get_impl)) : nullptr;
    void *enumerator = get_enumerator ? get_enumerator(enumerable, get_impl) : nullptr;
    ScopedManagedRoot enumerator_root(enumerator, gchandle_new, gchandle_free);
    void *move_impl = enumerator && move_method ? object_get_virtual_method(enumerator, move_method) : nullptr;
    void *current_impl = enumerator && current_method ? object_get_virtual_method(enumerator, current_method) : nullptr;
    auto move_next = move_impl ? reinterpret_cast<EnumeratorMoveNext>(*reinterpret_cast<void **>(move_impl)) : nullptr;
    auto current = current_impl ? reinterpret_cast<EnumeratorCurrent>(*reinterpret_cast<void **>(current_impl)) : nullptr;
    if (!enumerator || !enumerator_root.handle || !move_next || !current || !string_class || !gc_write_barrier) {
        LOGI("[DISPATCH-OBSERVE] ids unresolved enumerable=%p enumerator=%p get=%p move=%p current=%p",
             enumerable, enumerator, get_impl, move_impl, current_impl);
        return nullptr;
    }
    std::vector<void *> ids;
    // Generic-shared v152 iterators require the resolved implementation's
    // MethodInfo (rgctx). WhereEnumerableIterator<object>.MoveNext reads it
    // at +0x20; passing null crashed the 1.4.10 selected-team recheck.
    while (ids.size() < 100 && move_next(enumerator, move_impl)) {
        void *pikmin = current(enumerator, current_impl);
        void *id = pikmin ? get_inventory_item_id(pikmin, nullptr) : nullptr;
        if (id) ids.push_back(id);
    }
    if (ids.empty()) return nullptr;
    std::string ids_for_diagnostics;
    for (size_t index = 0; index < ids.size(); ++index) {
        if (index != 0) ids_for_diagnostics.push_back(',');
        const std::string id_text = utf8_string(ids[index]);
        ids_for_diagnostics.append(id_text.empty() ? "<empty>" : id_text);
    }
    void *array = array_new(string_class, ids.size());
    if (pikmin::dispatch_ids(ids_for_diagnostics).size() != ids.size()) return nullptr;
    if (!array) return nullptr;
    auto **values = reinterpret_cast<void **>(static_cast<uint8_t *>(array) + 0x20);
    for (size_t index = 0; index < ids.size(); ++index) gc_write_barrier(array, &values[index], ids[index]);
    if (selected_ids) *selected_ids = ids_for_diagnostics;
    if (selected_count) *selected_count = static_cast<int>(ids.size());
    LOGI("[DISPATCH-OBSERVE] selected %zu Pikmin ids array=%p ids=%s", ids.size(), array,
         ids_for_diagnostics.c_str());
    return array;
}

bool team_is_eligible(const std::vector<std::string> &team, const std::set<std::string> &eligible) {
    if (team.empty()) return false;
    for (const auto &id : team) if (!eligible.count(id)) return false;
    return true;
}

bool selected_team_matches(void *data, const std::vector<std::string> &expected) {
    if (!data || !selected_pikmins_method || expected.empty()) return false;
    auto getter = reinterpret_cast<GetEnumerable>(*reinterpret_cast<void **>(selected_pikmins_method));
    void *selected = getter(data, selected_pikmins_method);
    ScopedManagedRoot root(selected, gchandle_new, gchandle_free);
    if (!root.handle) return false;
    std::string actual;
    int count{};
    void *ids = picked_pikmin_ids_array(selected, &actual, &count);
    return ids && count == static_cast<int>(expected.size()) &&
            pikmin::same_dispatch_team(expected, pikmin::dispatch_ids(actual));
}

std::string gift_designated_pikmin(void *expedition) {
    if (!expedition || !wire_field_matches(object_get_class(expedition), "restriction_", 0x58)) return {};
    void *restriction = *reinterpret_cast<void **>(static_cast<uint8_t *>(expedition) + 0x58);
    if (!restriction || !wire_field_matches(object_get_class(restriction), "condition_", 0x18) ||
        !wire_field_matches(object_get_class(restriction), "conditionCase_", 0x20)) return {};
    // v152 RestrictionProto.ConditionOneofCase.AllowedPikminId = 1.
    if (*reinterpret_cast<int *>(static_cast<uint8_t *>(restriction) + 0x20) != 1) return {};
    void *id = *reinterpret_cast<void **>(static_cast<uint8_t *>(restriction) + 0x18);
    if (!id || !dispatch_string_class || object_get_class(id) != dispatch_string_class) return {};
    return utf8_string(id);
}

void log_dispatch_enumerable_metadata(void *object, const char *label) {
    if (!object || !object_get_class || !class_get_methods || !method_get_name || dispatch_enumerable_metadata_logged) return;
    dispatch_enumerable_metadata_logged = true;
    void *klass = object_get_class(object);
    LOGI("[DISPATCH-META] %s object=%p class=%s", label, object,
         klass && class_get_name ? class_get_name(klass) : "?");
    void *iter{};
    for (int count{}; klass && count < 80 && (iter = class_get_methods(klass, &iter)); ++count) {
        const char *name = method_get_name(iter);
        LOGI("[DISPATCH-META] method=%s params=%u", name ? name : "?",
             method_get_param_count ? method_get_param_count(iter) : 0);
    }
}

void nearby_block(const char *phase, const char *kind, const std::string &id, int64_t duration, int picked) {
    if (route_dispatch_diag.collecting && phase) {
        route_dispatch_diag.last_block = phase;
        if (std::strcmp(phase, "nearby-duration-blocked") == 0 && !id.empty())
            route_dispatch_diag.duration_blocked.insert(id);
    }
    const long long now = now_ms();
    auto &last = nearby_block_history[id];
    if (last.first != phase || now-last.second >= 10000) {
        append_dispatch_history(phase, kind, id, duration, picked);
        last = {phase, now};
    }
}

void publish_route_dispatch_diagnostics(long long observed_ms, const pikmin::RouteLease &lease,
                                        bool gate_ready, int selections_applied, int starts_requested) {
    if (!lease.valid() || lease.token.empty() || route_dispatch_diagnostics_path[0] == '\0') return;
    std::string reason;
    if (lease.phase != "walk") reason = "lease-not-walk";
    else if (!gate_ready) reason = route_location_gate.reason();
    else if (route_dispatch_diag.candidates.empty()) reason = "no-candidates";
    else if (starts_requested > 0) reason = "start-requested";
    else if (selections_applied > 0 || !nearby_selections.empty()) reason = "selection-settling";
    else if (!route_dispatch_diag.last_block.empty()) reason = route_dispatch_diag.last_block;
    else if (route_dispatch_diag.evaluated.empty()) reason = "waiting-next-scan";
    else reason = "no-start";
    const std::string tmp = std::string(route_dispatch_diagnostics_path) + ".tmp";
    FILE *f = std::fopen(tmp.c_str(), "w");
    if (!f) return;
    const bool ok = std::fprintf(f, "v2\t%lld\t%d\t%s\t%d\t%zu\t%zu\t%zu\t%zu\t%s"
            "\t%u\t%u\t%u\t%u\t%u\t%zu\t%zu\tend\n",
            observed_ms, getpid(), lease.token.c_str(), gate_ready ? 1 : 0,
            route_dispatch_diag.candidates.size(), route_dispatch_diag.evaluated.size(),
            route_dispatch_diag.selected.size(), route_dispatch_diag.started.size(), reason.c_str(),
            route_location_gate.catchup_events(), route_location_gate.over_40_samples(),
            route_location_gate.over_80_events(), route_location_gate.stale_events(),
            route_location_gate.recoveries(), route_dispatch_diag.recovered_started.size(),
            route_dispatch_diag.duration_blocked.size()) > 0;
    const bool closed = std::fclose(f) == 0;
    if (ok && closed && chmod(tmp.c_str(), 0644) == 0)
        std::rename(tmp.c_str(), route_dispatch_diagnostics_path);
}

void submit_armed_expedition(void *data, void *task, const std::string &id, const char *kind,
                             int64_t duration, int picked) {
    if(!task_mutation_allowed(id))return;
    const long long attempt_now = now_ms();
    pending_expedition_ms_since_previous_attempt = last_expedition_attempt_started_ms > 0
            ? attempt_now-last_expedition_attempt_started_ms : -1;
    last_expedition_attempt_started_ms = attempt_now;
    dispatch_reservations.sent(id, attempt_now);
    module_start_call = true;
    void *result = start_expedition(data, nullptr);
    module_start_call = false;
    ArmedDispatchInFlight flight{};
    flight.task = result;
    flight.task_handle = result && gchandle_new ? gchandle_new(result, false) : 0;
    flight.kind = kind; flight.started_ms = now_ms();
    flight.ms_since_previous_attempt = pending_expedition_ms_since_previous_attempt;
    flight.seen_this_scan = true;
    armed_dispatches.emplace(id, std::move(flight));
    append_dispatch_history("start-requested", kind, id, duration, picked);
    if (route_dispatch_diag.collecting && route_location_gate.recovered_window())
        route_dispatch_diag.recovered_started.insert(id);
    LOGI("[DISPATCH] start requested task=%s duration=%" PRId64 " picked=%d result=%p canStartAfter=%d finishAfter=%" PRId64,
            id.c_str(), duration, picked, result,
            get_expedition_can_try_start ? get_expedition_can_try_start(data, nullptr) : false,
            get_task_finish_time_ms ? get_task_finish_time_ms(task, nullptr) : 0);
}

// Worker callbacks touch only owned bytes, POSIX files and the synchronized diagnostic registry.
static pcc_projection::Worker &dispatch_projection_worker() {
    static pcc_projection::Worker worker(
        [](const pcc_projection::Snapshot &s) {
            const auto current=capability_registry.begin_scan();
            return s.pid==getpid()&&s.token.valid&&current.valid&&current.epoch==s.token.epoch;
        },
        [](const pcc_projection::Snapshot &s) {
            if(!s.target) {
                emu_atomic_projection::Writer output(dispatch_candidates_path,s.observed_ms);
                FILE *file=output.stream();
                return file&&std::fwrite(s.bytes.data(),1,s.bytes.size(),file)==s.bytes.size()&&output.commit();
            }
            pcc_projection::Writer output(s.target?dispatch_target_candidate_path:dispatch_candidates_path,s.observed_ms);
            FILE *file=output.stream();
            return file&&std::fwrite(s.bytes.data(),1,s.bytes.size(),file)==s.bytes.size()&&output.commit();
        },
        [](const pcc_projection::Snapshot &s) {
            return capability_registry.commit_scan(s.token,s.observed_ms,s.count,s.complete);
        });
    return worker;
}
struct DispatchProjectionTiming {
    long long began{steady_ms()},queued{};int64_t enqueue_us{};size_t bytes{};bool target{},accepted{};
    ~DispatchProjectionTiming() {
        static long long last{};const auto now=steady_ms();if(now-last<5000)return;last=now;
        auto &worker=dispatch_projection_worker();
        LOGI("[DISPATCH-PROJECTION] target=%d accepted=%d captureMs=%lld enqueueUs=%lld tailMs=%lld bytes=%zu queueWaitUs=%lld publishUs=%lld coalesced=%llu failed=%llu stale=%llu",
            target?1:0,accepted?1:0,(queued?queued:now)-began,static_cast<long long>(enqueue_us),queued?now-queued:0,bytes,
            static_cast<long long>(worker.queue_wait_us.load()),static_cast<long long>(worker.publish_us.load()),
            static_cast<unsigned long long>(worker.coalesced.load()),static_cast<unsigned long long>(worker.failed.load()),
            static_cast<unsigned long long>(worker.stale.load()));
    }
};
static void enqueue_dispatch_projection(pcc_projection::Rows &rows,pcc_cap::ScanToken token,long long observed,
        int count,bool complete,bool target,DispatchProjectionTiming &timing) {
    timing.queued=steady_ms();timing.bytes=rows.bytes.size();
    const auto begin=std::chrono::steady_clock::now();
    if(rows.valid)timing.accepted=dispatch_projection_worker().submit({std::move(rows.bytes),token,getpid(),observed,count,complete,target});
    timing.enqueue_us=std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-begin).count();
}
void write_dispatch_candidates(void *list, long long observed_ms, pcc_cap::ScanToken scan_token,
                               bool target_only = false) {
    if (!list || !get_pikmin_task_proto || !get_task_finish_time_ms ||
        !get_task_expedition || !get_expedition_target_case) return;
    auto *bytes = static_cast<uint8_t *>(list);
    void *items = *reinterpret_cast<void **>(bytes + 0x10);
    const int count = *reinterpret_cast<int *>(bytes + 0x18);
    if (!items || count < 0 || count > kMaxPikminTaskListCount) return;
    const std::string dispatch_mode = read_dispatch_mode();
    const bool route = dispatch_mode == "route";
    const bool armed = dispatch_mode == "armed" || route;
    const bool batch = dispatch_mode == "batch";
    const std::string batch_target = batch ? read_dispatch_target() : "";
    if (target_only && (!batch || batch_target.empty())) return;
    pcc_projection::Rows projection;
    DispatchProjectionTiming projection_timing;projection_timing.target=target_only;
    std::string availability_rows;
    // Read-only capability tied to this live process and projection, not module.prop.
    if (!target_only) {
        const std::string candidates_file(dispatch_candidates_path);
        const std::string area_path = candidates_file.substr(0, candidates_file.find_last_of('/') + 1)
                + "batch_area_capability.tsv";
        const std::string area_tmp = area_path + ".tmp";
        if (FILE *ack = std::fopen(area_tmp.c_str(), "w")) {
            const bool written = std::fprintf(ack, "v1\t%lld\t%d\t100\t2000\tend\n", now_ms(), getpid()) > 0;
            const bool closed = std::fclose(ack) == 0;
            if (written && closed && chmod(area_tmp.c_str(), 0644) == 0)
                std::rename(area_tmp.c_str(), area_path.c_str());
        }
    }
    int availability_count{};
    double current_latitude{}, current_longitude{};
    const bool has_current_location = current_location(current_latitude, current_longitude);
    const std::string armed_kind_filter = route ? "farm" : armed ? read_dispatch_kind_filter() : "all";
    const bool nearby = route || (armed && armed_kind_filter != "farm");
    if (nearby) update_nearby_location(now_ms());
    const bool nearby_ready = dispatch_location_ready(route);
    const auto route_diag_lease = route ? route_lease() : pikmin::RouteLease{};
    if (route && route_diag_lease.valid()) route_dispatch_diag.begin(route_diag_lease.token);
    else route_dispatch_diag.stop();
    for (auto it = nearby_selections.begin(); it != nearby_selections.end();) {
        if (!nearby || !nearby_ready || it->second.location_epoch != dispatch_location_epoch(route)
                || steady_ms()-it->second.since > 20000) {
            nearby_block("nearby-selection-reset", it->second.kind.c_str(), it->first, 0, it->second.team.size());
            dispatch_reservations.cancel(it->first);
            it = nearby_selections.erase(it);
        } else ++it;
    }
    const unsigned nearby_mask = read_nearby_selection();
    const std::string nearby_tmp = std::string(nearby_status_path) + ".tmp";
    if (!target_only) if (FILE *ack = std::fopen(nearby_tmp.c_str(), "w")) {
        std::fprintf(ack, "v1\t%lld\t%d\t%u\t%s\t%s\n", now_ms(), getpid(),
                     nearby_mask, dispatch_mode.c_str(), armed_kind_filter.c_str());
        const bool ok = std::fclose(ack) == 0;
        if (ok && chmod(nearby_tmp.c_str(), 0644) == 0)
            std::rename(nearby_tmp.c_str(), nearby_status_path);
    }
    // A controller stop is an explicit safety release.  Do not let a failed
    // or timed-out prior batch leave an in-memory confirmation lock that
    // blocks every later, independently armed batch.
    if (!armed && !batch && !dispatch_confirmation_pending_id.empty()) {
        dispatch_confirmation_pending_id.clear();
        dispatch_confirmation_started_observed_ms = 0;
    }
    // Turning armed mode off is an explicit controller release.  Do not keep
    // stale in-memory ids across a later, independent armed run.  The rooted
    // managed Tasks are intentionally not freed here: freeing a just-finished
    // Task has raced an IL2CPP continuation on v150.
    if (!armed && !armed_dispatches.empty()) armed_dispatches.clear();
    const bool batch_ready = batch && !batch_target.empty() &&
            dispatch_target_is_ready(batch_target, observed_ms);
    if (!batch || batch_selection_settling_id != batch_target) {
        dispatch_reservations.cancel(batch_selection_settling_id);
        batch_selection_settling_team.clear();
        batch_selection_settling_id.clear();
        batch_selection_settling_kind.clear();
        batch_selection_settling_started_ms = 0;
        batch_selection_settling_picked_count = 0;
    }
    const bool confirmation_due = !dispatch_confirmation_pending_id.empty() &&
            dispatch_confirmation_started_observed_ms < observed_ms;
    int candidates{};
    int expedition_tasks{};
    int finished_expedition_tasks{};
    int direct_seed_targets{}, direct_fruit_targets{}, gift_targets{}, bloomed_poi_targets{}, other_targets{};
    std::map<int, int> target_cases;
    std::map<int, int> unfinished_target_cases;
    int selections_applied{};
    int starts_requested{};
    bool start_requested{};
    bool pending_confirmation_seen{};
    // Whether this tick's raw list (before any of the continue-filters
    // below) contained the batch's named target at all -- see the tick
    // heartbeat appended after the loop. Distinguishes "the native tick
    // loop stopped running" from "it ran, but this candidate was
    // temporarily missing from what the game handed back", which look
    // identical from the Java side (both show as a silent stretch ending
    // in game-refresh-timeout).
    bool batch_target_seen_raw{};
    std::set<std::string> live_task_ids;
    bool complete_task_projection = true;
    const auto work_begin = steady_ms();
    nearby_work.begin(work_begin);
    struct WorkItem { int index; bool pending; int64_t checked; double distance; };
    std::vector<WorkItem> work_order;
    for (int i=0; i<count; ++i) {
        void *t=*reinterpret_cast<void **>(static_cast<uint8_t *>(items)+0x20+i*sizeof(void *));
        const auto key=t&&get_inventory_item_id?utf8_string(get_inventory_item_id(t,nullptr)):std::string{};
        if (target_only && key != batch_target) continue;
        const auto hint=nearby_work.hints.find(key);
        double metres=0;
        if (nearby && nearby_fix.valid && t) {
            void *p=get_pikmin_task_proto(t,nullptr);
            void *e=p?get_task_expedition(p,nullptr):nullptr;
            void *s=e&&get_expedition_spawn_point?get_expedition_spawn_point(e,nullptr):nullptr;
            if(s&&get_point_lat_degrees&&get_point_lng_degrees)
                metres=distance_metres(nearby_fix.game_lat,nearby_fix.game_lng,get_point_lat_degrees(s,nullptr),get_point_lng_degrees(s,nullptr));
        }
        work_order.push_back({i,nearby_selections.count(key)!=0,
                hint==nearby_work.hints.end()?0:hint->second.checked,metres});
    }
    if(nearby) std::stable_sort(work_order.begin(),work_order.end(),[&](const WorkItem &a,const WorkItem &b){
        if(a.pending!=b.pending)return a.pending;
        // Old/unseen candidates cannot be starved by an endless nearby stream.
        const bool old_a=work_begin-a.checked>=5000,old_b=work_begin-b.checked>=5000;
        if(old_a!=old_b)return old_a;
        if(old_a&&a.checked!=b.checked)return a.checked<b.checked;
        return a.distance<b.distance;
    });
    if (!target_only) for (auto &entry : armed_dispatches) entry.second.seen_this_scan = false;
    for (const auto &work_item : work_order) {
        const int index=work_item.index;
        void *task = *reinterpret_cast<void **>(static_cast<uint8_t *>(items) + 0x20 + index * sizeof(void *));
        void *proto = task ? get_pikmin_task_proto(task, nullptr) : nullptr;
        if (!task || !proto) complete_task_projection = false;
        void *expedition = proto ? get_task_expedition(proto, nullptr) : nullptr;
        const int target_case = expedition ? get_expedition_target_case(expedition, nullptr) : 0;
        const int64_t finish_ms = proto ? get_task_finish_time_ms(proto, nullptr) : 0;
        void *seed_target = expedition && get_expedition_seed ? get_expedition_seed(expedition, nullptr) : nullptr;
        void *fruit_target = expedition && get_expedition_fruit ? get_expedition_fruit(expedition, nullptr) : nullptr;
        void *bloomed_poi_target = expedition && get_expedition_bloomed_poi ? get_expedition_bloomed_poi(expedition, nullptr) : nullptr;
        void *gift_target = expedition && get_expedition_gift ? get_expedition_gift(expedition, nullptr) : nullptr;
        if (expedition) {
            ++expedition_tasks;
            ++target_cases[target_case];
            if (finish_ms != 0) ++finished_expedition_tasks;
            else ++unfinished_target_cases[target_case];
            if (seed_target) ++direct_seed_targets;
            else if (fruit_target) ++direct_fruit_targets;
            else if (get_expedition_gift && get_expedition_gift(expedition, nullptr)) ++gift_targets;
            else if (get_expedition_bloomed_poi && get_expedition_bloomed_poi(expedition, nullptr)) ++bloomed_poi_targets;
            else ++other_targets;
        }
        // The visible fruit list contains both direct Fruit targets and
        // BloomedPoi targets whose reward is a fruit collection.
        const char *kind = seed_target ? "seed" : (fruit_target || bloomed_poi_target ? "fruit" : (gift_target ? "gift" : nullptr));
        if (!task || !expedition || !kind) continue;
        void *id = get_inventory_item_id ? get_inventory_item_id(task, nullptr) : nullptr;
        const std::string id_text = utf8_string(id);
        if (batch && !batch_target.empty() && id_text == batch_target) batch_target_seen_raw = true;
        if (!id_text.empty()) live_task_ids.insert(id_text);
        else complete_task_projection = false;
        if (!target_only && confirmation_due && id_text == dispatch_confirmation_pending_id)
            pending_confirmation_seen = true;
        auto armed_inflight = armed_dispatches.find(id_text);
        if (!target_only && armed_inflight != armed_dispatches.end()) armed_inflight->second.seen_this_scan = true;
        if (finish_ms != 0) {
            if (!target_only && confirmation_due && id_text == dispatch_confirmation_pending_id) {
                append_dispatch_history("inventory-confirmed", kind, id_text, 0, 0);
                dispatch_confirmation_pending_id.clear();
                dispatch_confirmation_started_observed_ms = 0;
            }
            if (!target_only && armed_inflight != armed_dispatches.end()) {
                append_dispatch_history("inventory-confirmed", kind, id_text, 0, 0);
                armed_dispatches.erase(armed_inflight);
            }
            continue;
        }
        if (route && (std::strcmp(kind, "seed") == 0 || std::strcmp(kind, "fruit") == 0) && !id_text.empty())
            route_dispatch_diag.candidates.insert(id_text);
        const int64_t expiration_ms = get_expedition_expiration_time_ms
                ? get_expedition_expiration_time_ms(expedition, nullptr) : 0;
        void *point = get_expedition_spawn_point ? get_expedition_spawn_point(expedition, nullptr) : nullptr;
        const double latitude = point && get_point_lat_degrees ? get_point_lat_degrees(point, nullptr) : 0.0;
        const double longitude = point && get_point_lng_degrees ? get_point_lng_degrees(point, nullptr) : 0.0;
        void *finish_point = proto && get_task_finish_location ? get_task_finish_location(proto, nullptr) : nullptr;
        const double finish_latitude = finish_point && get_point_lat_degrees
                ? get_point_lat_degrees(finish_point, nullptr) : 0.0;
        const double finish_longitude = finish_point && get_point_lng_degrees
                ? get_point_lng_degrees(finish_point, nullptr) : 0.0;
        const double distance = nearby
                ? (nearby_fix.valid && point ? distance_metres(nearby_fix.game_lat, nearby_fix.game_lng, latitude, longitude) : -1.0)
                : (has_current_location && (latitude != 0.0 || longitude != 0.0)
                    ? distance_metres(current_latitude, current_longitude, latitude, longitude) : -1.0);
        if(!task_mutation_allowed(id_text)) {
            projection.append("%lld\t%s\t%s\t0\t%" PRId64 "\t%.7f\t%.7f\tprior-admission-unresolved\t%.1f\t0\t0\t0\n",
                observed_ms,kind,id_text.c_str(),expiration_ms,latitude,longitude,distance);
            ++candidates;continue;
        }
        if (nearby) {
            const auto hint=nearby_work.hints.find(id_text);
            const double moved=hint==nearby_work.hints.end()?0:distance_metres(
                    hint->second.lat,hint->second.lng,nearby_fix.game_lat,nearby_fix.game_lng);
            const bool pending=nearby_selections.count(id_text)!=0;
            const bool allowed=pikmin::nearby_kind_allowed(armed_kind_filter,nearby_mask,kind);
            if (!allowed || !nearby_ready || !nearby_work.capacity(steady_ms()) ||
                    !nearby_work.due(id_text,steady_ms(),moved,pending)) {
                // Complete fresh task projection, never cached start permission.
                projection.append("%lld\t%s\t%s\t0\t%" PRId64 "\t%.7f\t%.7f\tpending-travel-estimate\t%.1f\t%" PRId64 "\t0\t%d\n",
                        observed_ms,kind,id_text.c_str(),expiration_ms,latitude,longitude,distance,
                        hint==nearby_work.hints.end()?int64_t{0}:hint->second.duration,
                        hint==nearby_work.hints.end()?0:hint->second.picked);
                ++candidates;
                continue;
            }
            ++nearby_work.used;
            if (route && !id_text.empty()) route_dispatch_diag.evaluated.insert(id_text);
        }
        void *data = expedition_data_store && get_expedition_data_by_index
                ? get_expedition_data_by_index(expedition_data_store, 0, id, nullptr) : nullptr;
        if (data && !expedition_item_metadata_logged && object_get_class) {
            expedition_item_metadata_logged = true;
            log_class_methods("ExpeditionItemData", object_get_class(data));
        }
        // Force a recompute of this item's cached fields (duration,
        // CanTryStart, carrying power, ...) before reading any of them.
        // Candidate fix for the INVALID_ARGUMENT investigation: after a GPS
        // teleport, these caches may only refresh when the game's own UI
        // flow touches them, which this module's tighter
        // selection-to-start window otherwise skips.
        if (data && invalidate_expedition_cached_values) invalidate_expedition_cached_values(data, nullptr);
        int64_t game_duration = data && original_get_expedition_total_duration_ms
                ? original_get_expedition_total_duration_ms(data, nullptr) : 0;
        bool can_start = data && get_expedition_can_try_start
                ? get_expedition_can_try_start(data, nullptr) : false;
        if (game_duration <= 0 && id_text == observed_expedition_task_id) game_duration = observed_expedition_duration_ms;
        const bool is_gift = std::strcmp(kind, "gift") == 0;
        const std::string designated = is_gift ? gift_designated_pikmin(expedition) : std::string{};
        void *utils = data ? *reinterpret_cast<void **>(static_cast<uint8_t *>(data) + 0x48) : nullptr;
        void *scope = data ? *reinterpret_cast<void **>(static_cast<uint8_t *>(data) + 0x98) : nullptr;
        if (utils && !expedition_utils_metadata_logged && object_get_class) {
            expedition_utils_metadata_logged = true;
            log_class_methods("ExpeditionUtilsInstance", object_get_class(utils));
        }
        std::set<std::string> eligible_ids;
        std::map<std::string, int> pikmin_statuses;
        std::map<std::string, std::string> pikmin_assigned_tasks;
        void *task_key = get_expedition_item_key && data ? get_expedition_item_key(data, nullptr) : nullptr;
        void *pikmins = available_expedition_pikmins(task_key, id_text, data, is_gift, designated, eligible_ids,
                pikmin_statuses, pikmin_assigned_tasks);
        ScopedManagedRoot available_root(pikmins, gchandle_new, gchandle_free);
        const char *preflight = pikmin::preflight_reason(pikmins && available_root.handle && task_key && data,
                is_gift, id_text, designated, pikmin_statuses, pikmin_assigned_tasks, eligible_ids.size());
        const auto owner_state = pikmin_statuses.find(designated);
        const auto owner_assignment = pikmin_assigned_tasks.find(designated);
        availability_rows += id_text + "\t" + kind + "\t" + preflight + "\t" + designated + "\t" +
                (owner_assignment == pikmin_assigned_tasks.end() ? "" : owner_assignment->second) + "\t" +
                std::to_string(owner_state == pikmin_statuses.end() ? -1 : owner_state->second) + "\t" +
                std::to_string(eligible_ids.size()) + "\n";
        ++availability_count;
        void *picked = utils && pick_fastest_pikmins && pikmins && scope
                ? pick_fastest_pikmins(utils, data, pikmins, scope, nullptr) : nullptr;
        ScopedManagedRoot picked_root(picked, gchandle_new, gchandle_free);
        log_dispatch_enumerable_metadata(picked ? picked : pikmins, picked ? "picked" : "pikminCollection");
        int picked_count = count_enumerable_items(picked);
        bool gift_pikmin_unavailable = is_gift && (!picked || picked_count <= 0);
        if (is_gift) {
            const std::string &owner = designated;
            std::string picker_ids;
            int picker_id_count{};
            void *gift_ids = picked_pikmin_ids_array(picked, &picker_ids, &picker_id_count);
            const bool identity_matches = gift_ids && !owner.empty() && picker_id_count == 1 && picker_ids == owner;
            const bool owner_eligible = pikmins && eligible_ids.count(owner);
            gift_pikmin_unavailable = !identity_matches || !owner_eligible;
            const int owner_status = pikmin_statuses.count(owner) ? pikmin_statuses.at(owner) : -1;
            const std::string evidence = "owner=" + owner + ";projectedStatus=" + std::to_string(owner_status) +
                    ";assignedTask=" + pikmin_assigned_tasks[owner] + ";picker=" + picker_ids +
                    ";identity=" + (identity_matches ? "match" : "unknown-or-mismatch") +
                    ";eligible=" + (owner_eligible ? "1" : "0");
            if (last_gift_availability[id_text] != evidence) {
                append_dispatch_selection_diagnostics(kind, id_text, "gift-owner-readonly", picked_count,
                        picker_id_count, game_duration, false, false, evidence);
                last_gift_availability[id_text] = evidence;
            }
            if (gift_pikmin_unavailable) { picked = nullptr; picked_count = 0; can_start = false; }
        }
        if (batch_ready && id_text == batch_target && is_gift) {
            if (gift_target && !gift_target_metadata_logged && object_get_class) {
                gift_target_metadata_logged = true;
                log_class_methods("GiftTarget", object_get_class(gift_target));
            }
            LOGI("[GIFT-DIAG] task=%s player=%.7f,%.7f spawn=%.7f,%.7f finish=%.7f,%.7f duration=%" PRId64 " canStart=%d picked=%d",
                 id_text.c_str(), current_latitude, current_longitude, latitude, longitude,
                 finish_latitude, finish_longitude, game_duration, can_start ? 1 : 0, picked_count);
            LOGI("[GIFT-DIAG] task=%s gamePickerCount=%d; designated owner and native Allows filtered before picker",
                 id_text.c_str(), picked_count);
        }
        // Armed mode may start a bounded group of nearby tasks from this one
        // live scan. Batch mode is stricter: the Control Center must name this
        // exact task and prove a fresh arrival gate before any game
        // API is invoked. The native side rechecks the live game location.
        const bool armed_kind_allowed = pikmin::nearby_kind_allowed(armed_kind_filter, nearby_mask, kind);
        const bool requested = (armed && armed_kind_allowed) || (batch_ready && id_text == batch_target);
        // Control Center's own arrival gate already requires this same
        // distance column within 4 m (agreeing with the provider) for two
        // consecutive fresh scans before it ever authorises a batch dispatch,
        // so 4 m here does not reject anything the controller would not have
        // rejected itself -- it just makes the native side fail closed on
        // distance too, instead of trusting the controller's word for it.
        // Only trustworthy since current_location() now fails closed on a
        // stale fallback file (see kSystemGpsMaxAgeSeconds); at the old 25 m
        // this same reading was once 111 m wrong for over an hour.
        // Ordinary armed: selected-team <=300s on a later live update, no radius cap.
        // Keep a valid processed-game distance, and retain farm/batch distance gates.
        const double allowed_distance = batch ? (std::strcmp(kind, "gift") == 0 ? 4.0 : 100.0) : 200.0;
        const bool armed_capacity_available = armed &&
                !dispatch_reservations.has_sent(id_text) &&
                armed_dispatches.size() < kArmedMaxInFlight &&
                starts_requested < static_cast<int>(kArmedMaxStartsPerScan) &&
                armed_inflight == armed_dispatches.end() &&
                (!nearby || (nearby_ready && armed_dispatches.size()+nearby_selections.size() < kArmedMaxInFlight));
        const bool batch_capacity_available = batch && dispatch_confirmation_pending_id.empty();
        const bool batch_selection_pending = batch && !batch_selection_settling_id.empty();
        auto nearby_pending = nearby_selections.find(id_text);
        if (nearby && nearby_pending != nearby_selections.end()) {
            const auto selection = nearby_pending->second;
            if (steady_ms()-selection.since >= kNearbySelectionSettleMs) {
                const int64_t live_duration = data && original_get_expedition_total_duration_ms
                        ? original_get_expedition_total_duration_ms(data, nullptr) : 0;
                const bool same_team = data && team_is_eligible(selection.team, eligible_ids)
                        && selected_team_matches(data, selection.team);
                const bool power = data && get_expedition_has_enough_carrying_power
                        && get_expedition_has_enough_carrying_power(data, nullptr);
                update_nearby_location(now_ms());
                const double live_distance = nearby_fix.valid && point
                        ? distance_metres(nearby_fix.game_lat, nearby_fix.game_lng, latitude, longitude) : -1;
                const bool safe = requested && can_start && power && same_team && !gift_pikmin_unavailable
                        && pikmin::nearby_duration_safe(live_duration) && start_expedition
                        && dispatch_location_ready(route) && selection.location_epoch == dispatch_location_epoch(route)
                        && pikmin::dispatch_distance_allowed(true, false, live_distance) && read_dispatch_mode() == dispatch_mode
                        && (route || read_dispatch_kind_filter() == armed_kind_filter)
                        && pikmin::nearby_kind_allowed(armed_kind_filter, read_nearby_selection(), kind)
                        && starts_requested < static_cast<int>(kArmedMaxStartsPerScan)
                        && armed_dispatches.size() < kArmedMaxInFlight;
                if (safe) {
                    append_dispatch_selection_diagnostics(kind, id_text, "nearby-pre-start-settled", selection.team.size(),
                            selection.team.size(), live_duration, can_start, power, "");
                    submit_armed_expedition(data, task, id_text, kind, live_duration, selection.team.size());
                    if (route) route_dispatch_diag.started.insert(id_text);
                    ++starts_requested; start_requested = true;
                } else nearby_block(!pikmin::nearby_duration_safe(live_duration) ? "nearby-duration-blocked"
                        : "nearby-start-blocked", kind, id_text, live_duration, selection.team.size());
                dispatch_reservations.cancel(id_text);
                nearby_selections.erase(nearby_pending);
            }
        } else if (batch_selection_pending) {
            if (id_text == batch_selection_settling_id &&
                observed_ms - batch_selection_settling_started_ms >= kBatchSelectionSettleMs) {
                const bool carrying_power_settled = get_expedition_has_enough_carrying_power
                        ? get_expedition_has_enough_carrying_power(data, nullptr) : true;
                const bool same_team_available = pikmins && team_is_eligible(batch_selection_settling_team, eligible_ids) &&
                        selected_team_matches(data, batch_selection_settling_team);
                // Read the actual selected team's fresh native duration. No
                // observer fallback and no use of this tick's proposed picker.
                const int64_t live_duration = data && original_get_expedition_total_duration_ms
                        ? original_get_expedition_total_duration_ms(data, nullptr) : 0;
                game_duration = live_duration;
                const bool settled_gate = requested && data && can_start && carrying_power_settled &&
                        same_team_available && !gift_pikmin_unavailable && pikmin::batch_duration_safe(live_duration) &&
                        start_expedition && distance >= 0.0 && distance <= allowed_distance;
                if (settled_gate) {
                    if(!task_mutation_allowed(id_text))continue;
                    append_dispatch_selection_diagnostics(kind, id_text, "pre-start-settled",
                                                          batch_selection_settling_picked_count, 0,
                                                          game_duration, can_start,
                                                          carrying_power_settled, "");
                    const long long attempt_now = now_ms();
                    pending_expedition_ms_since_previous_attempt = last_expedition_attempt_started_ms > 0
                            ? attempt_now - last_expedition_attempt_started_ms : -1;
                    last_expedition_attempt_started_ms = attempt_now;
                    dispatch_reservations.sent(id_text, attempt_now);
                    module_start_call = true;
                    void *start_result = start_expedition(data, nullptr);
                    module_start_call = false;
                    const bool can_start_after = get_expedition_can_try_start
                            ? get_expedition_can_try_start(data, nullptr) : false;
                    const int64_t finish_after = get_task_finish_time_ms
                            ? get_task_finish_time_ms(task, nullptr) : 0;
                    start_requested = true;
                    ++starts_requested;
                    dispatch_confirmation_pending_id = id_text;
                    dispatch_confirmation_started_observed_ms = observed_ms;
                    if (start_result && gchandle_new) {
                        pending_expedition_task = start_result;
                        pending_expedition_task_handle = gchandle_new(start_result, false);
                        pending_expedition_task_id = id_text;
                        pending_expedition_task_kind = kind;
                        pending_expedition_task_started_ms = now_ms();
                    }
                    append_dispatch_history("start-requested", kind, id_text, game_duration,
                                            batch_selection_settling_picked_count);
                    LOGI("[DISPATCH] settled start task=%s waitedMs=%lld duration=%" PRId64
                         " picked=%d result=%p canStartAfter=%d finishAfter=%" PRId64,
                         id_text.c_str(), observed_ms - batch_selection_settling_started_ms,
                         game_duration, batch_selection_settling_picked_count, start_result,
                         can_start_after ? 1 : 0, finish_after);
                } else {
                    append_dispatch_history(!same_team_available ? "selected-team-unavailable" :
                                            (!pikmin::batch_duration_safe(live_duration) ? "batch-duration-blocked" : "selection-settle-blocked"), kind, id_text,
                                            game_duration, batch_selection_settling_picked_count);
                    LOGI("[DISPATCH] settled start blocked task=%s canStart=%d carrying=%d distance=%.2f",
                         id_text.c_str(), can_start ? 1 : 0, carrying_power_settled ? 1 : 0, distance);
                }
                dispatch_reservations.cancel(batch_selection_settling_id);
                batch_selection_settling_team.clear();
                batch_selection_settling_id.clear();
                batch_selection_settling_kind.clear();
                batch_selection_settling_started_ms = 0;
                batch_selection_settling_picked_count = 0;
            }
        } else if ((armed_capacity_available || batch_capacity_available) && requested && data && picked &&
            picked_count > 0 && set_expedition_pikmins &&
            pikmin::dispatch_distance_allowed(nearby, batch, distance) && (!batch || distance <= allowed_distance)) {
            // Gifts reach the picker only after designated-ID and native
            // Allows checks. Never substitute an unrelated idle Pikmin.
            std::string selected_ids;
            int selected_id_count{};
            void *ids = picked_pikmin_ids_array(picked, &selected_ids, &selected_id_count);
            ScopedManagedRoot ids_root(ids, gchandle_new, gchandle_free);
            const auto selected_team = pikmin::dispatch_ids(selected_ids);
            if (ids && ids_root.handle && selected_id_count == picked_count &&
                team_is_eligible(selected_team, eligible_ids) && dispatch_reservations.reserve(id_text, selected_team)) {
                set_expedition_pikmins(data, ids, nullptr);
                game_duration = original_get_expedition_total_duration_ms
                        ? original_get_expedition_total_duration_ms(data, nullptr) : game_duration;
                can_start = get_expedition_can_try_start
                        ? get_expedition_can_try_start(data, nullptr) : can_start;
                const bool carrying_power_after = get_expedition_has_enough_carrying_power
                        ? get_expedition_has_enough_carrying_power(data, nullptr) : true;
                append_dispatch_selection_diagnostics(kind, id_text, "post-set", picked_count,
                                                      selected_id_count, game_duration, can_start,
                                                      carrying_power_after, selected_ids);
                ++selections_applied;
                if (route) route_dispatch_diag.selected.insert(id_text);
                LOGI("[DISPATCH-OBSERVE] armed selection task=%s duration=%" PRId64 " canStart=%d picked=%d carryingPower=%d",
                     id_text.c_str(), game_duration, can_start ? 1 : 0, picked_count, carrying_power_after ? 1 : 0);
                // Ordinary nearby also waits for a later native tick; farm
                // retains its existing controller-owned arrival/dwell flow.
                gift_pikmin_unavailable = is_gift && !can_start;
                if (can_start && carrying_power_after && start_expedition && selected_team_matches(data, selected_team)) {
                    if (batch) {
                        // Do not call StartExpeditionAsync in the same update
                        // turn as SetPikmins. The next live inventory tick
                        // re-checks all native gates before it can start.
                        batch_selection_settling_id = id_text;
                        batch_selection_settling_kind = kind;
                        batch_selection_settling_started_ms = observed_ms;
                        batch_selection_settling_picked_count = picked_count;
                        batch_selection_settling_team = selected_team;
                        append_dispatch_history("selection-settling", kind, id_text,
                                                game_duration, picked_count);
                        LOGI("[DISPATCH] selection settling task=%s waitMs=%lld picked=%d",
                             id_text.c_str(), kBatchSelectionSettleMs, picked_count);
                    } else if (nearby) {
                        if (original_get_expedition_total_duration_ms && pikmin::nearby_duration_safe(game_duration)
                                && get_expedition_has_enough_carrying_power) {
                            nearby_selections.emplace(id_text, NearbySelection{kind, selected_team, steady_ms(), dispatch_location_epoch(route)});
                            append_dispatch_history("nearby-selection-settling", kind, id_text, game_duration, picked_count);
                        } else nearby_block("nearby-duration-blocked", kind, id_text, game_duration, picked_count);
                    } else {
                        submit_armed_expedition(data, task, id_text, kind, game_duration, picked_count);
                        start_requested = true; ++starts_requested;
                    }
                } else if (batch_ready && id_text == batch_target && gift_pikmin_unavailable &&
                           dispatch_last_gift_skip_id != id_text) {
                    // The designated ID exists, but the game rejected it (for
                    // example that Pikmin is busy). Never substitute another.
                    append_dispatch_history("gift-pikmin-unavailable", kind, id_text,
                                            game_duration, picked_count);
                    dispatch_last_gift_skip_id = id_text;
                }
                if ((!batch || batch_selection_settling_id != id_text) && !nearby_selections.count(id_text)) dispatch_reservations.cancel(id_text);
            } else {
                append_dispatch_history("selected-team-unavailable", kind, id_text, game_duration, picked_count);
            }
        } else if (batch_ready && id_text == batch_target) {
            // The batch's own named target failed the dispatch gate this
            // tick without ever reaching start_expedition() -- Java-side
            // this is invisible and eventually times out as
            // game-refresh-timeout, indistinguishable from an RPC fault
            // without this. Breaks down every sub-condition of the big `if`
            // above so a game_refresh_timeout can be told apart from an
            // actual INVALID_ARGUMENT fault: is it the confirmation lock
            // still held, the team picker returning nothing/zero, or the
            // native distance reading disagreeing with the 4 m batch gate
            // Control Center already thought it satisfied?
            const bool lock_held = !dispatch_confirmation_pending_id.empty();
            LOGI("[DISPATCH-GATE] batch target blocked task=%s lockHeld=%d requested=%d data=%p picked=%p pickedCount=%d distance=%.2fm allowed=%.1fm",
                 id_text.c_str(), lock_held ? 1 : 0, requested ? 1 : 0,
                 data, picked, picked_count, distance, allowed_distance);
            append_dispatch_gate_block(id_text, kind, lock_held, requested, data != nullptr,
                                       picked != nullptr, picked_count, distance, allowed_distance);
            if (gift_pikmin_unavailable && dispatch_last_gift_skip_id != id_text) {
                append_dispatch_history("gift-pikmin-unavailable", kind, id_text,
                                        game_duration, picked_count);
                dispatch_last_gift_skip_id = id_text;
            }
        }
        if(nearby) nearby_work.hints[id_text]={steady_ms(),game_duration,nearby_fix.game_lat,nearby_fix.game_lng,picked_count};
        projection.append("%lld\t%s\t%s\t0\t%" PRId64 "\t%.7f\t%.7f\tpending-travel-estimate\t%.1f\t%" PRId64 "\t%d\t%d\n", observed_ms,
                     kind, id_text.c_str(),
                     expiration_ms, latitude, longitude, distance, game_duration, can_start ? 1 : 0, picked_count);
        ++candidates;
    }
    // Tick heartbeat, batch mode only: proves this observation tick actually
    // ran (vs. the whole native tick loop having silently stopped, e.g. the
    // same underlying cause already found for "掃描" returning 0
    // candidates) and separately whether the batch's named target was even
    // present in the raw list this tick (vs. present but blocked -- see the
    // "batch target blocked" log above). A long stretch of no new lines
    // here during a game-refresh-timeout means the tick loop itself
    // stalled; lines present but batchTargetSeen=0 means the target
    // transiently vanished from what the game handed back.
    if (batch && !batch_target.empty() && !target_only) {
        append_dispatch_tick_trace(batch_target, batch_target_seen_raw, count, candidates);
    }
    if (target_only) {
        // This is only one named task, never a complete inventory projection.
        // Do not publish capability scan counts, reconcile reservations, or
        // confirm an absent task from this partial view.
        enqueue_dispatch_projection(projection,scan_token,observed_ms,count,false,true,projection_timing);
        return;
    }
    // GetPikminTaskList is the live inventory projection. Once a task that was
    // just submitted is absent from a later complete projection, record that
    // separately from the request rather than pretending the async call alone
    // was confirmation.
    if (confirmation_due && !dispatch_confirmation_pending_id.empty() && !pending_confirmation_seen) {
        append_dispatch_history("inventory-confirmed-absent", "unknown",
                                dispatch_confirmation_pending_id, 0, 0);
        dispatch_confirmation_pending_id.clear();
        dispatch_confirmation_started_observed_ms = 0;
    }
    // A complete task-list projection that no longer contains an armed call
    // is the same inventory confirmation used by batch mode, but evaluated
    // independently for every concurrent armed start.
    for (auto it = armed_dispatches.begin(); it != armed_dispatches.end();) {
        if (!it->second.seen_this_scan) {
            append_dispatch_history("inventory-confirmed-absent", it->second.kind.c_str(),
                                    it->first, 0, 0);
            it = armed_dispatches.erase(it);
        } else {
            ++it;
        }
    }
    if (complete_task_projection) {
        nearby_work.prune(live_task_ids);
        dispatch_reservations.reconcile_tasks(live_task_ids, observed_ms);
        for (auto it = nearby_selections.begin(); it != nearby_selections.end();) {
            if (!live_task_ids.count(it->first)) {
                dispatch_reservations.cancel(it->first);
                it = nearby_selections.erase(it);
            } else ++it;
        }
        for (auto it = nearby_block_history.begin(); it != nearby_block_history.end();) {
            if (!live_task_ids.count(it->first)) it = nearby_block_history.erase(it); else ++it;
        }
    }
    // CAP scan freshness is committed only by the worker after successful complete publication.
    enqueue_dispatch_projection(projection,scan_token,observed_ms,count,complete_task_projection,false,projection_timing);
    if (route) publish_route_dispatch_diagnostics(observed_ms, route_diag_lease, nearby_ready,
                                                  selections_applied, starts_requested);
    if(nearby) {
        static int64_t last_perf{};
        if(steady_ms()-last_perf>=5000){
            last_perf=steady_ms();
            LOGI("[DISPATCH-PERF] candidates=%d evaluated=%d elapsedMs=%lld",candidates,nearby_work.used,
                    static_cast<long long>(steady_ms()-work_begin));
        }
    }
    // Separate versioned advisory file keeps the existing 12-column contract.
    // Publish only complete files; readers reject stale/PID-mismatched data.
    const std::string availability_tmp = std::string(dispatch_availability_path) + ".tmp";
    FILE *availability = std::fopen(availability_tmp.c_str(), "w");
    if (availability) {
        if (!complete_task_projection) { availability_rows.clear(); availability_count = 0; }
        std::fprintf(availability, "v1\t%lld\t%d\n%send\t%d\n", observed_ms,
                static_cast<int>(getpid()), availability_rows.c_str(), availability_count);
        const bool written = std::fflush(availability) == 0 && !std::ferror(availability) &&
                fchmod(fileno(availability), 0644) == 0;
        const bool closed = std::fclose(availability) == 0;
        if (written && closed) std::rename(availability_tmp.c_str(), dispatch_availability_path);
    }
    FILE *status = std::fopen(dispatch_status_path, "w");
    if (!status) return;
    std::fprintf(status, "%lld\t%s\t%d\t%s\n", observed_ms,
                 armed ? "armed" : (batch ? "batch" : "observed"), candidates,
                 start_requested ? "start-requested" :
                 (selections_applied > 0 ? "selection-applied" :
                  (batch && !batch_ready ? "awaiting-arrival-gate" : "no-dispatch")));
    std::fclose(status);
    chmod(dispatch_status_path, 0644);
    FILE *diagnostics = std::fopen(dispatch_diagnostics_path, "w");
    if (!diagnostics) return;
    std::fprintf(diagnostics, "observed=%lld\texpeditions=%d\tfinish_nonzero=%d\tcandidates=%d\n",
                 observed_ms, expedition_tasks, finished_expedition_tasks, candidates);
    std::fprintf(diagnostics, "targets\tseed=%d\tfruit=%d\tgift=%d\tbloomed_poi=%d\tother=%d\n",
                 direct_seed_targets, direct_fruit_targets, gift_targets, bloomed_poi_targets, other_targets);
    for (const auto &entry : target_cases) {
        const auto unfinished = unfinished_target_cases.find(entry.first);
        std::fprintf(diagnostics, "target_case=%d\ttotal=%d\tfinish_zero=%d\n", entry.first,
                     entry.second, unfinished == unfinished_target_cases.end() ? 0 : unfinished->second);
    }
    std::fclose(diagnostics);
    chmod(dispatch_diagnostics_path, 0644);
}

void *hooked_complete_pikmin_task(void *self, void *task_id, bool discard_postcard, void *method_info) {
    append_return_trace(discard_postcard ? "manual-discard-postcard" : "manual-keep-postcard", self, task_id);
    return original_complete_pikmin_task
            ? original_complete_pikmin_task(self, task_id, discard_postcard, method_info) : nullptr;
}

// The preparer receives the game's own collection of returned task ids before
// a reward is claimed.  This trace is deliberately passive: it establishes a
// version-safe source for background work without reading guessed HashSet
// layouts or emitting any RPCs.
void *hooked_prepare_pikmin_tasks(void *self, void *task_ids, void *method_info) {
    LOGI("[RETURN-DIAG] prepare-pending self=%p taskIds=%p", self, task_ids);
    return original_prepare_pikmin_tasks
            ? original_prepare_pikmin_tasks(self, task_ids, method_info) : nullptr;
}

// This method is the game's own readiness gate.  It lets us capture only task
// ids that the client itself considers completable, rather than probing every
// expedition or inferring state from UI objects.
bool hooked_should_prepare_completion(void *self, void *task, void *method_info) {
    const bool ready = original_should_prepare_completion
            && original_should_prepare_completion(self, task, method_info);
    if (!ready || !task || !get_inventory_item_id) return ready;
    void *task_id = get_inventory_item_id(task, nullptr);
    append_return_trace("ready-task", self, task_id);
    return ready;
}

// The expedition page reads its cards from this inventory list.  Keep this
// diagnostic read-only until both its current layout and the game's readiness
// predicate have been observed on-device.
void *hooked_get_pikmin_task_list(void *self, void *method_info) {
    void *result = original_get_pikmin_task_list
            ? original_get_pikmin_task_list(self, method_info) : nullptr;
    const long long now = now_ms();
    if (!result || !get_inventory_item_id || !get_pikmin_task_proto || !get_task_finish_time_ms ||
        now - last_task_list_trace_ms < 5000) return result;
    last_task_list_trace_ms = now;

    // System.Collections.Generic.List<T>: _items at +0x10, _size at +0x18;
    // managed arrays store their first element at +0x20 on arm64 IL2CPP.
    auto *bytes = static_cast<uint8_t *>(result);
    void *items = *reinterpret_cast<void **>(bytes + 0x10);
    const int count = *reinterpret_cast<int *>(bytes + 0x18);
    if (!items || count < 0 || count > kMaxPikminTaskListCount) {
        LOGE("[RETURN-DIAG] task-list invalid self=%p list=%p count=%d", self, result, count);
        return result;
    }
    LOGI("[RETURN-DIAG] task-list self=%p count=%d", self, count);
    for (int index = 0; index < count; ++index) {
        void *task = *reinterpret_cast<void **>(static_cast<uint8_t *>(items) + 0x20 + index * sizeof(void *));
        if (!task) continue;
        void *id = get_inventory_item_id(task, nullptr);
        void *proto = get_pikmin_task_proto(task, nullptr);
        const int64_t finish_ms = proto ? get_task_finish_time_ms(proto, nullptr) : 0;
        LOGI("[RETURN-DIAG] task-list-item id=%s finishMs=%" PRId64 " due=%d",
             utf8_string(id).c_str(), finish_ms, finish_ms > 0 && finish_ms <= now ? 1 : 0);
    }
    return result;
}

// This constructor is a one-time Zenject injection point, not a UI rendering
// path.  It provides the exact live manager instances used by the game.
void hooked_pikmin_task_preparer_constructor(void *self, void *inventory, void *rpc,
                                             void *server_clock, void *method_info) {
    const uint64_t epoch_at_entry = capability_registry.current_epoch();   // before original / managed reads
    if (original_pikmin_task_preparer_constructor) {
        original_pikmin_task_preparer_constructor(self, inventory, rpc, server_clock, method_info);
    }
    return_preparer = self;
    return_inventory_manager = inventory;
    if (self && inventory) capability_registry.capture(pcc_cap::cap_preparer, epoch_at_entry);
    if (self && inventory) {
#if defined(PCC_UNIFIED)
        capture_display_inventory(inventory,epoch_at_entry);
#endif
    }
    LOGI("[RETURN-DIAG] preparer captured self=%p inventory=%p rpc=%p clock=%p",
         self, inventory, rpc, server_clock);
}

// Capture the injected manager from BlockerService initialization. The v152
// action-manager constructor is only RET (4 bytes); a far inline hook there
// overwrites the adjacent CompletePikminTaskAsync.MoveNext body.
void *action_manager_capture_field{};
void hooked_blocker_service_initialize(void *self, void *method_info) {
    const uint64_t epoch_at_entry = capability_registry.current_epoch();   // before original / managed reads
    if (original_blocker_service_initialize) {
        original_blocker_service_initialize(self, method_info);
    }
    void *manager = nullptr;
    if (self && action_manager_capture_field && field_get_value)
        field_get_value(self, action_manager_capture_field, &manager);
    if (manager) {
        return_action_manager = manager;
        capability_registry.capture(pcc_cap::cap_action_manager, epoch_at_entry);
    }
    LOGI("[RETURN-DIAG] injected action manager captured=%d", manager != nullptr);
}

// The store owns every live expedition data item, including entries whose UI
// page has never been opened.  Capturing it lets the background scanner use
// the game's own data object for each inventory task.
void hooked_expedition_data_store_constructor(void *self, void *method_info) {
    if (original_expedition_data_store_constructor) {
        original_expedition_data_store_constructor(self, method_info);
    }
    expedition_data_store = self;
    LOGI("[DISPATCH-OBSERVE] expedition data store captured self=%p", self);
}

// The picker evaluates this after selecting its fastest valid Pikmin.  By
// observing it we can use the game's own round-trip calculation, rather than
// converting map distance to a guessed travel time.  This hook is read-only.
int64_t hooked_get_expedition_total_duration_ms(void *self, void *method_info) {
    const int64_t duration = original_get_expedition_total_duration_ms
            ? original_get_expedition_total_duration_ms(self, method_info) : 0;
    const long long current = now_ms();
    if (self && duration > 0 && current - last_expedition_duration_observed_ms >= 1000) {
        last_expedition_duration_observed_ms = current;
        void *key = get_expedition_item_key ? get_expedition_item_key(self, nullptr) : nullptr;
        observed_expedition_task_id = utf8_string(key);
        observed_expedition_duration_ms = duration;
        LOGI("[DISPATCH-OBSERVE] picker task=%s totalDurationMs=%" PRId64,
             observed_expedition_task_id.c_str(), duration);
    }
    return duration;
}

// Runs on the game's main update thread.  It asks the game's own readiness
// predicate about each live inventory task and only writes diagnostics.
bool complete_return_list(void *items, int count) {
    if (!items || count < 0 || count > kMaxPikminTaskListCount || !get_inventory_item_id ||
        !get_pikmin_task_proto) return false;
    const auto capacity = *reinterpret_cast<uintptr_t *>(static_cast<uint8_t *>(items) + 0x18);
    if (capacity < static_cast<uintptr_t>(count)) return false;
    for (int i = 0; i < count; ++i) {
        void *task = *reinterpret_cast<void **>(static_cast<uint8_t *>(items) + 0x20 + i * sizeof(void *));
        if (!task || utf8_string(get_inventory_item_id(task, nullptr)).empty() ||
            !get_pikmin_task_proto(task, nullptr)) return false;
    }
    return true;
}

bool return_proto_ready(void *proto, long long now) {
    if (!proto || !get_task_finish_time_ms) return false;
    const auto finish = get_task_finish_time_ms(proto, nullptr);
    const int task_case = get_task_case ? get_task_case(proto, nullptr) : -1;
    const int requirement = get_task_completion_requirement
            ? get_task_completion_requirement(proto, task_completion_requirement_method) : -1;
    void *carry = task_case == 1 && get_task_carry ? get_task_carry(proto, nullptr) : nullptr;
    const bool resource = carry && get_carry_resource && get_carry_resource(carry, nullptr);
    return pikmin::return_task_ready(task_case, requirement, resource, finish, now);
}

// Read-only snapshot also runs in off/dry-run. No new hooks or generic iterators.
void write_return_candidates(void *items, int count, long long now) {
    if (!complete_return_list(items, count)) return;
    const std::string temporary = std::string(return_candidates_path) + ".tmp";
    FILE *file = std::fopen(temporary.c_str(), "w");
    if (!file) return;
    std::fprintf(file, "v1\t%lld\t%d\n", now, getpid());
    for (int i = 0; i < count; ++i) {
        void *task = *reinterpret_cast<void **>(static_cast<uint8_t *>(items) + 0x20 + i * sizeof(void *));
        void *proto = get_pikmin_task_proto(task, nullptr);
        const auto id = utf8_string(get_inventory_item_id(task, nullptr));
        const int requirement = get_task_completion_requirement
                ? get_task_completion_requirement(proto, task_completion_requirement_method) : -1;
        const auto detail = describe_return_reward(task);
        std::fprintf(file, "%s\t%d\t%d\t%lld\t%d\t%s\n", id.c_str(),
                get_task_case ? get_task_case(proto, nullptr) : -1, requirement,
                static_cast<long long>(get_task_finish_time_ms ? get_task_finish_time_ms(proto, nullptr) : 0),
                return_proto_ready(proto, now) ? 1 : 0, detail.c_str());
    }
    std::fprintf(file, "end\t%d\n", count);
    const bool ok = std::fflush(file) == 0 && !std::ferror(file);
    const bool closed = std::fclose(file) == 0;
    if (ok && closed && chmod(temporary.c_str(), 0644) == 0)
        std::rename(temporary.c_str(), return_candidates_path);
}

void dry_run_return_tasks() {
    const long long now = now_ms();
    static long long last_dispatch_scan_ms{};
    static long long last_batch_target_scan_ms{};
    const std::string mode = read_dispatch_mode();
    const bool walking = mode == "route" || (mode == "armed" && read_dispatch_kind_filter() != "farm");
    const bool has_batch_target = mode == "batch" && !read_dispatch_target().empty();
    const auto scan_scope = pikmin::DispatchScanCadence::choose(now, last_dispatch_scan_ms,
            last_batch_target_scan_ms, walking, has_batch_target);
    const bool dispatch_due = scan_scope != pikmin::DispatchScanScope::none;
    const bool return_due = now-last_return_dry_run_ms >= 5000;
    if (!dispatch_due && !return_due) return;
    if (scan_scope == pikmin::DispatchScanScope::full) last_dispatch_scan_ms = now;
    if (dispatch_due) last_batch_target_scan_ms = now;
    if (return_due) last_return_dry_run_ms = now;
    if (!return_preparer || !return_inventory_manager || !original_get_pikmin_task_list ||
        !original_should_prepare_completion || !get_inventory_item_id) {
        LOGI("[RETURN-DIAG] dry-run waiting preparer=%p inventory=%p taskList=%p shouldPrepare=%p getId=%p",
             return_preparer, return_inventory_manager,
             reinterpret_cast<void *>(original_get_pikmin_task_list),
             reinterpret_cast<void *>(original_should_prepare_completion),
             reinterpret_cast<void *>(get_inventory_item_id));
        return;
    }
    const pcc_cap::ScanToken scan_token = capability_registry.begin_scan();   // before any game data is read
    const long long scan_started_ms = steady_ms();
    void *list = original_get_pikmin_task_list(return_inventory_manager, nullptr);
    if (!list) {
        LOGI("[RETURN-DIAG] dry-run task-list null inventory=%p", return_inventory_manager);
        return;
    }
    if (dispatch_due) {
        const bool target_only = scan_scope == pikmin::DispatchScanScope::batch_target;
        write_dispatch_candidates(list, now, scan_token, target_only);
        if (has_batch_target) LOGI("[BATCH-PROBE-PERF] scope=%s elapsedMs=%lld",
                target_only ? "target" : "full", static_cast<long long>(steady_ms() - scan_started_ms));
    }
    if (!return_due) return; // Keep return diagnostics at their original cadence.
    void *items = *reinterpret_cast<void **>(static_cast<uint8_t *>(list) + 0x10);
    const int count = *reinterpret_cast<int *>(static_cast<uint8_t *>(list) + 0x18);
    if (!items || count < 0 || count > kMaxPikminTaskListCount) {
        LOGI("[RETURN-DIAG] dry-run invalid list=%p items=%p count=%d", list, items, count);
        return;
    }
    int ready_count{};
    int due_count{};
    write_return_candidates(items, count, now);
    for (int index = 0; index < count; ++index) {
        void *task = *reinterpret_cast<void **>(static_cast<uint8_t *>(items) + 0x20 + index * sizeof(void *));
        if (!task) continue;
        void *task_id = get_inventory_item_id(task, nullptr);
        if (original_should_prepare_completion(return_preparer, task, nullptr)) {
            ++ready_count;
            append_return_trace("dry-run-ready", return_preparer, task_id);
        }
        void *proto = get_pikmin_task_proto ? get_pikmin_task_proto(task, nullptr) : nullptr;
        const int64_t finish_ms = proto && get_task_finish_time_ms
                ? get_task_finish_time_ms(proto, nullptr) : 0;
        if (finish_ms > 0 && finish_ms <= now) {
            ++due_count;
            append_return_trace("dry-run-finish-due", return_preparer, task_id);
            log_task_variant_metadata(proto);
        }
    }
    LOGI("[RETURN-DIAG] dry-run tasks=%d preparerReady=%d finishDue=%d",
         count, ready_count, due_count);
}

void *find_class(const char *namespc, const char *name) {
    if (!game_domain || !domain_get_assemblies || !assembly_get_image || !class_from_name) return nullptr;
    size_t count{};
    const void **assemblies = domain_get_assemblies(game_domain, &count);
    for (size_t index = 0; assemblies && index < count; ++index) {
        void *klass = class_from_name(assembly_get_image(assemblies[index]), namespc, name);
        if (klass) return klass;
    }
    return nullptr;
}

// Metadata-only probe.  It never invokes an unknown method or touches a task;
// it simply records the public method names exposed by this exact game build so
// reward fields can be decoded without relying on guessed object offsets.
void log_class_methods(const char *label, void *proto_class) {
    if (!proto_class || !class_get_methods || !method_get_name || !method_get_param_count) return;
    void *iterator = nullptr;
    int logged{};
    while (void *method = class_get_methods(proto_class, &iterator)) {
        const char *name = method_get_name(method);
        if (!name) continue;
        LOGI("[RETURN-META] %s method=%s params=%u", label, name,
             method_get_param_count(method));
        if (++logged >= 160) {
            LOGE("[RETURN-META] method enumeration capped at 160 entries");
            break;
        }
    }
    LOGI("[RETURN-META] %s metadata methods=%d", label, logged);
}

void log_task_proto_methods(void *proto_class) {
    log_class_methods("PikminTaskProto", proto_class);
}

void log_task_variant_metadata(void *proto) {
    if (!proto || !object_get_class || !class_get_name) return;
    const GetTaskVariant getters[] = {get_task_carry, get_task_expedition, get_task_gift, get_task_poi_challenge};
    const char *labels[] = {"Carry", "Expedition", "Gift", "PoiChallenge"};
    for (int index = 0; index < 4; ++index) {
        if (!getters[index] || return_reward_variant_metadata_logged[index]) continue;
        void *variant = getters[index](proto, nullptr);
        if (!variant) continue;
        void *klass = object_get_class(variant);
        const char *name = klass ? class_get_name(klass) : "unknown";
        LOGI("[RETURN-META] active task variant=%s class=%s", labels[index], name ? name : "unknown");
        log_class_methods(labels[index], klass);
        if (index == 1) {
            const GetTaskVariant getters[] = {get_expedition_seed, get_expedition_fruit, get_expedition_gift,
                    get_expedition_bloomed_poi, get_expedition_postcard, get_expedition_postcard_with_items};
            const char *target_labels[] = {"Seed", "Fruit", "ExpeditionGift", "BloomedPoi", "Postcard", "PostcardWithItems"};
            for (int target = 0; target < 6; ++target) {
                if (!getters[target] || return_expedition_target_metadata_logged[target]) continue;
                void *value = getters[target](variant, nullptr);
                if (!value) continue;
                void *value_class = object_get_class(value);
                const char *value_name = value_class ? class_get_name(value_class) : "unknown";
                LOGI("[RETURN-META] active expedition target=%s class=%s", target_labels[target],
                     value_name ? value_name : "unknown");
                log_class_methods(target_labels[target], value_class);
                if (target == 3 && get_bloomed_poi_reward_v2 && !return_bloomed_poi_reward_metadata_logged) {
                    void *reward = get_bloomed_poi_reward_v2(value, nullptr);
                    if (reward) {
                        void *reward_class = object_get_class(reward);
                        const char *reward_name = reward_class ? class_get_name(reward_class) : "unknown";
                        LOGI("[RETURN-META] BloomedPoi RewardV2 class=%s", reward_name ? reward_name : "unknown");
                        log_class_methods("BloomedPoiRewardV2", reward_class);
                        return_bloomed_poi_reward_metadata_logged = true;
                        if (get_poi_reward_fruit && !return_bloomed_poi_fruit_metadata_logged) {
                            void *fruit = get_poi_reward_fruit(reward, nullptr);
                            if (fruit) {
                                void *fruit_class = object_get_class(fruit);
                                const char *fruit_name = fruit_class ? class_get_name(fruit_class) : "unknown";
                                LOGI("[RETURN-META] BloomedPoi Fruit class=%s", fruit_name ? fruit_name : "unknown");
                                log_class_methods("BloomedPoiFruit", fruit_class);
                                return_bloomed_poi_fruit_metadata_logged = true;
                                auto *repeated = static_cast<uint8_t *>(fruit);
                                void *items = *reinterpret_cast<void **>(repeated + 0x10);
                                const int count = *reinterpret_cast<int *>(repeated + 0x18);
                                if (!return_bloomed_poi_fruit_entry_metadata_logged && items && count > 0 && count <= 32) {
                                    void *entry = *reinterpret_cast<void **>(static_cast<uint8_t *>(items) + 0x20);
                                    void *entry_class = entry ? object_get_class(entry) : nullptr;
                                    const char *entry_name = entry_class ? class_get_name(entry_class) : "unknown";
                                    LOGI("[RETURN-META] BloomedPoi Fruit entry class=%s count=%d",
                                         entry_name ? entry_name : "unknown", count);
                                    log_class_methods("BloomedPoiFruitEntry", entry_class);
                                    return_bloomed_poi_fruit_entry_metadata_logged = true;
                                }
                            }
                        }
                    }
                }
                return_expedition_target_metadata_logged[target] = true;
            }
        }
        return_reward_variant_metadata_logged[index] = true;
        return;
    }
}

// Records a return-helper early return: the failing step plus every later
// step of this helper as blocked by it. wire_probe/logout_hook are independent.
void return_helper_failed(pcc_cap::Step step, pcc_cap::Code code) {
    capability_registry.step_failed(step, code);
    capability_registry.block_range(step, pcc_cap::expedition_pick_fastest, step);
}

void install_return_diagnostic_hook() {
    if (!class_get_method_from_name) {
        return_helper_failed(pcc_cap::return_action_manager_class, pcc_cap::Code::symbol);
        LOGE("[RETURN-DIAG] il2cpp_class_get_method_from_name unavailable");
        return;
    }
    void *klass = find_class("Niantic.Ichigo.Game.PikminTasks", "PikminTaskActionManager");
    if (!klass) {
        return_helper_failed(pcc_cap::return_action_manager_class, pcc_cap::Code::class_missing);
        LOGE("[RETURN-DIAG] PikminTaskActionManager class not found");
        return;
    }
    capability_registry.step_ok(pcc_cap::return_action_manager_class);
    void *capture_class = find_class("Niantic.Ichigo.Game.Challenge.MushroomChallenge", "BlockerService");
    action_manager_capture_field = capture_class && class_get_field
            ? class_get_field(capture_class, "pikminTaskActionManager") : nullptr;
    void *action_ctor = capture_class ? class_get_method_from_name(
            capture_class, "Zenject.IInitializable.Initialize", 0) : nullptr;
    void *action_ctor_entry = action_ctor ? *reinterpret_cast<void **>(action_ctor) : nullptr;
    if (!action_ctor_entry || !action_manager_capture_field || !field_get_offset || !field_get_value
            || field_get_offset(action_manager_capture_field) != 0x50) {
        return_helper_failed(pcc_cap::return_blocker_capture, pcc_cap::Code::field_missing);
        LOGE("[RETURN-DIAG] action manager injection capture metadata mismatch");
        return;
    }
    // Verify the exact v157 Initialize entry before writing hook bytes. This
    // capture is required by the task list, return claims, and planter engine.
    // The old v152 prologue rejected this build before any of those hooks ran.
    static constexpr uint32_t capture_prologue[] = {
        0xf81d0ffe,0xa90157f6,0xa9024ff4,0xd00491b4,0xaa0003f3,0x3966fe88
    };
    Dl_info capture_info{};
    if (!dladdr(action_ctor_entry, &capture_info) ||
        reinterpret_cast<uintptr_t>(action_ctor_entry) -
                reinterpret_cast<uintptr_t>(capture_info.dli_fbase) != kBlockerServiceInitializeRva ||
        std::memcmp(action_ctor_entry, capture_prologue, sizeof(capture_prologue)) != 0) {
        return_helper_failed(pcc_cap::return_blocker_capture, pcc_cap::Code::entry_mismatch);
        LOGE("[RETURN-DIAG] v153 injection capture entry mismatch");
        return;
    }
    A64HookFunction(action_ctor_entry, reinterpret_cast<void *>(hooked_blocker_service_initialize),
                    reinterpret_cast<void **>(&original_blocker_service_initialize));
    capability_registry.step_ok(pcc_cap::return_blocker_capture);
    LOGI("[RETURN-DIAG] action-manager injection hook installed method=%p entry=%p",
         action_ctor, action_ctor_entry);
    // This is the public overload that contains both the task id and postcard policy.
    void *method = class_get_method_from_name(klass, "CompletePikminTaskAsync", 2);
    if (!method) {
        return_helper_failed(pcc_cap::return_complete_task_hook, pcc_cap::Code::method_missing);
        LOGE("[RETURN-DIAG] CompletePikminTaskAsync(taskId, discardPostcard) not found");
        return;
    }
    void *entry = *reinterpret_cast<void **>(method);
    if (!entry) {
        return_helper_failed(pcc_cap::return_complete_task_hook, pcc_cap::Code::no_entry);
        LOGE("[RETURN-DIAG] CompletePikminTaskAsync has no native entry point");
        return;
    }
    A64HookFunction(entry, reinterpret_cast<void *>(hooked_complete_pikmin_task),
                    reinterpret_cast<void **>(&original_complete_pikmin_task));
    capability_registry.step_ok(pcc_cap::return_complete_task_hook);
    LOGI("[RETURN-DIAG] hook installed method=%p entry=%p", method, entry);

    void *preparer = find_class("Niantic.Ichigo.Game.PikminTasks", "PikminTaskCompletionPreparer");
    void *preparer_ctor = preparer ? class_get_method_from_name(preparer, ".ctor", 3) : nullptr;
    void *preparer_ctor_entry = preparer_ctor ? *reinterpret_cast<void **>(preparer_ctor) : nullptr;
    if (!preparer_ctor_entry) {
        return_helper_failed(pcc_cap::return_preparer_ctor_hook, pcc_cap::Code::ctor_missing);
        LOGE("[RETURN-DIAG] PikminTaskCompletionPreparer constructor not found");
        return;
    }
    A64HookFunction(preparer_ctor_entry, reinterpret_cast<void *>(hooked_pikmin_task_preparer_constructor),
                    reinterpret_cast<void **>(&original_pikmin_task_preparer_constructor));
    capability_registry.step_ok(pcc_cap::return_preparer_ctor_hook);
    LOGI("[RETURN-DIAG] preparer constructor hook installed method=%p entry=%p",
         preparer_ctor, preparer_ctor_entry);
    void *prepare_method = preparer
            ? class_get_method_from_name(preparer, "PrepareCompletionAsync", 1) : nullptr;
    void *prepare_entry = prepare_method ? *reinterpret_cast<void **>(prepare_method) : nullptr;
    if (!prepare_entry) {
        return_helper_failed(pcc_cap::return_prepare_completion_hook, pcc_cap::Code::method_missing);
        LOGE("[RETURN-DIAG] PrepareCompletionAsync(taskIds) not found");
        return;
    }
    A64HookFunction(prepare_entry, reinterpret_cast<void *>(hooked_prepare_pikmin_tasks),
                    reinterpret_cast<void **>(&original_prepare_pikmin_tasks));
    capability_registry.step_ok(pcc_cap::return_prepare_completion_hook);
    LOGI("[RETURN-DIAG] preparer hook installed method=%p entry=%p", prepare_method, prepare_entry);

    void *ready_method = class_get_method_from_name(preparer, "ShouldPrepareCompletion", 1);
    void *ready_entry = ready_method ? *reinterpret_cast<void **>(ready_method) : nullptr;
    void *task_class = find_class("Niantic.Ichigo.Inventory", "PikminTaskInventoryItem");
    void *id_method = task_class
            ? class_get_method_from_name(task_class, "get_Id", 0) : nullptr;
    void *id_entry = id_method ? *reinterpret_cast<void **>(id_method) : nullptr;
    if (!ready_entry || !id_entry) {
        return_helper_failed(pcc_cap::return_readiness_taskid, pcc_cap::Code::method_missing);
        LOGE("[RETURN-DIAG] readiness or task-id method not found");
        return;
    }
    capability_registry.step_ok(pcc_cap::return_readiness_taskid);
    get_inventory_item_id = reinterpret_cast<GetInventoryItemId>(id_entry);
    A64HookFunction(ready_entry, reinterpret_cast<void *>(hooked_should_prepare_completion),
                    reinterpret_cast<void **>(&original_should_prepare_completion));
    LOGI("[RETURN-DIAG] readiness hook installed method=%p entry=%p", ready_method, ready_entry);

    void *inventory = find_class("Niantic.Ichigo.Inventory", "InventoryManager");
    void *list_method = inventory ? class_get_method_from_name(inventory, "GetPikminTaskList", 0) : nullptr;
    void *list_entry = list_method ? *reinterpret_cast<void **>(list_method) : nullptr;
    void *proto_method = task_class ? class_get_method_from_name(task_class, "get_Proto", 0) : nullptr;
    void *proto_entry = proto_method ? *reinterpret_cast<void **>(proto_method) : nullptr;
    void *proto_class = find_class("Ichigo.Proto", "PikminTaskProto");
    log_task_proto_methods(proto_class);
    void *finish_method = proto_class ? class_get_method_from_name(proto_class, "get_FinishTimeMs", 0) : nullptr;
    void *finish_entry = finish_method ? *reinterpret_cast<void **>(finish_method) : nullptr;
    if (!list_entry || !proto_entry || !finish_entry) {
        return_helper_failed(pcc_cap::return_task_list_time, pcc_cap::Code::method_missing);
        LOGE("[RETURN-DIAG] task-list or task-time method not found");
        return;
    }
    capability_registry.step_ok(pcc_cap::return_task_list_time);
    // complete_task = CompletePikminTaskAsync hook installed AND task list/time resolved (both above).
    capability_registry.capture(pcc_cap::cap_complete_task, 0);
    get_pikmin_task_proto = reinterpret_cast<GetPikminTaskProto>(proto_entry);
    get_task_finish_time_ms = reinterpret_cast<GetTaskFinishTimeMs>(finish_entry);
    task_completion_requirement_method = proto_class
            ? class_get_method_from_name(proto_class, "get_CompletionRequirement", 0) : nullptr;
    get_task_completion_requirement = task_completion_requirement_method
            ? reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(task_completion_requirement_method)) : nullptr;
    void *finish_location_method = proto_class
            ? class_get_method_from_name(proto_class, "get_FinishLocation", 0) : nullptr;
    get_task_finish_location = finish_location_method
            ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(finish_location_method)) : nullptr;
    void *task_pikmin_id_method = proto_class
            ? class_get_method_from_name(proto_class, "get_PikminId", 0) : nullptr;
    get_task_pikmin_id = task_pikmin_id_method
            ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(task_pikmin_id_method)) : nullptr;
    void *expedition_item_class = find_class("Niantic.Ichigo.Game.Expedition.Data", "ExpeditionItemData");
    void *expedition_key_method = expedition_item_class
            ? class_get_method_from_name(expedition_item_class, "get_Key", 0) : nullptr;
    void *expedition_duration_method = expedition_item_class
            ? class_get_method_from_name(expedition_item_class, "get_TotalDurationMs", 0) : nullptr;
    void *expedition_duration_entry = expedition_duration_method
            ? *reinterpret_cast<void **>(expedition_duration_method) : nullptr;
    get_expedition_item_key = expedition_key_method
            ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(expedition_key_method)) : nullptr;
    if (expedition_duration_entry) {
        A64HookFunction(expedition_duration_entry,
                        reinterpret_cast<void *>(hooked_get_expedition_total_duration_ms),
                        reinterpret_cast<void **>(&original_get_expedition_total_duration_ms));
        LOGI("[DISPATCH-OBSERVE] duration hook installed method=%p entry=%p",
             expedition_duration_method, expedition_duration_entry);
    } else {
        LOGE("[DISPATCH-OBSERVE] ExpeditionItemData.TotalDurationMs not found");
    }
    void *expedition_store_class = find_class("Niantic.Ichigo.Game.Expedition.Data", "ExpeditionDataStore");
    void *expedition_store_ctor = expedition_store_class
            ? class_get_method_from_name(expedition_store_class, ".ctor", 0) : nullptr;
    void *expedition_store_ctor_entry = expedition_store_ctor
            ? *reinterpret_cast<void **>(expedition_store_ctor) : nullptr;
    void *by_index_method = expedition_store_class
            ? class_get_method_from_name(expedition_store_class, "ByIndex", 2) : nullptr;
    get_expedition_data_by_index = by_index_method
            ? reinterpret_cast<GetExpeditionDataByIndex>(*reinterpret_cast<void **>(by_index_method)) : nullptr;
    void *can_start_method = expedition_item_class
            ? class_get_method_from_name(expedition_item_class, "get_CanTryStart", 0) : nullptr;
    get_expedition_can_try_start = can_start_method
            ? reinterpret_cast<GetExpeditionBool>(*reinterpret_cast<void **>(can_start_method)) : nullptr;
    void *carrying_power_method = expedition_item_class
            ? class_get_method_from_name(expedition_item_class, "get_HasEnoughCarryingPower", 0) : nullptr;
    get_expedition_has_enough_carrying_power = carrying_power_method
            ? reinterpret_cast<GetExpeditionBool>(*reinterpret_cast<void **>(carrying_power_method)) : nullptr;
    void *invalidate_cached_method = expedition_item_class
            ? class_get_method_from_name(expedition_item_class, "InvalidateAllCachedValues", 0) : nullptr;
    invalidate_expedition_cached_values = invalidate_cached_method
            ? reinterpret_cast<SimpleMethod>(*reinterpret_cast<void **>(invalidate_cached_method)) : nullptr;
    LOGI("[DISPATCH-OBSERVE] carrying power method=%p invalidateCached method=%p",
         carrying_power_method, invalidate_cached_method);
    void *set_pikmins_method = expedition_item_class
            ? class_get_method_from_name(expedition_item_class, "SetPikmins", 1) : nullptr;
    set_expedition_pikmins = set_pikmins_method
            ? reinterpret_cast<SetExpeditionPikmins>(*reinterpret_cast<void **>(set_pikmins_method)) : nullptr;
    void *start_expedition_method = expedition_item_class
            ? class_get_method_from_name(expedition_item_class, "StartExpeditionAsync", 0) : nullptr;
    start_expedition = start_expedition_method
            ? reinterpret_cast<StartExpedition>(*reinterpret_cast<void **>(start_expedition_method)) : nullptr;
    void *pikmin_collection_method = inventory
            ? class_get_method_from_name(inventory, "get_PikminItemCollection", 0) : nullptr;
    get_pikmin_item_collection = pikmin_collection_method
            ? reinterpret_cast<GetEnumerable>(*reinterpret_cast<void **>(pikmin_collection_method)) : nullptr;
    available_pikmin_class = find_class("Niantic.Ichigo.Inventory", "PikminInventoryItem");
    available_proto_class = find_class("Ichigo.Proto", "PikminProto");
    void *availability_extensions = find_class("Niantic.Ichigo.Utils.Proto", "PikminProtoExtensions");
    void *availability_clock = find_class("Niantic.Ichigo.Utils", "ServerClock");
    available_list_method = verified_v153_method(inventory, "GetPikminList", 0, 0x74110f0);
    available_proto_method = verified_v153_method(available_pikmin_class, "get_Proto", 0, 0x73f97c4);
    available_status_method = verified_v153_method(available_proto_class, "get_StatusCase", 0, 0x5af9a48);
    available_restriction_method = verified_v153_method(availability_extensions,
            "ShouldRestrictActionsUnlessIsTask", 3, 0x6ea5dd4);
    available_clock_instance_method = verified_v153_method(availability_clock, "get_Instance", 0, 0x6e540f0);
    available_clock_time_method = verified_v153_method(availability_clock, "get_CurrentTimeMs", 0, 0x6e5445c);
    selected_pikmins_method = verified_v153_method(expedition_item_class, "get_Pikmins", 0, 0x60edc04);
    gift_allows_method = verified_v153_method(expedition_item_class, "Allows", 1, 0x60efd74);
    void *utils_class = find_class("Niantic.Ichigo.Game.Expedition", "ExpeditionUtils");
    void *pick_fastest_method = utils_class ? class_get_method_from_name(utils_class,
            "PickFastestUpToItemLimitsReservingForTroop", 3) : nullptr;
    pick_fastest_pikmins = pick_fastest_method
            ? reinterpret_cast<PickFastestPikmins>(*reinterpret_cast<void **>(pick_fastest_method)) : nullptr;
    if (expedition_store_ctor_entry) {
        A64HookFunction(expedition_store_ctor_entry,
                        reinterpret_cast<void *>(hooked_expedition_data_store_constructor),
                        reinterpret_cast<void **>(&original_expedition_data_store_constructor));
        LOGI("[DISPATCH-OBSERVE] store hook installed ctor=%p byIndex=%p canTryStart=%p pickFastest=%p setPikmins=%p start=%p",
             expedition_store_ctor_entry, by_index_method, can_start_method, pick_fastest_method,
             set_pikmins_method, start_expedition_method);
    } else {
        LOGE("[DISPATCH-OBSERVE] ExpeditionDataStore constructor not found");
    }
    {   // Diagnostic record of what the block above resolved; none of these are retried in-process.
        using namespace pcc_cap;
        const bool expedition_ok = expedition_item_class && expedition_store_class && expedition_store_ctor_entry
                && get_expedition_data_by_index && get_expedition_can_try_start && get_expedition_has_enough_carrying_power
                && invalidate_expedition_cached_values && set_expedition_pikmins && start_expedition
                && get_pikmin_item_collection && get_expedition_item_key && expedition_duration_entry;
        if (expedition_ok) { capability_registry.step_ok(expedition_methods); capability_registry.capture(cap_expedition_start, 0); }
        else capability_registry.step_failed(expedition_methods,
                (!expedition_item_class || !expedition_store_class) ? Code::class_missing : Code::method_missing);
        const bool availability_ok = available_list_method && available_proto_method && available_status_method
                && available_restriction_method && available_clock_instance_method && available_clock_time_method;
        if (availability_ok) capability_registry.step_ok(availability_base);
        else capability_registry.step_failed(availability_base, Code::method_unavailable);
        if (selected_pikmins_method) { capability_registry.step_ok(availability_selected); capability_registry.capture(cap_availability_selected, 0); }
        else capability_registry.step_failed(availability_selected, Code::method_unavailable);
        if (gift_allows_method) { capability_registry.step_ok(availability_gift); capability_registry.capture(cap_availability_gift, 0); }
        else capability_registry.step_failed(availability_gift, Code::method_unavailable);
        if (pick_fastest_pikmins) { capability_registry.step_ok(expedition_pick_fastest); capability_registry.capture(cap_pick_fastest, 0); }
        else capability_registry.step_failed(expedition_pick_fastest, Code::method_missing);
    }
    void *task_case = proto_class ? class_get_method_from_name(proto_class, "get_TaskCase", 0) : nullptr;
    get_task_case = task_case ? reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(task_case)) : nullptr;
    void *carry = proto_class ? class_get_method_from_name(proto_class, "get_Carry", 0) : nullptr;
    void *expedition = proto_class ? class_get_method_from_name(proto_class, "get_Expedition", 0) : nullptr;
    void *gift = proto_class ? class_get_method_from_name(proto_class, "get_Gift", 0) : nullptr;
    void *poi_challenge = proto_class ? class_get_method_from_name(proto_class, "get_PoiChallenge", 0) : nullptr;
    get_task_carry = carry ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(carry)) : nullptr;
    get_task_expedition = expedition ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(expedition)) : nullptr;
    get_task_gift = gift ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(gift)) : nullptr;
    get_task_poi_challenge = poi_challenge ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(poi_challenge)) : nullptr;
    void *carry_class = find_class("Ichigo.Proto", "CarryTaskProto");
    void *carry_resource = carry_class ? class_get_method_from_name(carry_class, "get_Resource", 0) : nullptr;
    void *carry_seed = carry_class ? class_get_method_from_name(carry_class, "get_PikminSeed", 0) : nullptr;
    void *carry_challenge = carry_class ? class_get_method_from_name(carry_class, "get_ChallengeInfo", 0) : nullptr;
    get_carry_challenge_info = carry_challenge ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(carry_challenge)) : nullptr;
    get_carry_resource = carry_resource ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(carry_resource)) : nullptr;
    get_carry_pikmin_seed = carry_seed ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(carry_seed)) : nullptr;
    void *seed_class = find_class("Ichigo.Proto", "PikminSeedProto");
    void *seed_type = seed_class ? class_get_method_from_name(seed_class, "get_SeedType", 0) : nullptr;
    void *seed_pikmin = seed_class ? class_get_method_from_name(seed_class, "get_Pikmin", 0) : nullptr;
    get_seed_type = seed_type ? reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(seed_type)) : nullptr;
    get_seed_pikmin = seed_pikmin ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(seed_pikmin)) : nullptr;
    void *expedition_class = find_class("Ichigo.Proto", "ExpeditionTaskProto");
    void *expedition_target_case = expedition_class
            ? class_get_method_from_name(expedition_class, "get_TargetCase", 0) : nullptr;
    get_expedition_target_case = expedition_target_case
            ? reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(expedition_target_case)) : nullptr;
    void *expedition_expiration = expedition_class
            ? class_get_method_from_name(expedition_class, "get_ExpirationTimeMs", 0) : nullptr;
    void *expedition_spawn_point = expedition_class
            ? class_get_method_from_name(expedition_class, "get_SpawnPoint", 0) : nullptr;
    get_expedition_expiration_time_ms = expedition_expiration
            ? reinterpret_cast<GetTaskLong>(*reinterpret_cast<void **>(expedition_expiration)) : nullptr;
    get_expedition_spawn_point = expedition_spawn_point
            ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(expedition_spawn_point)) : nullptr;
    void *point_class = find_class("Ichigo.Proto", "PointProto");
    void *point_lat = point_class ? class_get_method_from_name(point_class, "get_LatDegrees", 0) : nullptr;
    void *point_lng = point_class ? class_get_method_from_name(point_class, "get_LngDegrees", 0) : nullptr;
    get_point_lat_degrees = point_lat ? reinterpret_cast<GetTaskDouble>(*reinterpret_cast<void **>(point_lat)) : nullptr;
    get_point_lng_degrees = point_lng ? reinterpret_cast<GetTaskDouble>(*reinterpret_cast<void **>(point_lng)) : nullptr;
    const char *target_getters[] = {"get_Seed", "get_Fruit", "get_Gift", "get_BloomedPoi", "get_Postcard", "get_PostcardWithItems"};
    GetTaskVariant *target_functions[] = {&get_expedition_seed, &get_expedition_fruit, &get_expedition_gift,
            &get_expedition_bloomed_poi, &get_expedition_postcard, &get_expedition_postcard_with_items};
    for (int index = 0; index < 6; ++index) {
        void *method = expedition_class ? class_get_method_from_name(expedition_class, target_getters[index], 0) : nullptr;
        *target_functions[index] = method ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(method)) : nullptr;
    }
    void *pikmin_type_class = find_class("Ichigo.Proto", "PikminTypeProto");
    void *pikmin_type = pikmin_type_class ? class_get_method_from_name(pikmin_type_class, "get_Type", 0) : nullptr;
    void *pikmin_category = pikmin_type_class ? class_get_method_from_name(pikmin_type_class, "get_CategoryId", 0) : nullptr;
    void *pikmin_full_asset = pikmin_type_class ? class_get_method_from_name(pikmin_type_class, "get_FullAssetId", 0) : nullptr;
    get_pikmin_type = pikmin_type ? reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(pikmin_type)) : nullptr;
    get_pikmin_category = pikmin_category ? reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(pikmin_category)) : nullptr;
    get_pikmin_full_asset = pikmin_full_asset ? reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(pikmin_full_asset)) : nullptr;
    void *gift_class = find_class("Ichigo.Proto", "PikminGiftProto");
    void *gift_item_case = gift_class ? class_get_method_from_name(gift_class, "get_ItemCase", 0) : nullptr;
    void *gift_deco = gift_class ? class_get_method_from_name(gift_class, "get_Deco", 0) : nullptr;
    void *gift_rare_deco = gift_class ? class_get_method_from_name(gift_class, "get_RareDeco", 0) : nullptr;
    get_gift_item_case = gift_item_case ? reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(gift_item_case)) : nullptr;
    get_gift_deco = gift_deco ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(gift_deco)) : nullptr;
    get_gift_rare_deco = gift_rare_deco ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(gift_rare_deco)) : nullptr;
    void *deco_class = find_class("Ichigo.Proto", "PikminDecorationProto");
    void *deco_pikmin_type = deco_class ? class_get_method_from_name(deco_class, "get_PikminType", 0) : nullptr;
    void *deco_box_type = deco_class ? class_get_method_from_name(deco_class, "get_BoxType", 0) : nullptr;
    get_deco_pikmin_type = deco_pikmin_type ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(deco_pikmin_type)) : nullptr;
    get_deco_box_type = deco_box_type ? reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(deco_box_type)) : nullptr;
    void *rare_deco_class = find_class("Ichigo.Proto", "PikminRareDecorationProto");
    void *rare_deco_category = rare_deco_class ? class_get_method_from_name(rare_deco_class, "get_CategoryId", 0) : nullptr;
    get_rare_deco_category = rare_deco_category ? reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(rare_deco_category)) : nullptr;
    void *resource_class = find_class("Ichigo.Proto", "ResourceProto");
    void *resource_type = resource_class ? class_get_method_from_name(resource_class, "get_Type", 0) : nullptr;
    void *resource_pikmins = resource_class ? class_get_method_from_name(resource_class, "get_NumPikmins", 0) : nullptr;
    void *resource_flower = resource_class ? class_get_method_from_name(resource_class, "get_FlowerKind", 0) : nullptr;
    void *resource_honey_flower = resource_class ? class_get_method_from_name(resource_class, "get_HoneyBallFlowerKind", 0) : nullptr;
    void *resource_weight = resource_class ? class_get_method_from_name(resource_class, "get_WeightGrams", 0) : nullptr;
    void *resource_honey_balls = resource_class ? class_get_method_from_name(resource_class, "get_HoneyBall", 0) : nullptr;
    get_resource_type = resource_type ? reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(resource_type)) : nullptr;
    get_resource_num_pikmins = resource_pikmins ? reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(resource_pikmins)) : nullptr;
    get_resource_flower_kind = resource_flower ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(resource_flower)) : nullptr;
    get_resource_honey_flower_kind = resource_honey_flower ? reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(resource_honey_flower)) : nullptr;
    get_resource_weight_grams = resource_weight ? reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(resource_weight)) : nullptr;
    get_resource_honey_balls = resource_honey_balls ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(resource_honey_balls)) : nullptr;
    void *honey_class = find_class("Ichigo.Proto", "HoneyBallProto");
    void *honey_num_balls = honey_class ? class_get_method_from_name(honey_class, "get_NumBalls", 0) : nullptr;
    void *honey_type = honey_class ? class_get_method_from_name(honey_class, "get_HoneyType", 0) : nullptr;
    void *honey_flower_kind = honey_class ? class_get_method_from_name(honey_class, "get_HoneyFlowerKind", 0) : nullptr;
    void *honey_flower_text = honey_class ? class_get_method_from_name(honey_class, "get_FlowerKind", 0) : nullptr;
    get_honey_num_balls = honey_num_balls ? reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(honey_num_balls)) : nullptr;
    get_honey_type = honey_type ? reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(honey_type)) : nullptr;
    get_honey_flower_kind = honey_flower_kind ? reinterpret_cast<GetTaskInt>(*reinterpret_cast<void **>(honey_flower_kind)) : nullptr;
    get_honey_flower_kind_text = honey_flower_text ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(honey_flower_text)) : nullptr;
    void *bloomed_poi_class = find_class("Ichigo.Proto", "PoiFlowerBloomedRewardProto");
    void *reward_v2 = bloomed_poi_class
            ? class_get_method_from_name(bloomed_poi_class, "get_RewardV2", 0) : nullptr;
    get_bloomed_poi_reward_v2 = reward_v2
            ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(reward_v2)) : nullptr;
    void *poi_reward_class = find_class("Ichigo.Proto", "PoiRewardProto");
    void *poi_reward_fruit = poi_reward_class
            ? class_get_method_from_name(poi_reward_class, "get_Fruit", 0) : nullptr;
    get_poi_reward_fruit = poi_reward_fruit
            ? reinterpret_cast<GetTaskVariant>(*reinterpret_cast<void **>(poi_reward_fruit)) : nullptr;
    A64HookFunction(list_entry, reinterpret_cast<void *>(hooked_get_pikmin_task_list),
                    reinterpret_cast<void **>(&original_get_pikmin_task_list));
    LOGI("[RETURN-DIAG] task-list hook installed method=%p entry=%p", list_method, list_entry);
}

// Observe existing protobuf storage only. Never enumerate managed objects or
// invoke generated generic methods from this diagnostic hook.
bool wire_field_matches(void *klass, const char *name, size_t expected) {
    void *field = klass && class_get_field ? class_get_field(klass, name) : nullptr;
    return field && field_get_offset && field_get_offset(field) == expected;
}

void *hooked_send_expedition_rpc(void *self, void *request, void *cancel_token,
                               int retry_policy, void *method_info) {
    if (request && object_get_class(request) == dispatch_request_class) {
        auto *bytes = static_cast<uint8_t *>(request);
        void *id = *reinterpret_cast<void **>(bytes + 0x18);
        void *team = *reinterpret_cast<void **>(bytes + 0x20);
        void *point = *reinterpret_cast<void **>(bytes + 0x28);
        const std::string task_id = id && object_get_class(id) == dispatch_string_class ? utf8_string(id) : "invalid";
        double latitude = NAN, longitude = NAN;
        if (point && object_get_class(point) == dispatch_point_class) {
            latitude = *reinterpret_cast<double *>(static_cast<uint8_t *>(point) + 0x18);
            longitude = *reinterpret_cast<double *>(static_cast<uint8_t *>(point) + 0x20);
        }
        int count = -1;
        std::string ids;
        void *team_class = team ? object_get_class(team) : nullptr;
        if (wire_field_matches(team_class, "array", 0x10) && wire_field_matches(team_class, "count", 0x18)) {
            count = *reinterpret_cast<int *>(static_cast<uint8_t *>(team) + 0x18);
            void *array = *reinterpret_cast<void **>(static_cast<uint8_t *>(team) + 0x10);
            const size_t capacity = array ? *reinterpret_cast<size_t *>(static_cast<uint8_t *>(array) + 0x18) : 0;
            if (count >= 0 && count <= 100 && static_cast<size_t>(count) <= capacity && capacity <= 4096) {
                for (int i = 0; i < count; ++i) {
                    void *value = *reinterpret_cast<void **>(static_cast<uint8_t *>(array) + 0x20 + i * sizeof(void *));
                    if (i) ids.push_back(',');
                    ids += value && object_get_class(value) == dispatch_string_class ? utf8_string(value) : "invalid";
                }
            } else { count = -1; }
        }
        // Bounded diagnostic ring. No changes to the transmitted request.
        const long long observed = now_ms();
        std::vector<std::string> rows;
        std::ifstream previous(dispatch_wire_path);
        std::string row;
        while (std::getline(previous, row)) {
            if (std::strtoll(row.c_str(), nullptr, 10) >= observed - 24LL * 60 * 60 * 1000) rows.push_back(row);
        }
        previous.close();
        FILE *file = std::fopen(dispatch_wire_path, "w");
        if (file) {
            const size_t first = rows.size() > 255 ? rows.size() - 255 : 0;
            for (size_t i = first; i < rows.size(); ++i) std::fprintf(file, "%s\n", rows[i].c_str());
            std::fprintf(file, "%lld\t%s\t%s\t%.7f\t%.7f\t%d\t%s\n", observed,
                         module_start_call ? "module" : "game", task_id.c_str(), latitude, longitude, count, ids.c_str());
            std::fclose(file);
            chmod(dispatch_wire_path, 0644);
        }
        LOGI("[DISPATCH-WIRE] source=%s task=%s location=%.7f,%.7f count=%d",
             module_start_call ? "module" : "game", task_id.c_str(), latitude, longitude, count);
    }
    return original_send_expedition_rpc(self, request, cancel_token, retry_policy, method_info);
}

void install_dispatch_wire_probe() {
    if (!class_get_field || !field_get_offset) {
        capability_registry.step_failed(pcc_cap::wire_probe, pcc_cap::Code::symbol);
        return;
    }
    void *klass = find_class("Niantic.Ichigo.Rpc", "RpcManager");
    void *method = klass ? class_get_method_from_name(klass, "SendStartExpeditionRpcAsync", 3) : nullptr;
    void *entry = method ? *reinterpret_cast<void **>(method) : nullptr;
    Dl_info info{};
    // Cross-check live metadata against the SHA-pinned v157 APK, never reference/dump.cs.
    if (!entry || !dladdr(entry, &info) ||
        reinterpret_cast<uintptr_t>(entry) - reinterpret_cast<uintptr_t>(info.dli_fbase) != 0x73da0f4) {
        capability_registry.step_failed(pcc_cap::wire_probe, pcc_cap::Code::rpc_entry_mismatch);
        LOGE("[DISPATCH-WIRE] disabled: runtime RPC entry mismatch");
        return;
    }
    dispatch_request_class = find_class("Ichigo.Proto", "StartExpeditionRequestProto");
    dispatch_point_class = find_class("Ichigo.Proto", "PointProto");
    dispatch_string_class = find_class("System", "String");
    if (!dispatch_string_class ||
        !wire_field_matches(dispatch_request_class, "expeditionTaskId_", 0x18) ||
        !wire_field_matches(dispatch_request_class, "pikminId_", 0x20) ||
        !wire_field_matches(dispatch_request_class, "currentLocation_", 0x28) ||
        !wire_field_matches(dispatch_point_class, "latDegrees_", 0x18) ||
        !wire_field_matches(dispatch_point_class, "lngDegrees_", 0x20)) {
        capability_registry.step_failed(pcc_cap::wire_probe, pcc_cap::Code::proto_layout_mismatch);
        LOGE("[DISPATCH-WIRE] disabled: runtime protobuf layout mismatch");
        return;
    }
    A64HookFunction(entry, reinterpret_cast<void *>(hooked_send_expedition_rpc),
                    reinterpret_cast<void **>(&original_send_expedition_rpc));
    capability_registry.step_ok(pcc_cap::wire_probe);
    LOGI("[DISPATCH-WIRE] v157 runtime-verified hook installed entry=%p original=%p", entry,
         reinterpret_cast<void *>(original_send_expedition_rpc));
}

// This must run from a post-login game callback.  On v152 the library is
// mapped long before its domain is usable, and calling domain_get from the
// detached Zygisk worker can crash inside libil2cpp itself.
void initialize_runtime_metadata() {
    static std::mutex install_mutex;
    std::unique_lock<std::mutex> install_lock(install_mutex,std::try_to_lock);
    if (!install_lock.owns_lock()) return; // concurrent/reentrant callbacks never duplicate hooks
    if (runtime_metadata_ready || !domain_get) return;
    void *domain = domain_get();
    if (!domain) {
        capability_registry.set_domain(pcc_cap::Domain::pending);   // retried by the next login/map callback
        LOGW("[NECTAR] post-login IL2CPP domain unavailable; metadata deferred");
        return;
    }
    game_domain = domain;
    // Resolve prerequisites before committing the one-shot metadata/hook pass.
    // Missing classes during startup are retried by a later game callback;
    // once installation begins, no hook is installed a second time.
    if (!find_class("Niantic.Ichigo.Rpc", "RpcManager") ||
            !find_class("Niantic.Ichigo.Inventory", "InventoryManager")) {
        capability_registry.set_domain(pcc_cap::Domain::pending);
        return;
    }
    capability_registry.set_domain(pcc_cap::Domain::ready);
#if defined(PCC_UNIFIED)
    seed_preview::start("/data/user/0/com.nianticlabs.pikmin",display_context);
#endif
    install_return_diagnostic_hook();
    install_dispatch_wire_probe();
    // Resolve from the exact live RpcManager type; old reference/dump.cs is not v152.
    void *rpc=find_class("Niantic.Ichigo.Rpc","RpcManager");
    void *login=rpc?class_get_method_from_name(rpc,"OnLoggedIn",3):nullptr;
    void *logout=rpc?class_get_method_from_name(rpc,"OnLoggedOut",0):nullptr;
    void *login_entry=login?*reinterpret_cast<void **>(login):nullptr;
    void *logout_entry=logout?*reinterpret_cast<void **>(logout):nullptr;
    Dl_info li{},lo{};
    if(login_entry&&logout_entry&&dladdr(login_entry,&li)&&dladdr(logout_entry,&lo)
            &&li.dli_fbase==lo.dli_fbase
            &&reinterpret_cast<uintptr_t>(login_entry)-reinterpret_cast<uintptr_t>(li.dli_fbase)==kRpcManagerLoggedInRva){
        A64HookFunction(logout_entry,reinterpret_cast<void *>(hooked_logged_out),reinterpret_cast<void **>(&original_logged_out));
        std::lock_guard<std::mutex> lock(identity_mutex);identity_logout_hook_ready=original_logged_out!=nullptr;
    }
    {   // Diagnostic step recorded outside identity_mutex (never nested).
        bool hooked; { std::lock_guard<std::mutex> lock(identity_mutex); hooked = identity_logout_hook_ready; }
        if (hooked) capability_registry.step_ok(pcc_cap::logout_hook);
        else capability_registry.step_failed(pcc_cap::logout_hook, pcc_cap::Code::entry_mismatch);
    }
    runtime_metadata_ready = true; // release publication only after the one-shot pass completes
    LOGI("[NECTAR] post-login IL2CPP metadata installed");
}

long long now_ms() {
    timespec value{};
    clock_gettime(CLOCK_REALTIME, &value);
    return static_cast<long long>(value.tv_sec) * 1000LL + value.tv_nsec / 1000000LL;
}

std::string read_string(void *value) {
    if (!value) return {};
    const int length = *reinterpret_cast<int *>(static_cast<uint8_t *>(value) + 0x10);
    if (length <= 0 || length > 512) return {};
    auto *characters = reinterpret_cast<uint16_t *>(static_cast<uint8_t *>(value) + 0x14);
    std::string result;
    result.reserve(static_cast<size_t>(length));
    for (int index = 0; index < length; ++index) {
        const uint16_t character = characters[index];
        result.push_back(character < 0x80 ? static_cast<char>(character) : '?');
    }
    return result;
}

// Best-effort, read-only lookup of a faulted Task's exception, entirely
// through the same dynamic class/method resolution already used safely
// elsewhere in this file (find_class + class_get_method_from_name +
// object_get_virtual_method for polymorphic dispatch) -- never a hardcoded
// RVA, so a method that does not resolve just yields an empty string
// instead of guessing an offset into this exact game build. IsFaulted alone
// only says a dispatch failed, not why; this is what would let a rate-limit
// or validation message distinguish itself from a bare transient failure.
std::string read_task_exception_message(void *task) {
    if (!task || !object_get_class || !class_get_method_from_name) return {};
    void *task_class = object_get_class(task);
    void *get_exception_method = task_class ? class_get_method_from_name(task_class, "get_Exception", 0) : nullptr;
    auto get_exception = get_exception_method
            ? reinterpret_cast<ObjectGetter>(*reinterpret_cast<void **>(get_exception_method)) : nullptr;
    void *exception = get_exception ? get_exception(task, nullptr) : nullptr;
    if (!exception) return {};
    void *exception_class = object_get_class(exception);
    const char *exception_class_name = exception_class && class_get_name ? class_get_name(exception_class) : nullptr;
    void *message_method = exception_class ? class_get_method_from_name(exception_class, "get_Message", 0) : nullptr;
    void *message_impl = message_method && object_get_virtual_method
            ? object_get_virtual_method(exception, message_method) : message_method;
    auto get_message = message_impl
            ? reinterpret_cast<ObjectGetter>(*reinterpret_cast<void **>(message_impl)) : nullptr;
    void *message = get_message ? get_message(exception, nullptr) : nullptr;
    std::string text = read_string(message);
    if (exception_class_name && !text.empty()) return std::string(exception_class_name) + ": " + text;
    if (exception_class_name) return exception_class_name;
    return text;
}

void append_rpc_fault_diagnostics(const std::string &task_id, const char *kind, bool faulted,
                                  int64_t elapsed_ms, int64_t ms_since_previous_attempt,
                                  int consecutive_faults, const std::string &exception_text) {
    FILE *file = std::fopen(dispatch_rpc_fault_path, "a");
    if (!file) return;
    std::fprintf(file, "%lld\t%s\t%s\t%d\t%" PRId64 "\t%" PRId64 "\t%d\t%s\n",
                 now_ms(), task_id.c_str(), kind ? kind : "", faulted ? 1 : 0, elapsed_ms,
                 ms_since_previous_attempt, consecutive_faults, exception_text.c_str());
    std::fclose(file);
    chmod(dispatch_rpc_fault_path, 0644);
}

void append_dispatch_gate_block(const std::string &task_id, const char *kind, bool lock_held,
                                bool requested, bool has_data, bool has_picked, int picked_count,
                                double distance, double allowed_distance) {
    FILE *file = std::fopen(dispatch_gate_block_path, "a");
    if (!file) return;
    std::fprintf(file, "%lld\t%s\t%s\t%d\t%d\t%d\t%d\t%d\t%.2f\t%.1f\n",
                 now_ms(), task_id.c_str(), kind ? kind : "", lock_held ? 1 : 0, requested ? 1 : 0,
                 has_data ? 1 : 0, has_picked ? 1 : 0, picked_count, distance, allowed_distance);
    std::fclose(file);
    chmod(dispatch_gate_block_path, 0644);
}

void append_dispatch_tick_trace(const std::string &batch_target, bool target_seen_raw,
                                int raw_count, int filtered_count) {
    FILE *file = std::fopen(dispatch_tick_trace_path, "a");
    if (!file) return;
    std::fprintf(file, "%lld\t%s\t%d\t%d\t%d\n",
                 now_ms(), batch_target.c_str(), target_seen_raw ? 1 : 0, raw_count, filtered_count);
    std::fclose(file);
    chmod(dispatch_tick_trace_path, 0644);
}

std::string read_mode() {
    FILE *file = std::fopen(mode_path, "r");
    if (!file) return "diag";
    char value[32]{};
    std::fgets(value, sizeof(value), file);
    std::fclose(file);
    std::string result(value);
    while (!result.empty() && (result.back() == '\n' || result.back() == '\r' || result.back() == ' ')) {
        result.pop_back();
    }
    return result;
}

bool network_available() {
    ifaddrs *interfaces{};
    if (getifaddrs(&interfaces) != 0) return false;
    bool available = false;
    for (ifaddrs *item = interfaces; item; item = item->ifa_next) {
        if (!item->ifa_addr || item->ifa_addr->sa_family != AF_INET) continue;
        const unsigned flags = item->ifa_flags;
        if ((flags & IFF_UP) && !(flags & IFF_LOOPBACK)) {
            available = true;
            break;
        }
    }
    freeifaddrs(interfaces);
    return available;
}

#include "planting_observation.inc"

void write_planting_control_status(const std::string &mode, bool planting,
                                   const char *action, const std::string &petal_id = {}) {
    FILE *file = std::fopen(planting_control_status_path, "w");
    if (!file) return;
    std::fprintf(file, "%lld\t%s\t%d\t%d\t%s\t%s\n", now_ms(), mode.c_str(),
                 planting ? 1 : 0, planting_control_owned ? 1 : 0,
                 action, petal_id.empty() ? "-" : petal_id.c_str());
    std::fclose(file);
    chmod(planting_control_status_path, 0644);
}

void maybe_dismiss_planting_result_dialog() {
    if (!planting_result_auto_close_pending || !planting_result_dialog || !close_planting_result_dialog) return;
    const long long now = now_ms();
    // Only a dialog created after this module-owned stop request is eligible.
    // This prevents a later controller action from closing a player's older,
    // manually-created result dialog.
    if (planting_result_dialog_seen_ms < planting_result_stop_requested_ms || now < planting_result_close_after_ms) return;
    void *task = close_planting_result_dialog(planting_result_dialog, nullptr);
    if (task && gchandle_new) gchandle_new(task, false);
    if(route_stop_started && !route_stop_token.empty()) {
        route_close_task=task;
        if(task && gchandle_new)route_close_handle=gchandle_new(task,false);
    }
    LOGI("[PLANTING-CONTROL] auto-dismissed owned result dialog task=%p", task);
    planting_result_auto_close_pending = false;
}

// "on" and "off" are explicit commands from Control Center.  Anything
// else, including a missing file, is passive observation. This protects a
// manual planting session from being stopped merely because the controller
// has not been installed/configured yet.
std::string read_planting_control_mode() {
    FILE *file = std::fopen(planting_control_mode_path, "r");
    if (!file) return "observe";
    char value[16]{};
    std::fgets(value, sizeof(value), file);
    std::fclose(file);
    std::string result(value);
    while (!result.empty() && (result.back() == '\n' || result.back() == '\r' || result.back() == ' ')) result.pop_back();
    if (result == "on") return "on";
    return result == "off" ? "off" : "observe";
}

bool flower_farm_session_active() {
    FILE *file = std::fopen(flower_farm_mode_path, "r");
    if (!file) return false;
    char value[16]{};
    std::fgets(value, sizeof(value), file);
    std::fclose(file);
    return std::strncmp(value, "on", 2) == 0 && (value[2] == '\0' || value[2] == '\n' || value[2] == '\r');
}

#include "planting_transaction.inc"

// Read-only IL2CPP field API: no guessed v152 offsets, new hooks, managed
// getters, generic invocations or system-GPS fallback in nearby authorization.
void *nearby_value_class(void *field, const char *name, int size) {
    if (!field || !field_get_type || !class_from_type || !class_value_size) return nullptr;
    void *klass = class_from_type(field_get_type(field));
    const char *actual = klass && class_get_name ? class_get_name(klass) : nullptr;
    uint32_t align{};
    return actual && std::strcmp(actual, name) == 0 && class_value_size(klass, &align) == size ? klass : nullptr;
}

long long steady_ms() {
    timespec value{}; clock_gettime(CLOCK_MONOTONIC, &value);
    return static_cast<long long>(value.tv_sec)*1000LL + value.tv_nsec/1000000LL;
}

long long nearby_elapsed_ms() {
    // v152 libichigonative WallTime_GetElapsedWallTimeNanos uses clock id 7:
    // CLOCK_BOOTTIME, including suspend. latestRawLocationWallTimeMs is NOT
    // Unix time. Refuse clock errors; never infer an epoch offset from a fix.
    timespec value{};
    if (clock_gettime(CLOCK_BOOTTIME, &value) != 0) return -1;
    return static_cast<long long>(value.tv_sec)*1000LL + value.tv_nsec/1000000LL;
}

pikmin::NearbyFix read_nearby_game_fix() {
    pikmin::NearbyFix result;
    if (!runtime_metadata_ready || !location_controller || !object_get_class || !class_get_field || !field_get_offset || !field_get_value) return result;
    void *klass = object_get_class(location_controller);
    static void *checked_class{}, *raw_field{}, *processed_field{}, *time_field{};
    static bool layout_ok{};
    if (klass != checked_class) {
        checked_class = klass; layout_ok = false;
        raw_field = class_get_field(klass, "latestRawLocation");
        processed_field = class_get_field(klass, "<LatestDeviceLocation>k__BackingField");
        time_field = class_get_field(klass, "latestRawLocationWallTimeMs");
        void *raw_class = nearby_value_class(raw_field, "LatLngAlt", 24);
        void *processed_class = nearby_value_class(processed_field, "DeviceLocation", 32);
        void *location = processed_class ? class_get_field(processed_class, "Location") : nullptr;
        void *fake = processed_class ? class_get_field(processed_class, "IsFakeLocation") : nullptr;
        void *accuracy = processed_class ? class_get_field(processed_class, "AccuracyMeters") : nullptr;
        void *latlng = raw_class ? class_get_field(raw_class, "LatLng") : nullptr;
        void *altitude = raw_class ? class_get_field(raw_class, "AltitudeMeters") : nullptr;
        void *latlng_class = nearby_value_class(latlng, "LatLng", 16);
        void *lat = latlng_class ? class_get_field(latlng_class, "Lat") : nullptr;
        void *lng = latlng_class ? class_get_field(latlng_class, "Lng") : nullptr;
        // Value-class offsets may include the two-pointer object header.
        const auto first = [](void *f) { return f && (field_get_offset(f) == 0 || field_get_offset(f) == 16); };
        layout_ok = raw_class && processed_class && nearby_value_class(time_field, "Int64", 8)
                && nearby_value_class(location, "LatLngAlt", 24) == raw_class
                && first(location) && fake && accuracy
                && field_get_offset(fake) == field_get_offset(location)+24
                && field_get_offset(accuracy) == field_get_offset(location)+28
                && first(latlng) && altitude && field_get_offset(altitude) == field_get_offset(latlng)+16
                && first(lat) && lng && field_get_offset(lng) == field_get_offset(lat)+8
                && nearby_value_class(lat, "Double", 8) && nearby_value_class(lng, "Double", 8);
        LOGI("[NEARBY-GPS] runtime fields verified=%d raw=%p processed=%p time=%p", layout_ok ? 1 : 0,
                raw_field, processed_field, time_field);
    }
    if (!layout_ok) return result;
    double raw[3]{}, processed[4]{};
    field_get_value(location_controller, raw_field, raw);
    field_get_value(location_controller, processed_field, processed);
    field_get_value(location_controller, time_field, &result.raw_wall_ms);
    result.valid = true; result.raw_lat = raw[0]; result.raw_lng = raw[1];
    result.game_lat = processed[0]; result.game_lng = processed[1];
    return result;
}

void update_nearby_location(long long wall) {
    nearby_fix = read_nearby_game_fix();
    nearby_location_gate.observe(nearby_fix, nearby_elapsed_ms(), steady_ms());
    auto lease=route_lease();
    if(lease.token!=route_gate_token){route_location_gate.begin_session();route_gate_token=lease.token;}
    if(lease.valid())route_location_gate.observe(nearby_fix,nearby_elapsed_ms());else route_location_gate.reset();
    const bool stop_ok=emu_planting_tx::cleanup_ready();
    const std::string route_tmp=std::string(route_status_path)+".tmp";
    if(FILE *f=std::fopen(route_tmp.c_str(),"w")) {
        bool ok=std::fprintf(f,"v1\t%lld\t%d\t%s\t%s\t%d\t%d\t300000\n",wall,getpid(),lease.valid()?lease.token.c_str():"-",
            lease.valid()?lease.phase.c_str():"off",stop_ok?1:0,route_location_gate.ready(nearby_elapsed_ms())?1:0)>0;
        bool closed=std::fclose(f)==0;if(ok&&closed&&chmod(route_tmp.c_str(),0644)==0)std::rename(route_tmp.c_str(),route_status_path);
    }
    bool gate_published=false;
    const std::string temp = std::string(nearby_gate_status_path) + ".tmp";
    if (FILE *file = std::fopen(temp.c_str(), "w")) {
        std::fprintf(file, "v2\t%lld\t%d\t%s\t%llu\t%u\t%.7f\t%.7f\t%.7f\t%.7f\t%lld\t%lld\n",
                wall, getpid(), nearby_location_gate.reason(), static_cast<unsigned long long>(nearby_location_gate.epoch()),
                nearby_location_gate.samples(), nearby_fix.raw_lat, nearby_fix.raw_lng,
                nearby_fix.game_lat, nearby_fix.game_lng, static_cast<long long>(nearby_fix.raw_wall_ms),
                static_cast<long long>(pikmin::kNearbyMaxDurationMs));
        const bool ok = std::fclose(file) == 0;
        gate_published=ok && chmod(temp.c_str(), 0644)==0 && std::rename(temp.c_str(),nearby_gate_status_path)==0;
    }
    // Loaded engine capability must not depend on a valid GPS fix. Both clocks
    // identify this producer lifetime; the location gate and dispatch checks above stay unchanged.
    const long long engine_elapsed=nearby_elapsed_ms();
    if(gate_published && engine_elapsed>0) {
        const std::string engine_tmp=std::string(nearby_engine_capability_path)+".tmp";
        if(FILE *file=std::fopen(engine_tmp.c_str(),"w")) {
            bool ok=std::fprintf(file,"v1\t%lld\t%d\t%lld\tnearby-v2-time-only\t300000\n",wall,getpid(),engine_elapsed)>0;
            bool closed=std::fclose(file)==0;
            if(ok && closed && chmod(engine_tmp.c_str(),0644)==0)std::rename(engine_tmp.c_str(),nearby_engine_capability_path);
        }
    }
}

bool current_location(double &latitude, double &longitude) {
    void *candidates[3]{location_controller, nullptr, nullptr};
    if (interaction_settings) {
        candidates[1] = *reinterpret_cast<void **>(static_cast<uint8_t *>(interaction_settings) + kLocationControllerOffset);
    }
    if (planting_controller) {
        candidates[2] = *reinterpret_cast<void **>(static_cast<uint8_t *>(planting_controller) + kPlantingLocationControllerOffset);
    }
    for (void *controller : candidates) {
        if (!controller) continue;
        // v157 added a controller field and shifted raw location. Resolve the
        // named value-type field rather than reading the v152-era offset.
        if (!object_get_class || !class_get_field || !field_get_value) continue;
        void *raw_field = class_get_field(object_get_class(controller), "latestRawLocation");
        if (!raw_field) continue;
        struct RawPoint { double latitude, longitude, altitude; } raw{};
        field_get_value(controller, raw_field, &raw);
        const double candidate_latitude = raw.latitude;
        const double candidate_longitude = raw.longitude;
        if (std::isfinite(candidate_latitude) && std::isfinite(candidate_longitude) &&
            std::abs(candidate_latitude) > 0.0001 && std::abs(candidate_longitude) > 0.0001 &&
            std::abs(candidate_latitude) <= 90.0 && std::abs(candidate_longitude) <= 180.0) {
            latitude = candidate_latitude;
            longitude = candidate_longitude;
            return true;
        }
    }
    // The Magisk service rewrites this file every two seconds; it is used only
    // when the version-specific in-memory layout is unknown.  Refuse a stale
    // one: on 2026-08-29 a CRLF service.sh never started its loop, so this file
    // sat unchanged for over an hour and pinned the reported position ~90 m
    // from the player.  Distance gates then passed or failed on a location that
    // had not been true for hours, which is exactly the kind of silent wrong
    // answer this module must not give.  Without a fresh fix, fail closed and
    // let the caller treat the distance as unknown.
    struct stat gps_stat {};
    if (::stat(system_gps_path, &gps_stat) != 0) return false;
    const long long age_seconds =
            static_cast<long long>(time(nullptr)) - static_cast<long long>(gps_stat.st_mtime);
    if (age_seconds < 0 || age_seconds > kSystemGpsMaxAgeSeconds) {
        if (age_seconds != last_stale_gps_age_logged) {
            last_stale_gps_age_logged = age_seconds;
            LOGW("[GPS] ignoring stale %s (%lld s old); is service.sh running?",
                 system_gps_path, age_seconds);
        }
        return false;
    }
    FILE *fallback = std::fopen(system_gps_path, "r");
    if (!fallback) return false;
    const int read = std::fscanf(fallback, "%lf\t%lf", &latitude, &longitude);
    std::fclose(fallback);
    return read == 2 && std::isfinite(latitude) && std::isfinite(longitude) &&
           std::abs(latitude) > 0.0001 && std::abs(longitude) > 0.0001 &&
           std::abs(latitude) <= 90.0 && std::abs(longitude) <= 180.0;
}

void load_test_target() {
    if (target_loaded) return;
    target_loaded = true;
    FILE *file = std::fopen(target_path, "r");
    if (!file) return;
    char id[520]{};
    double latitude{}, longitude{};
    if (std::fscanf(file, "%519s\t%lf\t%lf", id, &latitude, &longitude) == 3) {
        FlowerRecord &record = flowers[id];
        record.latitude = latitude;
        record.longitude = longitude;
        record.state = 3;
        record.wilting_ms = 0;
        record.received = false;
        LOGI("[NECTAR-DIAG] fallback target loaded id=%s lat=%.7f lng=%.7f", id, latitude, longitude);
    }
    std::fclose(file);
}

double distance_metres(double lat1, double lng1, double lat2, double lng2) {
    constexpr double kEarthRadius = 6371000.0;
    constexpr double kRadians = 3.14159265358979323846 / 180.0;
    const double p1 = lat1 * kRadians;
    const double p2 = lat2 * kRadians;
    const double dp = (lat2 - lat1) * kRadians;
    const double dl = (lng2 - lng1) * kRadians;
    const double a = std::sin(dp / 2) * std::sin(dp / 2) +
                     std::cos(p1) * std::cos(p2) * std::sin(dl / 2) * std::sin(dl / 2);
    return kEarthRadius * 2.0 * std::atan2(std::sqrt(a), std::sqrt(1.0 - a));
}

bool resolve_request_class() {
    if (request_class) return true;
    if (!game_domain || !domain_get_assemblies || !assembly_get_image || !class_from_name) return false;
    size_t count{};
    const void **assemblies = domain_get_assemblies(game_domain, &count);
    for (size_t index = 0; assemblies && index < count; ++index) {
        void *image = assembly_get_image(assemblies[index]);
        void *klass = class_from_name(image, "Ichigo.Proto", "ClaimPoiFlowerVisitRewardRequestProto");
        if (klass) {
            request_class = klass;
            LOGI("[NECTAR] request class resolved from assembly index=%zu", index);
            return true;
        }
    }
    LOGE("[NECTAR] request class was not found");
    return false;
}

const char *failed_reason_name(int reason) {
    switch (reason) {
        case 0: return "NONE";
        case 1: return "INVENTORY_FULL";
        case 2: return "ALREADY_REWARDED";
        case 3: return "NOT_BLOOMING";
        default: return "UNKNOWN";
    }
}

void append_claim_log(const char *result, int failed_reason, int reward_case, int amount,
                      int honey_type, int flower_kind) {
    FILE *file = std::fopen(claim_log_path, "a");
    if (!file) return;
    std::fprintf(file, "%lld\t%s\t%s\t%.7f\t%.7f\t%.1f\t%d\t%s\t%d\t%d\t%d\t%d\n",
                 now_ms(), result, pending_id.c_str(), pending_gps_lat, pending_gps_lng,
                 pending_distance, failed_reason, failed_reason_name(failed_reason), reward_case,
                 amount, honey_type, flower_kind);
    std::fclose(file);
    chmod(claim_log_path, 0644);
}

void clear_pending_task() {
    // Keep completed Task handles rooted until the game process exits. Releasing a
    // freshly completed async Task here races an IL2CPP continuation in v150 and
    // can crash the Unity main thread. The number of claim Tasks per session is
    // small, so retaining them is the safer trade-off.
    pending_task_handle = 0;
    pending_task = nullptr;
    pending_id.clear();
    pending_intent_binding.clear();
}

void poll_pending_task() {
    if (!pending_task || !task_is_completed || !task_is_faulted ||
            !task_is_completed(pending_task, nullptr)) return;
    const bool faulted = task_is_faulted && task_is_faulted(pending_task, nullptr);
    void *response = *reinterpret_cast<void **>(static_cast<uint8_t *>(pending_task) + 0x50);
    if (faulted || !response) {
        const char *result = faulted ? "RPC_FAULTED" : "NO_RESPONSE";
        last_result = result;
        append_claim_log(result, -1, 0, 0, 0, 0);
        LOGE("[NECTAR] claim completed id=%s result=%s", pending_id.c_str(), result);
        clear_pending_task();
        return;
    }

    auto *response_bytes = static_cast<uint8_t *>(response);
    const int failed_reason = *reinterpret_cast<int *>(response_bytes + 0x28);
    void *rewards = *reinterpret_cast<void **>(response_bytes + 0x20);
    int reward_count{};
    void *array{};
    if (rewards) {
        auto *repeated_bytes = static_cast<uint8_t *>(rewards);
        array = *reinterpret_cast<void **>(repeated_bytes + 0x10);
        reward_count = *reinterpret_cast<int *>(repeated_bytes + 0x18);
        if (reward_count < 0 || reward_count > 32) reward_count = 0;
    }
    const char *result = failed_reason == 0 ? "SUCCESS" : "REJECTED";
    last_result = std::string(result) + ":" + failed_reason_name(failed_reason);
    // Generic faults/rejections never prove no side effect and keep the receipt unresolved.
    if (failed_reason == 0) nectar_intent_guard().resolve_success(pending_intent_binding);
    if (!array || reward_count == 0) {
        append_claim_log(result, failed_reason, 0, 0, 0, 0);
    } else {
        auto *array_bytes = static_cast<uint8_t *>(array);
        for (int index = 0; index < reward_count; ++index) {
            void *reward = *reinterpret_cast<void **>(array_bytes + 0x20 + index * sizeof(void *));
            if (!reward) continue;
            auto *reward_bytes = static_cast<uint8_t *>(reward);
            const int amount = *reinterpret_cast<int *>(reward_bytes + 0x18);
            void *reward_type = *reinterpret_cast<void **>(reward_bytes + 0x28);
            const int reward_case = *reinterpret_cast<int *>(reward_bytes + 0x30);
            int honey_type{}, flower_kind{};
            if (reward_case == 3 && reward_type) {
                auto *honey = static_cast<uint8_t *>(reward_type);
                honey_type = *reinterpret_cast<int *>(honey + 0x18);
                flower_kind = *reinterpret_cast<int *>(honey + 0x1C);
            }
            append_claim_log(result, failed_reason, reward_case, amount, honey_type, flower_kind);
            LOGI("[NECTAR] reward id=%s case=%d amount=%d honey=%d flower=%d",
                 pending_id.c_str(), reward_case, amount, honey_type, flower_kind);
        }
    }
    LOGI("[NECTAR] claim completed id=%s result=%s failedReason=%d rewards=%d",
         pending_id.c_str(), result, failed_reason, reward_count);
    clear_pending_task();
}

// StartExpeditionAsync() returns a plain Task with no response body, so the
// only thing worth reading is IsCompleted/IsFaulted -- the same read-only
// pattern poll_pending_task() already uses safely.  On 2026-08-29 a fruit
// dispatched from 0.2 m produced a "start requested" log line and never
// confirmed; a retry from the same spot minutes later confirmed in 15 s.
// Nothing distinguished the two in the log.  This makes that distinction
// visible: whether the RPC itself ever completed, and whether it faulted.
void poll_pending_expedition_task() {
    if (!pending_expedition_task) return;
    if (!task_is_completed || !task_is_completed(pending_expedition_task, nullptr)) {
        // A Task that never completes would otherwise watch forever.
        if (now_ms() - pending_expedition_task_started_ms > 30000) {
            LOGW("[DISPATCH-RPC] task=%s not completed after 30s; abandoning watch",
                 pending_expedition_task_id.c_str());
            pending_expedition_task = nullptr;
        }
        return;
    }
    const bool faulted = task_is_faulted && task_is_faulted(pending_expedition_task, nullptr);
    const int64_t elapsed_ms = now_ms() - pending_expedition_task_started_ms;
    // Best-effort only: read_task_exception_message() resolves everything
    // dynamically by name and returns empty on any unresolved step, so a
    // build where this does not work costs nothing beyond an empty column.
    const std::string exception_text = faulted ? read_task_exception_message(pending_expedition_task) : std::string();
    consecutive_expedition_rpc_faults = faulted ? consecutive_expedition_rpc_faults + 1 : 0;
    LOGI("[DISPATCH-RPC] task=%s completed faulted=%d elapsedMs=%" PRId64 " streak=%d exception=%s",
         pending_expedition_task_id.c_str(), faulted ? 1 : 0, elapsed_ms, consecutive_expedition_rpc_faults,
         exception_text.empty() ? "-" : exception_text.c_str());
    append_dispatch_history(faulted ? "start-rpc-faulted" : "start-rpc-completed",
                            pending_expedition_task_kind.c_str(), pending_expedition_task_id, elapsed_ms, 0);
    append_rpc_fault_diagnostics(pending_expedition_task_id, pending_expedition_task_kind.c_str(), faulted,
                                 elapsed_ms, pending_expedition_ms_since_previous_attempt,
                                 consecutive_expedition_rpc_faults, exception_text);
    // A faulted RPC never actually started the expedition, so the task
    // stays in the unfinished list forever -- neither of the two normal
    // release paths in write_dispatch_candidates (finish_ms becoming
    // non-zero, or the task disappearing from the projection) can ever
    // fire for it. Without this, armed mode (which has no equivalent of
    // batch mode's Java-side releaseNativeConfirmationLock()) gets stuck
    // reporting "no-dispatch" forever after a single faulted attempt,
    // until dispatch mode is toggled off and back on by hand. Release the
    // lock here instead, at the point the fault is actually detected, so
    // the very next tick can retry.
    if (faulted && dispatch_confirmation_pending_id == pending_expedition_task_id) {
        dispatch_confirmation_pending_id.clear();
        dispatch_confirmation_started_observed_ms = 0;
    }
    // Not releasing the GC handle mirrors clear_pending_task(): freeing a
    // just-completed Task raced an IL2CPP continuation on v150 and crashed
    // the main thread, and expeditions per session are few enough that
    // retaining the handle is the cheaper trade-off.
    pending_expedition_task = nullptr;
}

// Armed mode can have several StartExpeditionAsync calls in flight at once.
// Poll each independently; a completed successful RPC remains in the map
// until the inventory projection confirms that task, while a fault releases
// only its own id for a later retry.  Batch continues to use the single-task
// watcher above so its GPS controller cannot advance early.
void poll_armed_expedition_tasks() {
    for (auto it = armed_dispatches.begin(); it != armed_dispatches.end();) {
        ArmedDispatchInFlight &pending = it->second;
        if (!pending.task || pending.rpc_result_recorded) {
            ++it;
            continue;
        }
        if (!task_is_completed || !task_is_completed(pending.task, nullptr)) {
            if (now_ms() - pending.started_ms > 30000) {
                LOGW("[DISPATCH-RPC] armed task=%s not completed after 30s; retaining inventory watch",
                     it->first.c_str());
                // Do not re-start a request whose managed Task may still run.
                // Inventory confirmation remains the only release path.
                pending.task = nullptr;
            }
            ++it;
            continue;
        }
        const bool faulted = task_is_faulted && task_is_faulted(pending.task, nullptr);
        const int64_t elapsed_ms = now_ms() - pending.started_ms;
        const std::string exception_text = faulted ? read_task_exception_message(pending.task) : std::string();
        consecutive_expedition_rpc_faults = faulted ? consecutive_expedition_rpc_faults + 1 : 0;
        LOGI("[DISPATCH-RPC] armed task=%s completed faulted=%d elapsedMs=%" PRId64 " streak=%d exception=%s",
             it->first.c_str(), faulted ? 1 : 0, elapsed_ms, consecutive_expedition_rpc_faults,
             exception_text.empty() ? "-" : exception_text.c_str());
        append_dispatch_history(faulted ? "start-rpc-faulted" : "start-rpc-completed",
                                pending.kind.c_str(), it->first, elapsed_ms, 0);
        append_rpc_fault_diagnostics(it->first, pending.kind.c_str(), faulted,
                                     elapsed_ms, pending.ms_since_previous_attempt,
                                     consecutive_expedition_rpc_faults, exception_text);
        if (faulted) {
            // A faulted request leaves no expedition in the inventory, so its
            // own entry must be released. Other armed starts stay protected.
            it = armed_dispatches.erase(it);
        } else {
            pending.rpc_result_recorded = true;
            ++it;
        }
    }
}

void write_status(const std::string &mode, bool online, bool planting) {
    const auto pending_status = nectar_intent_guard().status(now_ms(), steady_ms(), pending_task != nullptr);
    const std::string &visible_result = pending_status.empty() ? last_result : pending_status;
    double latitude{}, longitude{};
    const bool has_location = current_location(latitude, longitude);
    FILE *file = std::fopen(status_path, "w");
    if (!file) {
        LOGE("[NECTAR] unable to write status path=%s errno=%d", status_path, errno);
        return;
    }
    std::fprintf(file, "%lld\t%s\t%d\t%d\t%d\t%.7f\t%.7f\t%zu\t%d\t%s\n",
                 now_ms(), mode.c_str(), planting ? 1 : 0, online ? 1 : 0,
                 has_location ? 1 : 0, latitude, longitude, flowers.size(),
                 rpc_manager ? 1 : 0, visible_result.c_str());
    std::fclose(file);
    chmod(status_path, 0644);
}

void maybe_claim() {
    const long long current = now_ms();
    const long long tick = steady_ms();
    if (tick - last_tick_ms < 1000) return;
    last_tick_ms = tick;
    write_identity(current);
    if (FILE *runtime = std::fopen((std::string(core_runtime_path)+".tmp").c_str(), "w")) {
#ifdef PCC_UNIFIED
        const bool written = std::fprintf(runtime, "v1\t%lld\t%d\t0.20.3\t900178\t157.0\n", current, getpid()) > 0;
#else
        const bool written = std::fprintf(runtime, "v1\t%lld\t%d\t1.4.33\t67\t157.0\n", current, getpid()) > 0;
#endif
        const bool closed = std::fclose(runtime) == 0;
        const std::string tmp = std::string(core_runtime_path)+".tmp";
        if (written && closed && chmod(tmp.c_str(),0644)==0) std::rename(tmp.c_str(),core_runtime_path);
    }
    update_nearby_location(current);
    const std::string mode = read_mode();
    poll_pending_task();
    if (nectar_intent_guard().retire_unconfirmed(steady_ms(), pending_task != nullptr)) {
        // Archive before releasing local polling. Never free or adopt the Task.
        clear_pending_task();
        last_result = "UNCONFIRMED_ARCHIVED_TARGET_EXCLUDED";
    }
    poll_pending_expedition_task();
    poll_armed_expedition_tasks();
    const bool online = network_available();
    const bool planting = is_planting();
    write_status(mode, online, planting);
    maybe_manage_planting();
    maybe_return_tasks();
    if (mode != "test_once" && mode != "auto") return;
    if (mode == "test_once" && test_once_sent) return;
    if (pending_task || nectar_unresolved()) return;
    load_test_target();
    // Claiming a visible big flower only needs a live game RPC session and a
    // location.  Flower planting is unrelated and must not gate collection.
    if (!rpc_manager || !online) {
        if (current - last_status_ms >= 5000) {
            last_status_ms = current;
            LOGI("[NECTAR-DIAG] waiting rpc=%d online=%d planting=%d flowers=%zu mode=%s",
                 rpc_manager ? 1 : 0, online ? 1 : 0, planting ? 1 : 0, flowers.size(), mode.c_str());
        }
        return;
    }
    if (current - last_claim_ms < 3000) return;

    double player_lat{}, player_lng{};
    if (!current_location(player_lat, player_lng)) {
        if (current - last_status_ms >= 5000) {
            last_status_ms = current;
            LOGI("[NECTAR-DIAG] waiting for LocationController flowers=%zu", flowers.size());
        }
        return;
    }
    for (auto &entry : flowers) {
        FlowerRecord &flower = entry.second;
        if (flower.attempted || flower.received || flower.state != 3) continue;
        if (flower.wilting_ms > 0 && flower.wilting_ms <= current) continue;
        const double distance = distance_metres(player_lat, player_lng, flower.latitude, flower.longitude);
        // GPS updates and map-object positions can differ by a few metres.
        // Permit a bounded tolerance above the nominal 100 m collection radius.
        if (distance > 120.0) continue;
        if (!resolve_request_class() || !object_new || !string_new || !request_constructor ||
            !request_set_map_object_id || !request_set_include_failure || !send_claim) return;
        // A durable intent precedes all managed request allocation and sending.
        // Uncertain persistence, an old unresolved receipt, or identity changes
        // suppress the send. A persisted-but-unsent intent stays unresolved.
        const auto intent_epoch = capability_registry.begin_scan();
        const auto intent_account = nectar_account_binding();
        const auto intent_target = pikmin::account_digest(
                entry.first + "\t" + std::to_string(flower.wilting_ms));
        if (!intent_epoch.valid || intent_epoch.epoch == 0 ||
                intent_epoch.epoch > static_cast<uint64_t>(INT64_MAX) ||
                !nectar_intent::hex64(intent_account) ||
                !nectar_intent::hex64(intent_target) || !gchandle_new) return;
        const auto admission = nectar_intent_guard().begin(intent_account, intent_target,
                static_cast<int64_t>(intent_epoch.epoch), getpid(), now_ms(), steady_ms());
        if (admission == nectar_intent::Begin::duplicate) {
            flower.attempted = true;
            continue;
        }
        if (admission != nectar_intent::Begin::admitted) return;
        const auto after_intent = capability_registry.begin_scan();
        if (!after_intent.valid || after_intent.epoch != intent_epoch.epoch ||
                nectar_account_binding() != intent_account) return;
        pending_intent_binding = nectar_intent_guard().record().binding;
        void *request = object_new(request_class);
        if (!request) return;
        request_constructor(request, nullptr);
        void *managed_id = string_new(entry.first.c_str());
        request_set_map_object_id(request, managed_id, nullptr);
        request_set_include_failure(request, true, nullptr);
        const auto send_epoch = capability_registry.begin_scan();
        if (!send_epoch.valid || send_epoch.epoch != intent_epoch.epoch ||
                nectar_account_binding() != intent_account) return;
        void *task = send_claim(rpc_manager, request, nullptr, 2, nullptr);
        flower.attempted = true;
        last_claim_ms = current;
        if (mode == "test_once") test_once_sent = true;
        if (task && gchandle_new) {
            const auto handle = gchandle_new(task, false);
            // A failed root leaves the durable intent unresolved. Never poll an
            // unrooted raw Task or replay the request to obtain another handle.
            if (!handle) { last_result = "TASK_ROOT_UNCONFIRMED"; return; }
            pending_task = task;
            pending_task_handle = handle;
            pending_id = entry.first;
            pending_gps_lat = player_lat;
            pending_gps_lng = player_lng;
            pending_distance = distance;
            last_result = "PENDING";
        }
        LOGI("[NECTAR] claim sent id=%s distance=%.1fm gps=%.7f,%.7f task=%p mode=%s",
             entry.first.c_str(), distance, player_lat, player_lng, task, mode.c_str());
        return;
    }
    if (current - last_status_ms >= 5000) {
        last_status_ms = current;
        LOGI("[NECTAR-DIAG] no eligible flower gps=%.7f,%.7f flowers=%zu", player_lat,
             player_lng, flowers.size());
    }
}

void log_flower(void *map_object) {
    if (!map_object || !object_get_class || !class_get_name) return;
    void *klass = object_get_class(map_object);
    const char *class_name = klass ? class_get_name(klass) : nullptr;
    if (!class_name || std::strcmp(class_name, "MapPoiFlower") != 0) return;
    interaction_settings = *reinterpret_cast<void **>(static_cast<uint8_t *>(map_object) + kInteractionSettingsOffset);

    auto *bytes = static_cast<uint8_t *>(map_object);
    void *map_proto = *reinterpret_cast<void **>(bytes + kInitialMapObjectProtoOffset);
    if (!map_proto) return;
    auto *proto_bytes = static_cast<uint8_t *>(map_proto);
    const std::string id = read_string(*reinterpret_cast<void **>(proto_bytes + kMapObjectIdOffset));
    void *point = *reinterpret_cast<void **>(proto_bytes + kMapObjectPointOffset);
    void *flower = *reinterpret_cast<void **>(proto_bytes + kMapObjectPayloadOffset);
    if (id.empty() || !point || !flower) return;

    auto *point_bytes = static_cast<uint8_t *>(point);
    auto *flower_bytes = static_cast<uint8_t *>(flower);
    const double latitude = *reinterpret_cast<double *>(point_bytes + kPointLatitudeOffset);
    const double longitude = *reinterpret_cast<double *>(point_bytes + kPointLongitudeOffset);
    const int state = *reinterpret_cast<int *>(flower_bytes + kFlowerStateOffset);
    const long long remaining = *reinterpret_cast<long long *>(flower_bytes + kFlowerRemainingOffset);
    const long long wilting_ms = *reinterpret_cast<long long *>(flower_bytes + kFlowerWiltingTimeOffset);
    const long long bloomed_ms = *reinterpret_cast<long long *>(flower_bytes + kFlowerBloomedTimeOffset);
    const bool received = *reinterpret_cast<bool *>(flower_bytes + kFlowerRewardReceivedOffset);
    FlowerRecord &record = flowers[id];
    record.latitude = latitude;
    record.longitude = longitude;
    record.state = state;
    record.wilting_ms = wilting_ms;
    record.received = received;

    char state_key[160];
    std::snprintf(state_key, sizeof(state_key), "%d:%lld:%lld:%lld:%d", state, remaining,
                  bloomed_ms, wilting_ms, received ? 1 : 0);
    auto previous = last_flower_state.find(id);
    if (previous != last_flower_state.end() && previous->second == state_key) return;
    last_flower_state[id] = state_key;
    LOGI("[NECTAR-DIAG] id=%s lat=%.7f lng=%.7f state=%d remaining=%lld bloomed=%lld wilting=%lld received=%d",
         id.c_str(), latitude, longitude, state, remaining, bloomed_ms, wilting_ms, received ? 1 : 0);
    FILE *file = std::fopen(flower_log_path, "a");
    if (file) {
        std::fprintf(file, "%lld\t%s\t%.7f\t%.7f\t%d\t%lld\t%lld\t%lld\t%d\n",
                     now_ms(), id.c_str(), latitude, longitude, state, remaining, bloomed_ms,
                     wilting_ms, received ? 1 : 0);
        std::fclose(file);
    }
}

void *hooked_get_flower_proto(void *flower, void *method_info) {
    void *result = original_get_flower_proto ? original_get_flower_proto(flower, method_info) : nullptr;
    log_flower(flower);
    return result;
}
void hooked_construct_proto_object(void *self, void *proto, void *method_info) {
    if (original_construct_proto_object) original_construct_proto_object(self, proto, method_info);
    log_flower(self);
}
void hooked_update_proto_object(void *self, void *proto, void *method_info) {
    if (original_update_proto_object) original_update_proto_object(self, proto, method_info);
    log_flower(self);
}
void hooked_flower_model_updated(void *self, void *method_info) {
    if (original_flower_model_updated) original_flower_model_updated(self, method_info);
    log_flower(self);
    // v152 registers live flowers without consistently calling the manager's
    // Update method.  Run the same mode-gated heartbeat here so diagnostic
    // status and opt-in automation both see those observations.
    maybe_claim();
    maybe_return_tasks();
}
void hooked_rpc_manager_constructor(void *self, void *method_info) {
    capability_registry.on_identity_reset();   // diagnostic epoch first, then the business identity
    invalidate_identity();
    if (original_rpc_manager_constructor) original_rpc_manager_constructor(self, method_info);
    rpc_manager = self;
    LOGI("[NECTAR-DIAG] RpcManager constructed this=%p", self);
}
void hooked_rpc_manager_logged_in(void *self, void *server_url, void *player_id,
                                  void *background_token, void *method_info) {
    const uint64_t login_epoch = capability_registry.on_login(now_ms());   // diagnostic epoch first
    invalidate_identity();
    ScopedManagedRoot player_root(player_id,gchandle_new,gchandle_free);
    rpc_manager = self;
    LOGI("[NECTAR-DIAG] RpcManager logged in this=%p", self);
    if (original_rpc_manager_logged_in) original_rpc_manager_logged_in(self, server_url, player_id, background_token, method_info);
    initialize_runtime_metadata();
#ifdef PCC_UNIFIED
    if (runtime_metadata_ready) pcc_gps_install_after_login();
#endif
    void *string_class=find_class("System","String");
    const bool string_ok=player_id&&string_class&&object_get_class(player_id)==string_class;
    std::string canonical;
    int id_units=0;
    if(player_root.handle&&string_ok){
        const auto *s=static_cast<Il2CppStringLayout *>(player_id);id_units=s->length;
        if(id_units>0&&id_units<=256)for(int i=0;i<id_units;++i){
            canonical.push_back(static_cast<char>(s->chars[i]&255));
            canonical.push_back(static_cast<char>(s->chars[i]>>8));
        }
    }
    const auto digest=pikmin::account_digest(canonical);
    bool identity_ready_now=false;
    {std::lock_guard<std::mutex> lock(identity_mutex);if(identity_logout_hook_ready)identity_hash=digest;identity_ready_now=identity_logout_hook_ready&&!identity_hash.empty();}
    // Outside identity_mutex; carries the epoch this login owns so a newer logout is never overwritten.
    if(identity_ready_now) capability_registry.on_identity_ready(login_epoch);
    LOGI("[SESSION-CHECK] logoutHook=%d rooted=%d stringType=%d units=%d digestReady=%d",
         identity_logout_hook_ready?1:0,player_root.handle?1:0,string_ok?1:0,id_units,digest.empty()?0:1);
    write_identity(now_ms());
}
void hooked_register_map_object(void *self, void *map_object, int tag, void *method_info) {
    const uint64_t epoch_at_entry = capability_registry.current_epoch();   // map time belongs to the session at entry
    if (original_register_map_object) original_register_map_object(self, map_object, tag, method_info);
    // Map objects are registered only after the live game world is active.
    // This is a reliable safe point when the earlier login callback occurred
    // before our direct hooks had been installed.
    initialize_runtime_metadata();
#ifdef PCC_UNIFIED
    if (runtime_metadata_ready) pcc_gps_install_after_login();
#endif
    capability_registry.on_map(now_ms(), epoch_at_entry);
    log_flower(map_object);
    maybe_claim();
    maybe_return_tasks();
}

std::string read_return_mode() {
    FILE *file = std::fopen(return_mode_path, "r");
    if (!file) return "dry-run";
    char value[32]{};
    std::fgets(value, sizeof(value), file);
    std::fclose(file);
    std::string result(value);
    while (!result.empty() && (result.back() == '\n' || result.back() == '\r' || result.back() == ' ')) result.pop_back();
    return result.empty() ? "dry-run" : result;
}

// Dispatch remains fail-closed until the control centre explicitly writes a
// recognised mode. "armed" is the original nearby one-shot mode; "batch"
// additionally requires an exact task id plus a fresh arrival proof.
std::string read_dispatch_mode() {
    FILE *file = std::fopen(dispatch_mode_path, "r");
    if (!file) return "off";
    char value[16]{};
    std::fgets(value, sizeof(value), file);
    std::fclose(file);
    if (std::strcmp(value,"route\n")==0 || std::strcmp(value,"route")==0) return route_lease().phase=="walk"?"route":"off";
    if (std::strncmp(value, "armed", 5) == 0) return "armed";
    return std::strncmp(value, "batch", 5) == 0 ? "batch" : "off";
}

// The filter is fail-open to preserve every existing armed workflow: only an
// exact recognised value narrows candidates. Batch mode never consults it.
std::string read_dispatch_kind_filter() {
    FILE *file = std::fopen(dispatch_kinds_path, "r");
    if (!file) return "all";
    char value[16]{};
    std::fgets(value, sizeof(value), file);
    std::fclose(file);
    std::string result(value);
    while (!result.empty() && (result.back() == '\n' || result.back() == '\r' || result.back() == ' ')) result.pop_back();
    return (result == "seed" || result == "fruit" || result == "gift" || result == "farm") ? result : "all";
}

unsigned read_nearby_selection() {
    FILE *file = std::fopen(nearby_kinds_path, "r");
    if (!file) return errno == ENOENT ? 3 : 0;
    char value[130]{};
    const size_t size = std::fread(value, 1, sizeof(value), file);
    const bool failed = std::ferror(file);
    std::fclose(file);
    return failed ? 0 : pikmin::nearby_selection(std::string(value, size));
}

std::string read_dispatch_target() {
    FILE *file = std::fopen(dispatch_target_path, "r");
    if (!file) return {};
    char value[256]{};
    std::fgets(value, sizeof(value), file);
    std::fclose(file);
    std::string result(value);
    while (!result.empty() && (result.back() == '\n' || result.back() == '\r' || result.back() == ' ')) result.pop_back();
    return result;
}

bool dispatch_target_is_ready(const std::string &target_id, long long observed_ms) {
    FILE *file = std::fopen(dispatch_ready_path, "r");
    if (!file) return false;
    char id[256]{};
    long long ready_ms{};
    const int fields = std::fscanf(file, "%255[^\t]\t%lld", id, &ready_ms);
    std::fclose(file);
    // A readiness proof is intentionally short-lived, so a stale file cannot
    // arm an expedition after the joystick was moved elsewhere.
    return fields == 2 && target_id == id && ready_ms > 0 &&
           observed_ms >= ready_ms && observed_ms - ready_ms <= 30000;
}

void append_dispatch_history(const char *event, const char *kind, const std::string &task_id,
                             int64_t duration_ms, int picked_count) {
    // Lazy construction AFTER Zygote fork. Only owned strings cross threads.
    static auto *writer = new pikmin::AsyncTsvLog;
    const auto time = now_ms();
    const std::string row = std::to_string(time)+"\t"+event+"\t"+kind+"\t"+task_id+"\t"+
            std::to_string(duration_ms)+"\t"+std::to_string(picked_count)+"\n";
    if (!writer->append(dispatch_history_path, row, time)) LOGE("[DISPATCH-LOG] history queue overflow");
}

// Retain exactly one day, matching dispatch_history.tsv. Values originate from
// managed inventory IDs, but normalize TSV delimiters defensively.
void append_dispatch_selection_diagnostics(const char *kind, const std::string &task_id,
                                           const char *phase, int picker_count, int id_count,
                                           int64_t duration_ms, bool can_try_start,
                                           bool carrying_power, const std::string &ids) {
    std::string safe_ids = ids;
    for (char &value : safe_ids) {
        if (value == '\t' || value == '\r' || value == '\n') value = '_';
    }
    static auto *writer = new pikmin::AsyncTsvLog;
    const auto time = now_ms();
    const std::string row = std::to_string(time)+"\t"+kind+"\t"+task_id+"\t"+phase+"\t"+
            std::to_string(picker_count)+"\t"+std::to_string(id_count)+"\t"+
            std::to_string(duration_ms)+"\t"+(can_try_start?"1":"0")+"\t"+
            (carrying_power?"1":"0")+"\t"+safe_ids+"\n";
    if (!writer->append(dispatch_selection_diagnostics_path, row, time)) LOGE("[DISPATCH-LOG] diagnostic queue overflow");
}

// The policy defaults to preserving postcards. Only the explicit "discard"
// value changes the boolean passed to the game's native completion API.
bool return_discard_postcard() {
    FILE *file = std::fopen(return_postcard_policy_path, "r");
    if (!file) return false;
    char value[32]{};
    std::fgets(value, sizeof(value), file);
    std::fclose(file);
    return std::strncmp(value, "discard", 7) == 0;
}

int return_batch_limit() {
    FILE *file = std::fopen(return_batch_limit_path, "r");
    if (!file) return 5;
    char value[16]{};
    std::fgets(value, sizeof(value), file);
    std::fclose(file);
    const long parsed = std::strtol(value, nullptr, 10);
    return parsed >= 1 && parsed <= 5 ? static_cast<int>(parsed) : 5;
}

// One-shot native validation.  This is intentionally separate from dry-run:
// it can dispatch at most one server-backed completion in a process lifetime.
void maybe_dispatch_one_return_task() {
    if (read_return_mode() != "one" || !return_action_manager ||
        !return_inventory_manager || !original_get_pikmin_task_list || !original_complete_pikmin_task ||
        !get_inventory_item_id || !get_pikmin_task_proto || !get_task_finish_time_ms) return;
    void *list = original_get_pikmin_task_list(return_inventory_manager, nullptr);
    if (!list) return;
    void *items = *reinterpret_cast<void **>(static_cast<uint8_t *>(list) + 0x10);
    const int count = *reinterpret_cast<int *>(static_cast<uint8_t *>(list) + 0x18);
    if (!complete_return_list(items, count)) return;
    if (return_one_waiting) {
        const bool pending_seen = task_list_contains_id(items, count, return_batch_pending_id);
        if (pikmin::return_confirmed(true, pending_seen)) {
            return_one_waiting = false;
            write_return_status("one-confirmed", count, 1, false, return_discard_postcard());
            return_batch_pending_id.clear();
            return_batch_pending_reward.clear();
        }
        return;
    }
    if (return_one_dispatched) return;
    const long long now = now_ms();
    for (int index = 0; index < count; ++index) {
        void *task = *reinterpret_cast<void **>(static_cast<uint8_t *>(items) + 0x20 + index * sizeof(void *));
        if (!task) continue;
        void *proto = get_pikmin_task_proto(task, nullptr);
        const int64_t finish_ms = proto ? get_task_finish_time_ms(proto, nullptr) : 0;
        if (!return_proto_ready(proto, now)) continue;
        void *task_id = get_inventory_item_id(task, nullptr);
        if (!task_id) continue;
        if(!task_mutation_allowed(utf8_string(task_id)))continue;
        return_one_dispatched = true;
        return_one_waiting = true;
        return_one_baseline_count = count;
        last_return_batch_mode = "one";
        return_batch_pending_id = utf8_string(task_id);
        return_batch_pending_reward = describe_return_reward(task);
        return_all_empty_reported = false;
        append_return_trace("native-dispatch-one", return_action_manager, task_id);
        const bool discard_postcard = return_discard_postcard();
        write_return_status("one-dispatched", count, 0, true, discard_postcard);
        void *async_task = original_complete_pikmin_task(return_action_manager, task_id, discard_postcard, nullptr);
        if (async_task && gchandle_new) gchandle_new(async_task, false);
        LOGI("[RETURN-DIAG] native one-shot dispatched task=%s async=%p",
             utf8_string(task_id).c_str(), async_task);
        return;
    }
}

void maybe_dispatch_return_batch() {
    const std::string mode = read_return_mode();
    const bool automatic_batch = mode == "batch" || mode == "all";
    if (!automatic_batch) {
        // OFF closes admission, but must not erase an unresolved request.
        // Preserve its exact ID for observation if automatic mode is restored.
        if (!return_batch_waiting && (last_return_batch_mode == "batch" || last_return_batch_mode == "all")) {
            return_batch_waiting = false;
            return_batch_stopped = false;
            return_batch_baseline_count = 0;
            return_batch_completed = 0;
            return_batch_pending_id.clear();
            return_batch_pending_reward.clear();
        }
        last_return_batch_mode = mode;
        return;
    }
    if (last_return_batch_mode != mode && !return_batch_waiting) {
        return_batch_waiting = false;
        return_batch_stopped = false;
        return_batch_baseline_count = 0;
        return_batch_completed = 0;
        return_batch_pending_id.clear();
        return_batch_pending_reward.clear();
        last_return_batch_mode = mode;
    }
    last_return_batch_mode = mode;
    // A timeout forbids another request, not observation of the original one.
    // Only a complete list confirming that exact ID absent can release it.
    if ((return_batch_stopped && !return_batch_waiting) || !return_action_manager ||
        !return_inventory_manager || !original_get_pikmin_task_list || !original_complete_pikmin_task ||
        !get_inventory_item_id || !get_pikmin_task_proto || !get_task_finish_time_ms) return;
    const auto session = capability_registry.begin_scan();
    const auto account = nectar_account_binding();
    if (!session.valid || !session.epoch || account.empty() ||
        (return_batch_waiting && (session.epoch != return_batch_pending_epoch || account != return_batch_pending_account))) return;
    void *list = original_get_pikmin_task_list(return_inventory_manager, nullptr);
    if (!list) return;
    void *items = *reinterpret_cast<void **>(static_cast<uint8_t *>(list) + 0x10);
    const int count = *reinterpret_cast<int *>(static_cast<uint8_t *>(list) + 0x18);
    if (!complete_return_list(items, count)) return;
    const auto after = capability_registry.begin_scan();
    if (!after.valid || after.epoch != session.epoch || nectar_account_binding() != account) return;
    const long long now = now_ms();
    if (return_batch_waiting) {
        const bool pending_seen = task_list_contains_id(items, count, return_batch_pending_id);
        if (pikmin::return_confirmed(true, pending_seen)) {
            return_batch_waiting = false;
            return_batch_stopped = false;
            ++return_batch_completed;
            LOGI("[RETURN-DIAG] batch confirmed completed=%d", return_batch_completed);
            write_return_status("batch-confirmed", count, return_batch_completed, false,
                                return_discard_postcard());
            return_batch_pending_id.clear();
            return_batch_pending_reward.clear();
        } else if (!return_batch_stopped && now - return_batch_dispatched_ms > 60000) {
            return_batch_stopped = true;
            LOGE("[RETURN-DIAG] batch stopped: pending task still present after 60s id=%s count=%d baseline=%d",
                 return_batch_pending_id.c_str(), count, return_batch_baseline_count);
            write_return_status("batch-stopped-timeout", count, return_batch_completed, true,
                                return_discard_postcard());
        }
        return;
    }
    const int batch_limit = mode == "all" ? INT32_MAX : return_batch_limit();
    if (return_batch_completed >= batch_limit) {
        return_batch_stopped = true;
        LOGI("[RETURN-DIAG] batch paused after %d confirmed completions", batch_limit);
        write_return_status("batch-paused-limit", count, return_batch_completed, false,
                            return_discard_postcard());
        return;
    }
    bool excluded_due=false;
    for (int index = 0; index < count; ++index) {
        void *task = *reinterpret_cast<void **>(static_cast<uint8_t *>(items) + 0x20 + index * sizeof(void *));
        if (!task) continue;
        void *proto = get_pikmin_task_proto(task, nullptr);
        const int64_t finish_ms = proto ? get_task_finish_time_ms(proto, nullptr) : 0;
        if (!return_proto_ready(proto, now)) continue;
        void *task_id = get_inventory_item_id(task, nullptr);
        if (!task_id) continue;
        if(!task_mutation_allowed(utf8_string(task_id))){excluded_due=true;continue;}
        const auto before_send = capability_registry.begin_scan();
        if (!before_send.valid || before_send.epoch != session.epoch || nectar_account_binding() != account || read_return_mode() != mode) return;
        return_batch_baseline_count = count;
        return_batch_dispatched_ms = now;
        return_batch_waiting = true;
        return_batch_pending_id = utf8_string(task_id);
        return_batch_pending_epoch = session.epoch;
        return_batch_pending_account = account;
        return_batch_pending_reward = describe_return_reward(task);
        return_all_empty_reported=false;
        append_return_trace("native-dispatch-batch", return_action_manager, task_id);
        const bool discard_postcard = return_discard_postcard();
        write_return_status("batch-dispatched", count, return_batch_completed, true, discard_postcard);
        void *async_task = original_complete_pikmin_task(return_action_manager, task_id, discard_postcard, nullptr);
        if (async_task && gchandle_new) gchandle_new(async_task, false);
        LOGI("[RETURN-DIAG] batch dispatched task=%s async=%p", utf8_string(task_id).c_str(), async_task);
        return;
    }
    if(excluded_due){
        if(!return_all_empty_reported)write_return_status("excluded-prior-unknown",count,return_batch_completed,false,return_discard_postcard());
        return_all_empty_reported=true;return;
    }
    if (mode == "all" && !return_all_empty_reported) {
        return_all_empty_reported = true;
        LOGI("[RETURN-DIAG] all mode idle: no due tasks remain; it will re-arm when a task appears");
        write_return_status("all-complete-no-due", count, return_batch_completed, false,
                            return_discard_postcard());
    }
}
#include "planter_automation.inc"
// InventoryManager layout is pinned independently for each game target.
constexpr size_t feeding_inventory_revision_offset = 0x2F8;
constexpr size_t feeding_inventory_items_offset = 0x2D8;
#include "feeding_probe.inc"
#include "feeding_rpc.inc"
#include "feeding_rotation_rpc.inc"
#include "feeding_coordinator.inc"
#include "auto_petal_switch.inc"

void maybe_return_tasks() {
    const long long current = now_ms();
    // Dispatch is incremental; do not accidentally inherit the one-second
    // return/planter/feed cadence. Their mutation rates remain unchanged.
    static long long last_projection_tick{};
    if(current-last_projection_tick>=250) {
        last_projection_tick=current;
        dry_run_return_tasks();
    }
    if (current - last_return_scheduler_ms < 1000) return;
    last_return_scheduler_ms = current;
    maybe_dispatch_one_return_task();
    maybe_dispatch_return_batch();
    planter_engine::tick();
    feeding_probe::tick();
    feeding_coordinator::tick();
    auto_petal_switch::tick();
    auto_petal_switch::inventory_tick();
}

#include "planting_reward.inc"

#include "late_capture.inc"
#include "game_daily_steps.inc"
#include "planting_catalogue.inc"
#include "daily_resources.inc"
#include "planting_selection.inc"
#include "planting_catalogue_query.inc"
void hooked_map_update(void *self, void *method_info) {
    if (original_map_update) original_map_update(self, method_info);
    initialize_runtime_metadata();
    if(runtime_metadata_ready) pcc_gps_install_after_login();
    emu_late::tick(self);
    emu_daily_steps::tick();
    emu_daily_resources::tick();
    emu_petal::query_tick();
    capability_registry.on_map(now_ms(),capability_registry.current_epoch());
    maybe_claim();
    maybe_return_tasks();
    maybe_collect_planting_reward();
}
void hooked_planting_result_dialog_start(void *self, void *method_info) {
    if (original_planting_result_dialog_start) original_planting_result_dialog_start(self, method_info);
    planting_result_dialog = self;
    planting_result_dialog_seen_ms = now_ms();
    LOGI("[PLANTING-CONTROL] result dialog observed self=%p", self);
}
void *hooked_maybe_show_speed_warning(void *self, void *method_info) {
    // Suppress only the modal presentation during a Control Center flower
    // farm.  SpeedMonitor still samples and exposes IsPlayerSpeeding, so this
    // cannot remove or bypass the game's actual speed restriction.
    if (flower_farm_session_active() && get_completed_task) {
        LOGI("[FLOWER-FARM] suppressed speed warning modal; restriction remains game-controlled");
        return get_completed_task(nullptr);
    }
    return original_maybe_show_speed_warning ? original_maybe_show_speed_warning(self, method_info) : nullptr;
}
void hooked_planting_init(void *self, void *method_info) {
    const uint64_t epoch_at_entry = capability_registry.current_epoch();   // before original
    if (original_planting_init) original_planting_init(self, method_info);
    planting_controller = self;
    if (self) capability_registry.capture(pcc_cap::cap_planting_controller, epoch_at_entry);
    // Capture is idempotent and only reads this actual initialized instance;
    // it installs result/reward observers and binds method metadata without
    // issuing a planting request. Late-capture polling retries if login
    // metadata/session binding is not ready yet.
    emu_planting::capture(self);
    if (self && !planting_controller_metadata_logged && object_get_class) {
        planting_controller_metadata_logged = true;
        void *klass = object_get_class(self);
        log_class_methods("PlantingController", klass);
        void *petal_method = klass ? class_get_method_from_name(klass, "get_FlowerPetalId", 0) : nullptr;
        void *start_method = klass ? class_get_method_from_name(klass, "StartPlantingWithConfirmationAsync", 2) : nullptr;
        void *stop_method = klass ? class_get_method_from_name(klass, "StopPlantingWithConfirmationAsync", 1) : nullptr;
        get_planting_flower_petal_id = petal_method
                ? reinterpret_cast<GetManagedString>(*reinterpret_cast<void **>(petal_method)) : nullptr;
        start_planting_with_confirmation = start_method
                ? reinterpret_cast<StartPlantingWithConfirmation>(*reinterpret_cast<void **>(start_method)) : nullptr;
        stop_planting_with_confirmation = stop_method
                ? reinterpret_cast<StopPlantingWithConfirmation>(*reinterpret_cast<void **>(stop_method)) : nullptr;
        void *dialog_class = find_class("Niantic.Ichigo.Game.Flowers.PlantingResultDialog", "FlowerPlantingResultDialog");
        void *dialog_start_method = dialog_class ? class_get_method_from_name(dialog_class, "Start", 0) : nullptr;
        void *dialog_close_method = dialog_class ? class_get_method_from_name(dialog_class, "OnCloseButtonClicked", 0) : nullptr;
        close_planting_result_dialog = dialog_close_method
                ? reinterpret_cast<NoArgTask>(*reinterpret_cast<void **>(dialog_close_method)) : nullptr;
        if (dialog_start_method && !planting_result_hook_installed) {
            A64HookFunction(*reinterpret_cast<void **>(dialog_start_method),
                            reinterpret_cast<void *>(hooked_planting_result_dialog_start),
                            reinterpret_cast<void **>(&original_planting_result_dialog_start));
            planting_result_hook_installed = original_planting_result_dialog_start != nullptr;
        }
        void *speed_monitor_class = find_class("Niantic.Ichigo.Location", "SpeedMonitor");
        void *speed_warning_method = speed_monitor_class
                ? class_get_method_from_name(speed_monitor_class, "MaybeShowSpeedWarningAsync", 0) : nullptr;
        void *task_class = find_class("System.Threading.Tasks", "Task");
        void *completed_task_method = task_class
                ? class_get_method_from_name(task_class, "get_CompletedTask", 0) : nullptr;
        get_completed_task = completed_task_method
                ? reinterpret_cast<StaticNoArgTask>(*reinterpret_cast<void **>(completed_task_method)) : nullptr;
        if (speed_warning_method && get_completed_task && !speed_warning_hook_installed) {
            A64HookFunction(*reinterpret_cast<void **>(speed_warning_method),
                            reinterpret_cast<void *>(hooked_maybe_show_speed_warning),
                            reinterpret_cast<void **>(&original_maybe_show_speed_warning));
            speed_warning_hook_installed = original_maybe_show_speed_warning != nullptr;
        }
        LOGI("[PLANTING-CONTROL] methods petal=%p start=%p stop=%p",
             get_planting_flower_petal_id, start_planting_with_confirmation,
             stop_planting_with_confirmation);
    }
}
#include "planting_result.inc"
#include "planting_capture.inc"
#include "planting_hooks.inc"
void hooked_location_controller_awake(void *self, void *method_info) {
    const uint64_t epoch_at_entry = capability_registry.current_epoch();   // taken before anything else in the hook
    location_controller = self;
    if (self) capability_registry.capture(pcc_cap::cap_location_controller, epoch_at_entry);
    if (original_location_controller_awake) original_location_controller_awake(self, method_info);
    LOGI("[NECTAR-DIAG] LocationController captured this=%p", self);
}

template <typename T>
void install_hook(uintptr_t base, uintptr_t rva, void *replacement, T &original) {
    A64HookFunction(reinterpret_cast<void *>(base + rva), replacement,
                    reinterpret_cast<void **>(&original));
}

void start(const char *game_data_dir) {
    static std::atomic_flag started = ATOMIC_FLAG_INIT;
    if (started.test_and_set()) return; // one worker, never reinstall direct hooks
    void *handle{};
    capability_registry.set_stage(pcc_cap::Stage::lib_wait);
    for (int attempt = 0; attempt < pcc_bootstrap::Retry::attempts && !handle; ++attempt) {
        handle = xdl_open("libil2cpp.so", XDL_DEFAULT);
        if (!handle && pcc_bootstrap::Retry::another(attempt))
            sleep(pcc_bootstrap::Retry::delay_after(attempt));
    }
    if (!handle) {
        capability_registry.set_bootstrap_failure(pcc_cap::Stage::lib_timeout, pcc_cap::Code::lib_timeout, 0);
        LOGE("[NECTAR] libil2cpp.so discovery exhausted after bounded retry; process recovery required"); return;
    }

    domain_get = reinterpret_cast<DomainGet>(xdl_sym(handle, "il2cpp_domain_get", nullptr));
    domain_get_assemblies = reinterpret_cast<DomainGetAssemblies>(xdl_sym(handle, "il2cpp_domain_get_assemblies", nullptr));
    assembly_get_image = reinterpret_cast<AssemblyGetImage>(xdl_sym(handle, "il2cpp_assembly_get_image", nullptr));
    class_from_name = reinterpret_cast<ClassFromName>(xdl_sym(handle, "il2cpp_class_from_name", nullptr));
    class_get_method_from_name = reinterpret_cast<ClassGetMethodFromName>(
            xdl_sym(handle, "il2cpp_class_get_method_from_name", nullptr));
    class_get_methods = reinterpret_cast<ClassGetMethods>(xdl_sym(handle, "il2cpp_class_get_methods", nullptr));
    class_get_field = reinterpret_cast<ClassGetField>(xdl_sym(handle, "il2cpp_class_get_field_from_name", nullptr));
    field_get_offset = reinterpret_cast<FieldGetOffset>(xdl_sym(handle, "il2cpp_field_get_offset", nullptr));
    field_get_value = reinterpret_cast<FieldGetValue>(xdl_sym(handle, "il2cpp_field_get_value", nullptr));
    field_get_type = reinterpret_cast<FieldGetType>(xdl_sym(handle, "il2cpp_field_get_type", nullptr));
    class_from_type = reinterpret_cast<ClassFromType>(xdl_sym(handle, "il2cpp_class_from_type", nullptr));
    class_value_size = reinterpret_cast<ClassValueSize>(xdl_sym(handle, "il2cpp_class_value_size", nullptr));
    method_get_name = reinterpret_cast<MethodGetName>(xdl_sym(handle, "il2cpp_method_get_name", nullptr));
    method_get_param_count = reinterpret_cast<MethodGetParamCount>(xdl_sym(handle, "il2cpp_method_get_param_count", nullptr));
    object_new = reinterpret_cast<ObjectNew>(xdl_sym(handle, "il2cpp_object_new", nullptr));
    array_new = reinterpret_cast<ArrayNew>(xdl_sym(handle, "il2cpp_array_new", nullptr));
    string_new = reinterpret_cast<StringNew>(xdl_sym(handle, "il2cpp_string_new", nullptr));
    gchandle_new = reinterpret_cast<GcHandleNew>(xdl_sym(handle, "il2cpp_gchandle_new", nullptr));
    gchandle_free = reinterpret_cast<GcHandleFree>(xdl_sym(handle, "il2cpp_gchandle_free", nullptr));
    reward_invoke = reinterpret_cast<RewardInvoke>(xdl_sym(handle, "il2cpp_runtime_invoke", nullptr));
    reward_unbox = reinterpret_cast<RewardUnbox>(xdl_sym(handle, "il2cpp_object_unbox", nullptr));
    planter_engine::configure(handle, game_data_dir);
    feeding_probe::configure(handle, game_data_dir);
    feeding_coordinator::configure(game_data_dir);
    gc_write_barrier = reinterpret_cast<GcWriteBarrier>(xdl_sym(handle, "il2cpp_gc_wbarrier_set_field", nullptr));
    object_get_class = reinterpret_cast<ObjectGetClass>(xdl_sym(handle, "il2cpp_object_get_class", nullptr));
    object_get_virtual_method = reinterpret_cast<ObjectGetVirtualMethod>(xdl_sym(handle, "il2cpp_object_get_virtual_method", nullptr));
    class_get_name = reinterpret_cast<ClassGetName>(xdl_sym(handle, "il2cpp_class_get_name", nullptr));
    Dl_info info{};
    if (!domain_get || !object_get_class || !class_get_name || !dladdr(reinterpret_cast<void *>(domain_get), &info)) {
        capability_registry.set_bootstrap_failure(pcc_cap::Stage::symbols_missing, pcc_cap::Code::symbol, 0);
        LOGE("[NECTAR] unable to resolve IL2CPP runtime symbols"); return;
    }
    // feeding_probe::configure resolved invoke/unbox above; feeding_coordinator::configure only sets paths.
    if (feeding_probe::resolved()) capability_registry.capture(pcc_cap::cap_feeding_probe, 0);
    const auto base = reinterpret_cast<uintptr_t>(info.dli_fbase);
    std::snprintf(flower_log_path, sizeof(flower_log_path), "%s/files/nectar_flowers.tsv", game_data_dir);
    std::snprintf(mode_path, sizeof(mode_path), "%s/files/nectar_rpc_mode.txt", game_data_dir);
    std::snprintf(target_path, sizeof(target_path), "%s/files/nectar_rpc_target.tsv", game_data_dir);
    std::snprintf(status_path, sizeof(status_path), "%s/files/nectar_status.tsv", game_data_dir);
    std::snprintf(claim_log_path, sizeof(claim_log_path), "%s/files/nectar_claims.tsv", game_data_dir);
    std::snprintf(system_gps_path, sizeof(system_gps_path), "%s/files/nectar_system_gps.tsv", game_data_dir);
    std::snprintf(return_trace_path, sizeof(return_trace_path), "%s/files/return_rpc_trace.tsv", game_data_dir);
    std::snprintf(return_history_path, sizeof(return_history_path), "%s/files/return_reward_history.tsv", game_data_dir);
    // These paths are shared with the root-protected Control Center.  Keeping
    // the native reader and controller writer identical prevents a UI setting
    // from silently remaining in dry-run mode.
    std::snprintf(return_mode_path, sizeof(return_mode_path), "/data/local/tmp/pikmin-return-mode.txt");
    std::snprintf(return_postcard_policy_path, sizeof(return_postcard_policy_path), "/data/local/tmp/pikmin-return-postcard-policy.txt");
    std::snprintf(return_status_path, sizeof(return_status_path), "%s/files/return_rpc_status.tsv", game_data_dir);
    std::snprintf(return_candidates_path, sizeof(return_candidates_path), "%s/files/return_candidates.tsv", game_data_dir);
    std::snprintf(return_batch_limit_path, sizeof(return_batch_limit_path), "/data/local/tmp/pikmin-return-batch-limit.txt");
    std::snprintf(compatibility_path, sizeof(compatibility_path), "%s/files/compatibility_status.tsv", game_data_dir);
    std::snprintf(core_runtime_path, sizeof(core_runtime_path), "%s/files/core_runtime.tsv", game_data_dir);
    std::snprintf(identity_path, sizeof(identity_path), "%s/files/automation_session.tsv", game_data_dir);
    std::snprintf(route_status_path, sizeof(route_status_path), "%s/files/route_status.tsv", game_data_dir);
    std::snprintf(dispatch_candidates_path, sizeof(dispatch_candidates_path), "%s/files/dispatch_candidates.tsv", game_data_dir);
    std::snprintf(dispatch_target_candidate_path, sizeof(dispatch_target_candidate_path), "%s/files/dispatch_target_candidate.tsv", game_data_dir);
    std::snprintf(dispatch_availability_path, sizeof(dispatch_availability_path), "%s/files/dispatch_availability.tsv", game_data_dir);
    std::snprintf(dispatch_status_path, sizeof(dispatch_status_path), "%s/files/dispatch_probe_status.tsv", game_data_dir);
    std::snprintf(dispatch_mode_path, sizeof(dispatch_mode_path), "/data/local/tmp/pikmin-dispatch-mode.txt");
    std::snprintf(dispatch_kinds_path, sizeof(dispatch_kinds_path), "/data/local/tmp/pikmin-dispatch-kinds.txt");
    std::snprintf(nearby_kinds_path, sizeof(nearby_kinds_path), "/data/local/tmp/pikmin-nearby-kinds.txt");
    std::snprintf(nearby_status_path, sizeof(nearby_status_path), "%s/files/nearby_dispatch_status.tsv", game_data_dir);
    std::snprintf(nearby_gate_status_path, sizeof(nearby_gate_status_path), "%s/files/nearby_gate_status.tsv", game_data_dir);
    std::snprintf(nearby_engine_capability_path, sizeof(nearby_engine_capability_path), "%s/files/nearby_engine_capability.tsv", game_data_dir);
    std::snprintf(route_dispatch_diagnostics_path, sizeof(route_dispatch_diagnostics_path), "%s/files/route_dispatch_diagnostics.tsv", game_data_dir);
    std::snprintf(dispatch_target_path, sizeof(dispatch_target_path), "/data/local/tmp/pikmin-dispatch-target.txt");
    std::snprintf(dispatch_ready_path, sizeof(dispatch_ready_path), "/data/local/tmp/pikmin-dispatch-ready.tsv");
    std::snprintf(dispatch_history_path, sizeof(dispatch_history_path), "%s/files/dispatch_history.tsv", game_data_dir);
    std::snprintf(dispatch_diagnostics_path, sizeof(dispatch_diagnostics_path), "%s/files/dispatch_diagnostics.tsv", game_data_dir);
    std::snprintf(dispatch_selection_diagnostics_path, sizeof(dispatch_selection_diagnostics_path), "%s/files/dispatch_selection_diagnostics.tsv", game_data_dir);
    std::snprintf(dispatch_rpc_fault_path, sizeof(dispatch_rpc_fault_path), "%s/files/dispatch_rpc_faults.tsv", game_data_dir);
    std::snprintf(dispatch_gate_block_path, sizeof(dispatch_gate_block_path), "%s/files/dispatch_gate_blocks.tsv", game_data_dir);
    std::snprintf(dispatch_tick_trace_path, sizeof(dispatch_tick_trace_path), "%s/files/dispatch_tick_trace.tsv", game_data_dir);
    std::snprintf(dispatch_wire_path, sizeof(dispatch_wire_path), "%s/files/dispatch_wire_v153.tsv", game_data_dir);
    std::snprintf(planting_control_mode_path, sizeof(planting_control_mode_path), "/data/local/tmp/pikmin-planting-mode.txt");
    std::snprintf(planting_control_status_path, sizeof(planting_control_status_path), "%s/files/planting_control_status.tsv", game_data_dir);
    std::snprintf(flower_farm_mode_path, sizeof(flower_farm_mode_path), "/data/local/tmp/pikmin-flower-farm-mode.txt");
    request_constructor = reinterpret_cast<RequestConstructor>(base + kRequestConstructorRva);
    request_set_map_object_id = reinterpret_cast<RequestSetString>(base + kRequestSetMapObjectIdRva);
    request_set_include_failure = reinterpret_cast<RequestSetBool>(base + kRequestSetIncludeFailureRva);
    send_claim = reinterpret_cast<SendClaim>(base + kSendClaimRva);
    struct stat il2cpp_stat{};
    if (!info.dli_fname || stat(info.dli_fname, &il2cpp_stat) != 0
            || il2cpp_stat.st_size != kExpectedIl2CppSize) {
        write_compatibility_status(false, info.dli_fname ? il2cpp_stat.st_size : 0);
        capability_registry.set_bootstrap_failure(pcc_cap::Stage::incompatible, pcc_cap::Code::il2cpp_size,
                                                  info.dli_fname ? static_cast<int64_t>(il2cpp_stat.st_size) : 0);
        LOGE("[NECTAR] unsupported libil2cpp.so; hooks were not installed");
        return;
    }
    write_compatibility_status(true, il2cpp_stat.st_size);
    // RVA-derived pointers for the roadside flower claim are set above; the request
    // class itself is resolved lazily at claim time and is not part of this bit.
    if (request_constructor && request_set_map_object_id && request_set_include_failure && send_claim)
        capability_registry.capture(pcc_cap::cap_nectar_claim, 0);
    task_is_completed = reinterpret_cast<TaskBool>(base + kTaskIsCompletedRva);
    task_is_faulted = reinterpret_cast<TaskBool>(base + kTaskIsFaultedRva);

    install_hook(base, kRegisterMapObjectRva, reinterpret_cast<void *>(hooked_register_map_object), original_register_map_object);
    install_hook(base, kMapObjectManagerUpdateRva, reinterpret_cast<void *>(hooked_map_update), original_map_update);
    install_hook(base, kFlowerModelUpdatedRva, reinterpret_cast<void *>(hooked_flower_model_updated), original_flower_model_updated);
    install_hook(base, kRpcManagerConstructorRva, reinterpret_cast<void *>(hooked_rpc_manager_constructor), original_rpc_manager_constructor);
    install_hook(base, kRpcManagerLoggedInRva, reinterpret_cast<void *>(hooked_rpc_manager_logged_in), original_rpc_manager_logged_in);
    install_hook(base, kPlantingInitRva, reinterpret_cast<void *>(hooked_planting_init), original_planting_init);
    install_hook(base, kPlantingStartRva, reinterpret_cast<void *>(hooked_planting_start), original_planting_start);
    install_hook(base, kPlantingStateUpdatedRva, reinterpret_cast<void *>(hooked_planting_state_updated), original_planting_state_updated);
    install_hook(base, kLocationControllerAwakeRva, reinterpret_cast<void *>(hooked_location_controller_awake), original_location_controller_awake);
    LOGI("[NECTAR] v153 direct hooks installed base=%" PRIxPTR "; metadata waits for login", base);
    // "hooks_called" means install_hook was invoked for each entry, not that any hook succeeded.
    capability_registry.set_stage(pcc_cap::Stage::hooks_called);
}

// Single writer for core_capabilities.tsv (loader heartbeat). Whole-state
// snapshot under the registry lock, format + I/O outside it; tmp+rename keeps
// the previous valid file on any failure. Nothing is logged per tick.
struct CapabilityFileSink final : pcc_cap::Sink {
    std::string tmp, final_path;
    bool write_tmp(const char *line, size_t len) override {
        FILE *f = std::fopen(tmp.c_str(), "w");
        if (!f) return false;
        const bool written = std::fwrite(line, 1, len, f) == len && std::fflush(f) == 0 && !std::ferror(f)
                && fchmod(fileno(f), 0644) == 0;
        const bool closed = std::fclose(f) == 0;
        return written && closed;
    }
    bool rename_tmp() override { return std::rename(tmp.c_str(), final_path.c_str()) == 0; }
    void discard_tmp() override { unlink(tmp.c_str()); }
};

}  // namespace

bool pikmin_capabilities_publish(const char *game_data_dir, bool gps_ready, const char *version, int code) {
    static char line[pcc_cap::kLineCapacity];   // heartbeat thread only
    static pcc_cap::PublishResult last = pcc_cap::PublishResult::published;
    if (!game_data_dir || !game_data_dir[0]) return false;
    capability_registry.set_gps(gps_ready);
    // Time is taken before the snapshot (R7): the line's epochMs never post-dates the state it describes.
    const pcc_cap::Sampled snap = pcc_cap::sample(capability_registry, [] { return static_cast<int64_t>(now_ms()); });
    CapabilityFileSink sink;
    sink.final_path = std::string(game_data_dir) + "/files/core_capabilities.tsv";
    sink.tmp = sink.final_path + ".tmp";
    const auto result = pcc_cap::publish(snap.state, snap.sampled_ms, getpid(), version, code, "157.0", sink, line, sizeof line);
    if (result != last) {   // log transitions only, never every tick
        if (result == pcc_cap::PublishResult::published) LOGI("[CAPS] core_capabilities.tsv publishing again");
        else LOGW("[CAPS] core_capabilities.tsv not updated (result=%d); previous file kept", static_cast<int>(result));
        last = result;
    }
    return result == pcc_cap::PublishResult::published;
}

void hack_prepare(const char *game_data_dir, void *, size_t) {
    std::thread worker(start, game_data_dir);
    worker.detach();
}
