package dev.ody.pikmincontrol;
/** Only these two internally constructed passive commands receive a 5s
 * transport budget on MuMu. Same decoders, 12s freshness, identity and framing;
 * no cached packet, retry, effect, Task or gameplay deadline is changed. */
final class EmulatorSavedDailyRead {
    static String identity()throws Exception{return read(EmulatorDailySessionIdentity.command(),"identity");}
    static String resources()throws Exception{return read(EmulatorDailyResources.command(),"resources");}
    private static String read(String command,String kind)throws Exception{
        BoundedCommand.Result r=BoundedCommand.root(command,5,131072);
        if(r==null||!r.ok||r.timedOut||r.truncated||r.exitCode==null||r.exitCode!=0){
            android.util.Log.w("PccSavedRead","passive "+kind+" unavailable; no cached/partial data accepted");
            throw new java.io.IOException("Complete passive "+kind+" read unavailable");
        }
        return r.text;
    }
}
