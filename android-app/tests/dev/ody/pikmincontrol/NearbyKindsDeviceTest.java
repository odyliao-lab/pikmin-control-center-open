package dev.ody.pikmincontrol;
/** Run with game open, dispatch OFF and no batch/farm session. No dispatch RPCs. */
public final class NearbyKindsDeviceTest {
    public static void main(String[] args) throws Exception {
        NearbyState before = NearbyGateway.read();
        if (!before.fresh || before.busy || !"off".equals(before.mode))
            throw new IllegalStateException("Requires live native, dispatch off, no farm");
        try {
            verify(0);
            verify(4);
            verify(1);
            verify(2);
            verify(3);
            System.out.println("PASS: actual Gateway -> 0644 file -> live native 0/4/1/2/3 masks");
        } finally { NearbyGateway.setKinds(before.mask); }
    }
    static void verify(int mask) throws Exception {
        NearbyGateway.setKinds(mask);
        for (int attempt = 0; attempt < 20; attempt++) {
            NearbyState state = NearbyGateway.read();
            if (!"off".equals(state.mode) || state.busy) throw new AssertionError("Mode changed");
            if (state.mask != mask) throw new AssertionError("Control round-trip failed");
            if (state.fresh && state.observed == mask) {
                System.out.println("native mask=" + mask);
                return;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("No native acknowledgement: " + mask);
    }
}
