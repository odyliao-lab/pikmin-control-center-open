package dev.ody.pikmincontrol;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** HOST-only shell selection: explicit configuration cannot fall back or turn into a skipped fixture. */
public final class ConfiguredShellTest {
    static int checks;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static void rejects(String configured){
        try{ReadinessGatewayTest.configuredSh(configured);}
        catch(AssertionError expected){
            check(expected.getMessage()!=null&&expected.getMessage().contains("PCC_TEST_SH"),"configuration failure is explicit");
            return;
        }
        throw new AssertionError("invalid configured shell was accepted");
    }
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("pcc-configured-shell-");
        try{
            rejects(null);rejects("");rejects(" ");rejects("sh");
            rejects(dir.resolve("missing-shell").toString());
            rejects(dir.toString());
            Path invalid=dir.resolve("invalid-shell.exe");
            ReadinessGatewayTest.write(invalid,"not an executable\n");
            rejects(invalid.toString());
            String extension=System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")?".exe":"";
            // A real executable that does not implement sh -c must fail its probe, not select another shell.
            File javaExe=new File(System.getProperty("java.home"),"bin/java"+extension);
            check(javaExe.isFile(),"JVM executable exists for wrong-program fixture");
            rejects(javaExe.getAbsolutePath());
            String sh=ReadinessGatewayTest.findSh();
            if(sh==null){ReadinessGatewayTest.skip("configured shell acceptance needs POSIX sh");return;}
            check(new File(sh).getAbsolutePath().equals(ReadinessGatewayTest.configuredSh(sh)),"valid absolute shell retained exactly");
            System.out.println("PASS ConfiguredShell: "+checks+" HOST checks; explicit selection fails closed, default discovery unchanged");
        }finally{ReadinessGatewayTest.deleteTree(dir);}
    }
}
