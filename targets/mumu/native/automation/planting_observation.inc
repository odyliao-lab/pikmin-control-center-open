// MuMu-only read-only observation. Missing metadata is UNKNOWN, never stopped.
namespace emu_late {
extern bool bound, revoked;
void *reference(void *, const char *);
bool boolean(void *, const char *, bool &);
}
namespace emu_planting_observation {
struct Snapshot { bool known; bool active; const char *reason; };
Snapshot read() {
    if (!runtime_metadata_ready || !emu_late::bound || emu_late::revoked)
        return {false, false, "session-unavailable"};
    if (!planting_controller || !object_get_class || !class_get_field || !field_get_value)
        return {false, false, "controller-unavailable"};
    void *expected = find_class("Niantic.Ichigo.Game.Flowers", "FlowerPlantingController");
    if (!expected || object_get_class(planting_controller) != expected)
        return {false, false, "controller-type-mismatch"};
    void *sender = emu_late::reference(planting_controller, "isPlanting");
    if (!sender) return {false, false, "sender-unavailable"};
    bool active{};
    // Actual v154 ChangeSender<bool> has a private instance bool `item`.
    // No guessed offset, generic getter invocation or pointer-sized bool write.
    if (!emu_late::boolean(sender, "item", active))
        return {false, false, "sender-bool-unavailable"};
    return {true, active, active ? "active" : "inactive"};
}
void publish(const Snapshot &value) {
    static long long last{};
    const long long now = now_ms();
    if (now >= last && now - last < 1000) return;
    last = now;
    std::string path(core_runtime_path);
    path = path.substr(0, path.find_last_of('/')) + "/emulator_planting_observation.tsv";
    const std::string temp = path + ".tmp";
    FILE *out = std::fopen(temp.c_str(), "w");
    if (!out) return;
    const bool written = std::fprintf(out, "v1\t%lld\t%d\t%s\t%s\tend\n", now, getpid(),
        value.known ? (value.active ? "active" : "inactive") : "unknown", value.reason) > 0;
    const bool closed = std::fclose(out) == 0;
    if (written && closed && chmod(temp.c_str(), 0644) == 0) std::rename(temp.c_str(), path.c_str());
}
}
bool is_planting() {
    const auto value = emu_planting_observation::read();
    emu_planting_observation::publish(value);
    // Existing bool-only cleanup callers must not turn an unreadable state into
    // confirmation of stopped. maybe_manage_planting separately blocks UNKNOWN.
    return !value.known || value.active;
}
