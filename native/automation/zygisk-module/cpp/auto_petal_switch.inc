// Shared phone/NativeBridge implementation. Runs on the existing game thread.
#include "auto_petal_policy.h"
#include "auto_petal_inventory_policy.h"
#include <sys/stat.h>
#include <fcntl.h>
#include <sys/syscall.h>
namespace auto_petal_switch {
using namespace auto_petals;
struct Row {std::string id,family;int color{},stock{};};
struct Observation {bool known{},active{};long long started{},changed{},revision{};std::string petal,account;uint64_t epoch{};std::vector<Row> rows;};
long long last_tick{},round{};std::string account,expected,revision;uint64_t epoch{};
bool paused{},initialized{},unresolved{};std::string pending_raw;
void *stop_task{};GcHandle stop_root{};long long ready_since{};std::string ready_key;
RecoveryWindow recovery_window;
void forget_stop(){if(stop_root&&gchandle_free)gchandle_free(stop_root);stop_root=0;stop_task=nullptr;}
const char *config_path="/data/local/tmp/pikmin-auto-petals.tsv";
std::string file(const char *leaf){std::string p(core_runtime_path);return p.substr(0,p.find_last_of('/'))+"/"+leaf;}
bool read(const std::string &path,std::string &out,size_t limit,uid_t owner) {
    int fd=open(path.c_str(),O_RDONLY|O_NOFOLLOW|O_CLOEXEC|O_NONBLOCK);if(fd<0)return false;
    struct stat st{};bool ok=fstat(fd,&st)==0&&S_ISREG(st.st_mode)&&st.st_nlink==1&&st.st_uid==owner&&
        (st.st_mode&0022)==0&&st.st_size>0&&static_cast<size_t>(st.st_size)<=limit;
    out.assign(ok?st.st_size:0,'\0');if(ok)ok=::read(fd,&out[0],out.size())==static_cast<ssize_t>(out.size());
    if(close(fd)!=0)ok=false;return ok;
}
bool save(const char *leaf,const std::string &raw,bool new_pending=false){
    const auto dest=file(leaf);struct stat st{};
    if(lstat(dest.c_str(),&st)==0){
        if(new_pending||!S_ISREG(st.st_mode)||st.st_uid!=getuid()||st.st_nlink!=1)return false;
    }else if(errno!=ENOENT)return false;
    const auto pattern=dest+".XXXXXX";
    std::vector<char> temp(pattern.begin(),pattern.end());temp.push_back(0);
    int fd=mkostemp(temp.data(),O_CLOEXEC);if(fd<0)return false;
    bool ok=fchmod(fd,0600)==0&&write(fd,raw.data(),raw.size())==static_cast<ssize_t>(raw.size())&&fsync(fd)==0;
    if(close(fd)!=0)ok=false;
    if(!ok){unlink(temp.data());return false;}
    int renamed=new_pending?syscall(SYS_renameat2,AT_FDCWD,temp.data(),AT_FDCWD,dest.c_str(),1 /* RENAME_NOREPLACE */):
        rename(temp.data(),dest.c_str());
    if(renamed!=0){unlink(temp.data());return false;}
    fd=open(file(".").c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC);ok=fd>=0&&fsync(fd)==0;if(fd>=0)close(fd);return ok;
}
bool exact(void *obj,const char *ns,const char *name){return obj&&object_get_class(obj)==find_class(ns,name);}
void *query(void *obj,const char *method){return feeding_probe::get(obj,method);}
bool scalar(void *obj,const char *method,const char *type,void *out,size_t size){
    void *box=query(obj,method);ScopedManagedRoot held(box,gchandle_new,gchandle_free);
    if(!feeding_probe::ok||!held.handle||!exact(box,"System",type))return false;
    void *p=feeding_probe::unbox(box);if(!p)return false;std::memcpy(out,p,size);return true;
}
std::string text(void *obj){
    if(!exact(obj,"System","String")){feeding_probe::ok=false;return {};}
    return feeding_probe::read_flower_text(obj);
}
Observation observe(){
    Observation s;using namespace feeding_probe;
    if(!runtime_metadata_ready||!invoke||!unbox||!return_inventory_manager||!gchandle_new||!gchandle_free)return s;
    ok=true;s.account=current_account();s.epoch=capability_registry.current_epoch();s.revision=server_revision();
    if(!ok||s.account.size()!=64||!s.epoch||s.revision<=0)return s;
    void *item=query(return_inventory_manager,"GetPlantingState");ScopedManagedRoot held(item,gchandle_new,gchandle_free);
    if(!ok||!held.handle||!exact(item,"Niantic.Ichigo.Inventory","PlantingStateInventoryItem"))return s;
    item=confirmed_item(item);ScopedManagedRoot confirmed(item,gchandle_new,gchandle_free);
    void *proto=query(item,"get_Proto");ScopedManagedRoot root(proto,gchandle_new,gchandle_free);
    if(!ok||!root.handle||!exact(proto,"Ichigo.Proto","PlantingStateProto")||
       !scalar(proto,"get_IsPlantingStarted","Boolean",&s.active,1)||
       !scalar(proto,"get_PlantingStartedTimeMs","Int64",&s.started,8)||
       !scalar(proto,"get_LastPetalTypeChangeTimeMs","Int64",&s.changed,8))return s;
    s.petal=text(query(proto,"get_FlowerPetalId"));
    void *list=query(return_inventory_manager,"GetFlowerPetalList");ScopedManagedRoot list_root(list,gchandle_new,gchandle_free);
    int count=-1;if(!scalar(list,"get_Count","Int32",&count,4)||count<0||count>512)return s;
    std::set<std::string> ids;
    const auto began=now_ms();
    for(int i=0;i<count;++i){
        void *args[]{&i};void *raw=feeding_probe::get(list,"get_Item",1,args);ScopedManagedRoot r(raw,gchandle_new,gchandle_free);
        if(!ok||!r.handle||!exact(raw,"Niantic.Ichigo.Inventory","FlowerPetalInventoryItem"))return s;
        void *value=confirmed_item(raw);ScopedManagedRoot v(value,gchandle_new,gchandle_free);
        Row row;row.id=text(query(value,"get_Id"));
        void *p=query(value,"get_Proto");ScopedManagedRoot ph(p,gchandle_new,gchandle_free);
        int flower=-1,kind=-1;
        if(!ok||!ph.handle||!exact(p,"Ichigo.Proto","FlowerPetalProto")||!id(row.id)||!ids.insert(row.id).second||
           !scalar(p,"get_NumPetal","Int32",&row.stock,4)||row.stock<0||row.stock>1000000)return s;
        // Existing pinned enum getters/conversion and exact game profile.
        void *pk=find_class("Ichigo.Proto","FlowerPetalProto");
        flower=integer(call(verified_v152_method(pk,"get_FlowerType",0,0x64E0174),p));
        kind=integer(call(verified_v152_method(pk,"get_Kind",0,0x64E0184),p));
        auto kind_text=text(query(p,"get_FlowerKind"));
        // Feeding's cached kind_to_text_method exists only after its opt-in capture.
        // Resolve our own pinned lookup so OFF feeding never supplies a null MethodInfo.
        if(kind==0)row.family=feeding_catalogue::family(0,kind_text);
        else if(kind==5)row.family=feeding_catalogue::family(5,"");
        else if(kind>0){void *kargs[]{&kind};
            auto mapped=text(static_call(verified_v152_method(find_class("Niantic.Ichigo.ModelControllers.Flowers","FlowerKindLookup"),"KindToObsoleteString",1,0x711A138),kargs));
            if(!mapped.empty())row.family=feeding_catalogue::family(0,mapped);
        }
        void *cargs[]{&flower};row.color=integer(static_call(verified_v152_method(find_class("Niantic.Ichigo.Utils.Proto","HoneyBallProtoExtensions"),"ToHoneyType",1,0x6D3E180),cargs));
        if(!ok||row.family.empty()||row.color<1||row.color>4||now_ms()-began>200)return s;
        s.rows.push_back(row);
    }
    s.known=ok&&s.revision==server_revision()&&s.account==current_account()&&s.epoch==capability_registry.current_epoch();return s;
}
void status(const Config &c,const Observation &s,const char *state){
    std::string raw="v1\t"+std::to_string(now_ms())+"\t"+std::to_string(getpid())+"\t"+c.revision+"\t"+state+"\t"+
        (s.known?"1":"0")+"\t"+(id(s.petal)?s.petal:"-")+"\t"+std::to_string(s.started)+"\n";
    if(s.known)for(const auto &r:s.rows)raw+="petal\t"+r.id+"\t"+r.family+"\t"+std::to_string(r.color)+"\t"+std::to_string(r.stock)+"\n";
    save("auto_petal_status.tsv",raw+"end\n");
}
// Read-only cache: independent of configuration revision and action enablement.
// The existing game-thread observation is reused during active automation.
long long last_inventory_tick{};
void publish_inventory(const Observation &s){
    if(!core_runtime_path[0])return;
    last_inventory_tick=now_ms();
    std::string raw="v2\t"+std::to_string(last_inventory_tick)+"\t"+std::to_string(getpid())+"\t"+
        (s.known?s.account:"-")+"\t"+std::to_string(s.known?s.epoch:0)+"\t"+
        std::to_string(s.known?s.revision:0)+"\t"+(s.known?"1":"0")+"\n";
    if(s.known)for(const auto &r:s.rows)raw+="petal\t"+r.id+"\t"+r.family+"\t"+std::to_string(r.color)+"\t"+std::to_string(r.stock)+"\n";
    raw+="end\n";
    save("auto_petal_inventory.tsv",raw);
}
void inventory_tick(){
    if(!core_runtime_path[0])return;
    if(!auto_petal_inventory::due(now_ms(),last_inventory_tick))return;
    publish_inventory(observe()); // Getters only; never enters the transaction engine.
}
bool stop_terminal(){
    if(!stop_task||!stop_root)return false;
    bool completed{};feeding_probe::ok=true;
    return scalar(stop_task,"get_IsCompleted","Boolean",&completed,1)&&completed;
}
bool stop_succeeded(){
    bool faulted{},canceled{};
    return stop_terminal()&&scalar(stop_task,"get_IsFaulted","Boolean",&faulted,1)&&!faulted&&
        scalar(stop_task,"get_IsCanceled","Boolean",&canceled,1)&&!canceled;
}
bool controller_state(bool active){
    bool starting{},result{},local_active{};feeding_probe::ok=true;
    if(!planting_controller)return false;
    void *klass=find_class("Niantic.Ichigo.Game.Flowers","FlowerPlantingController");
    void *observable=feeding_probe::call(verified_v152_method(klass,"get_IsPlanting",0,0x6088B98),planting_controller);
    ScopedManagedRoot held(observable,gchandle_new,gchandle_free);
    return held.handle&&scalar(observable,"get_MostRecent","Boolean",&local_active,1)&&local_active==active&&
        scalar(planting_controller,"get_IsStartingPlanting","Boolean",&starting,1)&&!starting&&
        scalar(planting_controller,"get_IsDisplayingPlantingResult","Boolean",&result,1)&&!result;
}
bool controller_clear(){return controller_state(false)&&!planting_control_pending_task&&read_planting_control_mode()!="on"&&!flower_farm_session_active();}
bool archive_pending(const std::string &name){
    const auto source=file("auto_petal_pending.tsv"),dest=file(name.c_str());
    struct stat st{};std::string verify;
    if(lstat(dest.c_str(),&st)==0){
        // Resume a visible rename after fsync failure, never replay a transaction.
        if(lstat(source.c_str(),&st)==0||errno!=ENOENT||!read(dest,verify,2048,getuid())||verify!=pending_raw)return false;
    }else if(errno!=ENOENT||!read(source,verify,2048,getuid())||verify!=pending_raw||
             syscall(SYS_renameat2,AT_FDCWD,source.c_str(),AT_FDCWD,dest.c_str(),1 /* RENAME_NOREPLACE */)!=0)return false;
    int dir=open(file(".").c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC);bool ok=dir>=0&&fsync(dir)==0;if(dir>=0)close(dir);return ok;
}
void tick(){
    if(now_ms()-last_tick<2000)return;last_tick=now_ms();
    std::string raw;Config c;if(!read(config_path,raw,8192,0)||!parse(raw,c))return;
    // OFF only scans during a bounded read-only panel query window.
    std::string request;long long requested{};
    if(read("/data/local/tmp/pikmin-auto-petals-query.tsv",request,64,0)){std::istringstream q(request);q>>requested;}
    const bool query_fresh=requested>0&&requested<=now_ms()&&now_ms()-requested<30000;
    if(!c.enabled&&!query_fresh&&!unresolved&&initialized)return;
    auto s=observe();
    publish_inventory(s);
    if(!initialized){initialized=true;struct stat st{};
        if(lstat(file("auto_petal_pending.tsv").c_str(),&st)==0){unresolved=true;read(file("auto_petal_pending.tsv"),pending_raw,2048,getuid());}
        else if(errno!=ENOENT){unresolved=true;}}
    // Never erase/replay an uncertain active change after process death or OFF.
    if(unresolved){
        if(pending_raw.empty())read(file("auto_petal_pending.tsv"),pending_raw,2048,getuid());
        std::istringstream in(pending_raw);std::string v,rev,acct,old,next,end;uint64_t ep=0;long long began=0,changed=0;
        const bool parsed=static_cast<bool>(in>>v>>rev>>acct>>ep>>began>>old>>next>>changed>>end)&&
            v=="v1"&&end=="end"&&uuid(rev)&&acct.size()==64&&acct.find_first_not_of("0123456789abcdef")==std::string::npos&&
            ep>0&&began>0&&changed>=0&&id(old)&&id(next)&&
            pending_raw=="v1\t"+rev+"\t"+acct+"\t"+std::to_string(ep)+"\t"+std::to_string(began)+"\t"+old+"\t"+next+"\t"+std::to_string(changed)+"\nend\n";
        const bool confirmed=parsed&&receipt(s.known,s.account==acct,s.epoch==ep,began,s.started,next,s.petal,s.active,changed,s.changed,stop_succeeded());
        if(confirmed){
            if(!archive_pending("auto-petal-confirmed-"+rev+"-"+std::to_string(began)+"-"+std::to_string(changed)+".tsv")){status(c,s,"storage-error");return;}
            forget_stop();recovery_window={};
            unresolved=false;expected=next;round=s.started;account=s.account;epoch=s.epoch;revision=rev;
            status(c,s,next=="STOP"?"stopped-at-reserve":"changed-confirmed");return;
        }
        // Recover only after the original account has left planting and any
        // retained stop task has settled. Preserve UNKNOWN without resending.
        const bool quiet=parsed&&s.known&&!s.active&&s.started==0&&s.account==acct&&
            controller_clear()&&(!stop_task||stop_terminal());
        if(recovery_window.ready(quiet,s.epoch,steady_ms())){
            if(!archive_pending("auto-petal-UNKNOWN-"+rev+"-"+std::to_string(began)+"-"+std::to_string(changed)+".tsv")){status(c,s,"storage-error");return;}
            unresolved=false;forget_stop();recovery_window={};
            round=0;expected.clear();paused=false;ready_since=0;ready_key.clear();
            status(c,s,"unknown-archived");return;
        }
        status(c,s,!parsed?"recovery-record-unavailable":quiet?"recovering-unknown":"waiting-confirmation");return;
    }
    if(!c.enabled){status(c,s,"off");return;}
    if(c.order.empty()){status(c,s,"waiting-settings");return;}
    if(!s.known){status(c,s,"waiting-inventory");return;}
    if(!s.active){ready_since=0;ready_key.clear();round=0;expected.clear();paused=false;status(c,s,"waiting-planting");return;}
    // Existing automated route/planting manager owns its sessions. Do not race it.
    if(read_planting_control_mode()=="on"||flower_farm_session_active()||planting_control_pending_task){status(c,s,"waiting-other-work");return;}
    if(round!=s.started||account!=s.account||epoch!=s.epoch||revision!=c.revision){
        round=s.started;account=s.account;epoch=s.epoch;expected=s.petal;revision=c.revision;paused=false;
    }
    if(s.started<=0){status(c,s,"waiting-inventory");return;}
    // Confirmed server-active can precede the controller's active ChangeSender.
    // SetFlowerPetal only sends RPC while that local observable is true.
    const auto key=s.account+":"+std::to_string(s.epoch)+":"+std::to_string(s.started);
    if(!controller_state(true)){ready_since=0;ready_key.clear();status(c,s,"waiting-controller");return;}
    if(ready_key!=key){ready_key=key;ready_since=now_ms();}
    if(!ready_to_change(true,true,false,false,ready_since,now_ms())){status(c,s,"waiting-controller");return;}
    if(s.petal!=expected)paused=true;
    if(paused){status(c,s,"manual-change-paused");return;}
    if(!get_planting_flower_petal_id||!planting_controller||
       utf8_string(get_planting_flower_petal_id(planting_controller,nullptr))!=s.petal){
        ready_since=0;ready_key.clear();status(c,s,"waiting-controller-sync");return;
    }
    std::map<std::string,int> stocks;for(const auto &r:s.rows)stocks.emplace(r.id,r.stock);
    auto choice=choose(c,s.petal,stocks,true);
    if(choice.action!=Action::change&&choice.action!=Action::exhausted){status(c,s,choice.action==Action::keep?"planting":choice.action==Action::outside?"outside-list":"waiting-inventory");return;}
    const bool stopping=choice.action==Action::exhausted;if(stopping)choice.next="STOP";
    // Active setter is VOID/fire-and-forget; its local getter is NOT a receipt.
    void *klass=find_class("Niantic.Ichigo.Game.Flowers","FlowerPlantingController");
    void *method=klass?class_get_method_from_name(klass,"SetFlowerPetal",1):nullptr;
    void *entry=method?*static_cast<void **>(method):nullptr;Dl_info image{};
    static const unsigned char pin[]={0xfe,0x0f,0x1d,0xf8,0xf6,0x57,0x01,0xa9,0xf4,0x4f,0x02,0xa9,0x75,0xa5,0x04,0xb0};
    if(!planting_controller||object_get_class(planting_controller)!=klass||!entry||!dladdr(entry,&image)||!image.dli_fbase||
       reinterpret_cast<uintptr_t>(entry)!=reinterpret_cast<uintptr_t>(image.dli_fbase)+0x608a42c||std::memcmp(entry,pin,sizeof(pin))){status(c,s,"method-unavailable");return;}
    if(stopping&&!stop_planting_with_confirmation){status(c,s,"method-unavailable");return;}
    // Pending is durable before invocation. Re-read policy and confirmed data.
    auto check=observe();std::string current_config;
    std::map<std::string,int> checked_stocks;for(const auto &r:check.rows)checked_stocks.emplace(r.id,r.stock);
    auto checked_choice=choose(c,check.petal,checked_stocks,check.known);
    if(!controller_state(true)||checked_choice.action!=choice.action||(!stopping&&checked_choice.next!=choice.next)||!check.known||!check.active||check.started!=s.started||check.petal!=s.petal||check.account!=s.account||check.epoch!=s.epoch||
       !read(config_path,current_config,8192,0)||current_config!=raw)return;
    pending_raw="v1\t"+c.revision+"\t"+s.account+"\t"+std::to_string(s.epoch)+"\t"+std::to_string(s.started)+"\t"+s.petal+"\t"+choice.next+"\t"+std::to_string(s.changed)+"\nend\n";
    unresolved=true; // A visible rename followed by failed directory fsync is uncertain too.
    if(!save("auto_petal_pending.tsv",pending_raw,true)){
        // This branch provably has not invoked the game. Retry later if no
        // journal was published, otherwise re-read the actual durable record.
        struct stat entry{};
        unresolved=lstat(file("auto_petal_pending.tsv").c_str(),&entry)==0||errno!=ENOENT;
        pending_raw.clear();status(c,s,"storage-error");return;
    }
    if(stopping){stop_task=stop_planting_with_confirmation(planting_controller,false,nullptr);if(stop_task)stop_root=gchandle_new(stop_task,false);status(c,s,"waiting-stop-confirmation");return;}
    void *str=string_new?string_new(choice.next.c_str()):nullptr;ScopedManagedRoot sr(str,gchandle_new,gchandle_free);
    if(!sr.handle){status(c,s,"waiting-confirmation");return;}
    void *args[]{str},*exception{};feeding_probe::invoke(method,planting_controller,args,&exception);
    status(c,s,"waiting-confirmation");
}
}
