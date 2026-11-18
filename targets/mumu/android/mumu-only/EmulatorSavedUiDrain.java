package dev.ody.pikmincontrol;
/** STOP/rest may drain only an owned ordinary-planting result, never start work. */
final class EmulatorSavedUiDrain {
    static EmulatorDailyUiMatcher.Scene scene(EmulatorDailyUiMatcher.Scene actual,boolean scheduled){
        if(scheduled||actual==EmulatorDailyUiMatcher.Scene.MAP||actual==EmulatorDailyUiMatcher.Scene.FLOWER_FREE||actual==EmulatorDailyUiMatcher.Scene.SPEED)return actual;
        return EmulatorDailyUiMatcher.Scene.UNKNOWN;
    }
}
