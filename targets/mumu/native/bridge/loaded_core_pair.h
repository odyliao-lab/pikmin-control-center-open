#pragma once
#include "root_apk_observation.h"
#include "loaded_payload.h"
namespace emu_payload {
struct LoadedCorePair {RootApkObservation installed;std::string loaded_sha;int64_t sampled{};};
inline bool same_asset_binding(const RootApkObservation &a,const RootApkObservation &b){
    return root_apk_detail::same_process(a.process,b.process)&&a.apk.uid==b.apk.uid&&a.apk.code==b.apk.code&&
        a.apk.updated==b.apk.updated&&a.apk.sha==b.apk.sha&&a.apk.signer==b.apk.signer&&
        a.core.version==b.core.version&&a.core.code==b.core.code&&a.core.zip_sha==b.core.zip_sha&&
        a.core.arm_sha==b.core.arm_sha&&a.core.loader_sha==b.core.loader_sha;
}
// Off-Update only. Actual self-binding Root observation + actual held loaded
// image digest, with a fresh second observation. No version-only fallback.
// This proves byte association ONLY, not account/GPS ownership or commands.
inline bool read_loaded_core_pair(LoadedImage &image,LoadedCorePair &out){
    const auto steady=std::chrono::steady_clock::now();const auto begin=root_apk_detail::now();
    RootApkObservation before,after;std::string loaded;
    if(!read_root_apk_observation(before)||!image.fingerprint(loaded)||loaded!=before.core.arm_sha||
       !read_root_apk_observation(after)||!same_asset_binding(before,after)||after.sampled<before.sampled||
       !image.recheck())return false;
    const auto end=root_apk_detail::now();
    if(end<begin||end-begin>3000||end>=before.until||end>=after.until||
       std::chrono::steady_clock::now()-steady>std::chrono::seconds(3))return false;
    LoadedCorePair result;result.installed=after;result.loaded_sha=loaded;result.sampled=end;out=result;return true;
}
}
