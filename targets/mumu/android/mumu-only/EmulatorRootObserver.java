package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Process;
import android.os.Looper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Background-thread-only observation lifecycle. Not a daily readiness gate.
 * Missing signed asset or unstaged Root artifacts refuses startup, no repair. */
final class EmulatorRootObserver {
    private static final String PACKAGE="dev.ody.pikmincontrol.mumu";
    private static final String CERT="47457c8438477d9d187a900dc11792871cb82a659b76cd0d9cc7b57300933de2";
    private static EmulatorObserverSession current;
    private static java.lang.Process pending;
    private static String stagedRoot;
    static synchronized void stageAndStart(Context context,int gamePid,int seconds)throws Exception {
        EmulatorRuntime.require();
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Observer staging must run off UI thread");
        if(current!=null||pending!=null||stagedRoot!=null)throw new IOException("Previous observer stage/session retained; reconcile first");
        String id=java.util.UUID.randomUUID().toString().replace("-","");
        String root="/data/local/tmp/pcc-emu-planting-test-"+id;
        EmulatorObserverMaterial declaration=material(context,root);
        // Validate scope before creating files or asking Root to stage.
        declaration.launch(gamePid,seconds);
        File cache=new File(context.getCacheDir(),"pcc-observer-"+id);
        String cachePath="/data/user/0/"+PACKAGE+"/cache/pcc-observer-"+id;
        if(!cache.getAbsolutePath().equals(cachePath)||!cache.mkdir())throw new IOException("New private observer cache required");
        extract(context,"root-observer.so",new File(cache,"apk-probe.so"),declaration.soSize,declaration.soSha);
        extract(context,"root-observer.dex",new File(cache,"classes.dex"),declaration.dexSize,declaration.dexSha);
        // Preserve material after any failed Root command. No automatic retry
        // in the same or another directory while staging outcome is unknown.
        stagedRoot=root;
        BoundedCommand.Result staged=BoundedCommand.root(declaration.stage(cachePath),10,256);
        if(!staged.ok||!staged.text.equals("observer-staged"))throw new IOException("Root observer stage unconfirmed; retained artifacts");
        start(context,root,gamePid,seconds);
    }
    static synchronized void start(Context context,String artifactRoot,int gamePid,int seconds)throws Exception {
        EmulatorRuntime.require();
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Observer startup must run off UI thread");
        if(current!=null||pending!=null)throw new IOException("Previous observer session not reconciled");
        EmulatorObserverMaterial material=material(context,artifactRoot);
        String command=material.launch(gamePid,seconds);
        // Root rechecks signed-asset hashes/metadata in the SAME launch command.
        pending=new ProcessBuilder("su","-mm","-c",command).start();
        try{
            current=new EmulatorObserverSession(pending,gamePid);pending=null;
            if(!current.awaitReady(12000))throw new IOException("Observer startup unconfirmed; retained session, no restart");
        }catch(Exception failure){closeLifetime();throw failure;}
        catch(Error failure){closeLifetime();throw failure;}
    }
    static synchronized void stop()throws Exception {
        EmulatorRuntime.require();
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Observer stop must run off UI thread");
        if(current==null){
            if(pending==null){
                if(stagedRoot!=null)throw new IOException("Observer stage retained without a session; manual reconciliation required");
                return;
            }
            closeLifetime();throw new IOException("Observer launch handle retained without Root identity; manual reconciliation required");
        }
        current.requestStop();
        if(!current.awaitLocalExit(6000))throw new IOException("Observer exit pending; retained process handle");
        int pid=current.pid();
        if(pid<=0)throw new IOException("Root observer identity unknown; manual reconciliation required");
        BoundedCommand.Result result=BoundedCommand.root("test ! -e /proc/"+pid+" && printf observer-exited",3,128);
        if(!result.ok||!result.text.equals("observer-exited"))throw new IOException("Root observer exit unconfirmed; no restart");
        boolean normal=current.normalLocalExit();current=null;stagedRoot=null;
        if(!normal)throw new IOException("Observer failed, Root exit confirmed; no automatic restart");
    }
    private static void closeLifetime(){
        if(current!=null)current.requestStop();
        if(pending!=null)try{pending.getOutputStream().close();}catch(IOException ignored){}
    }
    private static void extract(Context context,String asset,File file,long expected,String sha)throws Exception {
        if(!file.createNewFile())throw new IOException("Observer cache leaf already exists");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
        try(InputStream in=context.getAssets().open(asset);FileOutputStream out=new FileOutputStream(file)){
            byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1){count+=n;if(count>expected)throw new IOException("Observer asset exceeded declaration");digest.update(buffer,0,n);out.write(buffer,0,n);}
            out.getFD().sync();
        }
        StringBuilder actual=new StringBuilder();for(byte b:digest.digest())actual.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        if(count!=expected||!sha.equals(actual.toString())||!file.setReadOnly())throw new IOException("Observer asset bytes differ");
    }
    private static EmulatorObserverMaterial material(Context input,String root)throws Exception {
        Context context=input.getApplicationContext();
        if(!PACKAGE.equals(context.getPackageName()))throw new SecurityException("Wrong observer flavor");
        PackageInfo info=context.getPackageManager().getPackageInfo(PACKAGE,PackageManager.GET_SIGNING_CERTIFICATES);
        if(info.applicationInfo==null||info.applicationInfo.uid!=Process.myUid()||info.signingInfo==null||info.signingInfo.hasMultipleSigners())throw new SecurityException("Observer flavor identity");
        Signature[] signers=info.signingInfo.getApkContentsSigners();
        if(signers==null||signers.length!=1)throw new SecurityException("Observer signer count");
        StringBuilder digest=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()))digest.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        if(!CERT.equals(digest.toString()))throw new SecurityException("Observer signer mismatch");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream in=context.getAssets().open("root-observer.tsv")){
            int b;while((b=in.read())!=-1){if(bytes.size()>=512)throw new IOException("Observer declaration too large");bytes.write(b);}
        }
        return EmulatorObserverMaterial.parse(new String(bytes.toByteArray(),StandardCharsets.UTF_8),info.getLongVersionCode(),root);
    }
}
