package dev.ody.pikmincontrol;
import java.text.SimpleDateFormat;
import java.util.*;

/** Presentation only. Never infer quantities, action type or identity from adjacent rows. */
final class FeedingHistory {
    static String label(String state){
        switch(state){
            case "rotation-sent":return "換隊已送出 · 等待確認";
            case "rotate-confirmed":return "換隊完成 · 繼續餵食";
            case "rotation-no-replacements":return "暫無可更換成員 · 保留目前隊伍";
            case "rotation-preflight-changed":return "隊伍或設定已變更 · 稍後重查";
            case "rotation-method-unavailable":return "換隊介面尚未就緒 · 未送出";
            case "sent":return "已送出 · 等待確認";
            case "feed-confirmed":return "餵食完成";
            case "collect-confirmed":return "花瓣已收取";
            case "batch-squad-changed":return "已略過離隊目標 · 繼續目前隊伍";
            case "batch-confirmation-timeout":return "部分結果未確認 · 目標冷卻中";
            case "unconfirmed-cooldown":return "結果未確認 · 已留紀錄，稍後重查";
            case "recovery-storage-retry":return "紀錄保存異常 · 自動重試中";
            case "waiting-eligible-squad":return "等待符合條件的隊員或精華";
            case "waiting-other-work":return "等待其他工作完成";
            case "waiting-data":return "等待遊戲資料";
            case "waiting-rpc-no-retry":return "等待遊戲回覆 · 不重送";
            case "waiting-inventory-confirmation":return "等待庫存確認";
            case "off":return "已關閉餵食";
            case "preflight-invalid":return "條件未通過 · 未送出";
            case "preflight-expired":return "資料已過期 · 未送出";
            case "runtime-unavailable":return "等待原生環境就緒";
            case "account-changed":return "帳號已切換 · 未送出";
            case "method-mismatch":return "遊戲版本不相容 · 未送出";
            case "item-id-invalid":return "精華識別異常 · 未送出";
            case "request-root-failed":
            case "request-build-failed":return "操作準備失敗 · 未送出";
            case "intent-write-failed":return "操作紀錄保存失敗 · 未送出";
            case "rpc-start-uncertain":
            case "task-root-failed":return "⚠ 送出結果不明 · 待核對";
            case "config-unavailable":return "設定讀取失敗";
            case "config-changed":return "設定已變更 · 等待重查";
            case "pending-review":return "⚠ 操作待核對 · 自動重查";
            case "receipt-write-review":return "⚠ 紀錄保存異常 · 自動重試";
            case "inventory-mismatch-review":return "⚠ 庫存不符 · 自動重查";
            case "target-missing-review":return "⚠ 找不到目標 · 略過本筆";
            default:return state.contains("review")?"⚠ 異常已記錄 · 請查原始紀錄":"其他狀態 · 請查原始紀錄";
        }
    }
    static String kind(String id){
        int colon=id.lastIndexOf(':');
        if(colon<1||colon!=id.length()-2)return "";
        String color;switch(id.charAt(colon+1)){
            case '1':color="白";break;case '2':color="紅";break;
            case '3':color="藍";break;case '4':color="黃";break;default:return "";
        }
        String name=FeedingCatalogue.name(id.substring(0,colon));
        if(name.startsWith("未知花種"))name="未知花種";
        return name+"／"+color;
    }
    static String format(String raw){return format(raw,TimeZone.getDefault());}
    static String format(String raw,TimeZone zone){
        if(raw==null||raw.trim().isEmpty())return "尚無餵食／收瓣紀錄。";
        if(raw.length()>262144)return "紀錄過大 · 請查原始紀錄";
        SimpleDateFormat time=new SimpleDateFormat("MM-dd HH:mm",Locale.TAIWAN);time.setTimeZone(zone);
        List<String[]> rows=new ArrayList<>();int invalid=0;
        for(String line:raw.split("\n")){
            if(line.trim().isEmpty())continue;
            String[] f=line.replace("\r","").split("\t",-1);
            try{
                if(f.length!=3||!f[0].matches("[0-9]{1,13}")||Long.parseLong(f[0])<=0||!f[1].matches("[a-z-]{1,80}"))throw new IllegalArgumentException();
                rows.add(f);
            }catch(Exception e){invalid++;}
        }
        rows.sort((a,b)->Long.compare(Long.parseLong(b[0]),Long.parseLong(a[0])));
        StringBuilder out=new StringBuilder();
        for(String[] f:rows){
            if(out.length()>0)out.append('\n');
            out.append(time.format(new Date(Long.parseLong(f[0])))).append("  ").append(label(f[1]));
            String flower=kind(f[2]);if(!flower.isEmpty())out.append(" · ").append(flower);
        }
        if(invalid>0){if(out.length()>0)out.append('\n');out.append("⚠ ").append(invalid).append(" 筆格式異常 · 請查原始紀錄");}
        return out.length()==0?"尚無餵食／收瓣紀錄。":out.toString();
    }
}
