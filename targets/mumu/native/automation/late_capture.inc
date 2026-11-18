// Included only in the generated MuMu overlay, on the original map Update thread.
// Read existing Zenject caches; NEVER Resolve/instantiate a binding or replay login.
namespace emu_planting { void capture(void *); }
namespace emu_late {
using FieldFlags = int (*)(void *);
using TypeCode = int (*)(void *);
using ValueType = bool (*)(void *);
using ArrayLength = uintptr_t (*)(void *);
using ArrayElement = void *(*)(void *);
using ArrayRank = uint32_t (*)(void *);
FieldFlags flags{};
TypeCode type_code{};
ValueType value_type{};
ArrayLength array_length{};
ArrayElement array_element{};
ArrayRank array_rank{};
bool api_tried{}, revoked{}, bound{};
long long last_poll{};
void *bound_container{}, *player_manager{};
std::vector<GcHandle> roots;
std::string proposed_digest;
void *proposed_player{};
uint64_t proposed_epoch{};

bool exact(void *object, const char *ns, const char *name) {
    static std::map<std::string,void *> classes;
    std::string key=std::string(ns)+"."+name;
    auto it=classes.find(key);
    void *klass=it==classes.end()?find_class(ns,name):it->second;
    if(klass) classes[key]=klass;
    return object && klass && object_get_class(object) == klass;
}
void *reference(void *object, const char *name) {
    if (!object) return nullptr;
    void *field = class_get_field(object_get_class(object), name);
    if (!field || (flags(field) & 0x10)) return nullptr;
    void *type = field_get_type(field);
    int code = type ? type_code(type) : 0;
    if (code != 0x0e && code != 0x12 && code != 0x14 && code != 0x1c && code != 0x1d && code != 0x15) return nullptr;
    void *klass = class_from_type(type);
    if (!klass || value_type(klass)) return nullptr;
    void *result{};
    field_get_value(object, field, &result);
    return result;
}
bool boolean(void *object, const char *name, bool &result) {
    void *field = object ? class_get_field(object_get_class(object), name) : nullptr;
    if (!field || (flags(field)&0x10) || type_code(field_get_type(field)) != 2) return false;
    field_get_value(object, field, &result);
    return true;
}
void *method(void *object, const char *name, int count = 0) {
    if (!object) return nullptr;
    void *klass = object_get_class(object);
    void *m = class_get_method_from_name(klass, name, count);
    if (m) return object_get_virtual_method(object, m);
    // Explicit interface implementations, not an arbitrary first overload.
    void *iter{}; int examined = 0;
    const std::string suffix = std::string(".") + name;
    for (m = class_get_methods(klass, &iter); m && examined++ < 256; m = class_get_methods(klass, &iter)) {
        const char *n = method_get_name(m);
        std::string candidate = n ? n : "";
        if (method_get_param_count(m) == static_cast<unsigned>(count) && candidate.ends_with(suffix))
            return object_get_virtual_method(object, m);
    }
    return nullptr;
}
void *call(void *object, const char *name, bool &ok, void **args = nullptr, int count = 0) {
    void *m = method(object, name, count), *exception{};
    if (!ok || !m) { if(ok) LOGI("[EMU-LATE] missing read method=%s",name); ok = false; return nullptr; }
    void *result = reward_invoke(m, object, args, &exception);
    if (exception) { LOGI("[EMU-LATE] read exception method=%s",name); ok = false; }
    return ok ? result : nullptr;
}
int list_size(void *list, bool &ok) {
    void *boxed = call(list, "get_Count", ok);
    void *value = boxed ? reward_unbox(boxed) : nullptr;
    if (!value) { ok = false; return 0; }
    int n = *static_cast<int *>(value);
    if (n < 0 || n > 4096) { ok = false; return 0; }
    return n;
}
void *list_item(void *list, int index, bool &ok) {
    void *args[]{&index}; return call(list, "get_Item", ok, args, 1);
}
struct Found {
    void *preparer{}, *inventory{}, *rpc{}, *action{}, *store{}, *planting{}, *location{}, *player{};
    bool ambiguous{};
    void add(void *object, int depth=0) {
        if(!object || depth>4) return;
        // Existing, typed injected owners only. Feature locking wraps the raw store.
        if(exact(object,"Niantic.Ichigo.Game.Expedition.Data","FeatureLockedExpeditionDataStore") ||
           exact(object,"Niantic.Ichigo.Game.PikminTasks.ExpeditionTasks","ExpeditionPartyMapObjectManager") ||
           exact(object,"Niantic.Ichigo.Game.PikminTasks.ExpeditionTasks","ExpeditionSpawnMapObjectManager"))
            add(reference(object,"expeditionDataStore"),depth+1);
        if(exact(object,"Niantic.Ichigo.Game.AdventureScene","FlowerPlantingResultPlayer"))
            add(reference(object,"flowerPlantingController"),depth+1);
        // v154 existing ZenScope manager retains both the real controller and
        // its inventory. Never read the installer prefab or materialize a binding.
        // Found's ambiguity/session checks must accept the same inventory before
        // this controller may be published.
        if(exact(object,"Niantic.Ichigo.Game.Flowers","FlowerPlantingManager")) {
            add(reference(object,"inventoryManager"),depth+1);
            add(reference(object,"flowerPlantingController"),depth+1);
        }
        struct Target { const char *ns, *name; void **slot; } targets[]{
            {"Niantic.Ichigo.Game.PikminTasks", "PikminTaskCompletionPreparer", &preparer},
            {"Niantic.Ichigo.Inventory", "InventoryManager", &inventory},
            {"Niantic.Ichigo.Rpc", "RpcManager", &rpc},
            {"Niantic.Ichigo.Game.PikminTasks", "PikminTaskActionManager", &action},
            {"Niantic.Ichigo.Game.Expedition.Data", "ExpeditionDataStore", &store},
            {"Niantic.Ichigo.Game.Flowers", "FlowerPlantingController", &planting},
            {"Niantic.Ichigo.Location", "LocationController", &location},
            {"Niantic.Ichigo.Player", "PlayerManager", &player}
        };
        for (const auto &t : targets) if (exact(object,t.ns,t.name)) {
            if (*t.slot && *t.slot != object) ambiguous = true;
            *t.slot = object;
        }
    }
};
void cached(void *provider, Found &found, bool &ok) {
    if (exact(provider,"Zenject","InstanceProvider")) found.add(reference(provider,"_instance"));
    else if (exact(provider,"Zenject","CachedProvider")) {
        void *list = reference(provider,"_instances");
        if (!list) return; // Unmaterialized binding; never instantiate it.
        ScopedManagedRoot root(list,gchandle_new,gchandle_free);
        int n = list_size(list,ok);
        for (int i=0; i<n && ok; ++i) found.add(list_item(list,i,ok));
    } else if(exact(provider,"Zenject","CachedOpenTypeProvider")) {
        void *groups=reference(provider,"_cachedInstances");
        if(!groups) return;
        ScopedManagedRoot root(groups,gchandle_new,gchandle_free);
        int n=list_size(groups,ok);
        for(int i=0;i<n&&ok;++i) {
            void *list=list_item(groups,i,ok);
            ScopedManagedRoot lr(list,gchandle_new,gchandle_free);
            int size=list_size(list,ok);
            for(int j=0;j<size&&ok;++j) found.add(list_item(list,j,ok));
        }
    }
}
bool providers(void *container, Found &found, int &count) {
    bool ok = true;
    void *sequence = call(container,"get_AllProviders",ok);
    ScopedManagedRoot sr(sequence,gchandle_new,gchandle_free);
    void *enumerator = call(sequence,"GetEnumerator",ok);
    ScopedManagedRoot er(enumerator,gchandle_new,gchandle_free);
    if (!sr.handle || !er.handle) return false;
    for (count=0; ok && count<4096; ++count) {
        void *boxed = call(enumerator,"MoveNext",ok);
        void *value = boxed ? reward_unbox(boxed) : nullptr;
        if (!value) return false;
        if (!*static_cast<bool *>(value)) return ok;
        cached(call(enumerator,"get_Current",ok),found,ok);
    }
    return false;
}
bool graph(void *container, Found &found, int &count, std::set<void *> &seen) {
    if(seen.count(container)) return true;
    if(seen.size()>=16 || !exact(container,"Zenject","DiContainer")) return false;
    seen.insert(container);
    int own{};
    if(!providers(container,found,own)) return false;
    count+=own;
    bool ok=true;
    void *parents=call(container,"get_ParentContainers",ok);
    ScopedManagedRoot root(parents,gchandle_new,gchandle_free);
    if(!ok||!root.handle||!array_length||!array_element||!array_rank) return false;
    void *klass=object_get_class(parents);
    if(array_rank(klass)!=1 || array_element(klass)!=find_class("Zenject","DiContainer")) return false;
    const uintptr_t length=array_length(parents);
    if(length>16) return false;
    // Validated rank-one reference array; same arm64 IL2CPP header used by canonical core.
    auto items=reinterpret_cast<void **>(static_cast<unsigned char *>(parents)+0x20);
    for(uintptr_t i=0;i<length;++i) if(!graph(items[i],found,count,seen)) return false;
    return true;
}
std::string account(void *player, void *inventory, void *rpc) {
    bool started = false;
    if (!exact(player,"Niantic.Ichigo.Player","PlayerManager") ||
        reference(player,"inventoryManager") != inventory || reference(player,"rpcManager") != rpc ||
        !boolean(player,"isInventoryManagerStarted",started) || !started) return {};
    bool ok=true;
    void *info = call(player,"get_PlayerInfo",ok);
    ScopedManagedRoot ir(info,gchandle_new,gchandle_free);
    if (!ir.handle || !exact(info,"Niantic.Ichigo.Player","PlayerInfo")) return {};
    void *id = reference(info,"PlayerId");
    ScopedManagedRoot root(id,gchandle_new,gchandle_free);
    if (!root.handle || !exact(id,"System","String")) return {};
    const auto *s = static_cast<Il2CppStringLayout *>(id);
    if (s->length<=0 || s->length>256) return {};
    std::string canonical;
    for (int i=0;i<s->length;++i) {
        canonical.push_back(static_cast<char>(s->chars[i]&255));
        canonical.push_back(static_cast<char>(s->chars[i]>>8));
    }
    return pikmin::account_digest(canonical);
}
void clear() {
#if defined(PCC_UNIFIED)
    clear_display_inventory();
#endif
    bound=false; bound_container=nullptr; player_manager=nullptr;
    proposed_digest.clear(); proposed_player=nullptr;
    return_preparer=nullptr; return_inventory_manager=nullptr; rpc_manager=nullptr;
    return_action_manager=nullptr; expedition_data_store=nullptr;
    planting_controller=nullptr; location_controller=nullptr;
    get_planting_flower_petal_id=nullptr;
    start_planting_with_confirmation=nullptr; stop_planting_with_confirmation=nullptr;
    for (auto h:roots) if (h) gchandle_free(h);
    roots.clear();
}
void report(const char *state, const Found &f, int count) {
    char path[512]; std::snprintf(path,sizeof(path),"%s",core_runtime_path);
    std::string base(path); base=base.substr(0,base.find_last_of('/'))+"/emulator_late_capture.tsv";
    std::string temp=base+".tmp";
    FILE *out=std::fopen(temp.c_str(),"w");
    if (!out) return;
    bool ok=std::fprintf(out,"v1\t%lld\t%d\t%s\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\tend\n",
        now_ms(),getpid(),state,count,f.preparer!=nullptr,f.inventory!=nullptr,f.rpc!=nullptr,
        f.action!=nullptr,f.store!=nullptr,f.planting!=nullptr,f.location!=nullptr,f.player!=nullptr)>0;
    bool closed=std::fclose(out)==0;
    if(ok&&closed&&chmod(temp.c_str(),0644)==0) std::rename(temp.c_str(),base.c_str());
}
void tick(void *map) {
    if (!runtime_metadata_ready || revoked || now_ms()-last_poll<5000) return;
    last_poll=now_ms();
    const uint64_t entry_epoch=capability_registry.current_epoch();
    if (!api_tried) {
        api_tried=true; void *lib=xdl_open("libil2cpp.so",XDL_DEFAULT);
        flags=reinterpret_cast<FieldFlags>(xdl_sym(lib,"il2cpp_field_get_flags",nullptr));
        type_code=reinterpret_cast<TypeCode>(xdl_sym(lib,"il2cpp_type_get_type",nullptr));
        value_type=reinterpret_cast<ValueType>(xdl_sym(lib,"il2cpp_class_is_valuetype",nullptr));
        array_length=reinterpret_cast<ArrayLength>(xdl_sym(lib,"il2cpp_array_length",nullptr));
        array_element=reinterpret_cast<ArrayElement>(xdl_sym(lib,"il2cpp_class_get_element_class",nullptr));
        array_rank=reinterpret_cast<ArrayRank>(xdl_sym(lib,"il2cpp_class_get_rank",nullptr));
    }
    Found found{}; int count=0;
    if (!flags||!type_code||!value_type||!reward_invoke||!reward_unbox||!gchandle_new||!gchandle_free) {
        report("api-missing",found,0); return;
    }
    if (!exact(map,"Niantic.Ichigo.Game.MapObjects","MapObjectManager")) {
        report("map-type-mismatch",found,0);return;
    }
    void *factory=reference(map,"mapObjectFactory");
    void *container=exact(factory,"Niantic.Ichigo.Game.MapObjects","MapObjectFactory")
        ? reference(factory,"diContainer") : nullptr;
    if (!exact(container,"Zenject","DiContainer")) { report("waiting-container",found,0);return; }
    if (bound) {
        const auto digest=account(player_manager,return_inventory_manager,rpc_manager);
        std::string current; {std::lock_guard<std::mutex> lock(identity_mutex);current=identity_hash;}
        if (container!=bound_container || digest.empty() || digest!=current) {
            revoked=true; capability_registry.on_identity_reset(); invalidate_identity(); clear();
            report("session-changed-reload-required",found,0);
            return;
        }
        // Lazy UI controllers may materialize AFTER the coherent session was
        // bound. Read only existing provider instances; never Resolve/Init.
        // The graph walk itself is capped at 4096 providers / 16 containers.
        if (!planting_controller) {
            ScopedManagedRoot cr(container,gchandle_new,gchandle_free);
            std::set<void *> seen;
            if (!cr.handle || !graph(container,found,count,seen)) return;
            if (found.preparer) {
                found.add(reference(found.preparer,"inventoryManager"));
                found.add(reference(found.preparer,"rpcManager"));
            }
            if (found.ambiguous || !found.planting || found.preparer!=return_preparer ||
                found.inventory!=return_inventory_manager || found.rpc!=rpc_manager ||
                found.player!=player_manager || revoked ||
                capability_registry.current_epoch()!=entry_epoch) return;
            planting_controller=found.planting;
            emu_planting::capture(found.planting);
            if (!get_planting_flower_petal_id || !start_planting_with_confirmation ||
                !stop_planting_with_confirmation) planting_controller=nullptr;
            report(planting_controller?"lazy-planting-bound":"lazy-planting-wait",found,count);
        } else {
            // The controller's init hook can publish the real instance before
            // this coherent login binding or metadata is ready. Retry the
            // typed, side-effect-free capture on the existing 5-second poll;
            // otherwise the non-null pointer suppresses lazy discovery forever
            // and the result/reward hooks remain unavailable after manual UI use.
            emu_planting::capture(planting_controller);
        }
        return;
    }
    ScopedManagedRoot cr(container,gchandle_new,gchandle_free);
    std::set<void *> seen;
    if (!cr.handle || !graph(container,found,count,seen)) { report("provider-read-failed",found,count);return; }
    // These are already-injected dependencies, never constructed or resolved from a binding.
    if(found.preparer) {
        found.add(reference(found.preparer,"inventoryManager"));
        found.add(reference(found.preparer,"rpcManager"));
    }
    // Require a unique coherent injected graph, not merely objects of matching classes.
    if (found.ambiguous || !found.preparer || !found.inventory || !found.rpc || !found.player ||
        reference(found.preparer,"inventoryManager")!=found.inventory ||
        reference(found.preparer,"rpcManager")!=found.rpc) { report("waiting-coherent-graph",found,count);return; }
    const auto digest=account(found.player,found.inventory,found.rpc);
    bool logout_ready;{std::lock_guard<std::mutex> lock(identity_mutex);logout_ready=identity_logout_hook_ready;}
    if (digest.empty()||!logout_ready) { report("waiting-live-identity",found,count);return; }
    if(revoked || capability_registry.current_epoch()!=entry_epoch) {
        report("session-raced",found,count);return;
    }
    if (proposed_digest!=digest || proposed_player!=found.player || proposed_epoch!=entry_epoch) {
        proposed_digest=digest;proposed_player=found.player;proposed_epoch=entry_epoch;
        report("confirming-identity",found,count);return;
    }
    for (void *object:{container,found.preparer,found.inventory,found.rpc,found.action,found.store,
            found.planting,found.location,found.player}) if(object) {
        auto h=gchandle_new(object,false);
        if(!h){clear();report("root-failed",found,count);return;} roots.push_back(h);
    }
    if(revoked || capability_registry.current_epoch()!=entry_epoch) {
        clear();report("session-raced",found,count);return;
    }
    const uint64_t epoch=capability_registry.on_login(now_ms());
    invalidate_identity();
    return_preparer=found.preparer;return_inventory_manager=found.inventory;rpc_manager=found.rpc;
    return_action_manager=found.action;expedition_data_store=found.store;
    location_controller=found.location;player_manager=found.player;bound_container=container;
    {std::lock_guard<std::mutex> lock(identity_mutex);identity_hash=digest;}
    capability_registry.on_identity_ready(epoch);
    capability_registry.capture(pcc_cap::cap_preparer,epoch);
#if defined(PCC_UNIFIED)
    capture_display_inventory(found.inventory,epoch);
#endif
    if(found.action) capability_registry.capture(pcc_cap::cap_action_manager,epoch);
    if(found.location) capability_registry.capture(pcc_cap::cap_location_controller,epoch);
    // Use the same typed capture path for an initially-present controller and
    // a later lazy controller. It installs the pinned result Hook without
    // replaying Init or sending a planting request. Capture requires bound.
    planting_controller=found.planting;
    bound=true;
    if(found.planting) {
        emu_planting::capture(found.planting);
        if (!get_planting_flower_petal_id || !start_planting_with_confirmation ||
            !stop_planting_with_confirmation) planting_controller=nullptr;
    }
    write_identity(now_ms());report("bound",found,count);
    LOGI("[EMU-LATE] coherent existing graph bound; providers=%d",count);
}
} // namespace emu_late
