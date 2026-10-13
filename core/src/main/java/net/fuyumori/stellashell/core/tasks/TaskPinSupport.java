package net.fuyumori.stellashell.core.tasks;

/** Keep unverified OEM paths disabled; method presence alone is not runtime proof. */
public final class TaskPinSupport {
    public enum Route { UNSUPPORTED, WCT, SONY_ROOT, REDMAGIC_WCT }
    public static Route select(int sdk,String manufacturer,String model,boolean wct,boolean root){
        // NX809J's ordinary freeform path passed native pixel/input and unpin checks.
        // This is not the OEM game-window (WindowReply) API or its app allowlist.
        if(sdk==36&&"nubia".equalsIgnoreCase(manufacturer)&&"NX809J".equals(model))return wct?Route.REDMAGIC_WCT:Route.UNSUPPORTED;
        if(sdk==34&&"Sony".equalsIgnoreCase(manufacturer)&&"SOG06".equals(model)&&root)return Route.SONY_ROOT;
        return sdk>=35&&wct?Route.WCT:Route.UNSUPPORTED;
    }
    public static boolean supportsDisplay(Route route,int displayId){
        // Sony's external surfaces failed verification; REDMAGIC's external path
        // is unverified. Neither may inherit the generic all-display capability.
        return displayId>=0&&(route==Route.WCT||displayId==0&&(route==Route.SONY_ROOT||route==Route.REDMAGIC_WCT));
    }
    private TaskPinSupport(){}
}
