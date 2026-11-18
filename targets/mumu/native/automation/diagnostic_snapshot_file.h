#pragma once
#include <cstdio>
#include <cstdlib>
#include <fcntl.h>
#include <string>
#include <string_view>
#include <sys/stat.h>
#include <unistd.h>

namespace pikmin::diagnostic_snapshot {
// Ephemeral observations only. Never use this helper for a transaction journal.
// mkostemp creates a new exclusive inode, so a prior process's .tmp or a
// pre-existing symlink cannot block or redirect a new observation.
inline bool publish(const std::string &destination,std::string_view row) {
    if(row.empty()||row.size()>8192)return false;
    auto temporary=destination+".tmp.XXXXXX";
    int fd=mkostemp(temporary.data(),O_CLOEXEC);
    if(fd<0)return false;
    struct stat st{};
    bool ok=fstat(fd,&st)==0&&S_ISREG(st.st_mode)&&st.st_uid==getuid()&&st.st_nlink==1
        &&::write(fd,row.data(),row.size())==static_cast<ssize_t>(row.size())
        &&fsync(fd)==0&&fchmod(fd,0644)==0;
    if(close(fd)!=0)ok=false;
    if(ok&&rename(temporary.c_str(),destination.c_str())==0)return true;
    // Only our newly created name is removed. Retain prior diagnostics/orphans.
    unlink(temporary.c_str());return false;
}
}
