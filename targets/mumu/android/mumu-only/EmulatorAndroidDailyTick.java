package dev.ody.pikmincontrol;

import android.content.Context;

/** One Android tick: acquire the durable cross-process lock BEFORE reading
 * facts, and retain it through persistence, effects and observation. Scheduling
 * and independently verified gameplay ports must be supplied by the caller. */
final class EmulatorAndroidDailyTick {
    /** Detached durable state under the same lock; null only before first intent.
     * Use its exact binding/pending key to check service ownership. Never infer
     * ownership from a generic running flag or mutate/adopt the supplied state. */
    interface FactsSource { EmulatorDailyRunner.Facts read(EmulatorDailyRunner.State retained) throws Exception; }
    interface Clock { long now(); }
    interface LockedStore extends EmulatorDailyRunner.Store,AutoCloseable {}
    interface StoreSource { LockedStore open() throws Exception; }

    static EmulatorDailyRunner.Result run(Context context,FactsSource facts,
            EmulatorDailyRunner.Port port)throws Exception {
        EmulatorRuntime.require();
        return run(()->new AndroidStore(EmulatorAndroidDailyStore.open(context)),
                facts,port,System::currentTimeMillis,true);
    }

    /** Lifecycle/clock test seam; it does not manufacture ready or work ownership. */
    static EmulatorDailyRunner.Result run(StoreSource stores,FactsSource facts,
            EmulatorDailyRunner.Port port,Clock clock)throws Exception {
        return run(stores,facts,port,clock,false);
    }
    static EmulatorDailyRunner.Result run(StoreSource stores,FactsSource facts,
            EmulatorDailyRunner.Port port,Clock clock,boolean overnight)throws Exception {
        if(stores==null || facts==null || port==null || clock==null)
            throw new IllegalArgumentException("Complete daily tick ports required");
        try(LockedStore store=stores.open()) {
            if(store==null)throw new IllegalStateException("Exclusive durable store required");
            EmulatorDailyRunner.State retained=store.read();
            String before=retained==null?null:retained.encode();
            EmulatorDailyRunner.Facts fresh=facts.read(before==null?null:EmulatorDailyRunner.State.decode(before));
            // Facts is read-only. Refuse a broken adapter/out-of-band writer
            // before observation or effects, without overwriting either state.
            EmulatorDailyRunner.State after=store.read();
            String actual=after==null?null:after.encode();
            if(!java.util.Objects.equals(before,actual))
                throw new IllegalStateException("Daily state changed while collecting facts; no effect authorized");
            // Sample wall time AFTER collection, not before a possibly slow
            // root/HC read. Crossing 16:30/18:00 must use the current boundary.
            long now=clock.now();
            return new EmulatorDailyRunner(store,port,overnight).tick(now,fresh);
        }
        // A close failure propagates. Never retry a tick here: an effect may
        // already have been issued. Next invocation must read retained intent.
    }
    private static final class AndroidStore implements LockedStore {
        private final EmulatorAndroidDailyStore store;
        AndroidStore(EmulatorAndroidDailyStore store){this.store=store;}
        public EmulatorDailyRunner.State read()throws Exception{return store.read();}
        public void save(EmulatorDailyRunner.State s)throws Exception{store.save(s);}
        public void close()throws Exception{store.close();}
    }
}
