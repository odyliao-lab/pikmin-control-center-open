package dev.ody.pikmincontrol;

public final class SetupSnapshotTest {
    static int count;
    static void check(boolean b){count++;if(!b)throw new AssertionError("case "+count);}
    static String raw="probe_complete=yes\nroot_uid=0\nmagisk=30700\ngame=157.0\nil2cpp_size=257043464\nnative=1.5.27\nnative_code=95\nnative_disabled=no\nnative_pending=no\ncopy_pending=no\ncopy=v152.0-r13\ncopy_disabled=no\njoystick=5.3.3\nruntime=compatible\nguard_schema=v2\nguard_limit=300000\npid=42\nguard_pid=42\nguard_at=1000\n";
    static SetupSnapshot s(String r){return new SetupSnapshot(r,34,true,100000000,2000);}
    public static void main(String[] args){
        check(s(raw).compatible);check(s(raw).runtime);check(s(raw).components(true,true));
        check(s(raw).coreInstalled());
        check(s(raw.replace("1.5.27","1.5.28").replace("native_code=95","native_code=96")).coreInstalled());
        check(s(raw.replace("1.5.27","1.5.29").replace("native_code=95","native_code=97")).coreInstalled());
        check(!s(raw.replace("1.5.27","1.5.28")).coreInstalled());
        check(!s(raw.replace("native_code=95","native_code=84")).coreInstalled());
        check(!s(raw.replace("1.5.27","1.5.16")).coreInstalled());
        check(!s(raw.replace("probe_complete=yes","probe_complete=no")).root);
        check(!s(raw.replace("root_uid=0","root_uid=2000")).compatible);
        check(!s(raw.replace("magisk=30700","magisk=unknown")).compatible);
        check(!s(raw.replace("157.0","152.0")).compatible);
        check(!s(raw.replace("157.0","153.0")).compatible);
        check(!s(raw.replace("257043464","256261665")).compatible);
        check(!new SetupSnapshot(raw,35,true,100000000,2000).compatible);
        check(new SetupSnapshot(raw,33,true,100000000,2000).compatible);
        check(!new SetupSnapshot(raw,32,true,100000000,2000).compatible);
        check(!new SetupSnapshot(raw.replace("257043464","256261665"),33,true,100000000,2000).compatible);
        check(!new SetupSnapshot(raw,33,false,100000000,2000).compatible);
        check(!new SetupSnapshot(raw.replace("root_uid=0","root_uid=2000"),33,true,100000000,2000).compatible);
        check(!new SetupSnapshot(raw.replace("native_pending=no","native_pending=yes"),33,true,100000000,2000).runtime);
        check(!new SetupSnapshot(raw,34,false,100000000,2000).compatible);
        check(!s(raw.replace("native_pending=no","native_pending=yes")).runtime);
        check(!s(raw.replace("native_disabled=no","native_disabled=yes")).coreInstalled());
        check(!s(raw.replace("guard_pid=42","guard_pid=43")).runtime);
        check(!new SetupSnapshot(raw,34,true,100000000,11001).runtime);
        check(!new SetupSnapshot(raw,34,true,100000000,999).runtime);
        check(!s(raw.replace("native_code=95","native_code=99")).coreInstalled());
        check(!s(raw.replace("copy_pending=no","copy_pending=yes")).components(true,true));
        check(s(raw.replace("copy=v152.0-r13","copy=missing")).components(true,false));
        check(!s(raw.replace("joystick=5.3.3","joystick=5.3.4")).components(true,false));
        check(s(raw.replace("joystick=5.3.3","joystick=missing")).components(false,false));
        check(!s("").components(false,false));check(!JoystickAdapter.supported("5.3.3-clone"));
        check(s(raw).currentRuntime(2500));check(!s(raw).currentRuntime(12000));check(!s(raw).currentRuntime(1999));
        String unified=raw.replace("1.5.27","1.5.0").replace("native_code=95","native_code=68").replace("copy=v152.0-r13","copy=")
            +"pair_files=yes\npair_version=1.5.0\npair_code=68\npair_unified=yes\npair_pid=42\npair_at=1500\n";
        check(s(unified).components(true,true));check(s(unified).runtime);check(s(unified).summary().contains("整合核心內建"));
        check(!s(unified.replace("pair_files=yes","pair_files=no")).components(true,true));
        check(!s(unified.replace("pair_pid=42","pair_pid=43")).runtime);
        check(!s(unified.replace("pair_at=1500","pair_at=3000")).runtime);
        check(!s(unified.replace("pair_at=1500","pair_at=0")).runtime);
        check(!s(unified.replace("pair_code=68","pair_code=67")).coreInstalled());
        check(!s(unified.replace("pair_version=1.5.0","pair_version=1.4.33")).coreInstalled());
        check(!s(unified.replace("copy_pending=no","copy_pending=yes")).copyInstalled());
        check(!s(raw+"pair_files=no\n").coreInstalled()); // Do not fall back after an explicit failed paired probe.
        System.out.println("PASS SetupSnapshot: "+count+" environment and capability checks");
    }
}
