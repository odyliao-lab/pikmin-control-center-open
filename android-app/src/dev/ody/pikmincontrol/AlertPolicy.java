package dev.ody.pikmincontrol;
final class AlertPolicy {
    static String code(String state,String detail,int failures,int skipped) {
        if ("stop-incomplete".equals(state)) return "review";
        if ("error".equals(state)) {
            if (detail.contains("定位") || detail.contains("GPS") || detail.contains("arrival")) return "location";
            return "error";
        }
        return "completed".equals(state) && (failures>0 || skipped>0) ? "partial" : "";
    }
    static boolean due(long last,long now) {return last==0 || now<last || now-last>=900_000L;}
    static String message(String code) {
        switch(code) {
            case "steps-permission": return "步數寫入等待授權；請開啟步數工具，允許 Health Connect 讀寫與背景存取。";
            case "steps-storage": return "步數設定或紀錄無法保存；請開啟步數工具查看工作紀錄，尚未確認的寫入不會重送。";
            case "steps-unavailable": return "Health Connect 目前不可用；請確認已安裝並更新，再開啟步數工具。";
            case "resource": return "資源不足或資料待確認；已停止相關工作，請至系統查看資源狀態，不會自動丟棄或購買。";
            case "review": return "安全停止尚待核對；請查看遊戲與工作紀錄，再使用頁面的復原或停止入口。";
            case "location": return "定位未就緒或已失效；請確認JoyStick與遊戲地圖，再重新檢查。";
            case "partial": return "工作已結束，但有失敗或略過項目；請查看執行紀錄，勿直接重送未知結果。";
            case "nursery": return "育苗異常已記錄；依最新有效庫存自動觀察，條件具備時繼續。";
            default: return "工作異常結束；請查看執行紀錄。可到系統匯出診斷摘要，不會自動上傳。";
        }
    }
}
