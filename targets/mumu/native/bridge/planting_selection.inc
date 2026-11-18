// MuMu-only selection transaction. Included after the actual catalogue backend.
// SetFlowerPetal is a mutation (including an active-session RPC branch), not UI.
namespace emu_planting_tx {
namespace selection {
bool initialized{}, review{}, pending{}, confirmed{};
std::string desired, lease_token, session_account;
uint64_t session_epoch{};
std::string path(){return emu_planting_tx::path()+".selection";}
bool uuid(const std::string &s){
    if(s.size()!=36)return false;
    for(size_t i=0;i<s.size();++i){
        if(i==8||i==13||i==18||i==23){if(s[i]!='-')return false;}
        else if(!((s[i]>='0'&&s[i]<='9')||(s[i]>='a'&&s[i]<='f')))return false;
    }return true;
}
bool digest(const std::string &s){
    if(s.size()!=64)return false;
    for(char c:s)if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))return false;
    return true;
}
bool save(const char *phase){
    const auto dest=path(),temp=dest+".tmp";
    const std::string row="v1\t"+std::to_string(getpid())+"\t"+std::to_string(session_epoch)+"\t"+
        session_account+"\t"+lease_token+"\t"+phase+"\t"+desired+"\tend\n";
    int fd=open(temp.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(fd<0)return false;
    struct stat st{};
    bool ok=fstat(fd,&st)==0 && S_ISREG(st.st_mode) && st.st_uid==getuid() && st.st_nlink==1 &&
        fchmod(fd,0600)==0 && write(fd,row.data(),row.size())==static_cast<ssize_t>(row.size()) && fsync(fd)==0;
    if(close(fd)!=0)ok=false;
    if(!ok || rename(temp.c_str(),dest.c_str())!=0)return false;
    fd=open(dest.substr(0,dest.find_last_of('/')).c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0)return false;
    ok=fsync(fd)==0;if(close(fd)!=0)ok=false;return ok;
}
void initialize(){
    if(initialized)return;initialized=true;
    int fd=open(path().c_str(),O_RDONLY|O_NOFOLLOW|O_CLOEXEC);
    if(fd<0){review=errno!=ENOENT;return;}
    struct stat st{};char buf[512]{};
    bool ok=fstat(fd,&st)==0 && S_ISREG(st.st_mode) && st.st_uid==getuid() && st.st_nlink==1 &&
        (st.st_mode&0777)==0600 && st.st_size>0 && st.st_size<static_cast<off_t>(sizeof(buf));
    auto n=ok?read(fd,buf,sizeof(buf)-1):-1;if(close(fd)!=0)ok=false;
    std::vector<std::string> fields;std::string row=ok&&n==st.st_size?std::string(buf,n):std::string{};
    size_t from=0;
    for(size_t i=0;i<row.size();++i)if(row[i]=='\t'){fields.push_back(row.substr(from,i-from));from=i+1;}
    fields.push_back(row.substr(from));
    // Even confirmed selection is unresolved after process death. Only a
    // closed selection whose planting transaction also stopped may be reused.
    review=!(fields.size()==8 && fields[0]=="v1" && fields[1].size()<=10 && !fields[1].empty() &&
        fields[1][0]!='0' && fields[1].find_first_not_of("0123456789")==std::string::npos &&
        !fields[2].empty() && fields[2].size()<=20 && fields[2][0]!='0' &&
        fields[2].find_first_not_of("0123456789")==std::string::npos && digest(fields[3]) && uuid(fields[4]) &&
        fields[5]=="closed" && emu_petal::id_valid(fields[6]) && fields[7]=="end\n");
}
bool context(){
    const auto lease=route_lease();const auto observed=emu_planting_observation::read();
    long long started{};bool active{};
    return runtime_metadata_ready && manual_start_clear() && same_session() && planting_controller &&
        emu_late::exact(planting_controller,"Niantic.Ichigo.Game.Flowers","FlowerPlantingController") &&
        state.phase==Phase::idle && read_planting_control_mode()=="on" && lease.valid() &&
        lease.token==token && lease.account==account && lease.phase!="prepare" && uuid(token) && digest(account) &&
        observed.known && !observed.active && read_planting_state(started,active) && !active &&
        route_location_gate.ready(nearby_elapsed_ms()) && cleanup_ready();
}
bool selected(std::string &text){
    bool ok=true;void *value=emu_late::call(planting_controller,"get_FlowerPetalId",ok,nullptr,0);
    ScopedManagedRoot held(value,gchandle_new,gchandle_free);
    if(!ok || !held.handle || !emu_late::exact(value,"System","String"))return false;
    const auto *s=static_cast<Il2CppStringLayout *>(value);
    if(s->length<0 || s->length>80)return false;
    text.clear();for(int i=0;i<s->length;++i){if(s->chars[i]>127)return false;text.push_back(static_cast<char>(s->chars[i]));}
    return text.empty() || emu_petal::id_valid(text);
}
bool allowed(const std::string &id){
    const auto snapshot=emu_petal::read_managed_catalogue();
    if(!emu_petal::coherent(snapshot,now_ms(),getpid(),epoch,account))return false;
    for(const auto &row:snapshot.rows)if(row.id==id)return row.ordinary && row.stock>emu_petal::reserve;
    return false;
}
// Read-only resource check before binding a newly accepted command to the
// transaction. Never use request fields as the live account/epoch authority.
bool command_resources(const std::string &id,uint64_t live_epoch,const std::string &live_account){
    if(!emu_petal::id_valid(id))return false;
    const auto snapshot=emu_petal::read_managed_catalogue();
    if(!emu_petal::coherent(snapshot,now_ms(),getpid(),live_epoch,live_account))return false;
    for(const auto &row:snapshot.rows)if(row.id==id)return row.ordinary && row.stock>emu_petal::reserve;
    return false;
}
bool command_selection_clear(){initialize();return !review && !pending;}
void fail(){review=true;state.phase=Phase::review;wait_reason="selection-review-required";}
void *method(){
    void *klass=object_get_class(planting_controller);
    void *m=class_get_method_from_name(klass,"SetFlowerPetal",1);
    void *entry=m?*static_cast<void **>(m):nullptr;Dl_info image{};
    // Actual v154 method, not a blind RVA or active-session change shortcut.
    static const unsigned char pin[]{0xfe,0x0f,0x1d,0xf8,0xf6,0x57,0x01,0xa9,
        0xf4,0x4f,0x02,0xa9,0xb5,0x9f,0x04,0xb0};
    if(!entry || !dladdr(entry,&image) || !image.dli_fbase || !image.dli_fname ||
        !std::strstr(image.dli_fname,"libil2cpp.so") ||
        reinterpret_cast<uintptr_t>(entry)!=reinterpret_cast<uintptr_t>(image.dli_fbase)+0x5F538CC ||
        std::memcmp(entry,pin,sizeof(pin)))return nullptr;
    return m;
}
}
bool validate_selected_petal(const std::string &id){return selection::allowed(id);}
bool active_selected_petal_safe(const std::string &owned){
    // Same-session owned active transaction only. Reuse the confirmed catalogue
    // reader: predicted values/partial inventory remain UNKNOWN, not safe stock.
    std::string current,after;
    return state.phase==Phase::active && planting_control_owned && same_session() &&
        !owned.empty() && selection::selected(current) && current==owned &&
        selection::allowed(current) && selection::selected(after) && after==owned && same_session();
}
bool prepare_selection_impl(const std::string &requested){
    using namespace selection;
    selection::initialize();wait_reason="petal-catalogue-unavailable";
    if(review){fail();return false;}
    if(!context())return false;
    std::string current;if(!selected(current))return false;
    const auto catalogue=emu_petal::read_managed_catalogue();
    if(!emu_petal::coherent(catalogue,now_ms(),getpid(),epoch,account))return false;
    const emu_petal::Row *choice=nullptr;
    if(requested.empty())choice=emu_petal::available(catalogue,current);
    else if(emu_petal::id_valid(requested)) {
        for(const auto &row:catalogue.rows)
            if(row.id==requested && row.ordinary && row.stock>emu_petal::reserve){choice=&row;break;}
    }
    wait_reason="ordinary-petals-at-reserve";
    if(!choice)return false;
    if(confirmed){
        if(lease_token!=token || session_epoch!=epoch || session_account!=account || current!=desired){fail();return false;}
        return choice->id==desired && context() && allowed(desired);
    }
    if(pending){fail();return false;}
    if(choice->id==current)return context() && allowed(current);
    wait_reason="petal-selection-method-unavailable";
    if(!string_new || !reward_invoke || !gchandle_new || !gchandle_free)return false;
    void *m=method();if(!m)return false;
    desired=choice->id;lease_token=token;session_epoch=epoch;session_account=account;
    void *managed=string_new(desired.c_str());ScopedManagedRoot held(managed,gchandle_new,gchandle_free);
    if(!held.handle || !emu_late::exact(managed,"System","String"))return false;
    pending=true;
    // A persisted pending selection is never replayed, including a void return
    // with an exception or a successful setter whose read-back is unavailable.
    if(!save("pending")){storage_failed=true;fail();return false;}
    std::string rechecked;
    if(!allowed(desired) || method()!=m || !selected(rechecked) || rechecked!=current || !context()){fail();return false;}
    void *args[]{managed},*exception{};
    reward_invoke(m,planting_controller,args,&exception); // VOID; nullptr is not failure
    if(exception || !context() || !selected(rechecked) || rechecked!=desired || !allowed(desired)){fail();return false;}
    if(!save("confirmed")){storage_failed=true;fail();return false;}
    pending=false;confirmed=true;return true;
}
// Explicit command backend: never substitute a different available ID.
// The command consumer must persist/read back its operation and ownership first.
bool select_petal(const std::string &id){
    emu_planting_tx::initialize();
    return state.phase==Phase::idle && !storage_failed && !id.empty() && emu_petal::id_valid(id) && prepare_selection_impl(id);
}
// Existing ON/lease manager keeps its legacy choose-then-start behavior.
bool prepare_selection(){return prepare_selection_impl({});}
// START command backend must not call a setter, even when another ID has stock.
bool preselected_ready(const std::string &id){
    selection::initialize();
    if(id.empty() || !emu_petal::id_valid(id) || selection::review || selection::pending || !selection::context())return false;
    if(selection::confirmed && (selection::desired!=id || selection::lease_token!=token ||
       selection::session_epoch!=epoch || selection::session_account!=account))return false;
    std::string before,after;
    return selection::selected(before) && before==id && selection::allowed(id) &&
        selection::selected(after) && after==id && selection::context();
}
bool selection_terminal(){
    using namespace selection;
    if(review || pending)return false;
    if(!confirmed)return true;
    if(!same_session() || session_epoch!=epoch || session_account!=account || lease_token!=token || !save("closed"))return false;
    confirmed=false;return true;
}
}
