package net.fuyumori.stellashell;

/** Keep unverified OEM paths disabled; method presence alone is not runtime proof. */
final class TaskPinSupport {
    enum Route { UNSUPPORTED, WCT, SONY_ROOT }
    static Route select(int sdk,String manufacturer,String model,boolean wct,boolean root){
        if(sdk==36&&"nubia".equalsIgnoreCase(manufacturer)&&"NX809J".equals(model))return Route.UNSUPPORTED;
        if(sdk==34&&"Sony".equalsIgnoreCase(manufacturer)&&"SOG06".equals(model)&&root)return Route.SONY_ROOT;
        return sdk>=35&&wct?Route.WCT:Route.UNSUPPORTED;
    }
    static boolean supportsDisplay(Route route,int displayId){
        // On SOG06 the secondary display's task flags/order changed without its
        // surfaces following. Only the physical main display passed pixel/input tests.
        return displayId>=0&&(route==Route.WCT||route==Route.SONY_ROOT&&displayId==0);
    }
    private TaskPinSupport(){}
}
