// Included inside feeding_probe; opt-in raw observations, not feeding permission.
char squad_path[512]{};
size_t rotation_cursor{};
void publish_squad(const char *state, const std::string &rows = "", int count = 0) {
    // A published error must also invalidate the in-memory coordinator input.
    if(std::strcmp(state,"squad-unverified")!=0)ok=false;
    planter_engine::atomic_file(squad_path, "v2\t" + std::to_string(now_ms()) + "\t" +
        std::to_string(getpid()) + "\tobserve-only\t" + state + "\t" +
        std::to_string(count) + "\n" + rows + "end\n");
}
void observe_squad(int rotation_hearts=0) {
    ok = true;
    void *inventory = find_class("Niantic.Ichigo.Inventory", "InventoryManager");
    void *item_class = find_class("Niantic.Ichigo.Inventory", "PikminInventoryItem");
    void *proto_class = find_class("Ichigo.Proto", "PikminProto");
    void *list_m = verified_v152_method(inventory, "GetPikminList", 0, 0x7297664);
    void *proto_m = verified_v152_method(item_class, "get_Proto", 0, 0x7280450);
    void *id_m = verified_v152_method(find_class("Niantic.Ichigo.Inventory", "InventoryItem"), "get_Id", 0, 0x72A5540);
    void *status_m = verified_v152_method(proto_class, "get_StatusCase", 0, 0x5A252A8);
    void *troop_m = verified_v152_method(find_class("Niantic.Ichigo.Game.Pikmins", "PikminUtils"), "IsPikminInTroop", 2, 0x591D164);
    void *flower_m = verified_v152_method(proto_class, "get_FlowerState", 0, 0x5A24ED8);
    void *count_m = verified_v152_method(proto_class, "get_FlowerStateFlowerCount", 0, 0x5A24F08);
    void *yield_m = verified_v152_method(proto_class, "get_NextNumPetals", 0, 0x5A25084);
    void *hearts_m = verified_v152_method(find_class("Niantic.Ichigo.Utils.Proto", "PikminProtoExtensions"), "FriendshipHearts", 1, 0x6D45964);
    void *extensions=find_class("Niantic.Ichigo.Utils.Proto","PikminProtoExtensions");
    void *restriction_m=verified_v152_method(extensions,"ShouldRestrictActions",2,0x6D45658);
    void *flower_color_m=verified_v152_method(extensions,"GetFlowerColor",1,0x6D45E84);
    void *flower_kind_m=verified_v152_method(extensions,"GetFlowerKind",1,0x6D45EAC);
    void *flower_text_m=verified_v152_method(extensions,"GetFlowerKindString",1,0x6D47340);
    void *clock_class=find_class("Niantic.Ichigo.Utils","ServerClock");
    void *clock_m=verified_v152_method(clock_class,"get_Instance",0,0x6CF3E18);
    void *clock_time_m=verified_v152_method(clock_class,"get_CurrentTimeMs",0,0x6CF4184);
    if (!list_m || !proto_m || !id_m || !status_m || !troop_m || !flower_m || !count_m || !yield_m || !hearts_m ||
        !restriction_m || !flower_color_m || !flower_kind_m || !flower_text_m || !clock_m || !clock_time_m) {
        publish_squad("method-mismatch"); return;
    }
    void *clock=static_call(clock_m);
    ScopedManagedRoot clock_root(clock,gchandle_new,gchandle_free);
    void *clock_box=call(clock_time_m,clock);
    ScopedManagedRoot clock_box_root(clock_box,gchandle_new,gchandle_free);
    void *clock_value=ok&&clock_box?unbox(clock_box):nullptr;
    int64_t server_time=clock_value?*static_cast<int64_t *>(clock_value):0;
    if(server_time<=0){publish_squad("clock-unavailable");return;}
    void *list = call(list_m, return_inventory_manager);
    ScopedManagedRoot list_root(list, gchandle_new, gchandle_free);
    int count = integer(get(list, "get_Count"));
    if (!ok || count < 0 || count > 4096) { publish_squad("invalid-list"); return; }
    std::string rows; std::set<std::string> ids; int total = 0,standby_reads=0;
    const int start=rotation_hearts&&count?static_cast<int>(rotation_cursor%count):0;
    for (int position = 0; position < count && ok; ++position) {
        int i=(start+position)%count;
        // New roster work must not stall the automation lane. Discard the
        // whole snapshot on budget exhaustion; never send from partial data.
        if(rotation_hearts&&now_ms()-collecting.started>800){ok=false;break;}
        void *args[]{&i};
        void *item = get(list, "get_Item", 1, args);
        ScopedManagedRoot item_root(item, gchandle_new, gchandle_free);
        void *confirmed=confirmed_item(item);
        ScopedManagedRoot confirmed_root(confirmed,gchandle_new,gchandle_free);
        void *proto = call(proto_m, confirmed);
        ScopedManagedRoot proto_root(proto, gchandle_new, gchandle_free);
        int status = integer(call(status_m, proto));
        if (!ok || (status != 0 && status != 1 && status != 2 && status != 32)) {
            LOGI("[FEED-SQUAD] row=%d status=%d getters=%d", i, status, ok);
            publish_squad("status-invalid"); return;
        }
        // Use the same predicate as PikminInTroopTracker.UpdateTroopStatus.
        // v152 marks ordinary troop members Available=1; Entourage=32 alone
        // is NOT current squad membership. Some local carry tasks also qualify.
        void *troop_args[]{proto, return_inventory_manager}; void *troop_exception{};
        void *troop_box = invoke(troop_m, nullptr, troop_args, &troop_exception);
        ScopedManagedRoot troop_root(troop_box, gchandle_new, gchandle_free);
        void *troop_value = !troop_exception && troop_box ? unbox(troop_box) : nullptr;
        if (!troop_value) { publish_squad("troop-query-failed"); return; }
        const bool in_troop=*static_cast<unsigned char *>(troop_value)!=0;
        if (!in_troop && (!rotation_hearts || status!=32 || collecting.standby.size()>=40 || standby_reads>=64)) continue;
        if(!in_troop){++standby_reads;rotation_cursor=static_cast<size_t>(i+1);}
        std::string id = read_flower_text(call(id_m, item));
        if (!ok || id.empty() || feeding_catalogue::family(0,id).empty() || !ids.insert(id).second) {
            LOGI("[FEED-SQUAD] row=%d idLength=%zu getters=%d", i, id.size(), ok);
            publish_squad("identity-invalid"); return;
        }
        int flower = integer(call(flower_m, proto));
        int flower_count = integer(call(count_m, proto));
        int next_petals = integer(call(yield_m, proto));
        void *heart_args[]{proto}; void *exception{};
        void *heart_box = ok ? invoke(hearts_m, nullptr, heart_args, &exception) : nullptr;
        ScopedManagedRoot heart_root(heart_box, gchandle_new, gchandle_free);
        void *heart_value = !exception && heart_box ? unbox(heart_box) : nullptr;
        float hearts = heart_value ? *static_cast<float *>(heart_value) : -1.f;
        void *restriction_args[]{proto,&server_time};
        void *restriction_box=static_call(restriction_m,restriction_args);
        ScopedManagedRoot restriction_root(restriction_box,gchandle_new,gchandle_free);
        void *restriction_value=ok&&restriction_box?unbox(restriction_box):nullptr;
        if(!restriction_value){publish_squad("restriction-unavailable");return;}
        bool restricted=*static_cast<unsigned char *>(restriction_value)!=0;
        std::string flower_key;
        if(flower==5) {
            void *proto_args[]{proto};
            int flower_color=integer(static_call(flower_color_m,proto_args));
            int flower_kind=integer(static_call(flower_kind_m,proto_args));
            std::string text=read_flower_text(static_call(flower_text_m,proto_args));
            auto family=matched_family(flower_kind,text);
            void *color_args[]{&flower_color};
            int honey_color=integer(static_call(to_honey_method,color_args));
            if(!ok || family.empty() || honey_color<1 || honey_color>4){publish_squad("flower-identity-unavailable");return;}
            flower_key=family+":"+std::to_string(honey_color);
        }
        if (!ok || !std::isfinite(hearts) || hearts < 0 || hearts > 8 || flower_count < 0 ||
            next_petals < 0 || next_petals > 1000000 ||
            (flower != 0 && flower != 1 && flower != 3 && flower != 4 && flower != 5 && flower != 6)) { ok = false; break; }
        // Raw fields only. No inference of idle/cost/yield/canFeed authorization.
        feeding_observation::Pikmin observed{id,flower_key,status,static_cast<int>(std::ceil(hearts*1000.f)),flower,flower_count,next_petals,restricted};
        if(!in_troop) {
            if(!restricted && observed.heart_milli<rotation_hearts*1000 && (flower==1||flower==3||flower==4))
                collecting.standby.push_back(observed);
            continue;
        }
        collecting.squad.push_back(observed);
        rows += "pikmin\t" + id + "\t" + std::to_string(status) + "\t" + std::to_string(hearts) + "\t" +
            std::to_string(flower) + "\t" + std::to_string(flower_count) + "\t" + std::to_string(next_petals) + "\t" +
            (restricted ? "1" : "0") + "\n";
        if (++total > 100) { ok = false; break; }
    }
    if(rotation_hearts&&now_ms()-collecting.started>800)ok=false;
    if (!ok) publish_squad("invalid-squad");
    else {collecting.roster_complete=rotation_hearts>0;publish_squad("squad-unverified", rows, total);}
}
