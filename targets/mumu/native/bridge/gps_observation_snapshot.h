#pragma once
#include <cstdint>
#include <string>
namespace emu_gps_work {
// Pure cached bridge for the generated Map TU: intentionally does not include
// the path reader or expose writer-exclusion booleans/OwnerAuthority.
struct CachedFenceObservation {
    std::string boot,nonce;int pid{};uint64_t ticks{},device{},inode{},sampled{},until{};
};
extern "C" bool pcc_emulator_read_gps_fence_snapshot(int*,uint64_t*,char*,char*,uint64_t*,uint64_t*,uint64_t*,uint64_t*);
inline bool read_payload_fence_snapshot(CachedFenceObservation &out){
    CachedFenceObservation v;char boot[37]{},nonce[37]{};
    if(!pcc_emulator_read_gps_fence_snapshot(&v.pid,&v.ticks,boot,nonce,&v.device,&v.inode,&v.sampled,&v.until)||
       boot[36]!=0||nonce[36]!=0||v.pid<=0||!v.ticks||!v.inode||!v.sampled||v.until<=v.sampled||v.until-v.sampled!=500)return false;
    v.boot=std::string(boot,36);v.nonce=std::string(nonce,36);
    for(const auto *s:{&v.boot,&v.nonce})for(size_t i=0;i<36;++i){
        const char c=(*s)[i];if(i==8||i==13||i==18||i==23){if(c!='-')return false;}
        else if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))return false;
    }
    out=std::move(v);return true;
}
}
