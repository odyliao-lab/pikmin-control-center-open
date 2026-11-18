#pragma once
#include <condition_variable>
#include <deque>
#include <fstream>
#include <map>
#include <mutex>
#include <thread>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <string>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <cerrno>
#include <vector>
#include <sys/file.h>

namespace pikmin {
// File IO and retention run only on this native worker. No managed objects.
class AsyncTsvLog {
    struct Row { std::string path, text; int64_t now; };
    std::mutex mutex_;
    std::condition_variable wake_, drained_;
    std::deque<Row> queue_;
    bool stop_{}, busy_{};
    std::map<std::string,int64_t> pruned_;
    size_t queued_bytes_{};
    std::thread worker_;
    struct FileLease {
        int fd{-1};bool held{};
        explicit FileLease(const std::string &path){
            fd=open((path+".writer-lock").c_str(),O_RDWR|O_CREAT|O_NOFOLLOW|O_CLOEXEC,0600);
            struct stat st{};held=fd>=0&&fstat(fd,&st)==0&&S_ISREG(st.st_mode)&&flock(fd,LOCK_EX)==0;
        }
        ~FileLease(){if(held)flock(fd,LOCK_UN);if(fd>=0)close(fd);}
    };
    static bool retain(const std::string &path,int64_t now) {
        int source=open(path.c_str(),O_RDONLY|O_NOFOLLOW|O_CLOEXEC);
        if(source<0)return errno==ENOENT;
        struct stat st{};
        if(fstat(source,&st)!=0||!S_ISREG(st.st_mode)||st.st_size<0||st.st_size>64*1024*1024){close(source);return false;}
        FILE *in=fdopen(source,"r");if(!in){close(source);return false;}
        std::string pattern=path+".retain.XXXXXX";std::vector<char> tmp(pattern.begin(),pattern.end());tmp.push_back(0);
        int destination=mkstemp(tmp.data());if(destination<0){fclose(in);return false;}
        FILE *out=fdopen(destination,"w");if(!out){close(destination);unlink(tmp.data());fclose(in);return false;}
        bool ok=true;std::string line;char buffer[512];size_t read_count;
        auto emit=[&](){
            char *end=nullptr;errno=0;auto time=std::strtoll(line.c_str(),&end,10);
            bool valid=errno==0&&end!=line.c_str()&&*end=='\t'&&time>0;
            // Invalid/future evidence is retained verbatim, not erased as expired.
            return (valid&&time<now-86400000LL) || fwrite(line.data(),1,line.size(),out)==line.size();
        };
        while(ok&&(read_count=fread(buffer,1,sizeof(buffer),in))>0) {
            for(size_t i=0;i<read_count;i++){
                if(buffer[i]==0||line.size()>=16384){ok=false;break;}
                line+=buffer[i];
                if(buffer[i]=='\n'){if(!emit()){ok=false;break;}line.clear();}
            }
        }
        // An interrupted trailing row is not complete evidence: preserve source, refuse compaction.
        if(ok&&!line.empty())ok=false;
        ok=ok&&!ferror(in);if(fclose(in)!=0)ok=false;
        if(fflush(out)!=0||fchmod(destination,0644)!=0||fsync(destination)!=0)ok=false;
        if(fclose(out)!=0)ok=false;
        if(ok&&rename(tmp.data(),path.c_str())==0) {
            const auto slash=path.find_last_of('/');if(slash==std::string::npos)return false;
            int dir=open(path.substr(0,slash).c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
            if(dir<0)return false;bool durable=fsync(dir)==0;close(dir);return durable;
        }
        unlink(tmp.data());return false;
    }
    void run() {
        for (;;) {
            std::deque<Row> batch;
            { std::unique_lock<std::mutex> lock(mutex_);
              wake_.wait(lock,[&]{return stop_||!queue_.empty();});
              if(stop_&&queue_.empty())return;
              busy_=true; batch.swap(queue_); queued_bytes_=0; }
            std::map<std::string,std::pair<int64_t,std::string>> files;
            for(auto &row:batch){auto &f=files[row.path];f.first=row.now;f.second+=row.text;}
            for(auto &entry:files) {
                const auto &path=entry.first; const auto now=entry.second.first;
                FileLease lease(path);if(!lease.held){++errors;continue;}
                // At most once per minute, independent of event count.
                if(!pruned_.count(path)||now-pruned_[path]>=60000) {
                    pruned_[path]=now;
                    if(retain(path,now))++prunes;else ++errors;
                }
                int fd=open(path.c_str(),O_RDWR|O_APPEND|O_CREAT|O_NOFOLLOW|O_CLOEXEC,0644);
                if(fd<0){++errors;continue;}
                struct stat target{};if(fstat(fd,&target)!=0||!S_ISREG(target.st_mode)){close(fd);++errors;continue;}
                char last='\n';
                if(target.st_size>0&&pread(fd,&last,1,target.st_size-1)!=1){close(fd);++errors;continue;}
                FILE *out=fdopen(fd,"ab");if(!out){close(fd);++errors;continue;}
                const auto &text=entry.second.second;
                bool ok=(last=='\n'||fwrite("\n",1,1,out)==1)&&std::fwrite(text.data(),1,text.size(),out)==text.size();
                ok=(fflush(out)==0&&fsync(fd)==0&&fchmod(fd,0644)==0)&&ok;
                ok=(std::fclose(out)==0)&&ok;
                if(ok)bytes+=text.size();else ++errors;
            }
            {std::lock_guard<std::mutex> lock(mutex_);busy_=false;if(queue_.empty())drained_.notify_all();}
        }
    }
public:
    std::atomic<uint64_t> bytes{0},prunes{0},dropped{0},errors{0};
    AsyncTsvLog(){worker_=std::thread([this]{run();});}
    ~AsyncTsvLog(){ {std::lock_guard<std::mutex> lock(mutex_);stop_=true;}wake_.notify_one();worker_.join(); }
    bool append(const std::string &path,std::string text,int64_t now) {
        std::lock_guard<std::mutex> lock(mutex_);
        // Bounded memory; overload is explicitly reported, never success evidence.
        if(stop_||text.size()>16384||queue_.size()>=4096||queued_bytes_+text.size()>1048576){++dropped;return false;}
        queued_bytes_+=text.size();
        queue_.push_back({path,std::move(text),now});wake_.notify_one();return true;
    }
    void flush(){std::unique_lock<std::mutex> lock(mutex_);drained_.wait(lock,[&]{return queue_.empty()&&!busy_;});}
};
}
