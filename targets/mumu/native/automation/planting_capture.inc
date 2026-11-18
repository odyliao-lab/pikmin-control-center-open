// MuMu-only post-login metadata capture, from an observed controller callback.
// No Init replay, RPC invocation, control-file write or ownership takeover.
namespace emu_planting {
void capture(void *self) {
    if (!self || !runtime_metadata_ready || !object_get_class || !class_get_method_from_name ||
        !emu_late::bound || emu_late::revoked) return;
    void *expected=find_class("Niantic.Ichigo.Game.Flowers","FlowerPlantingController");
    void *klass=object_get_class(self);
    if (!expected || klass!=expected || planting_controller!=self) return;
    emu_planting_tx::install_result_hook();
    install_planting_reward_hook();
    if (get_planting_flower_petal_id && start_planting_with_confirmation && stop_planting_with_confirmation) return;
    // Late capture can bind inventory before this lazily-created controller exists.
    // Start/OnStateUpdated supplies the same real instance, after its original callback.
    void *petal=class_get_method_from_name(klass,"get_FlowerPetalId",0);
    void *start=class_get_method_from_name(klass,"StartPlantingWithConfirmationAsync",2);
    void *stop=class_get_method_from_name(klass,"StopPlantingWithConfirmationAsync",1);
    if (!petal || !start || !stop || !*static_cast<void **>(petal) || !*static_cast<void **>(start) || !*static_cast<void **>(stop)) return;
    // Hold the instance before retaining any pointer. Session reset releases it
    // with all other emu_late roots; no captured pointer crosses logout.
    auto root=gchandle_new(self,false);
    if (!root) return;
    emu_late::roots.push_back(root);
    get_planting_flower_petal_id=reinterpret_cast<GetManagedString>(*static_cast<void **>(petal));
    start_planting_with_confirmation=reinterpret_cast<StartPlantingWithConfirmation>(*static_cast<void **>(start));
    stop_planting_with_confirmation=reinterpret_cast<StopPlantingWithConfirmation>(*static_cast<void **>(stop));
    capability_registry.capture(pcc_cap::cap_planting_controller,capability_registry.current_epoch());
    LOGI("[EMU-PLANTING] observed lazy controller; start/stop metadata ready; no request sent");
}
}
