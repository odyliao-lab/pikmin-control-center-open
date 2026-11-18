package dev.ody.pikmincontrol;
public final class HarvestRouteTest {
    static int checks;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
    interface Attempt {void run() throws Exception;}
    static void rejects(Attempt a)throws Exception{try{a.run();throw new AssertionError("accepted unsafe route");}catch(IllegalArgumentException|org.xml.sax.SAXException expected){checks++;}}
    public static void main(String[] args)throws Exception {
        String body="<trkpt lat='0' lon='0'/><trkpt lat='0' lon='0.001'/><trkpt lat='0' lon='0'/>";
        String xml="<gpx version='1.1'><trk><trkseg>"+body+"</trkseg></trk></gpx>";
        HarvestRoute r=HarvestRoute.gpx(xml);
        check(r.points.size()==3);check(r.meters>222&&r.meters<223);check(r.estimatedSeconds()==43);
        check(r.waypoints().equals("0.0,0.0;0.0,0.001;0.0,0.0"));
        check(HarvestRoute.gpx(xml.replace("<gpx ","<gpx xmlns='http://www.topografix.com/GPX/1/1' ")).points.size()==3);
        check(HarvestRoute.coordinates("0,0\n0,0.001\n0,0").points.size()==3);
        rejects(()->HarvestRoute.gpx(xml.replace("</trkseg>","</trkseg><trkseg>"+body+"</trkseg>")));
        rejects(()->HarvestRoute.gpx("<!DOCTYPE gpx [<!ENTITY x SYSTEM 'file:///unread'>]>"+xml));
        rejects(()->HarvestRoute.gpx(xml.replace("lon='0.001'","lon='NaN'")));
        rejects(()->HarvestRoute.gpx(xml.replace("lon='0.001'","lon='181'")));
        rejects(()->HarvestRoute.gpx(xml.replace("</gpx>","")));
        rejects(()->HarvestRoute.gpx(xml.replace("<trkseg>","<other><trkseg>").replace("</trkseg>","</trkseg></other>")));
        rejects(()->HarvestRoute.gpx("<gpx><rte/></gpx>"));
        rejects(()->HarvestRoute.coordinates("0,0"));
        rejects(()->HarvestRoute.coordinates("0,0\n0,0"));
        rejects(()->HarvestRoute.coordinates("0,0\n10,10"));
        rejects(()->HarvestRoute.coordinates("0,0\nbad\n0,0.001"));
        rejects(()->HarvestRoute.gpx(new String(new char[HarvestRoute.MAX_CHARS+1])));
        System.out.println("PASS HarvestRoute: "+checks+" ordered path/GPX/entity/size/segment checks; NOT provider movement");
    }
}
