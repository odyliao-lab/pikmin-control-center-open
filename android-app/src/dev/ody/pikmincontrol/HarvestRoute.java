package dev.ody.pikmincontrol;

import java.io.StringReader;
import java.util.*;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

/** Ordered path, NOT a deduplicated list of expedition targets. No sorting or synthetic jumps. */
final class HarvestRoute {
    static final int MAX_CHARS=512*1024, MAX_POINTS=2000;
    static final double SPEED_KMH=19;
    final List<Point> points;
    final double meters;
    static final class Point {
        final double lat,lng;
        Point(double lat,double lng) {
            if(!Double.isFinite(lat)||!Double.isFinite(lng)||Math.abs(lat)>90||Math.abs(lng)>180)
                throw new IllegalArgumentException("座標超出範圍");
            this.lat=lat;this.lng=lng;
        }
    }
    private HarvestRoute(List<Point> input) {
        if(input.size()<2||input.size()>MAX_POINTS)throw new IllegalArgumentException("路線須有2～2000個節點");
        points=Collections.unmodifiableList(new ArrayList<>(input));
        double length=0;
        for(int i=1;i<points.size();i++)length+=distance(points.get(i-1),points.get(i));
        if(length<1||length>50000)throw new IllegalArgumentException("路線長度須介於1公尺與50公里");
        meters=length;
    }
    long estimatedSeconds(){return (long)Math.ceil(meters/(SPEED_KMH/3.6));}
    String waypoints(){StringBuilder s=new StringBuilder();for(Point p:points){if(s.length()>0)s.append(';');s.append(p.lat).append(',').append(p.lng);}return s.toString();}
    static double distance(Point a,Point b) {
        double x=Math.sin(Math.toRadians(b.lat-a.lat)/2),y=Math.sin(Math.toRadians(b.lng-a.lng)/2);
        return 12742000*Math.asin(Math.sqrt(Math.min(1,x*x+Math.cos(Math.toRadians(a.lat))*Math.cos(Math.toRadians(b.lat))*y*y)));
    }
    static HarvestRoute coordinates(String text) {
        bounded(text);List<Point> p=new ArrayList<>();
        for(String line:text.split("\\r?\\n")) {
            if(line.trim().isEmpty())continue;
            String[] f=line.trim().split("\\s*,\\s*",-1);
            if(f.length!=2)throw new IllegalArgumentException("路線格式有誤，請每行輸入緯度,經度");
            p.add(new Point(Double.parseDouble(f[0]),Double.parseDouble(f[1])));
            if(p.size()>MAX_POINTS)throw new IllegalArgumentException("路線節點過多");
        }
        return new HarvestRoute(p);
    }
    static HarvestRoute gpx(String text) throws Exception {
        bounded(text);
        // Reject DTD/entity declarations before parsing; never resolve external resources.
        if(text.contains("<!DOCTYPE")||text.contains("<!ENTITY"))throw new IllegalArgumentException("GPX不可含外部實體或DTD");
        SAXParserFactory f=SAXParserFactory.newInstance();f.setNamespaceAware(true);
        XMLReader reader=f.newSAXParser().getXMLReader();
        reader.setFeature("http://xml.org/sax/features/external-general-entities",false);
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        reader.setEntityResolver((publicId,systemId)->{throw new SAXException("External entity rejected");});
        List<Point> points=new ArrayList<>();
        DefaultHandler handler=new DefaultHandler(){
            final List<String> path=new ArrayList<>();int segments,tracks;
            public void startElement(String uri,String local,String q,Attributes a)throws SAXException {
                String tag=local.isEmpty()?q:local;path.add(tag);
                if(path.size()>20)throw new SAXException("GPX nesting too deep");
                if(path.size()==1&&!"gpx".equals(tag))throw new SAXException("Expected GPX");
                if("trk".equals(tag)&&++tracks>1)throw new SAXException("只接受單一路線");
                if("trkseg".equals(tag)&&++segments>1)throw new SAXException("GPX含多段路線，不能自動連接缺口");
                if("rte".equals(tag)||"wpt".equals(tag))throw new SAXException("請匯入含trkseg的GPX軌跡");
                if("trkpt".equals(tag)) {
                    if(!path.equals(Arrays.asList("gpx","trk","trkseg","trkpt")))throw new SAXException("Invalid GPX track structure");
                    try{points.add(new Point(Double.parseDouble(a.getValue("lat")),Double.parseDouble(a.getValue("lon"))));}
                    catch(RuntimeException e){throw new SAXException("Invalid GPX coordinate",e);}
                    if(points.size()>MAX_POINTS)throw new SAXException("路線節點過多");
                }
            }
            public void endElement(String uri,String local,String q){path.remove(path.size()-1);}
            public void fatalError(SAXParseException e)throws SAXException{throw e;}
        };
        reader.setContentHandler(handler);reader.setErrorHandler(handler);reader.parse(new InputSource(new StringReader(text)));
        return new HarvestRoute(points);
    }
    private static void bounded(String s){if(s==null||s.isEmpty()||s.length()>MAX_CHARS)throw new IllegalArgumentException("路線檔案空白或過大");}
}
