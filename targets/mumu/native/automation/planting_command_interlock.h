#pragma once
#include <cerrno>
#include <string>
#include <sys/stat.h>

namespace emu_planting_command {
// Persistent reservation, NOT command authority or an active-lock proof.
// Caller supplies the actual game files directory. Any object/error holds the
// legacy automatic starter; only proven ENOENT enables its old behavior.
inline bool namespace_reserved(const std::string &files) {
    if(files.empty() || files.front()!='/' || files.back()=='/' ||
       files.find('\0')!=std::string::npos)return true;
    struct stat parent{},after{},st{};
    if(::lstat(files.c_str(),&parent)!=0 || !S_ISDIR(parent.st_mode))return true;
    const bool reserved=::lstat((files+"/emulator-command").c_str(),&st)==0 || errno!=ENOENT;
    if(::lstat(files.c_str(),&after)!=0 || !S_ISDIR(after.st_mode) ||
       parent.st_dev!=after.st_dev || parent.st_ino!=after.st_ino)return true;
    return reserved;
}
}
