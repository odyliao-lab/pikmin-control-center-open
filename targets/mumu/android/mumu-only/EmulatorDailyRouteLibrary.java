package dev.ody.pikmincontrol;

import java.util.*;

/** Three private, selectable configuration candidates. Import/selection never
 * changes an installed configuration or grants acceptance. A selected route
 * retains its own exact route binding and must use the normal acceptance UI. */
final class EmulatorDailyRouteLibrary {
    static final String HEADER="route-library-v1\n";
    static final int MAX_BYTES=1048576; // Three individually bounded 13-segment city configurations, base64 envelope.
    static final String[] CITIES={"taipei","osaka","seoul"};
    private final List<EmulatorDailyConfiguration> candidates;
    EmulatorDailyRouteLibrary(List<EmulatorDailyConfiguration> inputs) {
        if(inputs==null||inputs.size()!=3)bad();
        candidates=Collections.unmodifiableList(new ArrayList<>(inputs));
        Set<String> routes=new HashSet<>();EmulatorDailyConfiguration first=candidates.get(0);
        if(first==null)bad();
        for(int i=0;i<candidates.size();i++){
            EmulatorDailyConfiguration next=candidates.get(i);
            if(next==null||next.created!=first.created||!identity(first.binding).equals(identity(next.binding))||!routes.add(next.binding.routeSha))bad();
            if(!next.city.isEmpty()&&!CITIES[i].equals(next.city))bad();
        }
    }
    private static String identity(EmulatorDailyRunner.Binding b){
        return b.device+"\t"+b.boot+"\t"+b.pid+"\t"+b.account+"\t"+b.sessionEpoch+"\t"+b.coreSha+"\t"+b.apkSha+"\t"+b.kmh;
    }
    EmulatorDailyConfiguration selected(String city){
        for(int i=0;i<CITIES.length;i++)if(CITIES[i].equals(city))return candidates.get(i);
        bad();return null;
    }
    String encode(){
        StringBuilder body=new StringBuilder(HEADER);
        for(int i=0;i<CITIES.length;i++)body.append(CITIES[i]).append('\t').append(EmulatorDailyRunner.base64(candidates.get(i).encode())).append('\n');
        String raw=body+"sha256\t"+EmulatorDailyRunner.digest(body.toString())+"\n";
        if(raw.length()>MAX_BYTES)bad();return raw;
    }
    static EmulatorDailyRouteLibrary decode(String raw,long now){
        if(raw==null||raw.length()>MAX_BYTES||raw.indexOf('\r')>=0||!raw.startsWith(HEADER))bad();
        String[] rows=raw.split("\n",-1);if(rows.length!=6||!rows[5].isEmpty())bad();
        String body=rows[0]+"\n"+rows[1]+"\n"+rows[2]+"\n"+rows[3]+"\n";
        if(!rows[4].equals("sha256\t"+EmulatorDailyRunner.digest(body)))bad();
        List<EmulatorDailyConfiguration> inputs=new ArrayList<>();
        for(int i=0;i<CITIES.length;i++){
            String[] f=rows[i+1].split("\t",-1);if(f.length!=2||!f[0].equals(CITIES[i]))bad();
            inputs.add(EmulatorDailyConfiguration.decode(EmulatorDailyRunner.unbase64(f[1]),now));
        }
        EmulatorDailyRouteLibrary library=new EmulatorDailyRouteLibrary(inputs);
        if(!library.encode().equals(raw))bad();return library;
    }
    private static void bad(){throw new IllegalArgumentException("Three sealed same-identity private city candidates required; not acceptance or replacement");}
}
