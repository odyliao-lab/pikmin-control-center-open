#pragma once
#include <string>
#include <vector>
#include <limits>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>

// Independent ordinary-planting authority. No GPS, route phase, provider or HC.
namespace emu_independent {
struct Permit {
    int pid{};unsigned long long epoch{};long long written{},until{};
    std::string boot,account,token;
    bool valid()const{return pid>0&&!token.empty();}
};
inline bool uuid(const std::string &s){
    if(s.size()!=36)return false;
    for(size_t i=0;i<s.size();++i){if(i==8||i==13||i==18||i==23){if(s[i]!='-')return false;}
        else if(!((s[i]>='0'&&s[i]<='9')||(s[i]>='a'&&s[i]<='f')))return false;}return true;
}
inline bool digest(const std::string &s){return s.size()==64&&s.find_first_not_of("0123456789abcdef")==std::string::npos;}
inline bool number(const std::string &s,unsigned long long &out){
    if(s.empty()||s[0]=='0'||s.size()>19)return false;out=0;
    for(char c:s){if(c<'0'||c>'9'||out>(9223372036854775807ULL-(c-'0'))/10)return false;out=out*10+(c-'0');}return true;
}
inline Permit parse(const std::string &wire,int pid,unsigned long long epoch,const std::string &account,const std::string &boot,long long now){
    if(wire.size()>512||wire.empty()||wire.back()!='\n'||wire.find('\r')!=std::string::npos)return {};
    std::vector<std::string> f;size_t from=0;
    for(size_t i=0;i<wire.size();++i)if(wire[i]=='\t'){f.push_back(wire.substr(from,i-from));from=i+1;}f.push_back(wire.substr(from));
    unsigned long long p{},e{},w{},u{};
    if(f.size()!=9||f[0]!="v1"||!uuid(f[1])||f[1]!=boot||!number(f[2],p)||p>2147483647||p!=static_cast<unsigned>(pid)||
       !number(f[3],e)||e!=epoch||!digest(f[4])||f[4]!=account||!uuid(f[5])||!number(f[6],w)||!number(f[7],u)||
       f[8]!="end\n"||now<1000000000000LL||w>static_cast<unsigned long long>(now)||u<=static_cast<unsigned long long>(now)||u<=w||u-w>120000)return {};
    Permit r;r.pid=pid;r.epoch=epoch;r.boot=boot;r.account=account;r.token=f[5];r.written=w;r.until=u;return r;
}
inline std::string boot_id(){
    char b[38]{};int fd=open("/proc/sys/kernel/random/boot_id",O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if(fd<0)return {};auto n=read(fd,b,sizeof(b));bool ok=close(fd)==0&&n==37&&b[36]=='\n';
    return ok&&uuid(std::string(b,36))?std::string(b,36):std::string{};
}
inline Permit read_permit(int pid,unsigned long long epoch,const std::string &account,long long now){
    const char *path="/data/local/tmp/pcc-independent-planting.tsv";struct stat leaf{},held{},after{};
    if(lstat(path,&leaf)!=0||!S_ISREG(leaf.st_mode)||leaf.st_uid!=0||leaf.st_nlink!=1||(leaf.st_mode&0777)!=0644||leaf.st_size<1||leaf.st_size>512)return {};
    int fd=open(path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW|O_NONBLOCK);if(fd<0)return {};
    char b[513]{};bool ok=fstat(fd,&held)==0&&held.st_ino==leaf.st_ino&&held.st_dev==leaf.st_dev&&held.st_size==leaf.st_size;
    auto n=ok?read(fd,b,sizeof(b)):-1;ok=ok&&n==held.st_size&&fstat(fd,&after)==0&&after.st_size==held.st_size;
    if(close(fd)!=0)ok=false;return ok?parse(std::string(b,n),pid,epoch,account,boot_id(),now):Permit{};
}
}
