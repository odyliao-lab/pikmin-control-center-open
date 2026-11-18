package dev.ody.pikmincontrol;

/** New saved-JoyStick mode, not a rename/migration of the old coordinate itinerary. */
final class EmulatorSavedRoutePlan {
    enum City {
        TAIPEI("Taipei-01"), SEOUL("Seoul-01"), TOKYO("Tokyo-01");
        final String savedName;City(String name){savedName=name;}
    }
    enum Loop { RETURN_TO_START }
    static final double KMH=19.0;
    final City city;final Loop loop=Loop.RETURN_TO_START;
    EmulatorSavedRoutePlan(City city){if(city==null)throw new IllegalArgumentException("Explicit saved city required");this.city=city;}
    String savedName(){return city.savedName;}
    String encode(){return "v1-saved-joystick\t"+savedName()+"\tRETURN_TO_START\t19.0\t06:00\t04:00\t03:30\tend\n";}
    static EmulatorSavedRoutePlan decode(String wire){
        for(City city:City.values()){EmulatorSavedRoutePlan p=new EmulatorSavedRoutePlan(city);if(p.encode().equals(wire))return p;}
        throw new IllegalArgumentException("Only exact approved saved-route names/loop/schedule accepted; no path/coordinates/import");
    }
}
