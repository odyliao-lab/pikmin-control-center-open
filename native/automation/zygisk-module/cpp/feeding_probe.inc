// v152 opt-in, read-only catalogue probe. No feeding or picking RPC is resolved.
// Included after planter_automation.inc for its atomic-file writer only.
#include "feeding_catalogue.h"
#include "feeding_observation.h"
#include "feeding_read_request.h"
#include <cmath>
namespace feeding_probe {
using Invoke = void *(*)(void *, void *, void **, void **);
using Unbox = void *(*)(void *);
Invoke invoke{};
Unbox unbox{};
void *list_method{}, *proto_method{}, *count_method{}, *color_method{}, *kind_method{};
void *petal_list_method{}, *petal_proto_method{}, *petal_count_method{}, *petal_color_method{}, *petal_kind_method{}, *to_honey_method{};
void *capacity_list_method{}, *capacity_proto_method{}, *capacity_petal_method{};
void *kind_text_method{}, *petal_kind_text_method{};
void *kind_to_text_method{};
bool attempted{}, compatible{}, ok{};
long long last_tick{};
char path[512]{};
feeding_observation::Snapshot latest;
feeding_observation::Snapshot collecting;
std::string current_account() {
    std::lock_guard<std::mutex> lock(identity_mutex);
    return identity_logout_hook_ready ? identity_hash : std::string{};
}
void *static_call(void *method, void **args = nullptr) {
    if(!ok || !method){ok=false;return nullptr;}
    void *exception{};
    void *result=invoke(method,nullptr,args,&exception);
    if(exception)ok=false;
    return ok?result:nullptr;
}

void *call(void *method, void *object, void **args = nullptr) {
    if (!ok || !method || !object) { ok = false; return nullptr; }
    void *exception{};
    void *value = invoke(method, object, args, &exception);
    if (exception) ok = false;
    return ok ? value : nullptr;
}
void *get(void *object, const char *name, int argc = 0, void **args = nullptr) {
    if (!object || !ok) { ok = false; return nullptr; }
    return call(class_get_method_from_name(object_get_class(object), name, argc), object, args);
}
int integer(void *boxed) {
    if (!boxed || !ok) { ok = false; return -1; }
    void *value = unbox(boxed);
    if (!value) { ok = false; return -1; }
    return *static_cast<int *>(value);
}
std::string read_flower_text(void *value) {
    ScopedManagedRoot root(value, gchandle_new, gchandle_free);
    if (!ok || !value) { ok = false; return {}; }
    const auto *text = static_cast<Il2CppStringLayout *>(value);
    if (text->length < 0 || text->length > 80) { ok = false; return {}; }
    std::string result;
    for (int i = 0; i < text->length; ++i) {
        if (text->chars[i] > 127 || text->chars[i] == 0) { ok = false; return {}; }
        result.push_back(static_cast<char>(text->chars[i]));
    }
    return result;
}
std::string flower_text(void *method, void *proto) {
    return read_flower_text(call(method, proto));
}
int64_t server_revision() {
    if(!return_inventory_manager || !wire_field_matches(object_get_class(return_inventory_manager),"latestServerSideTimestamp",feeding_inventory_revision_offset)) {
        ok=false;return 0;
    }
    return *reinterpret_cast<int64_t *>(static_cast<uint8_t *>(return_inventory_manager)+feeding_inventory_revision_offset);
}
void *confirmed_item(void *item) {
    if(!ok || !item || !return_inventory_manager ||
        !wire_field_matches(object_get_class(return_inventory_manager),"itemsById",feeding_inventory_items_offset)) {ok=false;return nullptr;}
    void *dictionary=*reinterpret_cast<void **>(static_cast<uint8_t *>(return_inventory_manager)+feeding_inventory_items_offset);
    ScopedManagedRoot dictionary_root(dictionary,gchandle_new,gchandle_free);
    void *id_method=verified_v152_method(find_class("Niantic.Ichigo.Inventory","InventoryItem"),"get_Id",0,0x72A5540);
    void *id=call(id_method,item);
    ScopedManagedRoot id_root(id,gchandle_new,gchandle_free);
    void *args[]{id};
    void *predicted=get(dictionary,"get_Item",1,args);
    ScopedManagedRoot predicted_root(predicted,gchandle_new,gchandle_free);
    if(!ok || !predicted){ok=false;return nullptr;}
    void *klass=object_get_class(predicted);
    void *has_method=verified_v152_method(klass,"get_HasPredictors",0,0xA4BA0C4);
    void *confirmed_method=verified_v152_method(klass,"get_ConfirmedValue",0,0xA4B9FE0);
    void *has_box=call(has_method,predicted);
    ScopedManagedRoot has_root(has_box,gchandle_new,gchandle_free);
    void *has_value=ok&&has_box?unbox(has_box):nullptr;
    // Manual operations can still be locally predicted. Wait for their server
    // confirmation instead of basing a new mutation on optimistic stock.
    if(!has_value || *static_cast<unsigned char *>(has_value)){ok=false;return nullptr;}
    void *confirmed=call(confirmed_method,predicted);
    if(!confirmed)ok=false;
    return confirmed;
}
std::string matched_family(int legacy, const std::string &text) {
    if (!ok || legacy < 0) { ok = false; return {}; }
    if (legacy == 0) return feeding_catalogue::family(legacy, text);
    if (legacy == 5) return feeding_catalogue::family(5, "");
    // Match the game's enum-first identity. Use its live catalogue mapping,
    // never a guessed table, to join newer string nectar with legacy petals.
    void *args[]{&legacy}; void *exception{};
    void *value = invoke(kind_to_text_method, nullptr, args, &exception);
    if (exception) { ok = false; return {}; }
    const auto mapped = read_flower_text(value);
    if (!ok || mapped.empty()) { ok = false; return {}; }
    return feeding_catalogue::family(0, mapped);
}
void publish(const char *state, const std::string &rows = "", int count = 0) {
    planter_engine::atomic_file(path, "v1\t" + std::to_string(now_ms()) + "\t" +
        std::to_string(getpid()) + "\tobserve-only\t" + state + "\t-\t" +
        std::to_string(count) + "\n" + rows + "end\n");
}
#include "feeding_squad_probe.inc"
void capture(int rotation_hearts=0) {
    const auto current = now_ms();
    latest={};collecting={};collecting.started=current;collecting.account=current_account();
    if (!invoke || !unbox || !object_get_class || !gchandle_new || !gchandle_free || !return_inventory_manager) {
        publish("waiting-inventory"); return;
    }
    if (!attempted) {
        attempted = true;
        list_method = verified_v152_method(find_class("Niantic.Ichigo.Inventory", "InventoryManager"), "GetHoneyBallList", 0, 0x7297C4C);
        proto_method = verified_v152_method(find_class("Niantic.Ichigo.Inventory", "HoneyBallInventoryItem"), "get_Proto", 0, 0x7281428);
        void *proto = find_class("Ichigo.Proto", "HoneyBallProto");
        count_method = verified_v152_method(proto, "get_NumBalls", 0, 0x5F753A0);
        color_method = verified_v152_method(proto, "get_HoneyType", 0, 0x5F753B0);
        kind_method = verified_v152_method(proto, "get_HoneyFlowerKind", 0, 0x5F753C0);
        kind_text_method = verified_v152_method(proto, "get_FlowerKind", 0, 0x5F753D0);
        kind_to_text_method = verified_v152_method(find_class("Niantic.Ichigo.ModelControllers.Flowers", "FlowerKindLookup"), "KindToObsoleteString", 1, 0x711A138);
        petal_list_method = verified_v152_method(find_class("Niantic.Ichigo.Inventory", "InventoryManager"), "GetFlowerPetalList", 0, 0x72993EC);
        petal_proto_method = verified_v152_method(find_class("Niantic.Ichigo.Inventory", "FlowerPetalInventoryItem"), "get_Proto", 0, 0x7285604);
        void *petal_proto = find_class("Ichigo.Proto", "FlowerPetalProto");
        petal_count_method = verified_v152_method(petal_proto, "get_NumPetal", 0, 0x64E0194);
        petal_color_method = verified_v152_method(petal_proto, "get_FlowerType", 0, 0x64E0174);
        petal_kind_method = verified_v152_method(petal_proto, "get_Kind", 0, 0x64E0184);
        petal_kind_text_method = verified_v152_method(petal_proto, "get_FlowerKind", 0, 0x64E01A4);
        to_honey_method = verified_v152_method(find_class("Niantic.Ichigo.Utils.Proto", "HoneyBallProtoExtensions"), "ToHoneyType", 1, 0x6D3E180);
        capacity_list_method = verified_v152_method(find_class("Niantic.Ichigo.Inventory", "InventoryManager"), "GetItemCapacityList", 0, 0x729AE80);
        capacity_proto_method = verified_v152_method(find_class("Niantic.Ichigo.Inventory", "ItemCapacityInventoryItem"), "get_Proto", 0, 0x7289DA0);
        capacity_petal_method = verified_v152_method(find_class("Ichigo.Proto", "ItemCapacityProto"), "get_Petal", 0, 0x67B8C74);
        compatible = list_method && proto_method && count_method && color_method && kind_method
                && petal_list_method && petal_proto_method && petal_count_method && petal_color_method && petal_kind_method && to_honey_method
                && capacity_list_method && capacity_proto_method && capacity_petal_method && kind_text_method && petal_kind_text_method && kind_to_text_method;
    }
    if (!compatible) { publish("method-mismatch"); return; }
    ok = true;
    collecting.server_revision=server_revision();
    if(!ok || collecting.server_revision<=0){publish("server-inventory-unavailable");return;}
    void *list = call(list_method, return_inventory_manager);
    ScopedManagedRoot list_root(list, gchandle_new, gchandle_free);
    int count = integer(get(list, "get_Count"));
    if (!ok || count < 0 || count > 512) { publish("invalid-inventory"); return; }
    // GetPetalCapacity(v152) reads this CurrentCount when the per-player proto exists;
    // its missing-proto fallback uses client settings, which this probe does NOT guess.
    int capacity = -1;
    void *capacity_list = call(capacity_list_method, return_inventory_manager);
    ScopedManagedRoot capacity_list_root(capacity_list, gchandle_new, gchandle_free);
    int capacity_count = integer(get(capacity_list, "get_Count"));
    if (!ok || capacity_count < 0 || capacity_count > 1) { publish("invalid-capacity"); return; }
    if (capacity_count == 1) {
        int index = 0; void *args[]{&index};
        void *item = get(capacity_list, "get_Item", 1, args);
        ScopedManagedRoot item_root(item, gchandle_new, gchandle_free);
        void *confirmed=confirmed_item(item);
        ScopedManagedRoot confirmed_root(confirmed,gchandle_new,gchandle_free);
        void *proto = call(capacity_proto_method, confirmed);
        ScopedManagedRoot proto_root(proto, gchandle_new, gchandle_free);
        void *petal = proto ? call(capacity_petal_method, proto) : nullptr;
        ScopedManagedRoot petal_root(petal, gchandle_new, gchandle_free);
        if (petal && ok) {
            void *method = verified_v152_method(object_get_class(petal), "get_CurrentCount", 0, 0x67BA1B0);
            capacity = integer(call(method, petal));
            if (capacity <= 0 || capacity > 1000000) ok = false;
        }
    }
    if (!ok) { publish("invalid-capacity"); return; }
    // Use the game's explicit flower-to-nectar conversion, not equal enum values.
    std::map<std::pair<std::string,int>, int> petal_stocks;
    void *petal_list = call(petal_list_method, return_inventory_manager);
    ScopedManagedRoot petal_list_root(petal_list, gchandle_new, gchandle_free);
    int petal_count = integer(get(petal_list, "get_Count"));
    if (!ok || petal_count < 0 || petal_count > 512) { publish("invalid-petals"); return; }
    for (int i = 0; i < petal_count && ok; ++i) {
        void *args[]{&i};
        void *item = get(petal_list, "get_Item", 1, args);
        ScopedManagedRoot item_root(item, gchandle_new, gchandle_free);
        void *confirmed=confirmed_item(item);
        ScopedManagedRoot confirmed_root(confirmed,gchandle_new,gchandle_free);
        void *p = call(petal_proto_method, confirmed);
        ScopedManagedRoot proto_root(p, gchandle_new, gchandle_free);
        int stock = integer(call(petal_count_method, p));
        int flower = integer(call(petal_color_method, p));
        int kind = integer(call(petal_kind_method, p));
        const std::string kind_text = flower_text(petal_kind_text_method, p);
        const std::string family = matched_family(kind, kind_text);
        if (!ok || stock < 0 || stock > 1000000 || kind < 0 || flower < 0 || flower > 7) { ok = false; break; }
        void *color_args[]{&flower}; void *exception{};
        void *converted = invoke(to_honey_method, nullptr, color_args, &exception);
        if (exception) { ok = false; break; }
        int color = integer(converted);
        if (color < 1 || color > 4 || family.empty()) { ok = false; break; }
        if (!petal_stocks.emplace(std::make_pair(family,color),stock).second) ok = false;
    }
    if (!ok) { publish("invalid-petals"); return; }
    std::string rows;
    std::set<std::string> ids;
    int total = 0;
    void *item_id_method=verified_v152_method(find_class("Niantic.Ichigo.Inventory","InventoryItem"),"get_Id",0,0x72A5540);
    if(!item_id_method){publish("item-id-method-mismatch");return;}
    std::set<std::string> item_ids;
    for (int i = 0; i < count && ok; ++i) {
        void *args[]{&i};
        void *item = get(list, "get_Item", 1, args);
        ScopedManagedRoot item_root(item, gchandle_new, gchandle_free);
        void *confirmed=confirmed_item(item);
        ScopedManagedRoot confirmed_root(confirmed,gchandle_new,gchandle_free);
        void *proto = call(proto_method, confirmed);
        ScopedManagedRoot proto_root(proto, gchandle_new, gchandle_free);
        int stock = integer(call(count_method, proto));
        int color = integer(call(color_method, proto));
        int kind = integer(call(kind_method, proto));
        const std::string kind_text = flower_text(kind_text_method, proto);
        LOGI("[FEED-PROBE] row=%d/%d getters=%d stock=%d color=%d legacy=%d textLength=%zu", i, count, ok, stock, color, kind, kind_text.size());
        if (!ok) { publish("nectar-getter-failed"); return; }
        if (stock < 0 || stock > 1000000 || kind < 0) { publish("nectar-value-invalid"); return; }
        // Exclude HAPPY/special nectar. Unknown ordinary identities invalidate the snapshot.
        if (color == 5) continue;
        const std::string family = matched_family(kind, kind_text);
        if (color < 1 || color > 4) { publish("nectar-color-unknown"); return; }
        if (family.empty()) { publish("nectar-kind-unknown"); return; }
        std::string id = family + ":" + std::to_string(color);
        if (!ids.insert(id).second) { publish("nectar-duplicate-kind"); return; }
        const char *colors[]{"other", "white", "red", "blue", "yellow"};
        // The full confirmed petal enumeration succeeded above. A nectar kind
        // with no petal item yet has zero petals (including never-plucked kinds).
        // Failed/partial/predicted inventories still return before this point.
        const int petals = feeding_observation::confirmed_petal_stock(petal_stocks,family,color,ok);
        const auto item_id=read_flower_text(call(item_id_method,item));
        if(!ok || item_id.empty() || feeding_catalogue::family(0,item_id).empty() || !item_ids.insert(item_id).second) {
            publish("item-id-invalid");return;
        }
        collecting.nectar.push_back({id,item_id,stock,petals,capacity,color,family=="flower-5"});
        // IDs are catalogue keys, NOT RPC ItemId. No localized name or capacity is guessed.
        rows += "kind\t" + id + "\t" + family + "\t" +
            (kind_text.empty() ? (family == "flower-5" ? std::string("普通精華") : std::string("花種 #") + std::to_string(kind)) : kind_text) +
            "\t" + colors[color] + "\t" + (family == "flower-5" ? "1" : "0") + "\t" +
            std::to_string(stock) + "\t" + std::to_string(petals) + "\t" + std::to_string(capacity) + "\n";
        ++total;
    }
    if (!ok) publish("invalid-inventory");
    else {
        publish("catalogue-unverified", rows, total);
        observe_squad(rotation_hearts);
        const auto final_revision=server_revision();
        if(ok && collecting.server_revision==final_revision && !collecting.account.empty() && collecting.account==current_account()) {
            collecting.captured=now_ms();collecting.complete=true;latest=std::move(collecting);
        }
    }
}
void tick() {
    const auto current=now_ms();
    if(current-last_tick<5000)return;
    last_tick=current;
    std::ifstream control("/data/local/tmp/pikmin-feeding-probe.txt");
    std::string mode,extra;control>>mode;
    const bool diagnostic=mode=="observe" && !(control>>extra);
    std::ifstream request("/data/local/tmp/pikmin-feeding-read-request.tsv",std::ios::binary);
    char buffer[81]{};request.read(buffer,sizeof(buffer));
    const bool requested=!request.bad() && feeding_read_request::valid(std::string(buffer,static_cast<size_t>(request.gcount())),current,getpid());
    if (mode != "observe" && !requested) return;
    if(!diagnostic && !requested)return;
    capture();
}
// Diagnostic only: whether configure() resolved the two IL2CPP entry points the probe needs.
bool resolved() { return invoke != nullptr && unbox != nullptr; }
void configure(void *handle, const char *game_data_dir) {
    invoke = reinterpret_cast<Invoke>(xdl_sym(handle, "il2cpp_runtime_invoke", nullptr));
    unbox = reinterpret_cast<Unbox>(xdl_sym(handle, "il2cpp_object_unbox", nullptr));
    std::snprintf(path, sizeof(path), "%s/files/feeding_snapshot.tsv", game_data_dir);
    std::snprintf(squad_path, sizeof(squad_path), "%s/files/feeding_squad_snapshot.tsv", game_data_dir);
}
} // namespace feeding_probe
