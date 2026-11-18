#pragma once
#include "planting_command_policy.h"
namespace emu_planting_command {
struct Grant {
    int pid{};uint64_t epoch{};
    std::string boot,account,owner,token,operation,core_sha,apk_sha;
    int64_t sampled{};
    bool global_lock{},namespace_lock{},legacy_quiescent{},core_paired{},apk_paired{};
};
struct Authority {
    virtual ~Authority()=default;
    // No Command argument. Caller must hold the full critical section; default
    // flags are false. A namespace lock alone cannot exclude legacy GPS writers.
    virtual bool read(Grant &)=0;
};
inline bool same_grant(const Grant &a,const Grant &b){
    return a.pid==b.pid&&a.epoch==b.epoch&&a.boot==b.boot&&a.account==b.account&&
        a.owner==b.owner&&a.token==b.token&&a.operation==b.operation&&
        a.core_sha==b.core_sha&&a.apk_sha==b.apk_sha;
}
}
