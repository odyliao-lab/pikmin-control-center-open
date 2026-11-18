#pragma once

namespace pcc_bootstrap {
// Only library discovery is retried. No metadata access, game call, or hook
// installation happens inside this policy. One worker owns the entire round.
struct Retry {
    static constexpr int attempts = 136;
    static constexpr int delay_after(int attempt) { return attempt < 120 ? 1 : 30; }
    static constexpr bool another(int attempt) { return attempt + 1 < attempts; }
};
}
