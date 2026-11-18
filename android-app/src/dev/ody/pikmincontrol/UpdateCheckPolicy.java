package dev.ody.pikmincontrol;

/** Pure metadata check scheduling; no background scheduler or download admission. */
final class UpdateCheckPolicy {
    static boolean due(boolean enabled,long now,long attempt,long success,int failures){
        if(!enabled)return false;
        if(attempt<=0||now<attempt)return true;
        long delay=success>=attempt?86_400_000L:failures<=1?300_000L:failures==2?1_800_000L:86_400_000L;
        return now-attempt>=delay;
    }
    static String status(long remote,long installed,int minSdk,int sdk,String version){
        if(remote<installed)return "本機安裝序號較高（"+installed+"）；目前正式版 "+version+"（"+remote+"）";
        if(remote==installed)return "目前已是最新正式版";
        return minSdk>sdk?"新版需要較新的 Android":"發現新版 "+version;
    }
}
