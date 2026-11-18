#include "../cpp/feeding_receipt.h"
#include <cassert>
#include <cstdlib>
#include <cstdio>
int main() {
    using namespace feeding_pending;
    char dir[]="/data/local/tmp/feeding-retirement-XXXXXX";assert(mkdtemp(dir));
    const std::string base=dir;
    Intent intent{"request-1",std::string(64,'a'),"feed","flower-5:3","pikmin-1",1000,101,20,1,1};
    feeding_receipt::Evidence e{intent.request,intent.account,intent.pikmin,intent.kind,true,true,true,1001,100,20};
    Journal journal(dir);Intent loaded;
    assert(journal.prepare(intent));
    e.authoritative=false;assert(!feeding_receipt::retire(journal,intent,e));
    assert(journal.load(loaded)==State::pending);e.authoritative=true;
    auto wrong=intent;wrong.request="wrong";
    assert(!journal.archive_confirmed(wrong,"receipt"));assert(journal.load(loaded)==State::pending);
    assert(feeding_receipt::retire(journal,intent,e));assert(journal.load(loaded)==State::absent);
    assert(!journal.prepare(intent)); // confirmed request IDs cannot be reused
    struct stat st{};const auto receipt=base+"/feeding-confirmed-request-1.tsv";
    assert(stat(receipt.c_str(),&st)==0 && (st.st_mode&0777)==0600 && st.st_size>0);
    intent.request="request-2";e.request=intent.request;assert(journal.prepare(intent));
    const auto partial=base+"/feeding-confirmed-request-2.tsv";
    int fd=open(partial.c_str(),O_WRONLY|O_CREAT|O_EXCL,0600);assert(fd>=0);assert(write(fd,"partial",7)==7);close(fd);
    assert(!feeding_receipt::retire(journal,intent,e));assert(journal.load(loaded)==State::pending);
    assert(unlink((base+"/feeding_pending.tsv").c_str())==0);
    assert(unlink(receipt.c_str())==0);assert(unlink(partial.c_str())==0);assert(rmdir(dir)==0);
    std::puts("PASS feeding durable retirement: evidence required, exact intent, receipt before clear, no ID reuse, partial receipt blocks");
}
