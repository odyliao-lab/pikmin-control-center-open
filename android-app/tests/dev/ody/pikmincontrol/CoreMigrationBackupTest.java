package dev.ody.pikmincontrol;
public final class CoreMigrationBackupTest {
    static int n;
    static void check(boolean b){if(!b)throw new AssertionError("backup "+n);n++;}
    public static void main(String[] args)throws Exception {
        String id="01234567-0123-0123-0123-0123456789ab",sha=new String(new char[64]).replace('\0','a');
        if(args.length==2&&args[0].equals("--archive-command")){System.out.print(CoreMigrationBackup.archiveCommand(id,args[1]));return;}
        check(CoreMigrationBackup.path(id).equals("/data/adb/pcc-core-backups/"+id));
        check(CoreMigrationBackup.verifyArchiveCommand(id,sha,"absent").contains("test ! -e"));
        check(CoreMigrationBackup.verifyArchiveCommand(id,sha,sha).contains("/gps.tar |"));
        for(String bad:new String[]{null,"","..","../../modules",";id",id+"'",id+"/extra",id.toUpperCase()}) {
            boolean rejected=false;try{CoreMigrationBackup.path(bad);}catch(Exception e){rejected=true;}check(rejected);
        }
        for(String bad:new String[]{null,"","-","absent","$(id)",sha+";id",sha.toUpperCase()}) {
            boolean rejected=false;try{CoreMigrationBackup.verifyArchiveCommand(id,bad,"absent");}catch(Exception e){rejected=true;}check(rejected);
        }
        for(String state:new String[]{"absent","enabled","disabled"}) {
            String command=CoreMigrationBackup.archiveCommand(id,state);
            check(command.contains("-le 64"));check(command.contains("-le 4096"));
            check(command.contains("tar -cpf"));check(!command.contains("rm "));check(!command.contains(".tsv"));
            check(command.contains("/gps.tar -C")==!state.equals("absent"));
        }
        for(String bad:new String[]{null,"","unknown","enabled;id"}) {
            boolean rejected=false;try{CoreMigrationBackup.archiveCommand(id,bad);}catch(Exception e){rejected=true;}check(rejected);
        }
        System.out.println("PASS CoreMigrationBackup: "+n+" path/hash/archive command-boundary checks; NOT root backup transaction execution");
    }
}
