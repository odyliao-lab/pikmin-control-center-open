package dev.ody.pikmincontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class StepLedger {
    static final int MAX_DISPLAY = 200;
    /** Soft compaction target. Today and pending spans are never dropped to hit this. */
    static final int MAX_OCCUPIED = 512;
    private final List<StepPlan> plans;
    private final List<long[]> occupied;

    StepLedger(List<StepPlan> plans) {
        this(plans, null);
    }

    StepLedger(List<StepPlan> plans, List<long[]> occupied) {
        this.plans = new ArrayList<StepPlan>(plans == null ? Collections.<StepPlan>emptyList() : plans);
        this.occupied = new ArrayList<long[]>();
        boolean trustPersistedSpans = occupied != null;
        if (trustPersistedSpans) {
            for (int i = 0; i < occupied.size(); i++) this.occupied.add(occupied.get(i));
            mergeOccupied();
        } else {
            for (int i = 0; i < this.plans.size(); i++) {
                if (StepPlan.CONFIRMED.equals(this.plans.get(i).status)) remember(this.plans.get(i));
            }
            mergeOccupied();
        }
        for (int i = 0; i < this.plans.size(); i++) {
            if (this.plans.get(i).pending()) remember(this.plans.get(i));
        }
        trimDisplay();
    }

    static StepLedger empty() { return new StepLedger(Collections.<StepPlan>emptyList()); }

    List<StepPlan> all() { return Collections.unmodifiableList(plans); }

    List<long[]> occupiedSpans() { return Collections.unmodifiableList(occupied); }

    StepPlan pending() {
        for (int i = plans.size() - 1; i >= 0; i--) if (plans.get(i).pending()) return plans.get(i);
        return null;
    }

    StepPlan lastConfirmed() {
        for (int i = plans.size() - 1; i >= 0; i--) {
            if (StepPlan.CONFIRMED.equals(plans.get(i).status)) return plans.get(i);
        }
        return null;
    }

    StepPlan byClientRecordId(String clientRecordId) {
        if (clientRecordId == null || clientRecordId.isEmpty()) return null;
        for (int i = plans.size() - 1; i >= 0; i--) {
            if (clientRecordId.equals(plans.get(i).clientRecordId)) return plans.get(i);
        }
        return null;
    }

    StepPlan byIdOrClient(String id, String clientRecordId) {
        if (id != null && id.length() > 0) {
            for (int i = plans.size() - 1; i >= 0; i--) {
                if (id.equals(plans.get(i).id)) return plans.get(i);
            }
        }
        return byClientRecordId(clientRecordId);
    }

    boolean overlaps(long startMs, long endMs) {
        for (int i = 0; i < occupied.size(); i++) {
            long[] span = occupied.get(i);
            if (span[0] < endMs && startMs < span[1]) return true;
        }
        return false;
    }

    long confirmedToday(long dayStart, long dayEnd) {
        long total = 0L;
        for (int i = 0; i < plans.size(); i++) {
            StepPlan plan = plans.get(i);
            if (StepPlan.CONFIRMED.equals(plan.status) && plan.startMs >= dayStart && plan.startMs < dayEnd)
                total += plan.count;
        }
        return total;
    }

    void add(StepPlan plan) {
        plans.add(plan);
        remember(plan);
        trimDisplay();
    }

    void replace(StepPlan plan) {
        for (int i = 0; i < plans.size(); i++) {
            if (plans.get(i).id.equals(plan.id) || plans.get(i).clientRecordId.equals(plan.clientRecordId)) {
                StepPlan previous = plans.get(i);
                plans.set(i, plan);
                if (!plan.occupies()) removeSpan(previous.startMs, previous.endMs);
                else remember(plan);
                return;
            }
        }
        add(plan);
    }

    void remember(StepPlan plan) {
        if (plan == null || !plan.occupies()) return;
        if (containsSpan(plan.startMs, plan.endMs)) return;
        occupied.add(new long[] { plan.startMs, plan.endMs });
        if (StepPlan.CONFIRMED.equals(plan.status)) mergeOccupied();
    }

    void compact(long keepFromMs) {
        List<long[]> kept = new ArrayList<long[]>();
        for (int i = 0; i < occupied.size(); i++) {
            long[] span = occupied.get(i);
            boolean pending = false;
            for (int p = 0; p < plans.size(); p++) {
                StepPlan plan = plans.get(p);
                if (plan.pending() && plan.startMs < span[1] && span[0] < plan.endMs) pending = true;
            }
            if (pending || span[1] > keepFromMs) kept.add(span);
        }
        occupied.clear();
        occupied.addAll(kept);
        mergeOccupied();
        while (occupied.size() > MAX_OCCUPIED) {
            int drop = -1;
            for (int i = 0; i < occupied.size(); i++) {
                if (occupied.get(i)[1] <= keepFromMs) { drop = i; break; }
            }
            if (drop < 0) break;
            occupied.remove(drop);
        }
    }

    void mergeOccupied() {
        List<long[]> pendingExact = new ArrayList<long[]>();
        List<long[]> rest = new ArrayList<long[]>();
        for (int i = 0; i < occupied.size(); i++) {
            if (pendingExactSpan(occupied.get(i))) pendingExact.add(occupied.get(i));
            else rest.add(occupied.get(i));
        }
        occupied.clear();
        occupied.addAll(rest);
        if (occupied.size() >= 2) {
            java.util.Collections.sort(occupied, new java.util.Comparator<long[]>() {
                public int compare(long[] a, long[] b) { return Long.compare(a[0], b[0]); }
            });
            List<long[]> merged = new ArrayList<long[]>();
            long[] cur = new long[] { occupied.get(0)[0], occupied.get(0)[1] };
            for (int i = 1; i < occupied.size(); i++) {
                long[] next = occupied.get(i);
                if (next[0] <= cur[1]) cur[1] = Math.max(cur[1], next[1]);
                else {
                    merged.add(cur);
                    cur = new long[] { next[0], next[1] };
                }
            }
            merged.add(cur);
            occupied.clear();
            occupied.addAll(merged);
        }
        for (int i = 0; i < pendingExact.size(); i++) {
            if (!containsSpan(pendingExact.get(i)[0], pendingExact.get(i)[1]))
                occupied.add(pendingExact.get(i));
        }
    }

    private boolean pendingExactSpan(long[] span) {
        for (int i = 0; i < plans.size(); i++) {
            StepPlan plan = plans.get(i);
            if (plan.pending() && plan.startMs == span[0] && plan.endMs == span[1]) return true;
        }
        return false;
    }

    private void removeSpan(long startMs, long endMs) {
        for (int i = occupied.size() - 1; i >= 0; i--) {
            if (occupied.get(i)[0] == startMs && occupied.get(i)[1] == endMs) occupied.remove(i);
        }
    }

    private boolean containsSpan(long startMs, long endMs) {
        for (int i = 0; i < occupied.size(); i++) {
            if (occupied.get(i)[0] == startMs && occupied.get(i)[1] == endMs) return true;
        }
        return false;
    }

    private void trimDisplay() {
        int guard = 0;
        while (plans.size() > MAX_DISPLAY && guard++ < plans.size() + 2) {
            int drop = -1;
            for (int i = 0; i < plans.size(); i++) {
                if (!plans.get(i).pending()) { drop = i; break; }
            }
            if (drop < 0) break;
            plans.remove(drop);
        }
    }
}
