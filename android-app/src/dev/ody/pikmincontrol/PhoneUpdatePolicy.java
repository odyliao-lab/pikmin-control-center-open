package dev.ody.pikmincontrol;

/** Phone update decisions. Intent is persisted BEFORE a non-repeatable installation. */
final class PhoneUpdatePolicy {
    enum Next { DOWNLOAD, PERMISSION, INSTALL_APK, WAIT_APK, VERIFY_APK, INSTALL_CORE,
        REBOOT, OPEN_GAME, REVIEW, RESTORE, WAIT, BLOCK }
    // Retire only an obsolete restore obligation after a newer APK AND its
    // own core were installed independently. This never grants install consent.
    static boolean supersededRestore(String phase,String resume,long installed,long target) {
        return target>0&&installed>target&&("restore".equals(phase)
            ||("error".equals(phase)&&"restore".equals(resume)));
    }
    static Next next(String phase,long installed,long target,boolean apkReady,boolean permission,
                     boolean session,String core) {
        if(target<=0||installed>target)return Next.BLOCK;
        if("download".equals(phase)||"apk-intent".equals(phase)) {
            if(installed==target)return Next.VERIFY_APK;
            if(session)return Next.WAIT_APK;
            // Missing session after an intent is ambiguous, never automatically create another.
            if("apk-intent".equals(phase))return Next.BLOCK;
            if(!apkReady)return Next.DOWNLOAD;
            return permission?Next.INSTALL_APK:Next.PERMISSION;
        }
        if(installed!=target)return Next.BLOCK;
        if(!"core".equals(phase)&&!"core-intent".equals(phase))return Next.BLOCK;
        switch(core) {
            case "install": case "successor":
                return "core-intent".equals(phase)?Next.BLOCK:Next.INSTALL_CORE;
            case "reboot": return Next.REBOOT;
            case "open-game": return Next.OPEN_GAME;
            case "review": return Next.REVIEW;
            case "ready": return Next.RESTORE;
            case "busy": return Next.WAIT;
            default: return Next.BLOCK;
        }
    }
}
