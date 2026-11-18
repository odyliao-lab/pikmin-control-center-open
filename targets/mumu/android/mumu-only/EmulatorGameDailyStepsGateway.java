package dev.ody.pikmincontrol;

/** Passive MuMu observation only. Binding is held by the caller, not read from packet. */
final class EmulatorGameDailyStepsGateway {
    private static final String ROOT="/data/user/0/com.nianticlabs.pikmin/files";
    static EmulatorGameDailySteps.Observation read(EmulatorDailyRunner.Binding expected)throws Exception {
        if(expected==null)throw new IllegalArgumentException("Independent runtime binding required");
        String identity="pidof com.nianticlabs.pikmin && cat /proc/sys/kernel/random/boot_id";
        String raw=GpsModuleGateway.farmRead(ProbeSnapshot.command(identity,command(),identity));
        return decode(raw,expected,System.currentTimeMillis());
    }
    static String command() {
        String leaf=ROOT+"/emulator_game_daily_steps.tsv";
        return "set -e; test -d "+ROOT+" && test ! -L "+ROOT+" && g=$(stat -c %u "+ROOT+") && "
            +"case \"$g\" in ''|*[!0-9]*) exit 1;; esac && test \"$g\" -ge 10000 && "
            +"test ! -L "+leaf+" && test -f "+leaf+" && exec 3<"+leaf+" && "
            +"IFS=' ' read -r held_pid held_stat < /proc/self/stat && "
            +"case \"$held_pid\" in ''|*[!0-9]*) exit 1;; esac && p=/proc/$held_pid/fd/3 && "
            +"test \"$(stat -Lc %u:%g:%a:%h $p)\" = \"$g:$g:600:1\" && "
            +"n=$(stat -Lc %s $p) && case \"$n\" in ''|*[!0-9]*) exit 1;; esac && "
            +"test \"$n\" -gt 0 && test \"$n\" -le 2048 && "
            +"b=$(stat -Lc %d:%i:%s:%Y:%Z:%u:%g:%a:%h $p) && raw=$(cat <&3) && "
            +"test \"$n\" -eq $(( ${#raw} + 1 )) && "
            +"test \"$(stat -Lc %d:%i:%s:%Y:%Z:%u:%g:%a:%h $p)\" = \"$b\" && "
            +"test ! -L "+leaf+" && test -f "+leaf+" && "
            +"test \"$(stat -c %d:%i:%s:%Y:%Z:%u:%g:%a:%h "+leaf+")\" = \"$b\" && "
            +"printf '%s\\n' \"$raw\"";
    }
    static EmulatorGameDailySteps.Observation decode(String raw,EmulatorDailyRunner.Binding expected,long now) {
        if(expected==null || raw==null || raw.length()>4096 || raw.indexOf('\r')>=0)bad();
        String[] sections=new String[3];int cursor=0;
        for(int i=0;i<3;i++) {
            String marker="\n__PCC_READ_"+i+"__\n";
            if(!raw.startsWith(marker,cursor))bad();
            int start=cursor+marker.length();
            String next="\n__PCC_READ_"+(i<2?Integer.toString(i+1):"END")+"__\n";
            int end=raw.indexOf(next,start);if(end<0)bad();
            sections[i]=raw.substring(start,end);cursor=end;
        }
        if(!raw.substring(cursor).equals("\n__PCC_READ_END__\n"))bad();
        String identity=expected.pid+"\n"+expected.boot+"\n";
        if(!sections[0].equals(identity) || !sections[2].equals(identity))bad();
        return EmulatorGameDailySteps.read(sections[1],expected,now);
    }
    private static void bad(){throw new IllegalStateException("Complete same-boot/PID game-counter transport required");}
}
