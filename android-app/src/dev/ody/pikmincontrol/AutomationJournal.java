package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Small, local-only activity history. It never contains account or game data. */
final class AutomationJournal {
    private static final String PREFS = "automation_journal";
    private static final String EVENTS = "events";
    private static final String SEPARATOR = "\u001e";
    private static final int MAX_EVENTS = 30;

    private AutomationJournal() {}

    static void record(Context context, String message) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = prefs.getString(EVENTS, "");
        List<String> events = new ArrayList<>();
        if (!stored.isEmpty()) {
            for (String event : stored.split(SEPARATOR)) if (!event.isEmpty()) events.add(event);
        }
        String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date());
        events.add(time + "　" + message);
        while (events.size() > MAX_EVENTS) events.remove(0);
        prefs.edit().putString(EVENTS, android.text.TextUtils.join(SEPARATOR, events)).apply();
    }

    static String recent(Context context, int count) {
        String stored = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(EVENTS, "");
        if (stored.isEmpty()) return "尚無自動化事件";
        String[] events = stored.split(SEPARATOR);
        StringBuilder result = new StringBuilder();
        for (int i = events.length - 1, shown = 0; i >= 0 && shown < count; i--, shown++) {
            if (result.length() > 0) result.append('\n');
            result.append(events[i]);
        }
        return result.toString();
    }
}
