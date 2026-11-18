package dev.ody.pikmincontrol;
public final class NectarRollbackReceiptTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    static void denied(String output)throws Exception{try{NectarModuleGateway.rollbackProofFromRead(output,"/private/files");throw new AssertionError();}catch(java.io.IOException expected){}}
    public static void main(String[] args)throws Exception {
        String valid="v1\tsuccess\taaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\tbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\tcccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\t100000\t1000\t123\t1\tc3db6ef6c490e3880ee1f94b207e4a21370220418b18daf52624d4b3ed055dea\n";
        check(NectarModuleGateway.rollbackReceiptAllows(valid));
        check(!NectarModuleGateway.rollbackReceiptAllows(valid.replace("success","pending")));
        check(!NectarModuleGateway.rollbackReceiptAllows(valid.replace("v1","v2")));
        check(!NectarModuleGateway.rollbackReceiptAllows(valid.substring(0,valid.length()-1)));
        check(!NectarModuleGateway.rollbackReceiptAllows(valid+"\n"));
        check(!NectarModuleGateway.rollbackReceiptAllows(valid.replace("100000","0100000")));
        check(!NectarModuleGateway.rollbackReceiptAllows(valid.replace("\t123\t","\t2147483648\t")));
        check(!NectarModuleGateway.rollbackReceiptAllows(valid.replace("\t1000\t","\t0\t")));
        check(!NectarModuleGateway.rollbackReceiptAllows(valid.replace("success","SUCCESS")));
        check(!NectarModuleGateway.rollbackReceiptAllows(valid.replace('a','d')));
        check(!NectarModuleGateway.rollbackReceiptAllows(null));
        check(NectarModuleGateway.rollbackProofFromRead("ABSENT\t1:2:3\n","/private/files").contains("test ! -e"));
        String header="PRESENT\t1:2:3\t1:4:"+valid.length()+"\n";
        check(NectarModuleGateway.rollbackProofFromRead(header+valid,"/private/files").contains("sha256sum"));
        denied("ABSENT\t1:2:3\nextra");denied(header+valid.replace("success","pending"));denied("PRESENT\t1:2:3\t1:4:1\n"+valid);
        denied("ABSENT\t$(exit):2:3\n");denied("ERROR\n");
        if(args.length==2){
            java.nio.file.Path out=java.nio.file.Paths.get(args[0]);String directory=args[1];
            java.nio.file.Files.write(out.resolve("valid-receipt.tsv"),valid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.write(out.resolve("read-command.sh"),NectarModuleGateway.rollbackReadCommand(directory).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }else if(args.length==4&&args[0].equals("proof")){
            String observed=new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(args[1])),java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.write(java.nio.file.Paths.get(args[3]),NectarModuleGateway.rollbackProofFromRead(observed,args[2]).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        System.out.println("PASS: nectar rollback strict receipt/proof parser");
    }
}
