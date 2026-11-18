package dev.ody.pikmincontrol;

import java.util.*;

/** Presentation only. No control changes, reads, timers or inferred successful operations. */
final class NoticePresentation {
    static final class Row {
        final int id; final String title, text;
        Row(int id,String title,String text){this.id=id;this.title=title;this.text=text;}
    }
    static boolean ongoing(int id){return id==152 || id==153 || id==154 || id==158 || id==159 || id==161 || id==369;}
    static int rank(int id){return id==152||id==154||id==369?0:id==161?1:id==159?2:3;}
    static List<Row> ordered(List<Row> rows){
        List<Row> result=new ArrayList<>();
        for(Row r:rows)if(ongoing(r.id))result.add(r);
        Collections.sort(result,(a,b)->{int d=Integer.compare(rank(a.id),rank(b.id));return d!=0?d:Integer.compare(a.id,b.id);});
        return result;
    }
    static String headline(List<Row> rows){
        StringBuilder s=new StringBuilder();
        for(Row r:ordered(rows)){if(s.length()>0)s.append(" · ");s.append(r.title);if(s.length()>110)break;}
        return s.toString();
    }
    static String stepTitle(String phase){
        if(StepView.WRITING.equals(phase)||StepView.RESTING.equals(phase)||StepView.SETTINGS_ON.equals(phase))return "步數寫入中";
        if(StepView.WAITING_PERMISSION.equals(phase))return "步數：等待權限";
        if(StepView.WAITING_WINDOW.equals(phase))return "步數：時段外等待";
        if(StepView.TARGET_REACHED.equals(phase))return "步數：今日已達標";
        if(StepView.RESULT_PENDING.equals(phase))return "步數：結果待確認";
        if(StepView.STORE_ERROR.equals(phase))return "步數：儲存失敗";
        if(StepView.REJECTED.equals(phase))return "步數：暫停寫入";
        if(StepView.STOPPED.equals(phase))return "步數：已停止";
        return "步數：目前無法運作";
    }
    static String stepText(StepView v){
        String reason;
        switch(v.phase){
            case StepView.WAITING_PERMISSION: reason="請開啟 Health Connect 權限設定";break;
            case StepView.RESULT_PENDING: reason="等待核對既有紀錄，不重複寫入";break;
            case StepView.STORE_ERROR: reason="請開啟控制中心檢查儲存狀態";break;
            case StepView.REJECTED: reason="請查看步數工作紀錄";break;
            case StepView.WAITING_WINDOW: reason="等待設定時段，不補寫錯過區間";break;
            default: reason="Health Connect 保存值，非遊戲採計";
        }
        return "今日 "+StepView.totalText(v.allSourcesToday)+" / "+v.target+" 步 · "+reason;
    }
    static boolean clearAlert(String state){return "running".equals(state)||"completed".equals(state)||"stopped".equals(state);}
    static boolean newAlert(String previous,String code){return !code.equals(previous);}
    static String stepAlert(String phase){
        if(StepView.WAITING_PERMISSION.equals(phase))return "steps-permission";
        if(StepView.STORE_ERROR.equals(phase))return "steps-storage";
        if(StepView.UNSUPPORTED.equals(phase))return "steps-unavailable";
        return "";
    }
    static boolean stepHealthy(String phase){return StepView.WRITING.equals(phase)||StepView.RESTING.equals(phase)
            ||StepView.SETTINGS_ON.equals(phase)||StepView.TARGET_REACHED.equals(phase);}
}
