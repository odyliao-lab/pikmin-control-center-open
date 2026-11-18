#pragma once
#include <climits>
#include <cstdint>
#include <string>
#include <utility>
#include <vector>
#include "session_identity.h"

namespace nectar_intent {
struct Record {
    std::string phase, account, target, binding;
    int64_t wall{}, mono{}, pid{}, epoch{};
};
inline bool hex64(const std::string& s) {
    if (s.size()!=64) return false;
    for(char c:s) if(!((c>='0'&&c<='9')||(c>='a'&&c<='f'))) return false;
    return true;
}
inline bool number(const std::string& s,int64_t& out) {
    if(s.empty()||s.size()>19)return false;
    int64_t n=0;
    for(char c:s){if(c<'0'||c>'9'||n>(INT64_MAX-(c-'0'))/10)return false;n=n*10+(c-'0');}
    if(n<=0||std::to_string(n)!=s)return false;out=n;return true;
}
inline bool valid(const Record& r) {
    return (r.phase=="pending"||r.phase=="success")&&hex64(r.account)&&hex64(r.target)&&
        hex64(r.binding)&&r.wall>0&&r.mono>0&&r.pid>0&&r.pid<=INT_MAX&&r.epoch>0;
}
inline std::string encode(const Record& r) {
    if(!valid(r))return {};
    const auto body=std::string("v1\t")+r.phase+"\t"+r.account+"\t"+r.target+"\t"+r.binding+
        "\t"+std::to_string(r.wall)+"\t"+std::to_string(r.mono)+"\t"+std::to_string(r.pid)+"\t"+std::to_string(r.epoch);
    const auto digest=pikmin::account_digest(body);
    return digest.empty()?std::string{}:body+"\t"+digest+"\n";
}
inline bool decode(const std::string& wire,Record& out) {
    if(wire.empty()||wire.size()>1024||wire.back()!='\n'||wire.find('\0')!=std::string::npos)return false;
    const auto line=wire.substr(0,wire.size()-1);std::vector<std::string> f;size_t pos=0;
    for(;;){auto end=line.find('\t',pos);f.push_back(line.substr(pos,end-pos));if(end==std::string::npos)break;pos=end+1;}
    if(f.size()!=10||f[0]!="v1"||!hex64(f[9]))return false;
    Record r;r.phase=f[1];r.account=f[2];r.target=f[3];r.binding=f[4];
    if(!number(f[5],r.wall)||!number(f[6],r.mono)||!number(f[7],r.pid)||!number(f[8],r.epoch)||!valid(r))return false;
    if(encode(r)!=wire)return false;out=std::move(r);return true;
}
inline bool equal(const Record&a,const Record&b){return encode(a)==encode(b);}
inline bool transition(const Record* old,const Record& next) {
    if(!valid(next))return false;
    if(!old)return next.phase=="pending";
    if(!valid(*old))return false;
    if(old->phase=="success")return next.phase=="pending"&&old->binding!=next.binding;
    Record terminal=*old;terminal.phase="success";
    return next.phase=="success"&&equal(terminal,next);
}
enum class Begin { admitted, duplicate, blocked, unavailable };

// Store must implement read(Record&, bool& exists), and a checked atomic
// replace(expected-or-null, next). Both return false on every uncertain error.
template<class Store> class Guard {
    Store store_;Record record_;bool loaded_{},exists_{},failed_{},owned_{};
public:
    explicit Guard(Store s):store_(std::move(s)){}
    void initialize(){if(loaded_)return;loaded_=true;if(!store_.read(record_,exists_))failed_=true;}
    bool blocked(){initialize();return failed_||(exists_&&record_.phase=="pending");}
    const Record& record()const{return record_;}
    bool retire_unconfirmed(int64_t mono,bool task_pending){
        initialize();if(failed_||!exists_||record_.phase!="pending")return false;
        // Live calls retain exclusive ownership for 60 seconds. Historical
        // requests are never resumed; quarantine permanently excludes their target.
        if(owned_&&task_pending&&(mono<record_.mono||mono-record_.mono<60000))return false;
        if(!store_.quarantine(record_)){failed_=true;return false;}
        exists_=false;owned_=false;record_=Record{};return true;
    }
    Begin begin(const std::string& account,const std::string& target,int64_t epoch,int64_t pid,int64_t wall,int64_t mono){
        initialize();if(blocked())return Begin::blocked;
        bool excluded=false;
        if(!store_.excluded(account,target,excluded)){failed_=true;return Begin::unavailable;}
        if(excluded)return Begin::duplicate;
        Record previous;bool previous_exists=false;
        if(!store_.read(previous,previous_exists)){failed_=true;return Begin::unavailable;}
        if(previous_exists&&previous.phase=="pending"){record_=previous;exists_=true;owned_=false;return Begin::blocked;}
        if(previous_exists&&previous.account==account&&previous.target==target)return Begin::duplicate;
        Record next;next.phase="pending";next.account=account;next.target=target;
        next.epoch=epoch;next.pid=pid;next.wall=wall;next.mono=mono;
        next.binding=pikmin::account_digest(account+"\t"+target+"\t"+std::to_string(epoch)+"\t"+
                std::to_string(pid)+"\t"+std::to_string(wall)+"\t"+std::to_string(mono));
        if(!valid(next)){failed_=true;return Begin::unavailable;}
        record_=next;exists_=true;owned_=false;
        if(!store_.replace(previous_exists?&previous:nullptr,next)){failed_=true;return Begin::unavailable;}
        owned_=true;return Begin::admitted;
    }
    // Only call for the actual successful response of the original rooted Task.
    // Fault, timeout, rejection and null Task must never enter this method.
    bool resolve_success(const std::string& binding){
        initialize();
        if(failed_||!owned_||!exists_||record_.phase!="pending"||record_.binding!=binding)return false;
        Record terminal=record_;terminal.phase="success";
        if(!store_.replace(&record_,terminal)){failed_=true;return false;}
        record_=std::move(terminal);owned_=false;return true;
    }
    std::string status(int64_t wall,int64_t mono,bool task_pending){
        initialize();if(!blocked())return {};
        std::string age="unknown",origin="storage";
        if(exists_&&valid(record_)){
            origin=failed_?"storage":(owned_?"live":"restart");
            const int64_t now=owned_?mono:wall,start=owned_?record_.mono:record_.wall;
            if(now>=start&&start>0)age=std::to_string(now-start);
        }
        const bool waiting=!failed_&&owned_&&task_pending&&age!="unknown"&&mono>=record_.mono&&mono-record_.mono<60000;
        return std::string(waiting?"PENDING:":"PENDING_UNCONFIRMED:")+age+":"+
            (exists_?std::to_string(record_.wall):"unknown")+":"+
            (exists_?record_.binding.substr(0,12):"unknown")+":"+origin;
    }
};
}
