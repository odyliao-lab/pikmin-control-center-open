package dev.ody.pikmincontrol;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;

/** App-private AtomicFile only. No root, no uploads, no automatic GPS commands. */
final class SavedRouteStore {
    private static AtomicFile file(Context c){return new AtomicFile(new File(c.getFilesDir(),"saved-routes.properties"));}
    static synchronized SavedRoutes load(Context c)throws Exception {
        AtomicFile f=file(c);
        if(!f.getBaseFile().exists()&&!new File(f.getBaseFile()+".bak").exists())return new SavedRoutes(Collections.emptyList());
        Properties p=new Properties();try(InputStream in=f.openRead()){
            p.load(new ByteArrayInputStream(SetupBundle.read(in,2*1024*1024)));
        }return SavedRoutes.decode(p);
    }
    static synchronized void save(Context c,SavedRoutes routes)throws Exception {
        // Re-read before writing: never replace corrupt existing storage with an empty list.
        load(c);ByteArrayOutputStream bytes=new ByteArrayOutputStream();routes.encode().store(bytes,"Private saved routes; never upload");
        if(bytes.size()>2*1024*1024)throw new IOException("路線庫已滿，請先移除不用的路線");
        AtomicFile f=file(c);FileOutputStream out=null;
        try {out=f.startWrite();out.write(bytes.toByteArray());f.finishWrite(out);}
        catch(Exception e){if(out!=null)f.failWrite(out);throw e;}
    }
}
