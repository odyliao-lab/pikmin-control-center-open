#pragma once
#include "feeding_pending.h"
#include <algorithm>

namespace feeding_receipt {
// Pure reconciliation only. A successful Task alone, or an unrelated inventory
// delta alone, never authorizes retiring a durable intent. The caller must bind
// the response to this request and obtain an authoritative post-response read.
struct Evidence {
    std::string request, account, pikmin, kind;
    bool response_success{}, authoritative{}, target_changed{};
    int64_t observed_epoch{}, nectar{-1}, petals{-1};
    std::vector<std::string> confirmed_targets;
};
enum class Result { confirmed, waiting, review_required };
inline Result reconcile(const feeding_pending::Intent &intent, const Evidence &e) {
    if (!feeding_pending::valid(intent)) return Result::review_required;
    if (e.account != intent.account || e.request != intent.request ||
        e.pikmin != intent.pikmin || e.kind != intent.kind) return Result::review_required;
    if (!e.response_success || !e.authoritative || e.observed_epoch <= intent.epoch)
        return Result::waiting;
    if (!e.target_changed) return Result::waiting;
    if(!intent.targets.empty()) {
        std::set<std::string> confirmed(e.confirmed_targets.begin(),e.confirmed_targets.end());
        if(confirmed.size()!=e.confirmed_targets.size())return Result::review_required;
        for(const auto &id:confirmed)
            if(std::find(intent.targets.begin(),intent.targets.end(),id)==intent.targets.end())return Result::review_required;
        if(confirmed.size()!=intent.targets.size())return Result::waiting;
    }
    // Resource limits are enforced before sending. Walking rewards, planting
    // and manual collection can change global balances during this request.
    // Completion requires the bound successful RPC AND a fresh target change;
    // balances in the receipt are observations, not attributable deltas.
    if (intent.action == "feed" && intent.nectar_before < intent.cost) return Result::review_required;
    if (intent.action == "collect" && intent.yield <= 0) return Result::review_required;
    return Result::confirmed;
}
inline bool retire(feeding_pending::Journal &journal,const feeding_pending::Intent &intent,const Evidence &e) {
    if(reconcile(intent,e)!=Result::confirmed)return false;
    const std::string receipt=feeding_pending::encode(intent)+"confirmed\t"+
        std::to_string(e.observed_epoch)+"\t"+std::to_string(e.nectar)+"\t"+std::to_string(e.petals)+"\n";
    return journal.archive_confirmed(intent,receipt);
}
}
