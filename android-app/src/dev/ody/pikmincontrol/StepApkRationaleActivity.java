package dev.ody.pikmincontrol;
/** APK-provider rationale is public on API33 but exposes no health data or write entry. */
public final class StepApkRationaleActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state){
        super.onCreate(state);
        android.widget.TextView text=new android.widget.TextView(this); text.setPadding(32,48,32,32);
        text.setText("控制中心步數工具\n\n僅依使用者確認讀寫步數；寫入屬人工／程式紀錄，不是實際步行證明。\n資料留在本機Health Connect，不自動上傳。可隨時停止，並在Health Connect撤銷授權。\nHC寫入、聚合與遊戲採計是不同結果。"); setContentView(text);
    }
}
