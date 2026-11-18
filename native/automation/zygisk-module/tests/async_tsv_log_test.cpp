#include "../cpp/async_tsv_log.h"
#include <cassert>
#include <unistd.h>
int main(){
    const auto path=std::string(access("/data/local/tmp",F_OK)==0?"/data/local/tmp/":"/tmp/")+"pcc-async-log-test-"+std::to_string(getpid());
    const int64_t now=200000000;
    {std::ofstream f(path);f<<"1\texpired\n";
     for(int i=0;i<10000;++i)f<<now<<"\t"<<std::string(1000,'x')<<"\n";}
    {
        pikmin::AsyncTsvLog log;
        for(int i=0;i<1000;++i)assert(log.append(path,std::to_string(now)+"\tevent\n",now));
        log.flush();assert(log.prunes==1&&log.errors==0&&log.dropped==0);
        assert(log.bytes<20000);
        std::ifstream f(path);std::string line;int count=0;
        while(std::getline(f,line)){assert(line.find("expired")==std::string::npos);++count;}
        assert(count==11000);
        assert(!log.append(path,std::string(16385,'x'),now));assert(log.dropped==1);
        assert(log.append(path,std::to_string(now+86460000)+"\tnew-day\n",now+86460000));
        log.flush();assert(log.prunes==2);
        std::ifstream next(path);count=0;while(std::getline(next,line))++count;assert(count==1);
    }
    std::remove(path.c_str());
    {std::ofstream f(path);f<<"malformed evidence\n1x\tkeep\n1\texpired\n";}
    {pikmin::AsyncTsvLog log;assert(log.append(path,std::to_string(now)+"\tnew\n",now));log.flush();assert(log.errors==0);}
    {std::ifstream f(path);std::string all((std::istreambuf_iterator<char>(f)),{});assert(all.find("malformed evidence")!=std::string::npos&&all.find("1x\tkeep")!=std::string::npos&&all.find("expired")==std::string::npos);}
    // Two independent workers serialize retention and append via one stable sidecar lock.
    {pikmin::AsyncTsvLog a,b;for(int i=0;i<100;i++){assert(a.append(path,std::to_string(now)+"\ta\n",now));assert(b.append(path,std::to_string(now)+"\tb\n",now));}a.flush();b.flush();assert(a.errors==0&&b.errors==0);}
    {std::ifstream f(path);std::string line;int count=0;while(std::getline(f,line))++count;assert(count==203);}
    std::remove(path.c_str());
    const auto linked=path+".link";{std::ofstream f(path);f<<"keep-original\n";}assert(symlink(path.c_str(),linked.c_str())==0);
    {pikmin::AsyncTsvLog log;assert(log.append(linked,"changed\n",now));log.flush();assert(log.errors>=1);}
    {std::ifstream f(path);std::string line;std::getline(f,line);assert(line=="keep-original");}
    unlink(linked.c_str());unlink((linked+".writer-lock").c_str());
    // A too-long torn row prevents compaction; original prefix is never replaced.
    {std::ofstream f(path);f<<std::string(16385,'x')<<"\n";}
    {pikmin::AsyncTsvLog log;assert(log.append(path,"new\n",now));log.flush();assert(log.errors>=1);}
    {std::ifstream f(path);std::string line;std::getline(f,line);assert(line.size()==16385);}
    // Interrupted trailing record and abandoned pre-rename temporary file never replace source.
    {std::ofstream f(path);f<<"1\tpartial";}
    const auto abandoned=path+".retain.abandoned";{std::ofstream f(abandoned);f<<"incomplete replacement";}
    {pikmin::AsyncTsvLog log;assert(log.append(path,"200000000\tnew\n",now));log.flush();assert(log.errors>=1);}
    {std::ifstream f(path);std::string all((std::istreambuf_iterator<char>(f)),{});assert(all=="1\tpartial\n200000000\tnew\n");}
    assert(access(abandoned.c_str(),F_OK)==0);unlink(abandoned.c_str());
    unlink(path.c_str());unlink((path+".writer-lock").c_str());
    puts("PASS history retention: 10MB, bounded queue, next-day, malformed preserve, concurrent writers, symlink and torn-row refusal");
}
