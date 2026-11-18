// Real filesystem checks for non-replacing archival; no game or managed calls.
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <iterator>
#include <vector>
#include "../cpp/planter_pending_store.h"
static std::string read(const std::string &p) {
    std::ifstream f(p,std::ios::binary);return {std::istreambuf_iterator<char>(f),{}};
}
static void write(const std::string &p,const std::string &s) {
    std::ofstream f(p,std::ios::binary);f<<s;assert(f.good());
}
int main() {
    const char *tmp=std::getenv("TMPDIR");
    std::string pattern=std::string(tmp?tmp:"/tmp")+"/planter-store-XXXXXX";
    std::vector<char> chars(pattern.begin(),pattern.end());chars.push_back(0);
    char *created=mkdtemp(chars.data());assert(created);std::string dir=created;
    using Store=pikmin::planter::PendingStore;Store store(dir);
    const auto pending=dir+"/planter_pending.tsv";
    const std::string legacy=std::string(9000,'x')+std::string("\0legacy\n",8);
    write(pending,legacy);write(pending+".recovered","older-proof");
    struct stat before{},after{};assert(lstat(pending.c_str(),&before)==0);
    std::string retained;assert(store.archive(false,10,retained)==Store::Archive::retained);
    assert(access(pending.c_str(),F_OK)!=0&&read(dir+"/"+retained)==legacy);
    assert(lstat((dir+"/"+retained).c_str(),&after)==0&&before.st_ino==after.st_ino&&after.st_nlink==1);
    assert(read(pending+".recovered")=="older-proof");
    // A pre-existing archive, including a symlink, is never replaced/followed.
    auto sequence=std::stoull(retained.substr(retained.find_last_of('.')+1));
    const auto prefix="planter_pending.tsv.unknown."+std::to_string(getpid())+".20.";
    const auto collision=dir+"/"+prefix+std::to_string(sequence+1);
    write(collision,"immutable-proof");
    const auto link=dir+"/"+prefix+std::to_string(sequence+2);
    assert(symlink("outside",link.c_str())==0);
    assert(store.create("new-intent\n")&&!store.create("overwrite\n"));
    assert(store.archive(false,20,retained)==Store::Archive::retained);
    assert(read(collision)=="immutable-proof"&&read(dir+"/"+retained)=="new-intent\n");
    assert(lstat(link.c_str(),&after)==0&&S_ISLNK(after.st_mode));
    assert(store.create("confirmed-intent\n"));
    assert(store.archive(true,30,retained)==Store::Archive::retained);
    assert(retained.find(".confirmed.")!=std::string::npos&&read(dir+"/"+retained)=="confirmed-intent\n");
    assert(store.archive(false,40,retained)==Store::Archive::absent&&retained.empty());
    // Unsafe source entries are preserved without reading or moving them.
    assert(mkfifo(pending.c_str(),0600)==0);
    assert(store.archive(false,50,retained)==Store::Archive::retry&&!store.create("replace"));
    assert(lstat(pending.c_str(),&after)==0&&S_ISFIFO(after.st_mode));assert(unlink(pending.c_str())==0);
    assert(symlink("outside",pending.c_str())==0);
    assert(store.archive(false,60,retained)==Store::Archive::retry);
    assert(lstat(pending.c_str(),&after)==0&&S_ISLNK(after.st_mode));assert(unlink(pending.c_str())==0);
    assert(store.create("retry-intent\n"));
    assert(chmod(dir.c_str(),0777)==0);
    assert(store.archive(false,70,retained)==Store::Archive::retry&&read(pending)=="retry-intent\n");
    assert(chmod(dir.c_str(),0700)==0);
    assert(store.archive(false,70,retained)==Store::Archive::retained&&read(dir+"/"+retained)=="retry-intent\n");
    std::puts("PASS planter pending: binary original inode/bytes, no hard links, collisions, symlink/FIFO refusal, confirmed/UNKNOWN, retry; no game calls");
}
