// Real read-only metadata sample, on the observed Map lane. No Root/file/JNI.
class GameConsumerSource final:public ConsumerSource {
public:
    bool read(ConsumerSample &out)override {
        if(!on_lane()||revoked||!runtime_metadata_ready||!emu_late::bound||emu_late::revoked||logout_seen.load(std::memory_order_acquire))return false;
        const auto epoch=capability_registry.current_epoch();if(!epoch)return false;
        ConsumerSample sample;
        sample.clock.before=nearby_elapsed_ms();sample.clock.wall=now_ms();
        const auto fix=read_nearby_game_fix();sample.clock.after=nearby_elapsed_ms();
        if(!sample.clock.valid()||!fix.valid||epoch!=capability_registry.current_epoch()||!on_lane())return false;
        sample.valid=true;sample.raw_ms=fix.raw_wall_ms;
        sample.raw_lat=fix.raw_lat;sample.raw_lng=fix.raw_lng;sample.game_lat=fix.game_lat;sample.game_lng=fix.game_lng;
        out=sample;return true;
    }
};
