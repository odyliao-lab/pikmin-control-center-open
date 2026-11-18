// Explicit one-shot, read-only diagnostic. All automation must already be OFF.
#include <charconv>
namespace emu_petal {
struct Query {int pid{};unsigned long long epoch{};long long expires{};std::string account,nonce;};
bool lower_hex(const std::string &s,size_t length){
    if(s.size()!=length)return false;
    for(char c:s)if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))return false;
    return true;
}
bool uuid(const std::string &s){
    if(s.size()!=36)return false;
    for(size_t i=0;i<s.size();++i){
        if(i==8||i==13||i==18||i==23){if(s[i]!='-')return false;}
        else if(!lower_hex(s.substr(i,1),1))return false;
    }
    return true;
}
std::vector<std::string> fields(const std::string &row){
    std::vector<std::string> f;size_t begin{};
    for(size_t i=0;i<=row.size();++i)if(i==row.size() || row[i]=='\t'){f.push_back(row.substr(begin,i-begin));begin=i+1;}
    return f;
}
template<class T> bool decimal(const std::string &s,T &value){
    if(s.empty() || (s.size()>1 && s[0]=='0'))return false;
    for(char c:s)if(c<'0'||c>'9')return false;
    auto r=std::from_chars(s.data(),s.data()+s.size(),value);
    return r.ec==std::errc{} && r.ptr==s.data()+s.size();
}
bool parse_query(const std::string &data,Query &q){
    if(data.empty() || data.size()>256 || data.back()!='\n' || data.find('\n')!=data.size()-1)return false;
    auto f=fields(data.substr(0,data.size()-1));
    if(f.size()!=7 || f[0]!="v1" || f[6]!="end" || !lower_hex(f[3],64) || !uuid(f[4]) ||
       !decimal(f[1],q.pid) || q.pid<=0 || !decimal(f[2],q.epoch) || !q.epoch ||
       !decimal(f[5],q.expires) || q.expires<=0)return false;
    q.account=f[3];q.nonce=f[4];return true;
}
bool query_current(const Query &q,long long now,int pid,unsigned long long epoch,const std::string &account){
    return q.pid==pid && q.epoch==epoch && q.account==account && now>0 && q.expires>now && q.expires-now<=15000;
}
bool read_file(const std::string &path,std::string &out,bool private_file){
    int fd=open(path.c_str(),O_RDONLY|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return false;
    struct stat st{};char buf[513]{};
    bool ok=fstat(fd,&st)==0 && S_ISREG(st.st_mode) && st.st_nlink==1 &&
        (st.st_uid==getuid() || (!private_file && st.st_uid==0)) &&
        (st.st_mode&0777)==(private_file?0600:0644) && st.st_size>0 && st.st_size<=1024*1024;
    ssize_t n=ok?read(fd,buf,sizeof(buf)-1):-1;
    if(close(fd)!=0)ok=false;
    if(!ok || n<=0 || (private_file && n!=st.st_size))return false;
    out.assign(buf,n);return true;
}
bool controls_off(const std::string &base){
    std::string bytes;
    for(const char *mode:{"dispatch","return","planter","planting","flower-farm"})
        if(!read_file(base+"/pikmin-"+mode+"-mode.txt",bytes,false) || bytes!="off\n")return false;
    if(!read_file(base+"/pikmin-feeding-config.tsv",bytes,false))return false;
    auto end=bytes.find('\n');if(end==std::string::npos)return false;
    auto f=fields(bytes.substr(0,end));
    // FeedingConfig.encode() uses UUID.toString(), not a 32-digit digest.
    return f.size()==10 && f[0]=="v3" && uuid(f[1]) && f[2]=="0" && f[6]=="0";
}
std::string query_dir(){std::string p(core_runtime_path);return p.substr(0,p.find_last_of('/'));}
bool write_catalogue(const Query &q,const Snapshot &s){
    const auto dest=query_dir()+"/emulator_petal_catalogue.tsv",temp=dest+".tmp-"+q.nonce;
    struct stat old{};
    if(lstat(dest.c_str(),&old)==0){
        if(!S_ISREG(old.st_mode) || old.st_nlink!=1 || old.st_uid!=getuid() || (old.st_mode&0777)!=0600)return false;
    }else if(errno!=ENOENT)return false;
    int fd=open(temp.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);if(fd<0)return false;
    const auto count=s.complete?s.rows.size():0;
    std::string data="v1\t"+std::to_string(s.captured)+"\t"+std::to_string(q.pid)+"\t"+std::to_string(q.epoch)+
        "\t"+q.account+"\t"+q.nonce+"\t"+(s.complete?"complete":"unknown")+"\t"+std::to_string(s.revision)+"\t"+std::to_string(count)+"\n";
    if(s.complete)for(const auto &r:s.rows)data+="r\t"+r.id+"\t"+std::to_string(r.color)+"\t"+
        std::to_string(r.stock)+"\t"+(r.ordinary?"1":"0")+"\n";
    data+="end\t"+std::to_string(count)+"\n";
    struct stat st{};bool ok=fstat(fd,&st)==0 && S_ISREG(st.st_mode) && st.st_uid==getuid() && st.st_nlink==1 &&
        fchmod(fd,0600)==0 && write(fd,data.data(),data.size())==static_cast<ssize_t>(data.size()) && fsync(fd)==0;
    if(close(fd)!=0)ok=false;
    return ok && rename(temp.c_str(),dest.c_str())==0;
}
long long last_query_probe{};std::string consumed_query;
void query_tick(const std::string &control_base="/data/local/tmp"){
    const auto now=now_ms();if(now>=last_query_probe && now-last_query_probe<1000)return;last_query_probe=now;
    if(!runtime_metadata_ready || !emu_late::bound || emu_late::revoked)return;
    std::string data;if(!read_file(query_dir()+"/emulator_petal_read_request.tsv",data,true))return;
    Query q;if(!parse_query(data,q) || consumed_query==q.nonce)return;
    std::string account;{std::lock_guard<std::mutex> lock(identity_mutex);account=identity_hash;}
    if(!query_current(q,now,getpid(),capability_registry.current_epoch(),account) || !controls_off(control_base))return;
    long long started{};bool active{};
    if(!emu_planting_tx::read_planting_state(started,active) || active)return;
    consumed_query=q.nonce; // a write/capture failure is reported by absence, not silently retried
    auto s=read_managed_catalogue();
    {std::lock_guard<std::mutex> lock(identity_mutex);account=identity_hash;}
    if(!query_current(q,now_ms(),getpid(),capability_registry.current_epoch(),account) ||
       !emu_late::bound || emu_late::revoked || !controls_off(control_base))return;
    if(!coherent(s,now_ms(),q.pid,q.epoch,q.account))s.complete=false;
    write_catalogue(q,s);
}
}
