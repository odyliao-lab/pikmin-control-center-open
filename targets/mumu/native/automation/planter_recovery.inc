// Automatic adoption: never reads manual request nonces or replays an old intent.
// Retention failures retry every normal tick. They never become a manual stop.
char recovery_state_path[512]{};
bool retention_retry{};
void recovery_status(const std::string &result) {
    atomic_file(recovery_state_path,"v1\t"+std::to_string(now_ms())+"\t"+std::to_string(getpid())+
            "\tauto\t"+result+"\n");
}
bool recover(const Snapshot &s) {
    if (pending.watch.active) return false;
    std::string retained;
    const auto outcome = pending_store().archive(archive_confirmed, steady_ms(), retained);
    if (outcome == pikmin::planter::PendingStore::Archive::retry) {
        if (!retention_retry) {
            history("archive-retry", "", -1, "original remains untouched; automatic current-state adoption");
            adoption.wait(steady_ms(), true);
        }
        retention_retry=true;
        // An inaccessible old journal must not stop enabled work indefinitely.
        // A future create is exclusive and cannot overwrite this original.
    } else {
        retention_retry=false;
    }
    if (outcome == pikmin::planter::PendingStore::Archive::retained) {
        history(archive_confirmed ? "confirmed-record-retained" : "unknown-record-retained", "", -1,
            retained + (archive_event.empty() ? " restart" : " " + archive_event));
        adoption.wait(steady_ms(), !archive_confirmed);
    }
    archive_event.clear(); archive_confirmed=false;
    const bool ready = adoption.observe(s.complete && current_session(s), s.epoch, s.account, steady_ms());
    recovery_status(ready ? "ready" : "observing-current-state");
    if (!ready) status(mode() == "off" ? "off" : "observing-current-state");
    return ready;
}
