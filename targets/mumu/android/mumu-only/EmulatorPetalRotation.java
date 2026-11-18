package dev.ody.pikmincontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** MuMu-only decision policy. No RPC, shell, coordinate or inventory mutation. */
final class EmulatorPetalRotation {
    static final int RESERVE = 50;
    enum Action { WAIT, KEEP, STOP_OWNED, START, REVIEW }
    static final class Petal {
        final String id;
        final int color, stock;
        final boolean ordinary;
        Petal(String id, int color, int stock, boolean ordinary) {
            if (id == null || !id.matches("[A-Za-z0-9._:-]{1,80}") || color < 1 || color > 4 || stock < -1 || stock > 1000000)
                throw new IllegalArgumentException("Invalid petal observation");
            this.id=id; this.color=color; this.stock=stock; this.ordinary=ordinary;
        }
    }
    static final class Snapshot {
        final long captured;
        final int pid;
        final String account;
        final boolean complete;
        final List<Petal> petals;
        Snapshot(long captured, int pid, String account, boolean complete, List<Petal> petals) {
            if (captured < 0 || pid <= 0 || account == null || account.isEmpty() || petals == null || petals.size() > 512)
                throw new IllegalArgumentException("Invalid snapshot identity");
            Set<String> ids=new HashSet<>();
            for (Petal p:petals) if (p == null || !ids.add(p.id)) throw new IllegalArgumentException("Duplicate/null petal");
            this.captured=captured; this.pid=pid; this.account=account; this.complete=complete;
            this.petals=Collections.unmodifiableList(new ArrayList<>(petals));
        }
    }
    static final class Decision {
        final Action action;
        final String petal, reason;
        Decision(Action action, String petal, String reason) { this.action=action; this.petal=petal; this.reason=reason; }
    }
    static Decision decide(long now, int pid, String account, boolean authorized,
                           boolean active, boolean owned, boolean uncertain, String selected,
                           Set<String> attempted, Snapshot snapshot) {
        if (active && !owned) return result(Action.REVIEW,"manual-session-preserved");
        // An uncertain request is never replayed, even if the next frame says inactive.
        if (uncertain) return result(Action.REVIEW,"request-needs-confirmation");
        boolean fresh=snapshot != null && snapshot.complete && snapshot.pid == pid &&
                snapshot.account.equals(account) && now >= snapshot.captured && now-snapshot.captured <= 12000;
        if (!authorized || !fresh) return result(active && owned ? Action.STOP_OWNED : Action.WAIT,
                authorized ? "inventory-or-identity-unavailable" : "outside-owned-session");
        if (attempted == null) throw new IllegalArgumentException("Attempt journal required");
        Petal current=null, best=null;
        for (Petal p:snapshot.petals) {
            if (p.id.equals(selected)) current=p;
            if (p.stock > RESERVE && !attempted.contains(p.id) &&
                    (best == null || p.stock > best.stock || (p.stock == best.stock &&
                    (p.color < best.color || (p.color == best.color && p.id.compareTo(best.id) < 0))))) best=p;
        }
        if (active) {
            if (current == null || current.stock <= RESERVE)
                return result(Action.STOP_OWNED,"low-or-unknown-stock");
            return result(Action.KEEP,"stock-available");
        }
        // Prefer the selected flower kind to avoid unnecessary switches.
        if (current != null && current.stock > RESERVE && !attempted.contains(current.id)) best=current;
        if (best == null) return result(Action.WAIT,"no-unattempted-petals");
        return new Decision(Action.START,best.id,"confirmed-stock");
    }
    private static Decision result(Action action,String reason) { return new Decision(action,"",reason); }
}
