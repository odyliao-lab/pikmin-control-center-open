#pragma once
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

namespace pcc {
inline void send_hc_guard_result(int fd,bool clear){
    const unsigned char frame[]{'H','C',1,static_cast<unsigned char>(clear?1:0)};
    (void)send(fd,frame,sizeof(frame),MSG_NOSIGNAL|MSG_DONTWAIT);
}
inline bool read_hc_guard_result(int fd){
    if(fd<0)return false;pollfd event{fd,POLLIN,0};
    if(poll(&event,1,500)!=1||!(event.revents&POLLIN))return false;
    unsigned char frame[4]{};
    return recv(fd,frame,sizeof(frame),MSG_DONTWAIT)==sizeof(frame)&&frame[0]=='H'&&frame[1]=='C'&&frame[2]==1&&frame[3]==1;
}
// No caller-controlled paths or operations: the companion only returns a fixed versioned verdict.
inline void send_guard_result(int fd, bool allowed) {
    const unsigned char frame[]{'P','C',1,static_cast<unsigned char>(allowed?1:0)};
    (void)send(fd,frame,sizeof(frame),MSG_NOSIGNAL|MSG_DONTWAIT);
}
inline bool read_guard_result(int fd) {
    if(fd<0)return false;
    pollfd event{fd,POLLIN,0};
    // Interrupted, late or partial replies fail closed; never wait indefinitely before specialization.
    if(poll(&event,1,500)!=1 || !(event.revents&POLLIN))return false;
    unsigned char frame[4]{};
    return recv(fd,frame,sizeof(frame),MSG_DONTWAIT)==sizeof(frame) &&
        frame[0]=='P' && frame[1]=='C' && frame[2]==1 && frame[3]==1;
}
}
