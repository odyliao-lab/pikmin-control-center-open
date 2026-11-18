package dev.ody.pikmincontrol;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.util.Properties;

/** Durable receipt proof beside the unchanged uncertain checkpoint. Never enables resume. */
final class BatchReconciliationStore {
    private static File path(Context c,WorkCheckpoint cp) {
        return new File(new File(c.getFilesDir(),"batch-reconciled"),cp.id+"-"+cp.properties().getProperty("checksum")+".properties");
    }
    private static Properties read(AtomicFile file)throws Exception {
        try(InputStream in=file.openRead()) {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))!=-1){if(bytes.size()+n>1_048_576)throw new IOException("對帳收據過大");bytes.write(buffer,0,n);}
            Properties p=new Properties();p.load(new ByteArrayInputStream(bytes.toByteArray()));return p;
        }
    }
    private static BatchLateReceipt.Proof verify(WorkCheckpoint cp,Properties p)throws Exception {
        if(p.size()!=9 || !"1".equals(p.getProperty("schema")) || !cp.id.equals(p.getProperty("checkpoint"))
                || !cp.properties().getProperty("checksum").equals(p.getProperty("checksum")))throw new IOException("對帳收據與原批次不符");
        Properties original=new Properties();original.load(new StringReader(p.getProperty("original")));
        WorkCheckpoint archived=WorkCheckpoint.restore(original);
        if(!archived.uncertain || !archived.properties().getProperty("checksum").equals(cp.properties().getProperty("checksum")))
            throw new IOException("原未確認批次封存不符");
        BatchLateReceipt.Proof proof=BatchLateReceipt.find(cp,p.getProperty("states"),p.getProperty("rewards"),Long.parseLong(p.getProperty("checkedAt")));
        if(proof==null || !proof.task.equals(p.getProperty("task")) || proof.receivedAt!=Long.parseLong(p.getProperty("receivedAt")))
            throw new IOException("對帳收據回讀未確認");
        return proof;
    }
    static synchronized boolean present(Context c,WorkCheckpoint cp){File f=path(c,cp);return f.exists()||new File(f+".bak").exists();}
    static synchronized BatchLateReceipt.Proof get(Context c,WorkCheckpoint cp)throws Exception {return verify(cp,read(new AtomicFile(path(c,cp))));}
    static synchronized void save(Context c,WorkCheckpoint cp,String states,String rewards,long now)throws Exception {
        WorkCheckpoint current=CheckpointStore.read(c,GpsWorkLock.BATCH);
        if(!current.properties().getProperty("checksum").equals(cp.properties().getProperty("checksum")))throw new IOException("批次紀錄已變更，未保存對帳");
        if(present(c,cp)){get(c,cp);return;}
        BatchLateReceipt.Proof proof=BatchLateReceipt.find(cp,states,rewards,now);
        if(proof==null)throw new IOException("沒有完整的同任務領回證據，保留未確認紀錄");
        Properties p=new Properties();p.setProperty("schema","1");p.setProperty("checkpoint",cp.id);
        p.setProperty("checksum",cp.properties().getProperty("checksum"));p.setProperty("task",proof.task);
        p.setProperty("receivedAt",Long.toString(proof.receivedAt));p.setProperty("checkedAt",Long.toString(now));
        p.setProperty("states",states);p.setProperty("rewards",rewards);
        StringWriter original=new StringWriter();cp.properties().store(original,"original uncertain checkpoint; NEVER replay");p.setProperty("original",original.toString());
        File target=path(c,cp),dir=target.getParentFile();
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("無法建立對帳收據目錄");
        AtomicFile file=new AtomicFile(target);FileOutputStream out=null;
        try {out=file.startWrite();p.store(out,"late exact-task reward proof; original checkpoint unchanged; NEVER replay");file.finishWrite(out);}
        catch(Exception error){if(out!=null)file.failWrite(out);throw error;}
        verify(cp,read(file));
    }
}
