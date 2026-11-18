package dev.ody.pikmincontrol;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
/** Signed-APK catalog selection. No device mutations or fallback to another version. */
final class CoreRollbackCatalog {
    static final int MAX_PAIRS=96;
    static boolean disableLegacyCopy(boolean unified,String gpsState,boolean userOverride)throws IOException {
        return !unified&&!userOverride&&CopyModeState.needsOff(gpsState);
    }
    static void validate(Collection<CorePair> pairs,long app,int target)throws IOException {
        HashSet<Integer> codes=new HashSet<>();
        if(pairs.isEmpty()||pairs.size()>MAX_PAIRS)throw new IOException("回退目錄大小不符");
        for(CorePair p:pairs)if(p.app!=app||p.code>=target||!codes.add(p.code))
            throw new IOException("回退目錄版本不符或重複");
    }
    static CorePair installed(Collection<CorePair> pairs,String code,int target)throws IOException {
        for(CorePair p:pairs)if(code.equals(""+p.code)&&CorePair.upgrade(p.code,target))return p;
        throw new IOException("目前核心版本不在本APK可升級範圍，尚未修改模組");
    }
    static CorePair recorded(Collection<CorePair> pairs,String sha)throws IOException {
        for(CorePair p:pairs)if(p.sha.equals(sha))return p;
        throw new IOException("本APK缺少原交易回退包，請保留紀錄");
    }
}
