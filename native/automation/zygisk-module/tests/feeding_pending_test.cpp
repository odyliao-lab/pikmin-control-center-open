#include "../cpp/feeding_pending.h"
#include <cassert>
#include <cstdio>
#include <cstdlib>
#include <sys/wait.h>
int main(){
    using namespace feeding_pending;
    Intent v{"request-1",std::string(64,'a'),"feed","flower-5:1","pikmin-1",1700000000000,101,20,1,1,{}}, parsed;
    const auto encoded=encode(v); assert(!encoded.empty()&&decode(encoded,parsed));
    auto batch=v;batch.targets={"pikmin-1","pikmin-2"};batch.cost=2;batch.yield=3;
    assert(decode(encode(batch),parsed)&&parsed.targets==batch.targets);
    batch.targets.push_back("pikmin-2");batch.cost=3;assert(encode(batch).empty());
    assert(!decode(encoded+"junk",parsed)); assert(!decode("",parsed));
    auto bad=v;bad.cost=0;assert(encode(bad).empty());bad=v;bad.account="unknown";assert(encode(bad).empty());
    const std::string root=std::getenv("TMPDIR")?std::getenv("TMPDIR"):"/data/local/tmp";
    std::string pattern=root+"/feeding-pending-XXXXXX";
    std::vector<char> dir_buf(pattern.begin(),pattern.end());dir_buf.push_back('\0');
    char *dir=dir_buf.data();assert(mkdtemp(dir));
    const std::string path=std::string(dir)+"/feeding_pending.tsv";
    {Journal journal(dir); assert(journal.load(parsed)==State::absent);assert(journal.prepare(v));assert(!journal.prepare(v));}
    {Journal reopened(dir);assert(reopened.load(parsed)==State::pending);assert(parsed.account==v.account);assert(!reopened.prepare(v));}
    struct stat st{};assert(stat(path.c_str(),&st)==0&&(st.st_mode&0777)==0600);
    // Simulate process death during initial write: malformed evidence must block.
    assert(unlink(path.c_str())==0);int fd=open(path.c_str(),O_CREAT|O_WRONLY,0600);assert(fd>=0);
    assert(write(fd,"v1",2)==2);close(fd);
    {Journal corrupt(dir);assert(corrupt.load(parsed)==State::unreadable);assert(!corrupt.prepare(v));
        assert(corrupt.archive_unreadable(123));assert(corrupt.load(parsed)==State::absent);}
    const auto torn=std::string(dir)+"/feeding-unreadable-"+std::to_string(getpid())+"-123.tsv";
    fd=open(torn.c_str(),O_RDONLY);assert(fd>=0);char torn_bytes[8]{};
    assert(read(fd,torn_bytes,sizeof(torn_bytes))==2 && std::string(torn_bytes,2)=="v1");close(fd);
    assert(unlink(torn.c_str())==0);assert(symlink("missing-target",path.c_str())==0);
    {Journal symlinked(dir);assert(symlinked.load(parsed)==State::unreadable);assert(!symlinked.prepare(v));
        assert(!symlinked.archive_unreadable(124));}
    assert(unlink(path.c_str())==0);
    // Two processes race for one intent: exactly one may authorize the first send.
    pid_t child=fork();assert(child>=0);
    if(child==0){Journal j(dir);_exit(j.prepare(v)?0:1);}
    bool parent=Journal(dir).prepare(v);int status=0;assert(waitpid(child,&status,0)==child&&WIFEXITED(status));
    assert(static_cast<int>(parent)+(WEXITSTATUS(status)==0?1:0)==1);
    {Journal j(dir);assert(j.archive_unconfirmed(v));assert(j.load(parsed)==State::absent);
        assert(j.has_archived(v));
        assert(!j.archive_unconfirmed(v));assert(!j.prepare(v));}
    const auto audit=std::string(dir)+"/feeding-unconfirmed-"+v.request+".tsv";
    fd=open(audit.c_str(),O_RDONLY);assert(fd>=0);
    char bytes[512]{};const auto count=read(fd,bytes,sizeof(bytes));close(fd);
    assert(count==static_cast<ssize_t>(encoded.size())&&std::string(bytes,count)==encoded);
    assert(unlink(audit.c_str())==0);assert(rmdir(dir)==0);
    std::puts("PASS feeding pending: serialization, reopen, malformed/symlink refusal and process race; NOT game/RPC verification");
}
