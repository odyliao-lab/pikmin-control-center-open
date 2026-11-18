// Uses the existing, exact-ELF-gated detail and Canvas callbacks. No second
// inline hook on DoUpdate, no retained raw managed object, no display toggle.
namespace gps_carousel_adapter {
std::array<GcHandle,8> pages{};
gps_carousel::Selection selection;
void *active_getter{};
bool checked{},ready{};

bool metadata() {
    if(checked)return ready;checked=true;
    auto behaviour=find("UnityEngine","Behaviour");
    active_getter=behaviour?method_named(behaviour,"get_isActiveAndEnabled",0):nullptr;
    ready=display_game.adult.rva && active_getter && key_getter
        && field_is(detail_class,"isFocused",0x1c8)
        && field_is(detail_class,"<Expedition>k__BackingField",0x1c0);
    return ready;
}
void track(void *page) {
    if(!metadata()||!is(page,detail_class))return;
    for(auto &h:pages)if(h&&target(h)==page)return;
    for(auto &h:pages)if(!h||!target(h)) {
        if(h)release(h);h=weak_new(page,false);return;
    }
    // Pool capacity exceeded: fail closed, never guess which page is current.
    ready=false;selection.cancel();
}
void tick() {
    if(!ready)return;
    void *selected=nullptr;unsigned count=0;
    for(auto &h:pages)if(h){
        auto page=target(h);if(!page){release(h);h=0;continue;}
        if(!read<bool>(page,0x1c8))continue;
        auto boxed=call(active_getter,page);auto active=boxed?unbox(boxed):nullptr;
        if(!active||!read<bool>(active,0))continue;
        selected=page;++count;
    }
    auto data=count==1?read<void*>(selected,0x1c0):nullptr;
    auto key=is(data,expedition_data_class)?string_value(call(key_getter,data)):std::u16string{};
    const auto now=std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    if(selection.observe(key,now,count==1&&pcc_gps_detail_enabled())) {
        // Re-read the selected page's authoritative data after settlement.
        if(pcc_gps_copy_selected_expedition(data))selection.copied();
        else selection.observe({},now,false); // bounded retry after another settlement
    }
}
}
