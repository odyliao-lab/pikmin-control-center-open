package dev.ody.pikmincontrol;

import android.content.Context;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

final class StepAndroidStore implements StepStore {
    private static final Object PROCESS_LOCK = new Object();
    private final AtomicFile file;

    StepAndroidStore(Context context) {
        this.file = new AtomicFile(new File(context.getApplicationContext().getFilesDir(), "step-ledger.properties"));
    }

    public Object transactionLock() { return PROCESS_LOCK; }

    public StepLoadResult load() {
        synchronized (PROCESS_LOCK) {
            File base = file.getBaseFile();
            File bak = new File(base.getPath() + ".bak");
            if (!base.exists() && !bak.exists()) return StepLoadResult.missing();
            try (InputStream in = file.openRead()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (bytes.size() + n > 1_048_576) throw new java.io.IOException("step snapshot too large");
                    bytes.write(buffer, 0, n);
                }
                String raw = bytes.toString("UTF-8");
                if (raw.isEmpty()) return StepLoadResult.unreadable("empty-file");
                return StepLoadResult.valid(raw);
            } catch (Exception error) {
                return StepLoadResult.unreadable(error.getClass().getSimpleName());
            }
        }
    }

    public void save(String snapshot) {
        synchronized (PROCESS_LOCK) {
            FileOutputStream out = null;
            try {
            byte[] bytes = (snapshot == null ? "" : snapshot).getBytes("UTF-8");
            if (bytes.length > 1_048_576) throw new StepStoreException("snapshot-too-large");
            out = file.startWrite();
            out.write(bytes);
            file.finishWrite(out);
            } catch (Exception error) {
                if (out != null) file.failWrite(out);
                throw new StepStoreException("save-failed", error);
            }
        }
    }
}
