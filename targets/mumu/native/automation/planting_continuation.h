#pragma once
#include <cerrno>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>
#include <sstream>
#include "session_identity.h"

// UNKNOWN evidence, never a settlement or replay queue. No game APIs here.
namespace planting_continuation {
inline bool uuid(const std::string& s) {
    if(s.size()!=36)return false;
    for(size_t i=0;i<s.size();++i)if(i==8||i==13||i==18||i==23){if(s[i]!='-')return false;}
    else if(!((s[i]>='0'&&s[i]<='9')||(s[i]>='a'&&s[i]<='f')))return false;
    return true;
}
inline std::vector<std::string> fields(const std::string& s) {
    std::vector<std::string> f;size_t from=0;
    for(size_t i=0;i<s.size();++i)if(s[i]=='\t'){f.push_back(s.substr(from,i-from));from=i+1;}
    f.push_back(s.substr(from));return f;
}
inline bool number(const std::string& s) {return !s.empty()&&s.size()<=19&&s[0]!='0'&&s.find_first_not_of("0123456789")==std::string::npos;}
inline bool original(const std::string& s,const std::string& account) {
    if(account.find_first_not_of("0123456789abcdef")!=std::string::npos)return false;
    const auto f=fields(s);
    return f.size()==10&&f[0]=="v3"&&number(f[1])&&f[1].size()<=10&&number(f[2])&&
        account.size()==64&&f[3]==account&&uuid(f[4])&&
        (f[5]=="review"||f[5]=="start-pending"||f[5]=="active-confirmed"||f[5]=="stop-pending")&&
        number(f[6])&&(f[7]=="0"||f[7]=="1")&&(f[8]=="0"||number(f[8]))&&f[9]=="end\n";
}
// A confirmed terminal is NOT UNKNOWN. A different, archived dead selector
// can outlive it when later STARTs reuse the selected flower without a setter.
// Keep both histories; only the independently witnessed terminal can anchor
// this narrow cold predecessor recovery.
inline bool terminal_original(const std::string& s,const std::string& account) {
    const auto f=fields(s);
    return account.size()==64&&account.find_first_not_of("0123456789abcdef")==std::string::npos&&
        f.size()==10&&f[0]=="v3"&&number(f[1])&&f[1].size()<=10&&number(f[2])&&f[3]==account&&uuid(f[4])&&
        f[5]=="stopped-confirmed"&&number(f[6])&&f[7]=="1"&&number(f[8])&&f[9]=="end\n";
}
inline bool terminal_selector(const std::string& terminal,const std::string& selected,
        const std::string& prior,const std::string& prior_selected,const std::string& settlement,
        const std::string& account) {
    if(!terminal_original(terminal,account)||!original(prior,account)||selected!=prior_selected)return false;
    const auto t=fields(terminal),s=fields(selected),p=fields(prior),r=fields(settlement);
    if(s.size()!=8||s[0]!="v1"||!number(s[1])||s[1].size()>10||!number(s[2])||s[3]!=account||
       !uuid(s[4])||s[4]==t[4]||(s[5]!="confirmed"&&s[5]!="closed")||s[6].empty()||s[6].size()>80||
       s[6].find_first_not_of("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._:-")!=std::string::npos||s[7]!="end\n"||
       p[1]!=s[1]||p[2]!=s[2]||p[3]!=s[3]||p[4]!=s[4])return false;
    if(r.size()!=10||r[0]!="PCCSETTLEMENT/1"||r[1]!=account||r[2]!=t[1]||r[3]!=t[2]||r[4]!=t[4]||
       r[5]!=t[8]||!number(r[6])||r[7].empty()||r[7].size()>19||r[7].find_first_not_of("0123456789")!=std::string::npos||
       r[8]!="closed-confirmed"||r[9]!="end\n")return false;
    // Equal-length decimal comparison avoids overflow from untrusted fields.
    auto le=[](const std::string& a,const std::string& b){return a.size()<b.size()||(a.size()==b.size()&&a<=b);};
    return le(t[8],r[6])&&le(r[6],t[6]);
}
inline bool read(const std::string& path,std::string& bytes,bool& exists) {
    bytes.clear();exists=false;
    int fd=open(path.c_str(),O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0)return errno==ENOENT;
    struct stat st{};char buf[4097];ssize_t n=::read(fd,buf,sizeof(buf));
    bool ok=fstat(fd,&st)==0&&S_ISREG(st.st_mode)&&st.st_uid==getuid()&&st.st_nlink==1&&
        (st.st_mode&0777)==0600&&n>0&&n<=4096&&n==st.st_size;
    if(close(fd))ok=false;
    if(ok){bytes.assign(buf,n);exists=true;}return ok;
}
inline bool preserve(const std::string& path,const std::string& bytes) {
    std::string old;bool exists=false;if(bytes.empty()||!read(path,old,exists))return false;
    if(exists&&old!=bytes)return false;
    if(!exists){
        int fd=open(path.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
        if(fd<0)return false;
        size_t done=0;bool ok=true;
        while(done<bytes.size()) {auto n=write(fd,bytes.data()+done,bytes.size()-done);if(n<0&&errno==EINTR)continue;if(n<=0){ok=false;break;}done+=n;}
        if(fsync(fd))ok=false;if(close(fd))ok=false;if(!ok)return false;
    }
    // Re-sync existing evidence too: retry after a failed directory fsync must
    // not silently grant continuation with an uncommitted archive.
    int fd=open(path.c_str(),O_RDONLY|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return false;
    bool ok=fsync(fd)==0;if(close(fd))ok=false;
    int dir=open(path.substr(0,path.find_last_of('/')).c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if(dir<0)return false;if(fsync(dir))ok=false;if(close(dir))ok=false;
    return ok&&read(path,old,exists)&&exists&&old==bytes;
}
inline std::string current_boot(){
    int fd=open("/proc/sys/kernel/random/boot_id",O_RDONLY|O_CLOEXEC);if(fd<0)return {};
    char b[80];auto n=::read(fd,b,sizeof(b));close(fd);if(n<=0||n>=static_cast<ssize_t>(sizeof(b)))return {};
    std::string s(b,n);if(!s.empty()&&s.back()=='\n')s.pop_back();return uuid(s)?s:std::string{};
}
inline bool process_ticks(const std::string& pid,std::string& ticks,bool& exists){
    exists=false;if(!number(pid))return false;
    int fd=open(("/proc/"+pid+"/stat").c_str(),O_RDONLY|O_CLOEXEC);if(fd<0)return errno==ENOENT;
    char b[4096];auto n=::read(fd,b,sizeof(b));close(fd);if(n<=0||n>=static_cast<ssize_t>(sizeof(b)))return false;
    std::string s(b,n);auto end=s.rfind(')');if(end==std::string::npos)return false;
    std::istringstream in(s.substr(end+1));for(int field=3;field<=22;++field)if(!(in>>ticks))return false;
    exists=true;return number(ticks);
}
// App-published provenance extends the legacy v3 row, which has no boot/ticks.
// It is tied to the exact original bytes and never certifies an RPC outcome.
inline bool dead_owner_proof(const std::string& directory,const std::string& original_row){
    const auto old=fields(original_row);if(old.size()!=10||!uuid(old[4]))return false;
    std::string row;bool exists=false;
    if(!read(directory+"/planting-dead-owner-"+old[4]+".tsv",row,exists)||!exists)return false;
    const auto f=fields(row);
    if(f.size()!=9||f[0]!="PCCPLANTOWNER/1"||!uuid(f[1])||f[2]!=old[1]||!number(f[3])||
       f[4]!=old[2]||f[5]!=old[3]||f[6]!=old[4]||f[7]!=pikmin::account_digest(original_row)||f[8]!="end\n")return false;
    const auto boot=current_boot();if(boot.empty())return false;if(boot!=f[1])return true;
    std::string ticks;bool live=false;
    return process_ticks(f[2],ticks,live)&&(!live||ticks!=f[3]);
}
inline bool token_available(const std::string& directory,const std::string& token){
    if(!uuid(token))return false;std::string row;bool exists=false;
    return read(directory+"/planting-unconfirmed-"+token+".tsv",row,exists)&&!exists;
}
struct QuietWindow {
    long long first{},last{};
    bool observe(long long now,bool safe) {
        if(!safe||now<=0||now<last){first=last=0;return false;}
        if(last&&now-last>10000)first=0;
        if(!first)first=now;last=now;return now-first>=30000;
    }
};
}
