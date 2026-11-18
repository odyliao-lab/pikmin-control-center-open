package dev.ody.pikmincontrol;
import android.content.Context;
import android.content.pm.*;
import android.os.Looper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** MuMu worker-only GPS fence entry. Missing signed actor asset refuses. This
 * serializes cooperating callers, NOT provider drain or ALL writer admission. */
final class EmulatorGpsFence {
    private static final String PACKAGE="dev.ody.pikmincontrol.mumu";
    private static final String CERT="47457c8438477d9d187a900dc11792871cb82a659b76cd0d9cc7b57300933de2";
    private static final EmulatorGpsFenceCoordinator coordinator=new EmulatorGpsFenceCoordinator();
    private static EmulatorGpsFenceCoordinator.Ports lastPorts;
    private static EmulatorGpsFenceMaterial staged;private static String stageIntent;
    static void prepare(Context context,int gamePid)throws Exception{execute(context,gamePid,()->{});}
    static synchronized void execute(Context input,int gamePid,EmulatorGpsFenceCoordinator.Effect effect)throws Exception{
        EmulatorRuntime.require();
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("GPS fence must run off UI thread");
        Context context=input.getApplicationContext();
        if(coordinator.retained())throw new IOException("Original GPS session retained; stop/reconcile it before another effect");
        if(staged==null)stage(context,gamePid);
        final EmulatorGpsFenceMaterial material=staged;
        // Re-authenticate the current signed declaration before every action.
        EmulatorGpsFenceMaterial fresh=material(context,material.root);
        if(fresh.appCode!=material.appCode||fresh.size!=material.size||!fresh.sha.equals(material.sha))throw new SecurityException("GPS material changed");
        lastPorts=new EmulatorGpsFenceCoordinator.Ports(){
            public Process launch(String nonce)throws Exception{return new ProcessBuilder("su","-mm","-c",material.launch(gamePid,nonce)).start();}
            public boolean rootAbsent(int pid)throws Exception{
                BoundedCommand.Result r=BoundedCommand.root("test ! -e /proc/"+pid+" && printf gps-exited",3,128);
                return r!=null&&r.ok&&!r.truncated&&!r.timedOut&&r.exitCode!=null&&r.exitCode==0&&"gps-exited".equals(r.text);
            }
        };
        coordinator.execute(lastPorts,java.util.UUID.randomUUID().toString(),gamePid,effect);
    }
    static synchronized void stop()throws Exception{
        EmulatorRuntime.require();
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("GPS stop must run off UI thread");
        if(lastPorts!=null)coordinator.stop(lastPorts);
        else if(coordinator.retained()||stageIntent!=null)throw new IOException("Original GPS state retained without exit proof");
    }
    private static void stage(Context context,int gamePid)throws Exception{
        if(stageIntent!=null||coordinator.retained())throw new IOException("GPS stage/session retained; reconcile original outcome");
        String id=java.util.UUID.randomUUID().toString().replace("-","");
        String root="/data/local/tmp/pcc-gps-fence-stage-"+id;
        EmulatorGpsFenceMaterial material=material(context,root);
        material.launch(gamePid,java.util.UUID.randomUUID().toString()); // scope before writes
        File cache=new File(context.getCacheDir(),"pcc-gps-fence-"+id);
        String path="/data/user/0/"+PACKAGE+"/cache/pcc-gps-fence-"+id;
        if(!cache.getAbsolutePath().equals(path)||!cache.mkdir())throw new IOException("Private new GPS cache required");
        stageIntent=root; // uncertain extraction/staging retained; no new UUID retry
        File target=new File(cache,"gps-fence");if(!target.createNewFile())throw new IOException("GPS cache already exists");
        MessageDigest hash=MessageDigest.getInstance("SHA-256");long count=0;
        try(InputStream in=context.getAssets().open("gps-fence");FileOutputStream out=new FileOutputStream(target)){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){count+=n;if(count>material.size)throw new IOException("GPS asset size");hash.update(b,0,n);out.write(b,0,n);}out.getFD().sync();
        }
        if(count!=material.size||!material.sha.equals(hex(hash.digest()))||!target.setReadOnly())throw new IOException("GPS asset differs");
        BoundedCommand.Result result=BoundedCommand.root(material.stage(path,android.os.Process.myUid()),10,256);
        if(result==null||!result.ok||result.timedOut||result.truncated||result.exitCode==null||result.exitCode!=0||!"gps-staged".equals(result.text))throw new IOException("Root GPS stage unconfirmed; retained");
        staged=material;stageIntent=null;
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
    private static EmulatorGpsFenceMaterial material(Context context,String root)throws Exception{
        if(!PACKAGE.equals(context.getPackageName()))throw new SecurityException("Wrong GPS flavor");
        PackageInfo info=context.getPackageManager().getPackageInfo(PACKAGE,PackageManager.GET_SIGNING_CERTIFICATES);
        if(info.applicationInfo==null||info.applicationInfo.uid!=android.os.Process.myUid()||info.signingInfo==null||info.signingInfo.hasMultipleSigners())throw new SecurityException("GPS flavor identity");
        Signature[] signers=info.signingInfo.getApkContentsSigners();
        if(signers==null||signers.length!=1||!CERT.equals(hex(MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()))))throw new SecurityException("GPS signer differs");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream in=context.getAssets().open("gps-fence.tsv")){
            int b;while((b=in.read())!=-1){if(bytes.size()>=256)throw new IOException("GPS declaration exceeded");bytes.write(b);}
        }
        return EmulatorGpsFenceMaterial.parse(new String(bytes.toByteArray(),StandardCharsets.UTF_8),info.getLongVersionCode(),root);
    }
}
