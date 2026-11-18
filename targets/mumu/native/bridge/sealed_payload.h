#pragma once
#include <cerrno>
#include <cstddef>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
namespace emu_payload {
constexpr int image_seals=F_SEAL_WRITE|F_SEAL_GROW|F_SEAL_SHRINK|F_SEAL_SEAL;
// A sealed anonymous image fixes the exact bytes passed to NativeBridge. This
// is NOT proof of loading, a digest, an APK pair or ownership authority.
inline bool sealed_image(int fd,size_t bytes){
    struct stat st{};
    const int seals=fcntl(fd,F_GET_SEALS);
    return fd>=0&&bytes>0&&bytes<=12*1024*1024&&fstat(fd,&st)==0&&
        S_ISREG(st.st_mode)&&st.st_uid==geteuid()&&st.st_size==static_cast<off_t>(bytes)&&
        seals>=0&&(seals&image_seals)==image_seals;
}
inline int create_image(const unsigned char *data,size_t bytes){
    if(!data||bytes==0||bytes>12*1024*1024)return -1;
    const int fd=memfd_create("pcc-arm-canary",MFD_CLOEXEC|MFD_ALLOW_SEALING);
    if(fd<0)return -1;
    size_t at=0;
    while(at<bytes){
        const auto n=pwrite(fd,data+at,bytes-at,static_cast<off_t>(at));
        if(n<0&&errno==EINTR)continue;
        if(n<=0){close(fd);return -1;}at+=static_cast<size_t>(n);
    }
    if(fcntl(fd,F_ADD_SEALS,image_seals)!=0||!sealed_image(fd,bytes)){close(fd);return -1;}
    return fd;
}
}
