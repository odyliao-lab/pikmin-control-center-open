package dev.ody.pikmincontrol;

/** Separate v1 recovery capability/ack; old planter status protocol remains compatible. */
final class PlanterRecovery {
    final boolean supported;
    final String nonce, result, pid, unavailable;
    PlanterRecovery(String raw,String pid,long now){
        raw=raw==null?"":raw.trim();pid=pid==null?"":pid.trim();
        String[] f=raw.split("\t",-1);
        long at=f.length==5?PlanterState.number(f[1]):-1;
        unavailable=pid.isEmpty()?"pid-unavailable":!pid.matches("[1-9][0-9]*")?"pid-ambiguous"
            :raw.isEmpty()?"missing":f.length!=5?"malformed":!"v1".equals(f[0])?"schema"
            :!f[2].matches("[1-9][0-9]*")||at<=0?"malformed":!pid.equals(f[2])?"pid-changed"
            :now<at?"clock":now-at>60000?"stale":"";
        supported=unavailable.isEmpty();
        nonce=supported?f[3]:"";result=supported?f[4]:"unsupported";
        this.pid=supported?f[2]:"";
    }
    String message(){
        if(!supported)switch(unavailable){
            case "pid-unavailable":return "未取得遊戲程序，請開啟遊戲更新育苗回報";
            case "pid-ambiguous":return "偵測到多個遊戲程序，請先關閉分身，只保留主空間遊戲";
            case "missing":return "尚未收到育苗恢復回報，請開啟遊戲；若仍無資料，至系統核對配對核心";
            case "pid-changed":return "遊戲已重開，上次育苗回報屬於舊程序；請回遊戲更新";
            case "stale":return "育苗恢復回報已過期，請回遊戲停留約10秒再回來";
            case "clock":return "回報時間與手機時間不一致，請確認時間設定並回遊戲更新";
            case "schema":return "育苗恢復回報格式不相容，請至系統核對本APK配對核心";
            default:return "育苗恢復回報不完整，請回遊戲更新；未清除待確認紀錄";
        }
        switch(result){
            case "ready":return "可手動核對並恢復育苗";
            case "checking-inventory":return "正在核對，請留在遊戲約10秒後回來查看";
            case "recovered-plant-state-confirmed":return "已確認原花苗種入指定槽位，解除暫停；保留目前開關";
            case "recovered-pluck-target-retired":return "舊拔苗目標已不存在，解除暫停；不補算成功，保留目前開關";
            case "recovered-no-request-created":return "未送出遊戲請求，解除暫停；保留目前開關";
            case "not-paused":return "目前沒有需要解除的安全暫停";
            case "rpc-in-flight":return "上次請求仍在執行，暫不恢復；請留在遊戲等待回報";
            case "result-unknown":return "上次結果仍不明，保留暫停；請先查看遊戲育苗狀態與紀錄";
            case "expired-or-game-restarted":return "恢復請求已逾時或遊戲已重開，請更新狀態後重試";
            case "ledger-changed":return "待確認操作已改變，請更新狀態後重試";
            case "invalid-ledger":case "ledger-missing":return "待確認紀錄缺失或損壞，需檢查原始紀錄，未解除暫停";
            case "archive-failed":return "無法保存恢復紀錄，未解除暫停";
            case "slot-unavailable":return "原槽位目前無法確認，未解除暫停";
            case "invalid-request":return "恢復請求格式無效，未解除暫停；請更新狀態後重試";
            case "incompatible":return "育苗介面尚未相容，請至系統核對本APK配對核心";
            default:return "收到未識別的育苗恢復狀態，請查看診斷紀錄；不代表核心版本過舊";
        }
    }
    static String request(String nonce,String pid,long now,String ledger){
        if(!nonce.matches("[a-zA-Z0-9_-]{1,64}")||!pid.matches("[1-9][0-9]*")||now<=0)throw new IllegalArgumentException("恢復識別無效");
        String expected="0\tnone\tnone\t-1";
        if(!"MISSING".equals(ledger)){
            String[] f=ledger.trim().split("\\s+");
            if(f.length!=4||PlanterState.number(f[0])<=0||PlanterState.number(f[0])>now||!("pluck".equals(f[1])||"plant".equals(f[1]))
                    ||!f[2].matches("[A-Za-z0-9_:-]{1,256}")||PlanterState.number(f[3])<0||PlanterState.number(f[3])>=8)
                throw new IllegalStateException("待確認紀錄損壞，不能直接清除");
            expected=String.join("\t",f);
        }
        return "v1\t"+nonce+"\t"+pid+"\t"+now+"\t"+expected+"\n";
    }
}
