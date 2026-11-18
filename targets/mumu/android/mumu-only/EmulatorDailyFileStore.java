package dev.ody.pikmincontrol;

import java.io.IOException;
import java.nio.file.Path;

/** Typed daily HOST journal; existing wire/4096B bound retained. */
final class EmulatorDailyFileStore extends EmulatorFileJournal<EmulatorDailyRunner.State> implements EmulatorDailyRunner.Store {
    EmulatorDailyFileStore(Path root)throws IOException{super(root,"daily-runner");}
    protected EmulatorDailyRunner.State decode(String wire){return EmulatorDailyRunner.State.decode(wire);}
    protected String encode(EmulatorDailyRunner.State state){return state.encode();}
}
