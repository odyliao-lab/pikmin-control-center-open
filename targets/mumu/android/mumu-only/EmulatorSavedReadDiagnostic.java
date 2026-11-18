package dev.ody.pikmincontrol;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
/** One bounded private failed-read diagnostic. NEVER a usable observation. */
final class EmulatorSavedReadDiagnostic {
    static void failed(String command,int exit,byte[] output){
        try{
            String sha=EmulatorDailyUiStore.sha(command.getBytes(StandardCharsets.UTF_8));
            android.util.Log.w("PccSavedRead","exit="+exit+" query="+sha+" bytes="+output.length);
            Path file=Paths.get("/data/user/0/dev.ody.pikmincontrol.mumu/files/saved-read-first-failure.txt");
            byte[] header=("DIAGNOSTIC-ONLY-NOT-OBSERVATION\nexit="+exit+"\nquery="+sha+"\n").getBytes(StandardCharsets.US_ASCII);
            // One fixed private file, no overwrites or history adoption.
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))return;
            try(java.nio.channels.FileChannel out=java.nio.channels.FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
                out.write(java.nio.ByteBuffer.wrap(header));
                out.write(java.nio.ByteBuffer.wrap(output,0,Math.min(output.length,2048)));out.force(true);
            }
        }catch(Exception diagnosticOnly){}
    }
}
