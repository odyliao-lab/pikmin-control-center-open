package dev.ody.pikmincontrol;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;
public final class CoreModuleProofTest {
    static int n;
    static void check(boolean value){if(!value)throw new AssertionError("module proof "+n);n++;}
    static byte[] zip(boolean old)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream out=new ZipOutputStream(bytes)) {
            for(String name:new String[]{"module.prop","service.sh","zygisk/arm64-v8a.so"}) {
                out.putNextEntry(new ZipEntry(name));String text=name.equals("module.prop")?"fixture":(old?"old-":"new-")+(name.equals("service.sh")?"service":"so");
                out.write(text.getBytes(StandardCharsets.UTF_8));out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
    static CorePair pair(boolean old,byte[] zip)throws Exception {
        return new CorePair("schema="+(old?1:2)+"\nversion="+(old?"1.4.33":"1.5.0")+"\ncode="+(old?67:68)
            +"\napp=66\nsize="+zip.length+"\nsha256="+SetupBundle.sha(zip));
    }
    public static void main(String[] args)throws Exception {
        byte[] old=zip(true),next=zip(false);CorePair a=pair(false,next),b=pair(true,old);
        String command=CoreModuleProof.knownInstalled(a,next,b,old);
        if(args.length==1&&args[0].equals("--command")){System.out.print(command);return;}
        check(command.contains("test -f"));check(command.contains("test ! -L"));
        check(command.contains("= 68 ||"));check(command.contains("= 67;"));
        check(command.contains(SetupBundle.sha("old-so".getBytes(StandardCharsets.UTF_8))));
        check(command.contains(SetupBundle.sha("new-service".getBytes(StandardCharsets.UTF_8))));
        for(String bad:new String[]{"/","/data/adb/modules/other","/data/adb/modules/pikmin_nectar_rpc/../other","; id"}) {
            boolean rejected=false;try{CoreModuleProof.paths(bad);}catch(Exception e){rejected=true;}check(rejected);
        }
        boolean rejected=false;try{CoreModuleProof.knownInstalled(b,old,a,next);}catch(Exception e){rejected=true;}check(rejected);
        System.out.println("PASS CoreModuleProof: "+n+" path/hash command checks; NOT Android root execution");
    }
}
