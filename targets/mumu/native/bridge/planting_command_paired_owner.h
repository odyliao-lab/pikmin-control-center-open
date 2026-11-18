#pragma once
#include "planting_command_owner.h"
#include <chrono>
#include "planting_command_provider_gate.h"
#include "gps_observation_snapshot.h"

namespace emu_planting_command {
// Account and writer exclusion must come from the independent global handoff,
// never from a command/authority file or the APK observer's lock.
struct OwnershipSnapshot {
    int pid{};uint64_t epoch{};
    std::string boot,account,owner,token,operation;
    int64_t sampled{};bool writers_excluded{};
};
struct OwnershipSource {
    virtual ~OwnershipSource()=default;
    virtual bool read(OwnershipSnapshot &)=0;
};
struct WriterLease {
    std::string boot,owner,token,operation;
    int64_t sampled{},until{};
    bool critical_section{},app_excluded{},legacy_excluded{},root_excluded{},provider_restored{};
    ProviderBoundary provider;
    // Must be assigned by the independent Root lease producer, not inferred
    // from any currently visible fence. Missing expected binding denies.
    struct FenceBinding {std::string nonce;int pid{};uint64_t ticks{},device{},inode{};} fence;
};
struct WriterExclusionSource {
    virtual ~WriterExclusionSource()=default;
    // Independent held GPS critical section and restored provider required.
    // Root cannot attest the game's consumer queue. ProviderGenerationGate
    // separately checks actual Map samples; even that is not a full drain proof.
    // No account, Command or authority.tsv input; default flags deny admission.
    virtual bool read(WriterLease &)=0;
};
// Supplied only by the loaded-image worker's verified cache in production.
// No disk hashing or JNI/PackageManager calls on the Map Update lane.
struct PairSnapshot {
    int pid{},uid{};
    std::string boot,core_sha,apk_sha;
    int64_t sampled{},until{};
};
// C linkage is intentional: nectar's generated overlay lives inside an
// anonymous namespace while the verified worker lives in payload_core.cpp.
// Only primitive fixed-size outputs cross that TU boundary, not C++ strings.
extern "C" bool pcc_emulator_read_pair_snapshot(int*,int*,char*,char*,char*,int64_t*,int64_t*);
inline bool read_payload_pair_snapshot(PairSnapshot &out){
    PairSnapshot value;char boot[37]{},core[65]{},apk[65]{};
    if(!pcc_emulator_read_pair_snapshot(&value.pid,&value.uid,boot,core,apk,&value.sampled,&value.until)||
       boot[36]!=0||core[64]!=0||apk[64]!=0)return false;
    value.boot=std::string(boot,36);value.core_sha=std::string(core,64);value.apk_sha=std::string(apk,64);
    if(!uuid(value.boot)||!digest(value.core_sha)||!digest(value.apk_sha))return false;
    out=std::move(value);return true;
}
class PairedOwnerEnvironment final:public OwnerEnvironment {
    OwnershipSource &ownership_;
    using PairReader=bool(*)(PairSnapshot &);
    PairReader pair_;
    static bool same(const OwnershipSnapshot &a,const OwnershipSnapshot &b){
        return a.pid==b.pid&&a.epoch==b.epoch&&a.boot==b.boot&&a.account==b.account&&
            a.owner==b.owner&&a.token==b.token&&a.operation==b.operation;
    }
    static bool valid(const OwnershipSnapshot &o,const PairSnapshot &p,int64_t at){
        return o.pid==getpid()&&o.epoch>0&&uuid(o.boot)&&digest(o.account)&&
            uuid(o.owner)&&uuid(o.token)&&uuid(o.operation)&&o.writers_excluded&&
            o.sampled>=1000000000000LL&&o.sampled<=at&&at-o.sampled<=1000&&
            p.pid==o.pid&&p.uid==static_cast<int>(geteuid())&&p.boot==o.boot&&
            digest(p.core_sha)&&digest(p.apk_sha)&&p.sampled>=1000000000000LL&&
            p.sampled<=at&&at-p.sampled<=1000&&p.until>at&&p.until-p.sampled<=5000;
    }
public:
    explicit PairedOwnerEnvironment(OwnershipSource &source,PairReader reader=read_payload_pair_snapshot):
        ownership_(source),pair_(reader){}
    int64_t now()override {
        return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
    }
    bool read(RuntimePair &out)override {
        const auto steady=std::chrono::steady_clock::now();const auto begin=now();
        OwnershipSnapshot before,after;PairSnapshot first,last;
        if(!pair_||!ownership_.read(before)||!pair_(first))return false;
        const auto first_read=now();
        if(first_read<begin||!valid(before,first,first_read)||!ownership_.read(after)||!pair_(last))return false;
        const auto end=now();
        if(end<begin||end-begin>1000||std::chrono::steady_clock::now()-steady>std::chrono::seconds(1)||
           !same(before,after)||after.sampled<before.sampled||!valid(after,last,end)||
           last.sampled<first.sampled||first.core_sha!=last.core_sha||first.apk_sha!=last.apk_sha||
           end>=first.until)return false;
        RuntimePair result;result.pid=after.pid;result.epoch=after.epoch;result.boot=after.boot;
        result.account=after.account;result.owner=after.owner;result.token=after.token;result.operation=after.operation;
        result.core_sha=last.core_sha;result.apk_sha=last.apk_sha;
        result.sampled=end;result.writers_excluded=true;out=std::move(result);return true;
    }
};
}
