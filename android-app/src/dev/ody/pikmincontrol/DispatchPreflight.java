package dev.ody.pikmincontrol;

import java.util.HashSet;

/** Advisory only: absence/unknown never means unavailable, nor authorises Start. */
final class DispatchPreflight {
    private DispatchPreflight() {}
    static final long MAX_AGE_MS = 12000L;
    static final class Observation {
        final long at;
        final String pid, task, kind, reason, owner, assigned;
        Observation(long at, String pid, String[] f) {
            this.at = at; this.pid = pid; task = f[0]; kind = f[1]; reason = f[2]; owner = f[3]; assigned = f[4];
            status = Integer.parseInt(f[5]); eligible = Integer.parseInt(f[6]);
        }
        boolean alreadyDispatched() { return kind.equals("gift") && !owner.isEmpty() && assigned.equals(task)
                && status == 2 && eligible == 0; }
        final int status, eligible;
        boolean blocked() { return alreadyDispatched() || reason.equals("gift-owner-busy") || reason.equals("no-available-team"); }
        boolean fresh(long now) { return at > 0 && now >= at && now - at <= MAX_AGE_MS; }
        boolean confirms(Observation first, long now) {
            return first != null && first.fresh(now) && fresh(now) && at > first.at && blocked() && first.blocked()
                    && pid.equals(first.pid) && task.equals(first.task) && kind.equals(first.kind)
                    && reason.equals(first.reason) && owner.equals(first.owner) && assigned.equals(first.assigned);
        }
    }
    static Observation parse(String raw, String livePid, String taskId, String kind, long now) {
        if (raw == null || livePid == null || !livePid.trim().matches("[1-9][0-9]*")) return null;
        String[] lines = raw.replace("\r", "").trim().split("\n", -1);
        if (lines.length < 2) return null;
        try {
            String[] header = lines[0].split("\t", -1), end = lines[lines.length-1].split("\t", -1);
            if (header.length != 3 || !header[0].equals("v1") || !header[2].equals(livePid.trim())
                    || end.length != 2 || !end[0].equals("end") || Integer.parseInt(end[1]) != lines.length-2) return null;
            long at = Long.parseLong(header[1]);
            HashSet<String> ids = new HashSet<>();
            Observation found = null;
            for (int i = 1; i < lines.length-1; i++) {
                String[] f = lines[i].split("\t", -1);
                if (f.length != 7 || f[0].isEmpty() || !ids.add(f[0])) return null;
                int status = Integer.parseInt(f[5]), eligible = Integer.parseInt(f[6]);
                if (eligible < 0) return null;
                if (!taskId.equals(f[0]) || !kind.equals(f[1])) continue;
                if (f[2].equals("gift-owner-busy") && (!kind.equals("gift") || f[3].isEmpty()
                        || f[4].isEmpty() || f[4].equals(taskId) || status != 2 || eligible != 0)) return null;
                if (f[2].equals("no-available-team") && ((!kind.equals("seed") && !kind.equals("fruit")) || eligible != 0)) return null;
                found = new Observation(at, header[2], f);
            }
            return found != null && found.fresh(now) ? found : null;
        } catch (NumberFormatException invalid) { return null; }
    }
}
