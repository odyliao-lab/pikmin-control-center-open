#include "../cpp/async_dispatch_projection.h"
#include <unistd.h>
#include <sys/stat.h>
#include <atomic>
static std::atomic<bool> fail_directory_sync{false};
static int projection_test_fsync(int fd) {
    struct stat state{};
    if(fstat(fd,&state)==0&&S_ISDIR(state.st_mode)&&fail_directory_sync.load()){errno=EIO;return -1;}
    return ::fsync(fd);
}
#define fsync projection_test_fsync
#include "../cpp/dispatch_projection_file.h"
#undef fsync
#include <cassert>
#include <cstring>
#include <vector>
#include <cstdlib>

using namespace pcc_projection;
using namespace std::chrono_literals;
struct Gate {
    std::mutex mutex;std::condition_variable cv;bool open{};
    void set(){std::lock_guard<std::mutex> lock(mutex);open=true;cv.notify_all();}
    void wait(){std::unique_lock<std::mutex> lock(mutex);assert(cv.wait_for(lock,5s,[&]{return open;}));}
};
static Snapshot job(pcc_cap::Registry &r,int64_t stamp,bool target=false) {
    Snapshot s;s.bytes=std::to_string(stamp);s.token=r.begin_scan();s.pid=getpid();s.observed_ms=stamp;s.count=1;s.complete=true;s.target=target;return s;
}
static bool current(pcc_cap::Registry &r,const Snapshot &s) {
    auto t=r.begin_scan();return t.valid&&s.token.valid&&t.epoch==s.token.epoch;
}
static std::string read(const std::string &path) {
    FILE *f=fopen(path.c_str(),"r");assert(f);char data[64];size_t n=fread(data,1,sizeof(data),f);fclose(f);return {data,n};
}
int main() {
    Rows rows;rows.append("%lld\t%s\t%s\t0\t%lld\t%.7f\t%.7f\tpending-travel-estimate\t%.1f\t%lld\t%d\t%d\n",
        10LL,"fruit","fixture",20LL,0.0,0.0,1.25,30LL,1,2);
    assert(rows.valid&&rows.bytes=="10\tfruit\tfixture\t0\t20\t0.0000000\t0.0000000\tpending-travel-estimate\t1.2\t30\t1\t2\n");
    Rows long_row;long_row.append("%s",std::string(16384,'x').c_str());assert(!long_row.valid&&long_row.bytes.empty());
    Rows overflow;for(int i=0;i<1025;i++)overflow.append("%s",std::string(1024,'x').c_str());assert(!overflow.valid&&overflow.bytes.empty());
    {
        pcc_cap::Registry registry;Gate entered,release;std::vector<int64_t> published;
        Worker worker([&](const Snapshot&s){return current(registry,s);},[&](const Snapshot&s){
            if(s.observed_ms==1){entered.set();release.wait();}published.push_back(s.observed_ms);return true;
        },[&](const Snapshot&s){return registry.commit_scan(s.token,s.observed_ms,s.count,s.complete);});
        assert(worker.submit(job(registry,1)));entered.wait();
        // The publisher remains blocked while the producer replaces only pending full work.
        assert(worker.submit(job(registry,2)));assert(worker.submit(job(registry,3)));assert(worker.submit(job(registry,4,true)));
        assert(worker.coalesced==1);release.set();assert(worker.wait_idle(5s));
        assert((published==std::vector<int64_t>{1,3,4}));assert(registry.snapshot().scan_ms==3);
        auto bad=job(registry,5);bad.bytes.assign(max_bytes+1,'x');assert(!worker.submit(std::move(bad)));
        bad=job(registry,5);bad.pid++;assert(!worker.submit(std::move(bad)));
        bad=job(registry,5);bad.token.valid=false;assert(!worker.submit(std::move(bad)));
        bad=job(registry,5);bad.complete=false;assert(worker.submit(std::move(bad)));assert(worker.wait_idle(5s));
        assert(registry.snapshot().scan_ms==3);
    }
    {
        pcc_cap::Registry registry;bool fail=true;
        Worker worker([&](const Snapshot&s){return current(registry,s);},[&](const Snapshot&){return !fail;},
            [&](const Snapshot&s){return registry.commit_scan(s.token,s.observed_ms,s.count,s.complete);});
        assert(worker.submit(job(registry,20)));assert(worker.wait_idle(5s));assert(worker.failed==1&&registry.snapshot().scan_ms==0);
        fail=false;assert(worker.submit(job(registry,21)));assert(worker.wait_idle(5s));assert(registry.snapshot().scan_ms==21);
    }
    {
        pcc_cap::Registry registry;Gate entered,release;int calls=0;
        Worker worker([&](const Snapshot&s){return current(registry,s);},[&](const Snapshot&){++calls;entered.set();release.wait();return true;},
            [&](const Snapshot&s){return registry.commit_scan(s.token,s.observed_ms,s.count,s.complete);});
        auto old=job(registry,30);assert(worker.submit(old));entered.wait();registry.on_logout();
        assert(worker.submit(old));release.set();assert(worker.wait_idle(5s));
        assert(calls==1&&worker.stale==2&&registry.snapshot().scan_ms==0);
    }
    const char *base=getenv("TMPDIR");std::string pattern=std::string(base?base:"/tmp")+"/pcc-projection-XXXXXX";
    std::vector<char> name(pattern.begin(),pattern.end());name.push_back(0);char *dir=mkdtemp(name.data());assert(dir);
    std::string full=std::string(dir)+"/dispatch_candidates.tsv",target=std::string(dir)+"/dispatch_target_candidate.tsv",alias=std::string(dir)+"/alias";
    {Writer w(full.c_str(),1);assert(w.stream());assert(fputs("old",w.stream())>=0);assert(w.commit());}assert(read(full)=="old");
    {Writer w(full.c_str(),2);assert(w.stream());assert(fputs("new",w.stream())>=0);assert(w.commit());}assert(read(full)=="new");
    {Writer w(full.c_str(),3);assert(w.stream());assert(fputs("partial",w.stream())>=0);}assert(read(full)=="new");
    {Writer w(full.c_str(),4);assert(w.stream());FILE *f=fopen(full.c_str(),"a");assert(f);assert(fputs("changed",f)>=0);fclose(f);assert(!w.commit());}assert(read(full)=="newchanged");
    {Writer w(target.c_str(),5);assert(w.stream());assert(fputs("target",w.stream())>=0);assert(w.commit());}assert(read(target)=="target");
    {
        pcc_cap::Registry registry;
        Worker worker([&](const Snapshot&s){return current(registry,s);},[&](const Snapshot&s){
            Writer w(full.c_str(),s.observed_ms);return w.stream()&&fputs(s.bytes.c_str(),w.stream())>=0&&w.commit();
        },[&](const Snapshot&s){return registry.commit_scan(s.token,s.observed_ms,s.count,s.complete);});
        fail_directory_sync=true;
        assert(worker.submit(job(registry,40)));assert(worker.wait_idle(5s));
        assert(read(full)=="40"&&worker.failed==1&&registry.snapshot().scan_ms==0);
        fail_directory_sync=false;
    }
    {
        pcc_cap::Registry registry;Gate entered,release;
        Worker worker([&](const Snapshot&s){return current(registry,s);},[&](const Snapshot&s){
            Writer w(full.c_str(),s.observed_ms);assert(w.stream());assert(fputs(s.bytes.c_str(),w.stream())>=0);
            entered.set();release.wait();return w.commit();
        },[&](const Snapshot&s){return registry.commit_scan(s.token,s.observed_ms,s.count,s.complete);});
        assert(worker.submit(job(registry,41)));entered.wait();registry.on_logout();release.set();assert(worker.wait_idle(5s));
        assert(read(full)=="41"&&worker.stale==1&&registry.snapshot().scan_ms==0);
    }
    assert(link(full.c_str(),alias.c_str())==0);{Writer w(full.c_str(),6);assert(!w.stream());}assert(unlink(alias.c_str())==0);
    assert(unlink(target.c_str())==0);assert(symlink(full.c_str(),target.c_str())==0);{Writer w(target.c_str(),7);assert(!w.stream());}
    assert(read(full)=="41");assert(unlink(target.c_str())==0);assert(unlink(full.c_str())==0);assert(rmdir(dir)==0);
    puts("PASS async dispatch projection: bounded owned rows, blocked-publisher coalescing, separate target lane, epoch/failure CAP guards and atomic-file failures; no managed/game calls");
}
