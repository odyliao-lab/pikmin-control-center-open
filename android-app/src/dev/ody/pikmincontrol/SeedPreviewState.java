package dev.ody.pikmincontrol;

/** Display-only capability, with a fresh same-process acknowledgement. */
final class SeedPreviewState {
    final boolean desired, fresh, compatible, applied;
    final String phase;
    SeedPreviewState(String control,String report,String beforePid,String afterPid,long now) {
        this(control,report,beforePid,afterPid,now,SeedPreviewTarget.GAME);
    }
    SeedPreviewState(String control,String report,String beforePid,String afterPid,long now,String expectedGame) {
        desired="on".equals(control.trim());
        boolean valid=false, supported=false, enabled=false;String stage="waiting";
        String[] p=report.trim().split("\t",-1);
        try {
            if(p.length==10&&"v1".equals(p[0])&&beforePid.matches("[1-9][0-9]{0,6}")
                    &&beforePid.equals(afterPid)&&p[2].equals(beforePid)&&("157.0".equals(expectedGame)||"156.1".equals(expectedGame))&&p[3].equals(expectedGame)){
                long at=Long.parseLong(p[1]);
                valid=at>0&&now>=at&&now-at<=5000;
                stage=p[4];supported="ready".equals(stage)||"waiting-cards".equals(stage);
                enabled="1".equals(p[5]);
                if(!"0".equals(p[5])&&!enabled)valid=false;
                for(int i=6;i<10;i++)if(!p[i].matches("[0-9]{1,10}"))valid=false;
                if(valid&&(Long.parseLong(p[6])>128||Long.parseLong(p[7])>255
                        ||Long.parseLong(p[8])>0xffffffffL||Long.parseLong(p[9])>0xffffffffL))valid=false;
            }
        }catch(RuntimeException ignored){valid=false;}
        fresh=valid;compatible=valid&&supported;applied=compatible&&enabled;phase=stage;
    }
    String message(){
        if(!desired)return !fresh?"已關閉，等待遊戲確認":applied?"正在恢復原花苗名稱…":"已關閉，顯示原花苗名稱";
        if(!fresh)return "已開啟，等待遊戲載入花苗預覽";
        if(!compatible)return "目前遊戲或核心不支援花苗預覽";
        if(!applied)return "已開啟，等待套用";
        return "ready".equals(phase)?"已啟用 · 花苗與探險頁顯示顏色、已知種類":"已啟用 · 開啟遊戲花苗清單或探險頁即可預覽";
    }
}
