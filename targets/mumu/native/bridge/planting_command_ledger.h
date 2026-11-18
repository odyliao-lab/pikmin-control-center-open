#pragma once
#include "planting_command_policy.h"
#include <set>

// Unwired native operation ledger. Store must durably save/read back before send.
// Caller holds the GLOBAL work lock, not merely a per-file lock. Loaded state
// from another process/boot needs explicit reconciliation, never auto-adoption.
namespace emu_planting_command {
inline const char *action_name(Action a){return a==Action::select?"select":a==Action::start?"start":a==Action::stop?"stop":"invalid";}
inline std::string command_wire(const Command &c){
    return "v2\t"+std::to_string(c.pid)+"\t"+std::to_string(c.epoch)+"\t"+c.account+"\t"+c.owner+"\t"+c.token+"\t"+c.operation+"\t"+action_name(c.action)+"\t"+c.petal+"\t"+std::to_string(c.started)+"\t"+std::to_string(c.expires)+"\t"+c.boot+"\tend\n";
}
enum class ReceiptPhase { accepted, selected, started, stopped, review };
inline const char *phase_name(ReceiptPhase p){return p==ReceiptPhase::accepted?"accepted":p==ReceiptPhase::selected?"selected":p==ReceiptPhase::started?"started":p==ReceiptPhase::stopped?"stopped":p==ReceiptPhase::review?"review":"invalid";}
inline std::string joined(const std::set<std::string> &items){std::string s;for(const auto &i:items){if(!s.empty())s+=',';s+=i;}return s;}
struct Ledger {
    Command command;
    ReceiptPhase phase{ReceiptPhase::accepted};
    int64_t written{},observed{},native_started{};
    std::string reason{"pending"};
    std::set<std::string> operations,tokens;
};
inline bool valid(const Ledger &s){
    Command c;if(!parse(command_wire(s.command),c)||s.written<1000000000000LL||s.written>=c.expires||c.expires-s.written>15000||
       s.observed<0||s.observed>9999999999999LL||(s.observed>0&&s.observed<s.written)||s.native_started<0||
       s.operations.empty()||s.operations.size()>12||s.tokens.empty()||s.tokens.size()>4||
       !s.operations.count(c.operation)||!s.tokens.count(c.token)||!petal_id(s.reason))return false;
    for(const auto &v:s.operations)if(!uuid(v))return false;
    for(const auto &v:s.tokens)if(!uuid(v))return false;
    if(s.phase==ReceiptPhase::accepted)return s.observed==0&&s.native_started==0&&s.reason=="pending";
    if(s.phase==ReceiptPhase::review)return s.observed>=s.written&&s.reason!="pending"&&s.reason!="confirmed";
    if(s.observed<=s.written||s.reason!="confirmed")return false;
    return (s.phase==ReceiptPhase::selected&&c.action==Action::select&&s.native_started==0)||
           (s.phase==ReceiptPhase::started&&c.action==Action::start&&s.native_started>0)||
           (s.phase==ReceiptPhase::stopped&&c.action==Action::stop&&s.native_started==c.started);
}
inline std::string encode(const Ledger &s){
    if(!valid(s))return {};
    auto wire=command_wire(s.command);wire.replace(0,2,"r2");wire.resize(wire.size()-4);
    wire+=std::string(phase_name(s.phase))+"\t"+std::to_string(s.written)+"\t"+std::to_string(s.observed)+"\t"+std::to_string(s.native_started)+"\t"+s.reason+"\t"+joined(s.operations)+"\t"+joined(s.tokens)+"\tend\n";
    return wire.size()<=2048?wire:std::string{};
}
inline bool split_set(const std::string &s,std::set<std::string> &out){
    size_t from=0;for(size_t i=0;i<=s.size();i++)if(i==s.size()||s[i]==','){auto v=s.substr(from,i-from);if(!uuid(v)||!out.insert(v).second)return false;from=i+1;}return true;
}
inline bool decode(const std::string &wire,Ledger &out){
    if(wire.empty()||wire.size()>2048||wire.back()!='\n'||wire.find('\r')!=std::string::npos||wire.find('\0')!=std::string::npos||wire.find('\n')!=wire.size()-1)return false;
    std::vector<std::string> f;size_t from=0;for(size_t i=0;i<wire.size();i++)if(wire[i]=='\t'){f.push_back(wire.substr(from,i-from));from=i+1;}f.push_back(wire.substr(from));
    if(f.size()!=20||f[0]!="r2"||f[19]!="end\n")return false;
    std::string cw="v2";for(size_t i=1;i<=11;i++)cw+='\t'+f[i];cw+="\tend\n";
    Ledger s;if(!parse(cw,s.command))return false;
    if(f[12]=="accepted")s.phase=ReceiptPhase::accepted;else if(f[12]=="selected")s.phase=ReceiptPhase::selected;else if(f[12]=="started")s.phase=ReceiptPhase::started;else if(f[12]=="stopped")s.phase=ReceiptPhase::stopped;else if(f[12]=="review")s.phase=ReceiptPhase::review;else return false;
    uint64_t written{},observed{},started{};if(!number(f[13],9999999999999ULL,written)||!number(f[14],9999999999999ULL,observed)||!number(f[15],std::numeric_limits<int64_t>::max(),started))return false;
    s.written=static_cast<int64_t>(written);s.observed=static_cast<int64_t>(observed);s.native_started=static_cast<int64_t>(started);s.reason=f[16];
    if(!split_set(f[17],s.operations)||!split_set(f[18],s.tokens)||encode(s)!=wire)return false;
    out=s;return true;
}
struct Store {
    virtual ~Store()=default;
    // false means error, NEVER absence. present=false only after proven absence.
    virtual bool read(bool &present,Ledger &out)=0;
    virtual bool save(const Ledger &entry)=0;
};
struct Port {
    virtual ~Port()=default;
    // These facts must be independently re-read, not copied from request.
    virtual bool context(const Command &,Context &out)=0;
    virtual void send(const Command &)=0;
};
enum class Consume { blocked, observe_only, sent_unconfirmed, review, storage_failed };
inline bool commit(Store &store,const Ledger &s){
    const auto wire=encode(s);if(wire.empty()||!store.save(s))return false;
    bool present=false;Ledger read;return store.read(present,read)&&present&&encode(read)==wire;
}
inline bool same_work(const Command &a,const Command &b){return a.boot==b.boot&&a.pid==b.pid&&a.epoch==b.epoch&&a.account==b.account&&a.owner==b.owner;}
inline bool next(const Ledger &old,const Command &c){
    Command checked;if(!parse(command_wire(c),checked))return false;
    if(!valid(old)||!same_work(old.command,c)||old.operations.count(c.operation)||old.operations.size()>=12)return false;
    if(old.phase==ReceiptPhase::selected)return c.action==Action::start&&c.token==old.command.token&&c.petal==old.command.petal;
    if(old.phase==ReceiptPhase::started)return c.action==Action::stop&&c.token==old.command.token&&c.petal==old.command.petal&&c.started==old.native_started;
    if(old.phase==ReceiptPhase::stopped)return c.action!=Action::stop&&!old.tokens.count(c.token)&&old.tokens.size()<4;
    return false;
}
inline Consume consume(const std::string &request,Store &store,Port &port){
    Command c;if(!parse(request,c))return Consume::blocked;
    bool present=false;Ledger old;if(!store.read(present,old))return Consume::storage_failed;
    if(present){
        if(!valid(old))return Consume::storage_failed;
        if(command_wire(old.command)==request)return old.phase==ReceiptPhase::review?Consume::review:Consume::observe_only;
        if(!next(old,c))return Consume::blocked;
    }else if(c.action==Action::stop)return Consume::blocked;
    Context facts;if(!port.context(c,facts)||!matches(c,facts))return Consume::blocked;
    if(present&&facts.now<=old.observed)return Consume::blocked;
    Ledger s;if(present){s.operations=old.operations;s.tokens=old.tokens;}
    s.command=c;s.written=facts.now;s.operations.insert(c.operation);s.tokens.insert(c.token);
    if(!commit(store,s))return Consume::storage_failed;
    // No effect after a lost save/readback or a last-moment context change.
    Context fresh;if(!port.context(c,fresh)||!matches(c,fresh)||fresh.now<s.written){
        s.phase=ReceiptPhase::review;s.observed=s.written;s.reason="pre-send-context-changed";
        return commit(store,s)?Consume::review:Consume::storage_failed;
    }
#if defined(__cpp_exceptions)
    try{port.send(c);}catch(...){/* Accepted journal remains uncertain, never re-send. */}
#else
    // Real game payload uses -fno-exceptions. Intent is already durable; a
    // process interruption still leaves accepted work for observe-only review.
    port.send(c);
#endif
    return Consume::sent_unconfirmed;
}
// Called ONLY after the native adapter independently proves Task/selection/result
// and current scoped state. Scalar receipt values alone are not native proof.
enum class Confirmation { rejected, confirmed, review, storage_failed };
inline Confirmation confirm(Store &store,Port &port,const Command &echo,ReceiptPhase phase,int64_t now,int64_t native_started){
    bool present=false;Ledger s;if(!store.read(present,s))return Confirmation::storage_failed;
    if(!present||!valid(s)||command_wire(s.command)!=command_wire(echo))return Confirmation::rejected;
    if(s.phase==ReceiptPhase::review)return Confirmation::review;
    if(s.phase!=ReceiptPhase::accepted||(now!=0&&(now<1000000000000LL||now>9999999999999LL)))return Confirmation::rejected;
    // Re-read current identity even when an echoed receipt is structurally valid.
    // Send expiry is deliberately NOT used for observing an already-sent intent.
    Context fresh;
    if(!port.context(echo,fresh)||(now!=0&&fresh.now!=now)||!fresh.trusted_session||!fresh.known||
       fresh.boot!=echo.boot||fresh.pid!=echo.pid||fresh.epoch!=echo.epoch||fresh.account!=echo.account||
       fresh.owner!=echo.owner||fresh.token!=echo.token||fresh.operation!=echo.operation)return Confirmation::rejected;
    // Live adapters use the independently sampled clock, not a timestamp read
    // immediately before managed reads (which can take several milliseconds).
    if(now==0)now=fresh.now;
    if(now<1000000000000LL||now>9999999999999LL)return Confirmation::rejected;
    if(now<s.written){s.phase=ReceiptPhase::review;s.observed=s.written;s.reason="clock-moved-backwards";return commit(store,s)?Confirmation::review:Confirmation::storage_failed;}
    if(now==s.written)return Confirmation::rejected;
    const auto budget=s.command.action==Action::stop?120000:60000;
    if(now-s.written>=budget){s.phase=ReceiptPhase::review;s.observed=now;s.reason="confirmation-deadline-expired";return commit(store,s)?Confirmation::review:Confirmation::storage_failed;}
    if(phase!=ReceiptPhase::selected&&phase!=ReceiptPhase::started&&phase!=ReceiptPhase::stopped)return Confirmation::rejected;
    if(fresh.selected!=echo.petal)return Confirmation::rejected;
    if(phase==ReceiptPhase::selected&&(fresh.active||fresh.owned))return Confirmation::rejected;
    if(phase==ReceiptPhase::started&&(!fresh.active||!fresh.owned||native_started<=0||fresh.owned_started!=native_started))return Confirmation::rejected;
    if(phase==ReceiptPhase::stopped&&(fresh.active||fresh.owned||!fresh.result_closed||fresh.closed_started!=echo.started||native_started!=echo.started))return Confirmation::rejected;
    s.phase=phase;s.observed=now;s.native_started=native_started;s.reason="confirmed";
    if(!valid(s))return Confirmation::rejected;
    return commit(store,s)?Confirmation::confirmed:Confirmation::storage_failed;
}
inline Confirmation confirm(Store &store,Port &port,const Command &echo,ReceiptPhase phase,int64_t native_started){
    return confirm(store,port,echo,phase,0,native_started);
}
}
