package dev.ody.pikmincontrol;

/** Durable request identity for explicit lifecycle rounds. Completed requests
 * are history, not current provider/consumer authority. */
final class EmulatorGpsRequests {
    interface Store {
        String get(String key);
        void pending(String request) throws Exception;
        // One atomic durable update: retain completed wire and remove active.
        void complete(String request,String wire) throws Exception;
    }
    static void valid(String request){
        if(request==null||!request.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Lifecycle request identity required");
    }
    static String choose(Store store,String activeRequest,String newRequest)throws Exception{
        String pending=store.get("pending");
        if(!pending.isEmpty())valid(pending);
        if(activeRequest!=null){
            valid(activeRequest);
            if(newRequest!=null||(!pending.isEmpty()&&!pending.equals(activeRequest)))
                throw new IllegalStateException("Original lifecycle unfinished; resume it first");
            pending=activeRequest;
        }else if(newRequest!=null){
            valid(newRequest);
            if(!pending.isEmpty()&&store.get("completed."+pending).isEmpty())
                throw new IllegalStateException("Unknown prior request retained; no new round");
            if(!store.get("completed."+newRequest).isEmpty())
                throw new IllegalStateException("New round must have a new identity");
            pending=newRequest;
        }else if(pending.isEmpty())throw new IllegalStateException("No prior lifecycle to resume");
        store.pending(pending);
        if(!pending.equals(store.get("pending")))throw new IllegalStateException("Request readback differs; no effect");
        return pending;
    }
    static boolean completed(Store store,String request){
        valid(request);
        if(!request.equals(store.get("pending")))throw new IllegalStateException("Request is not the retained pending request");
        String wire=store.get("completed."+request);
        if(wire.isEmpty())return false;
        if(!store.get("entry").isEmpty())throw new IllegalStateException("Completed and active lifecycle conflict; retained");
        return true;
    }
    static void complete(Store store,String request,String wire)throws Exception{
        valid(request);
        if(wire==null||wire.isEmpty()||wire.length()>4096||!request.equals(store.get("pending"))||!wire.equals(store.get("entry")))
            throw new IllegalStateException("Exact restored journal required for archive");
        String old=store.get("completed."+request);
        if(!old.isEmpty()&&!old.equals(wire))throw new IllegalStateException("Completed history differs; retained");
        store.complete(request,wire);
        if(!wire.equals(store.get("completed."+request))||!store.get("entry").isEmpty())
            throw new IllegalStateException("Archive readback differs; no new round");
    }
    private EmulatorGpsRequests(){}
}
