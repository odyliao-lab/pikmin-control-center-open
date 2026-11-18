package dev.ody.pikmincontrol.hccompat;

/** No Android imports. Removing only the SDK gate never creates a permission grant. */
public final class CompatPolicy {
    public static final String PACKAGE = "com.nianticlabs.pikmin";
    public static final String MODULE = "dev.ody.pikmincontrol.hccompat";
    public static final String APK_SHA = "4d28ce6c8e5c7efa1e6a864edaa545991ffe52597f751d37db55bce69c4af957";
    public static final String LEGACY_APK_SHA = "070c75d6e253317438dc4ac1b0f60b5dbf9e5250763827b7c6b6197f0e81e9dc";
    public static boolean target(String pkg, String process, int sdk, String model) {
        return PACKAGE.equals(pkg) && PACKAGE.equals(process) && sdk == 33 && "2306EPN60G".equals(model);
    }
    public static boolean identity(String version, long code, String hash) {
        return ("157.0".equals(version) && code == 1793360009L && APK_SHA.equals(hash))
                || ("156.1".equals(version) && code == 1793076318L && LEGACY_APK_SHA.equals(hash));
    }
    public static boolean support(boolean enabled, boolean verified, boolean provider, int backgroundFeature) {
        return enabled && verified && provider && backgroundFeature == 2;
    }
    private CompatPolicy() {}
}
