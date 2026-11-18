// v154 RVA 0x5F5346C is NOT MonoBehaviour.Start: preserve the Task<bool>
// return and both managed arguments plus MethodInfo. MuMu overlay only.
void *hooked_planting_start(void *self, void *petal, bool confirmation, void *method_info) {
    // Mark foreign/manual entry BEFORE its original can send anything. A late
    // inactive observation must not authorize another start or petal switch.
    emu_planting_tx::note_start(self,petal,confirmation);
    void *task = original_planting_start
            ? original_planting_start(self, petal, confirmation, method_info) : nullptr;
    planting_controller = self;
    emu_planting::capture(self);
    return task;
}

// v154 RVA 0x5F53CA8 has one managed PlantingStateInventoryItem argument.
void hooked_planting_state_updated(void *self, void *state, void *method_info) {
    if (original_planting_state_updated) original_planting_state_updated(self, state, method_info);
    planting_controller = self;
    emu_planting::capture(self);
}
