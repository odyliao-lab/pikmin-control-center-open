package dev.ody.pikmincontrol;

/** Framed, bounded reads in one shell. Not an atomic snapshot of native writers. */
final class ProbeSnapshot {
    private ProbeSnapshot() {}
    /** Preserve framing whitespace; scalar root helpers that trim output are unsafe here. */
    static String[] checkedSections(BoundedCommand.Result read, int count) {
        if (read == null || !read.ok || read.timedOut || read.truncated)
            throw new IllegalStateException("核心回報讀取未完成，請稍後重新核對");
        return sections(read.text, count);
    }

    static String command(String... reads) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < reads.length; i++) {
            out.append("printf '\\n%s\\n' '__PCC_READ_").append(i).append("__'; (")
                    .append(reads[i]).append("); ");
        }
        return out.append("printf '\\n%s\\n' '__PCC_READ_END__'").toString();
    }

    static String[] sections(String raw, int count) {
        if (raw == null || count < 1) throw new IllegalStateException("Incomplete probe read");
        String normalized = raw.replace("\r", "");
        String[] result = new String[count];
        int cursor = 0;
        for (int i = 0; i < count; i++) {
            String marker = "\n__PCC_READ_" + i + "__\n";
            int start = normalized.indexOf(marker, cursor);
            if (start < 0 || !normalized.substring(cursor, start).trim().isEmpty())
                throw new IllegalStateException("Incomplete probe section");
            start += marker.length();
            String next = "\n__PCC_READ_" + (i + 1 < count ? Integer.toString(i + 1) : "END") + "__\n";
            int end = normalized.indexOf(next, start);
            if (end < 0) throw new IllegalStateException("Incomplete probe terminator");
            result[i] = normalized.substring(start, end);
            cursor = end;
        }
        if (!normalized.substring(cursor).trim().equals("__PCC_READ_END__"))
            throw new IllegalStateException("Unexpected probe suffix");
        return result;
    }
}
