package dev.ody.pikmincontrol;

/** Passive MuMu telemetry read only. The native producer truncates this leaf
 * before writing; an observed empty read can be replaced at most twice. Opaque
 * base64 roundtrip preserves ALL nonempty bytes (including invalid framing).
 * No field parsing, default state, timestamp, control write or gameplay call. */
final class EmulatorPlantingStatusRead {
    static final String LEAF="/data/user/0/com.nianticlabs.pikmin/files/planting_control_status.tsv";
    static String command() {
        // ProbeSnapshot adds its own '(' without a separator. Keep whitespace
        // here so Android mksh sees nested subshells, never arithmetic '(('.
        return " (pccPlantingLeaf='"+LEAF+"'; "
            +"test -f \"$pccPlantingLeaf\" && test ! -L \"$pccPlantingLeaf\" || exit 0; "
            +"for pccPlantingAttempt in 1 2 3; do "
            +"pccPlantingWire=$(base64 < \"$pccPlantingLeaf\" 2>/dev/null) || exit 0; "
            +"if test -n \"$pccPlantingWire\"; then printf '%s' \"$pccPlantingWire\" | base64 -d; exit; fi; "
            +"if test \"$pccPlantingAttempt\" -lt 3; then sleep 0.01; fi; "
            +"done)";
    }
}
