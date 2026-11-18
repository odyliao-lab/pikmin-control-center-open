// MuMu only. Passive observation of the catalogue already sampled by the
// existing five-second reserve check. Never selects, starts, stops or retries.
namespace emu_owned_petals {
void publish(const emu_petal::Snapshot &sample,const std::string &selected,
             const std::string &selected_after) {
    using namespace emu_planting_tx;
    const auto lease=route_lease();
    if(state.phase!=Phase::active || !planting_control_owned || storage_failed ||
       !same_session() || !manual_start_clear() || owned_started<=0 ||
       !lease.valid() || lease.token!=token || lease.account!=account ||
       lease.phase=="prepare" || read_planting_control_mode()!="on")return;
    const auto captured=now_ms();
    const bool complete=selected==owned_petal && selected_after==owned_petal &&
        emu_petal::coherent(sample,captured,getpid(),epoch,account);
    const auto count=complete?sample.rows.size():0;
    std::string data="v1\t"+std::to_string(captured)+"\t"+std::to_string(getpid())+"\t"+
        std::to_string(epoch)+"\t"+account+"\t"+token+"\t"+std::to_string(owned_started)+"\t"+
        owned_petal+"\t"+(complete?"complete":"unknown")+"\t"+
        std::to_string(complete?sample.revision:0)+"\t"+std::to_string(count)+"\n";
    if(complete)for(const auto &r:sample.rows)data+="r\t"+r.id+"\t"+std::to_string(r.color)+"\t"+
        std::to_string(r.stock)+"\t"+(r.ordinary?"1":"0")+"\n";
    data+="end\t"+std::to_string(count)+"\n";
    if(data.size()>65536 || !emu_petal::id_valid(owned_petal))return;
    const auto dest=emu_planting_tx::path()+".petals",temp=dest+".tmp";
    struct stat old{};
    if(lstat(dest.c_str(),&old)==0) {
        if(!S_ISREG(old.st_mode)||old.st_nlink!=1||old.st_uid!=getuid()||(old.st_mode&0777)!=0600)return;
    }else if(errno!=ENOENT)return;
    // A failed exclusive temp remains evidence, not an invitation to overwrite.
    int fd=open(temp.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(fd<0)return;
    struct stat st{};
    bool ok=fstat(fd,&st)==0 && S_ISREG(st.st_mode) && st.st_uid==getuid() && st.st_nlink==1 &&
        fchmod(fd,0600)==0 && write(fd,data.data(),data.size())==static_cast<ssize_t>(data.size()) && fsync(fd)==0;
    if(close(fd)!=0)ok=false;
    const auto fresh=route_lease();
    if(!ok || !same_session() || state.phase!=Phase::active || !fresh.valid() ||
       fresh.token!=token || fresh.account!=account || fresh.phase=="prepare" ||
       read_planting_control_mode()!="on" || rename(temp.c_str(),dest.c_str())!=0)return;
    fd=open(dest.substr(0,dest.find_last_of('/')).c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if(fd>=0){fsync(fd);close(fd);}
    // This file is observation only. Failed publication does not change native
    // state or permit wider resource checks; readers reject stale/missing bytes.
}
}
