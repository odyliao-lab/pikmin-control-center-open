#include "../cpp/companion_probe.h"
#include <cassert>
#include <chrono>
#include <cstdio>

static bool reply(const unsigned char* bytes,size_t count) {
    int fds[2];assert(socketpair(AF_UNIX,SOCK_STREAM,0,fds)==0);
    if(count)assert(send(fds[1],bytes,count,MSG_NOSIGNAL)==static_cast<ssize_t>(count));
    close(fds[1]);bool result=pcc::read_guard_result(fds[0]);close(fds[0]);return result;
}
int main() {
    unsigned char frame[]{'P','C',1,1};int n=0;
    assert(reply(frame,4));++n;
    for(size_t length=0;length<4;++length){assert(!reply(frame,length));++n;}
    for(int field=0;field<4;++field){frame[field]^=0xff;assert(!reply(frame,4));frame[field]^=0xff;++n;}
    assert(!pcc::read_guard_result(-1));++n;
    int fds[2];assert(socketpair(AF_UNIX,SOCK_STREAM,0,fds)==0);
    auto start=std::chrono::steady_clock::now();assert(!pcc::read_guard_result(fds[0]));
    auto elapsed=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count();
    assert(elapsed>=400 && elapsed<2000);++n;
    pcc::send_guard_result(fds[1],false);assert(!pcc::read_guard_result(fds[0]));++n;
    pcc::send_guard_result(fds[1],true);assert(pcc::read_guard_result(fds[0]));++n;
    pcc::send_hc_guard_result(fds[1],false);assert(!pcc::read_hc_guard_result(fds[0]));++n;
    pcc::send_hc_guard_result(fds[1],true);assert(pcc::read_hc_guard_result(fds[0]));++n;
    assert(!pcc::read_hc_guard_result(-1));++n;
    for(size_t length=0;length<4;++length){
        int pair[2];assert(socketpair(AF_UNIX,SOCK_STREAM,0,pair)==0);
        unsigned char hc[]{'H','C',1,1};if(length)assert(send(pair[1],hc,length,MSG_NOSIGNAL)==static_cast<ssize_t>(length));
        close(pair[1]);assert(!pcc::read_hc_guard_result(pair[0]));close(pair[0]);++n;
    }
    // A valid core-loader verdict must never count as HC's exclusive-owner verdict.
    pcc::send_guard_result(fds[1],true);assert(!pcc::read_hc_guard_result(fds[0]));++n;
    close(fds[0]);close(fds[1]);
    std::printf("PASS companion protocol: %d actual socket/timeout cases; NOT Zygisk companion or game load acceptance\n",n);
}
