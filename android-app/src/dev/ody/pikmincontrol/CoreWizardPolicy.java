package dev.ody.pikmincontrol;

final class CoreWizardPolicy {
    static String pending(boolean rebooted,boolean migration,String phase,int previous,int target){
        if(!rebooted)return "reboot";
        return migration&&phase.equals("staged")&&previous>0&&previous<target?"successor":"review";
    }
    static String idleFailure(Integer code){
        if(code!=null&&code==10)return "Pikmin仍在背景執行；請使用停止並繼續，或強制停止遊戲後重試";
        if(code!=null&&code==11)return "自動化控制尚未全部停止；請先完成停止清理";
        return "";
    }
}
