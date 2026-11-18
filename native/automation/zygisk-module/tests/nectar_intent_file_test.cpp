#include "../cpp/nectar_intent_store.h"
#include <cassert>
#include <cstdlib>
#include <fstream>
#include <sys/wait.h>
int main(int argc,char**argv){
    using nectar_intent::FileStore;
    using Guard=nectar_intent::Guard<FileStore>;
    const char* root=argc==2?argv[1]:std::getenv("TMPDIR");
    // run-host-tests provides TMPDIR; direct execution can pass a task-owned path.
    std::string pattern=std::string(root?root:"/tmp")+"/pcc-nectar-intent-XXXXXX";
    std::vector<char> temp(pattern.begin(),pattern.end());temp.push_back(0);
    char* dir=mkdtemp(temp.data());assert(dir);
    const std::string path(dir),account(64,'a'),target(64,'b');
    // The child exits with an unresolved receipt: no real RPC is called.
    pid_t child=fork();assert(child>=0);
    if(child==0){
        Guard writer{FileStore(path)};
        bool ok=writer.begin(account,target,1,getpid(),100000,1000)==nectar_intent::Begin::admitted;
        _exit(ok?0:1);
    }
    int result=0;assert(waitpid(child,&result,0)==child&&WIFEXITED(result)&&WEXITSTATUS(result)==0);
    Guard restored{FileStore(path)};
    assert(restored.blocked());
    assert(restored.begin(account,target,2,getpid(),200000,2000)==nectar_intent::Begin::blocked);
    assert(restored.status(200000,2000,false).find(":restart")!=std::string::npos);
    assert(restored.retire_unconfirmed(2000,false));
    assert(!restored.blocked());
    assert(restored.begin(account,target,2,getpid(),200000,2000)==nectar_intent::Begin::duplicate);
    const std::string other(64,'c');
    assert(restored.begin(account,other,2,getpid(),200000,2000)==nectar_intent::Begin::admitted);
    assert(!restored.retire_unconfirmed(61999,true));
    assert(restored.retire_unconfirmed(62000,true));
    Guard next_process{FileStore(path)};
    assert(next_process.begin(account,target,3,getpid(),300000,3000)==nectar_intent::Begin::duplicate);
    assert(next_process.begin(account,other,3,getpid(),300000,3000)==nectar_intent::Begin::duplicate);
    assert(next_process.begin(std::string(64,'d'),target,3,getpid(),300000,3000)==nectar_intent::Begin::admitted);
    // Strict checksum/truncation: never convert corrupt evidence to empty.
    {std::ofstream out(path+"/nectar_intent.tsv",std::ios::binary|std::ios::trunc);out<<"v1\tpending\n";}
    Guard corrupt{FileStore(path)};assert(corrupt.blocked());
    assert(corrupt.begin(account,target,3,getpid(),300000,3000)==nectar_intent::Begin::blocked);
    // Test-owned directory only; never use a production receipt path.
    assert(unlink((path+"/nectar_intent.tsv").c_str())==0);
    assert(symlink("other-file",(path+"/nectar_intent.tsv").c_str())==0);
    Guard linked{FileStore(path)};assert(linked.blocked());
    assert(unlink((path+"/nectar_intent.tsv").c_str())==0);
    assert(mkfifo((path+"/nectar_intent.tsv").c_str(),0600)==0);
    Guard fifo{FileStore(path)};assert(fifo.blocked()); // O_NONBLOCK then fstat rejects FIFO.
    assert(unlink((path+"/nectar_intent.tsv").c_str())==0);
    assert(symlink(path.c_str(),(path+"-alias").c_str())==0);
    Guard directory_link{FileStore(path+"-alias")};assert(directory_link.blocked());
    assert(unlink((path+"-alias").c_str())==0);
    assert(unlink((path+"/nectar_intent.lock").c_str())==0);
    assert(unlink((path+"/"+FileStore::quarantine_leaf(account,target)).c_str())==0);
    assert(unlink((path+"/"+FileStore::quarantine_leaf(account,other)).c_str())==0);
    assert(rmdir(path.c_str())==0);
}
