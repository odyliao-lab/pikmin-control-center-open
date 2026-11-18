from pathlib import Path
root = Path(__file__).resolve().parents[4]
for relative in ("native/automation/zygisk-module/cpp/nectar_rpc.cpp", "targets/mumu/native/automation/nectar_rpc.cpp"):
    source = (root / relative).read_text(encoding="utf-8")
    poll = source.split("void poll_pending_task() {", 1)[1].split("// StartExpeditionAsync()", 1)[0]
    assert "!task_is_completed || !task_is_faulted ||" in poll
    fault = poll.split("if (faulted || !response) {", 1)[1].split("return;", 1)[0]
    assert "clear_pending_task();" in fault and "resolve_success" not in fault
    assert "if (failed_reason == 0) nectar_intent_guard().resolve_success(pending_intent_binding);" in poll
    clear = source.split("void clear_pending_task() {", 1)[1].split("void poll_pending_task()", 1)[0]
    assert "gchandle_free(" not in clear and "resolve_success" not in clear
    claim = source.split("void maybe_claim() {", 1)[1].split("void *hooked_", 1)[0]
    assert claim.index("nectar_intent_guard().begin") < claim.index("object_new(request_class)") < claim.index("send_claim(rpc_manager")
    assert claim.index("poll_pending_task();") < claim.index("nectar_intent_guard().retire_unconfirmed") < claim.index("nectar_intent_guard().begin")
    retired = claim.split("if (nectar_intent_guard().retire_unconfirmed", 1)[1].split("}", 1)[0]
    assert "clear_pending_task();" in retired and "resolve_success" not in retired
    assert "if (pending_task || nectar_unresolved()) return;" in claim
    assert "if (admission != nectar_intent::Begin::admitted) return;" in claim
    assert "send_epoch.epoch != intent_epoch.epoch" in claim
    assert "const long long tick = steady_ms();" in claim and "last_tick_ms = tick;" in claim
    assert claim.index("const auto handle = gchandle_new(task, false);") < claim.index('if (!handle) { last_result = "TASK_ROOT_UNCONFIRMED"; return; }') < claim.index("pending_task = task;")
feeding = (root / "native/automation/zygisk-module/cpp/feeding_coordinator.inc").read_text(encoding="utf-8")
assert "pending_task || nectar_unresolved();" in feeding
print("PASS: source integration contracts; no device/managed execution")
