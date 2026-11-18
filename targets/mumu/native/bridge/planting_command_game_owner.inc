// Included inside emu_planting_runtime, after on_lane(). No file/Root work here.
class GameOwnershipSource final:public OwnershipSource {
    WriterExclusionSource &writers_;
    ConsumerSource &consumer_;ProviderGenerationGate generation_;
    using FenceReader=bool(*)(emu_gps_work::CachedFenceObservation &);
    FenceReader fence_;
    static int64_t wall(){return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();}
    static bool valid(const WriterLease &lease,int64_t at,const emu_gps_work::CachedFenceObservation &fence){
        return uuid(lease.boot)&&uuid(lease.owner)&&uuid(lease.token)&&uuid(lease.operation)&&
            lease.sampled>=1000000000000LL&&lease.sampled<=at&&at-lease.sampled<=1000&&
            lease.until>at&&lease.until-lease.sampled<=5000&&lease.critical_section&&
            lease.app_excluded&&lease.legacy_excluded&&lease.root_excluded&&lease.provider_restored&&
            uuid(lease.fence.nonce)&&lease.fence.pid>0&&lease.fence.pid!=getpid()&&lease.fence.ticks&&lease.fence.inode&&
            fence.boot==lease.boot&&fence.nonce==lease.fence.nonce&&fence.pid==lease.fence.pid&&
            fence.ticks==lease.fence.ticks&&fence.device==lease.fence.device&&fence.inode==lease.fence.inode;
    }
    static bool account(std::string &out){
        std::unique_lock<std::mutex> lock(identity_mutex,std::try_to_lock);
        if(!lock.owns_lock()||!digest(identity_hash))return false;out=identity_hash;return true;
    }
public:
    explicit GameOwnershipSource(WriterExclusionSource &writers,ConsumerSource &consumer,
        FenceReader fence=emu_gps_work::read_payload_fence_snapshot):writers_(writers),consumer_(consumer),fence_(fence){}
    bool read(OwnershipSnapshot &out)override {
        const auto deny=[this]{generation_.suspend();return false;};
        if(!on_lane()||revoked||!runtime_metadata_ready||!emu_late::bound||emu_late::revoked||
           logout_seen.load(std::memory_order_acquire))return deny();
        const auto steady=std::chrono::steady_clock::now();const auto begin=wall();
        const auto epoch=capability_registry.current_epoch();std::string before,after;
        WriterLease first,last;ConsumerSample sample;
        emu_gps_work::CachedFenceObservation root_before,root_after;
        if(!epoch||!fence_||!account(before)||!fence_(root_before)||!writers_.read(first))return deny();
        const auto first_read=wall();
        // Bracket the actual consumer read, not just its inputs. A writer
        // revoked while sampling must be visible in the trailing lease.
        if(first_read<begin||!valid(first,first_read,root_before)||!consumer_.read(sample)||!writers_.read(last)||
           !fence_(root_after)||!account(after))return deny();
        const auto end=wall();
        if(end<begin||end-begin>1000||std::chrono::steady_clock::now()-steady>std::chrono::seconds(1)||
           !valid(last,end,root_after)||end>=first.until||last.sampled<first.sampled||
           root_after.sampled<root_before.sampled||root_after.sampled>=root_before.until||
           root_after.pid!=root_before.pid||root_after.ticks!=root_before.ticks||root_after.nonce!=root_before.nonce||
           root_after.device!=root_before.device||root_after.inode!=root_before.inode||
           last.boot!=first.boot||last.owner!=first.owner||last.token!=first.token||last.operation!=first.operation||
           last.provider.generation!=first.provider.generation||last.provider.pid!=first.provider.pid||last.provider.ticks!=first.provider.ticks||
           before!=after||capability_registry.current_epoch()!=epoch||!on_lane()||revoked||!runtime_metadata_ready||
           !emu_late::bound||emu_late::revoked||logout_seen.load(std::memory_order_acquire))return deny();
        if(!generation_.observe(first.provider,sample)||!generation_.observe(last.provider,sample))return false;
        OwnershipSnapshot value;value.pid=getpid();value.epoch=epoch;value.account=after;
        value.boot=last.boot;value.owner=last.owner;value.token=last.token;value.operation=last.operation;
        value.sampled=end;value.writers_excluded=true;out=std::move(value);return true;
    }
};
