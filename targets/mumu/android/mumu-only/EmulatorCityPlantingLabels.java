package dev.ody.pikmincontrol;

/** Labels only: a daily GPS planting run is not a flower-field harvest job. */
final class EmulatorCityPlantingLabels {
    static String title(boolean daily) {return daily?"城市移動種花":"花田採果";}
    static String channel(boolean daily,String legacy) {return daily?"daily_city_planting":legacy;}
    static String detail(boolean daily,String legacy) {
        return daily?"GPS 路線 · 一般種花 · 全花種保留 "+EmulatorPetalRotation.RESERVE:legacy;
    }
}
