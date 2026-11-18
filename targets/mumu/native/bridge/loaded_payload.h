#pragma once
#include "sealed_payload.h"
#include "payload_sha256.h"
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <dlfcn.h>
#include <dirent.h>
#include <elf.h>
#include <limits>
#include <sys/sysmacros.h>
namespace emu_payload {
// Binds an executable anchor to the same sealed backing inode and offers a
// digest of that backing. Not an installed APK proof, memory-patch defence or
// command authority. Run off Update; callers serialize all instance operations.
class LoadedImage {
    int fd_{-1};pid_t pid_{};void *anchor_{};uintptr_t base_{};size_t bytes_{};
    struct stat identity_{};
    static bool same(const struct stat &a,const struct stat &b){return a.st_dev==b.st_dev&&a.st_ino==b.st_ino&&a.st_size==b.st_size;}
    static bool offset_for(int fd,size_t size,uintptr_t base,uintptr_t address,uint64_t &offset){
        Elf64_Ehdr header{};
        if(pread(fd,&header,sizeof(header),0)!=sizeof(header)||memcmp(header.e_ident,ELFMAG,SELFMAG)||
           header.e_ident[EI_CLASS]!=ELFCLASS64||header.e_ident[EI_DATA]!=ELFDATA2LSB||
           header.e_ident[EI_VERSION]!=EV_CURRENT||header.e_version!=EV_CURRENT||header.e_type!=ET_DYN||
           header.e_ehsize!=sizeof(header)||header.e_phentsize!=sizeof(Elf64_Phdr)||
           header.e_phnum==0||header.e_phnum>128||header.e_phoff>size||
           uint64_t(header.e_phnum)*sizeof(Elf64_Phdr)>size-header.e_phoff||address<base)return false;
#if defined(__aarch64__)
        if(header.e_machine!=EM_AARCH64)return false;
#elif defined(__x86_64__)
        if(header.e_machine!=EM_X86_64)return false;
#else
        return false;
#endif
        const uint64_t relative=address-base;
        bool found=false;uint64_t actual{};
        for(unsigned i=0;i<header.e_phnum;++i){
            Elf64_Phdr p{};
            if(pread(fd,&p,sizeof(p),static_cast<off_t>(header.e_phoff+i*sizeof(p)))!=sizeof(p))return false;
            if(p.p_type!=PT_LOAD||(p.p_flags&PF_X)==0||relative<p.p_vaddr||relative-p.p_vaddr>=p.p_filesz)continue;
            if(found||p.p_offset>size||p.p_filesz>size-p.p_offset)return false;
            actual=p.p_offset+(relative-p.p_vaddr);found=true;
        }
        if(!found)return false;offset=actual;return true;
    }
    struct Map {uint64_t offset{},inode{};unsigned dev_major{},dev_minor{};};
    static bool mapping(uintptr_t address,Map &out){
        // Own proc file only; do not accept a configurable fixture/witness path.
        FILE *maps=fopen("/proc/self/maps","re");if(!maps)return false;
        char line[4096]{};size_t total=0;bool good=false,failed=false;Map observed;
        while(fgets(line,sizeof(line),maps)){
            const auto length=std::strlen(line);total+=length;
            if(total>2*1024*1024||length==0||line[length-1]!='\n'){failed=true;break;}
            unsigned long long begin{},end{},file_offset{},inode{};unsigned dev_major{},dev_minor{};char permissions[5]{};
            if(std::sscanf(line,"%llx-%llx %4s %llx %x:%x %llu",&begin,&end,permissions,&file_offset,&dev_major,&dev_minor,&inode)!=7){failed=true;break;}
            if(address<begin||address>=end)continue;
            if(good||permissions[0]!='r'||permissions[2]!='x'||permissions[3]!='p'||inode==0||
               address-begin>std::numeric_limits<uint64_t>::max()-file_offset){failed=true;break;}
            observed.offset=file_offset+(address-begin);observed.inode=inode;
            observed.dev_major=dev_major;observed.dev_minor=dev_minor;good=true;
        }
        if(ferror(maps))failed=true;if(fclose(maps)!=0)failed=true;if(good&&!failed){out=observed;return true;}return false;
    }
    static bool same_map(const Map &map,const struct stat &st){return map.inode==st.st_ino&&map.dev_major==major(st.st_dev)&&map.dev_minor==minor(st.st_dev);}
public:
    ~LoadedImage(){if(fd_>=0)close(fd_);}
    LoadedImage()=default;LoadedImage(const LoadedImage &)=delete;LoadedImage &operator=(const LoadedImage &)=delete;
    bool recheck(){
        if(fd_<0||pid_!=getpid()||!anchor_||!sealed_image(fd_,bytes_))return false;
        struct stat current{};Dl_info info{};uint64_t offset{};Map map;
        return fstat(fd_,&current)==0&&same(current,identity_)&&dladdr(anchor_,&info)!=0&&
            reinterpret_cast<uintptr_t>(info.dli_fbase)==base_&&
            offset_for(fd_,bytes_,base_,reinterpret_cast<uintptr_t>(anchor_),offset)&&
            mapping(reinterpret_cast<uintptr_t>(anchor_),map)&&same_map(map,identity_)&&map.offset==offset;
    }
    bool capture(void *anchor){
        if(fd_>=0||!anchor)return false;
        Dl_info info{};Map map;
        if(dladdr(anchor,&info)==0||!info.dli_fbase||!mapping(reinterpret_cast<uintptr_t>(anchor),map))return false;
        // Android canonicalizes proc-FD names to /memfd:... (deleted). Never
        // trust that pathname: locate the held FD by the actual mapped inode.
        DIR *directory=opendir("/proc/self/fd");if(!directory)return false;
        int held=-1;struct stat st{};unsigned entries=0;bool failed=false;
        for(;;){
            errno=0;const auto *entry=readdir(directory);if(!entry){if(errno)failed=true;break;}
            if(++entries>4096){failed=true;break;}
            int original=0;unsigned digits=0;bool numeric=true;
            for(const char *number=entry->d_name;*number;++number){if(*number<'0'||*number>'9'||++digits>9||original>(std::numeric_limits<int>::max()-(*number-'0'))/10){numeric=false;break;}original=original*10+(*number-'0');}
            if(!numeric||!digits)continue;
            struct stat candidate{};
            if(fstat(original,&candidate)!=0||!same_map(map,candidate)||candidate.st_size<=0||candidate.st_size>12*1024*1024||!sealed_image(original,static_cast<size_t>(candidate.st_size)))continue;
            const int duplicate=fcntl(original,F_DUPFD_CLOEXEC,0);uint64_t offset{};
            if(duplicate<0)continue;
            if(fstat(duplicate,&candidate)!=0||!same_map(map,candidate)||!sealed_image(duplicate,static_cast<size_t>(candidate.st_size))||
               !offset_for(duplicate,candidate.st_size,reinterpret_cast<uintptr_t>(info.dli_fbase),reinterpret_cast<uintptr_t>(anchor),offset)||offset!=map.offset){close(duplicate);continue;}
            held=duplicate;st=candidate;break;
        }
        if(closedir(directory)!=0)failed=true;
        if(failed||held<0){if(held>=0)close(held);return false;}
        fd_=held;pid_=getpid();anchor_=anchor;base_=reinterpret_cast<uintptr_t>(info.dli_fbase);bytes_=st.st_size;identity_=st;
        if(recheck())return true;
        close(fd_);fd_=-1;anchor_=nullptr;bytes_=0;return false;
    }
    // Keep internal FD private; consumers receive their own CLOEXEC duplicate,
    // after current mapping/seals/PID validation, for independent byte hashing.
    int duplicate(){return recheck()?fcntl(fd_,F_DUPFD_CLOEXEC,0):-1;}
    // Caller serializes capture/recheck/fingerprint, off Map Update. Only a
    // fresh mapped sealed image is digested; output is untouched on failure.
    bool fingerprint(std::string &out){
        const int fd=duplicate();if(fd<0)return false;std::string digest;
        const bool hashed=sha256_sealed_fd(fd,digest);const bool closed=close(fd)==0;
        if(!hashed||!closed||!recheck())return false;out=digest;return true;
    }
};
}
