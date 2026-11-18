package dev.ody.pikmincontrol;

import java.nio.charset.StandardCharsets;

/** MuMu-only transport composition. Caller holds the existing GPS actor.
 * Both original complete frames are read anew; no cached authorization/fix.
 */
final class EmulatorWalkFrame {
    static final String MARK="\n__PCC_WALK_OBSERVATION__\n";
    static String command(String runtime,String observation){
        if(runtime==null||observation==null||runtime.isEmpty()||observation.isEmpty()||runtime.contains(MARK.trim())||observation.contains(MARK.trim()))
            throw new IllegalArgumentException("walk frame commands");
        return runtime+"; printf '\\n%s\\n' '__PCC_WALK_OBSERVATION__'; "+observation;
    }
    static String[] split(BoundedCommand.Result read){
        if(read==null||!read.ok||read.timedOut||read.truncated||read.exitCode==null||read.exitCode!=0||read.text==null||read.text.indexOf('\r')>=0)
            throw new IllegalStateException("walk-frame-read-unconfirmed");
        int at=read.text.indexOf(MARK);
        if(at<0||read.text.indexOf(MARK,at+MARK.length())>=0)
            throw new IllegalStateException("walk-frame-boundary");
        String runtime=read.text.substring(0,at),observation=read.text.substring(at+MARK.length());
        if(runtime.getBytes(StandardCharsets.UTF_8).length>32768||observation.getBytes(StandardCharsets.UTF_8).length>1048576)
            throw new IllegalStateException("walk-frame-size");
        ProbeSnapshot.sections(runtime,5);
        // Native petal decoder subsequently checks the exact ten-section frame.
        ProbeSnapshot.sections(observation,10);
        return new String[]{runtime,observation};
    }
}
