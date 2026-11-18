package dev.ody.pikmincontrol;

/** javac/java, no Android runtime required. */
public final class ProbeSnapshotTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static void rejects(String value) {
        try { ProbeSnapshot.sections(value, 2); throw new AssertionError("accepted incomplete snapshot"); }
        catch (IllegalStateException expected) { checks++; }
    }
    public static void main(String[] args) {
        String runtime = "\n__PCC_READ_0__\n123\n\n__PCC_READ_1__\nv1\t1000\t123\t1.4.29\t63\t152.0\n\n__PCC_READ_2__\n123\n\n__PCC_READ_END__\n";
        String[] core = ProbeSnapshot.checkedSections(new BoundedCommand.Result(runtime,true,false,false),3);
        check(core[0].trim().equals("123") && core[2].trim().equals("123"));
        check(core[1].equals("v1\t1000\t123\t1.4.29\t63\t152.0\n"));
        for (BoundedCommand.Result read : new BoundedCommand.Result[] {
                null, new BoundedCommand.Result(runtime,false,false,false),
                new BoundedCommand.Result(runtime,true,true,false),
                new BoundedCommand.Result(runtime,true,false,true),
                new BoundedCommand.Result(runtime.trim(),true,false,false),
                new BoundedCommand.Result(runtime + "unexpected",true,false,false)}) {
            try { ProbeSnapshot.checkedSections(read,3); throw new AssertionError("accepted invalid core read"); }
            catch (IllegalStateException expected) { checks++; }
        }
        String raw = "\n__PCC_READ_0__\nfirst\n\n__PCC_READ_1__\nsecond\n\n__PCC_READ_END__\n";
        String[] parts = ProbeSnapshot.sections(raw, 2);
        check(parts[0].equals("first\n")); check(parts[1].equals("second\n"));
        check(ProbeSnapshot.sections(raw.replace("\n", "\r\n"), 2)[0].equals("first\n"));
        String empty = "\n__PCC_READ_0__\n\n__PCC_READ_1__\n\n__PCC_READ_END__\n";
        check(ProbeSnapshot.sections(empty, 2)[1].isEmpty());
        rejects(raw.replace("__PCC_READ_END__", "")); rejects(raw.replace("__PCC_READ_1__", "bad"));
        rejects("permission denied" + raw); rejects(raw + "unexpected"); rejects(null);
        String command = ProbeSnapshot.command("true", "true");
        check(command.contains("__PCC_READ_0__") && command.contains("__PCC_READ_1__") && command.endsWith("'__PCC_READ_END__'"));

        String h = "1000\tstart-requested\tgift\ttask\t45\t1\n"
                + "1001\tgift-pikmin-unavailable\tgift\ttask\t0\t0\n";
        DispatchConfirmation.Evidence e = new DispatchConfirmation.Evidence(h, "", "task", 1000);
        check(e.started && e.giftUnavailable); // Caller prioritises an observed start.
        check(!e.collected && !e.inventoryConfirmed && !e.faulted);
        check(!new DispatchConfirmation.Evidence(h, "", "other", 1000).started);
        check(!new DispatchConfirmation.Evidence(h, "", "task", 1002).started);
        check(!new DispatchConfirmation.Evidence(h, "", "task", 0).started);
        check(!new DispatchConfirmation.Evidence("bad\tstart-requested\tgift\ttask\t45\t1", "", "task", 1).started);
        check(!new DispatchConfirmation.Evidence("1000\tstart-requested\tgift\ttask", "", "task", 1).started);
        String reward = "1005\tbatch-confirmed\ttask\t28\t1\t0\tkeep\tall\tdetail\n";
        e = new DispatchConfirmation.Evidence("1004\tstart-rpc-faulted\tgift\ttask\t0\t0\n", reward, "task", 1000);
        check(e.collected && e.faulted); // Preserve stronger exact-task receipt precedence.
        check(new DispatchConfirmation.Evidence("1006\tinventory-confirmed-absent\tunknown\ttask\t0\t0\r\n", "", "task", 1000).inventoryConfirmed);
        check(!new DispatchConfirmation.Evidence("", "", "task", 1000).inventoryConfirmed);
        System.out.println("ProbeSnapshotTest PASS " + checks + " checks");
    }
}
