package dev.ody.pikmincontrol;

/** Maps engine views to UI copy. Never loads the store itself. */
final class StepUiAdapter {
    static boolean writesDisabled(StepView view) {
        return view == null || StepView.STORE_ERROR.equals(view.phase);
    }

    static String statusLine(StepView view) {
        if (view == null) return StepView.phaseLabel(StepView.STORE_ERROR);
        String line = StepView.phaseLabel(view.phase) + "\n" + view.detail;
        if (view.lastOpDetail.length() > 0 && !view.lastOpDetail.equals(view.detail))
            line = line + "\n上次：" + view.lastOpDetail;
        return line;
    }

    static String settingsNote(StepView view, StepSettings settings) {
        if (writesDisabled(view)) return "儲存失敗，設定未套用，已禁止寫入。";
        if (settings == null) return "設定未知。";
        return "目標 " + settings.target;
    }

    static final class Presented {
        final String phase;
        final String detail;
        final Long allSources;
        final Long thisApp;
        final String opKind;
        final String lastOpDetail;
        Presented(StepView view) {
            this.phase = view == null ? StepView.STORE_ERROR : view.phase;
            this.detail = view == null ? "" : view.detail;
            this.allSources = view == null ? null : view.allSourcesToday;
            this.thisApp = view == null ? null : view.thisAppToday;
            this.opKind = view == null ? "" : view.opKind;
            this.lastOpDetail = view == null ? "" : view.lastOpDetail;
        }
    }

    static Presented present(StepView view) { return new Presented(view); }
}
