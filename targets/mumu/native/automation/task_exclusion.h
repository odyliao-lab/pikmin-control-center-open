#pragma once
#include "planting_continuation.h"
#include "session_identity.h"

// Denial only. The original admission remains UNKNOWN, never a retry queue.
namespace task_exclusion {
inline bool hex(const std::string& s){return s.size()==64&&s.find_first_not_of("0123456789abcdef")==std::string::npos;}
inline std::string leaf(const std::string& account,const std::string& id){return "task-unconfirmed-"+pikmin::account_digest(account+"\t"+id)+".tsv";}
inline bool allowed(const std::string& directory,const std::string& account,const std::string& id){
    if(!hex(account)||id.empty()||id.size()>160||id.find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-")!=std::string::npos)return false;
    std::string row;bool exists=false;
    if(!planting_continuation::read(directory+"/"+leaf(account,id),row,exists))return false;
    if(!exists)return true;
    // Corrupt, unexpected or changed evidence also denies this target. It is
    // not an absent exclusion; other targets have independent file names.
    const auto f=planting_continuation::fields(row);
    if(f.size()!=6||f[0]!="PCCEXCLUDE/1"||f[1]!=account||f[2]!=pikmin::account_digest(id)||!hex(f[3])||(f[4]!="UNKNOWN"&&f[4]!="COLLECTED_NO_REPLAY")||f[5]!="end\n")return false;
    return false;
}
}
