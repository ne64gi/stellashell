package net.fuyumori.stellashell.core.launch;

import java.util.Objects;
import java.util.function.Supplier;

/** Pure routing/geometry policy. Ordinary phone launches never request workspace facts. */
public final class AppLaunchDecision {
    public enum Choice { AUTO, PRIMARY, FLOATING }
    public enum Route { PUBLIC_LAUNCHER, BRIDGE_PROFILE, ANDROID_FULLSCREEN, BRIDGE_REQUIRED }
    public static final class Request {
        public final String component;
        public final int displayId;
        public final boolean newWindow;
        public final Choice choice;
        public Request(String component,int displayId,boolean newWindow,Choice choice){
            this.component=component;this.displayId=displayId;this.newWindow=newWindow;
            this.choice=Objects.requireNonNull(choice);
        }
    }
    public static final class Inputs {
        public final boolean standardPhone,compact,defaultFloating,bridgeReady;
        public final AppLaunchProfile.Plan planned;
        public final String resolvedComponent;
        public final int physicalWidth,physicalHeight,left,top,right,bottom;
        public Inputs(boolean standardPhone,boolean compact,boolean defaultFloating,boolean bridgeReady,
                AppLaunchProfile.Plan planned,String resolvedComponent,int physicalWidth,int physicalHeight,
                int left,int top,int right,int bottom){
            this.standardPhone=standardPhone;this.compact=compact;this.defaultFloating=defaultFloating;
            this.bridgeReady=bridgeReady;this.planned=Objects.requireNonNull(planned);
            this.resolvedComponent=Objects.requireNonNull(resolvedComponent);
            this.physicalWidth=physicalWidth;this.physicalHeight=physicalHeight;
            this.left=left;this.top=top;this.right=right;this.bottom=bottom;
        }
    }
    public final Route route;
    public final AppLaunchProfile.Plan plan;
    public final boolean floating;
    public final String resolvedComponent;
    private AppLaunchDecision(Route route,AppLaunchProfile.Plan plan,boolean floating,String resolved){
        this.route=route;this.plan=plan;this.floating=floating;resolvedComponent=resolved;
    }
    public static AppLaunchDecision choose(Request request,boolean basicHome,Supplier<Inputs> workspace){
        Objects.requireNonNull(request);
        if(basicHome&&request.choice!=Choice.FLOATING)
            return new AppLaunchDecision(Route.PUBLIC_LAUNCHER,null,false,"");
        Inputs facts=Objects.requireNonNull(workspace.get());
        boolean floating=request.choice==Choice.AUTO?facts.defaultFloating:request.choice==Choice.FLOATING;
        AppLaunchProfile.Plan plan=facts.planned;
        if((facts.standardPhone&&request.choice==Choice.FLOATING)||(facts.compact&&floating)){
            int width=facts.right-facts.left,height=facts.bottom-facts.top;
            plan=new AppLaunchProfile.Plan(AppLaunchProfile.Mode.WINDOWED,facts.left+width/6,facts.top+height/6,
                    facts.right-width/6,facts.bottom-height/6);
        }else if(facts.compact){
            plan=new AppLaunchProfile.Plan(AppLaunchProfile.Mode.FULLSCREEN,0,0,facts.physicalWidth,facts.physicalHeight);
        }
        Route route=facts.bridgeReady?Route.BRIDGE_PROFILE:
                request.newWindow||plan.windowingMode!=1?Route.BRIDGE_REQUIRED:Route.ANDROID_FULLSCREEN;
        return new AppLaunchDecision(route,plan,floating,facts.resolvedComponent);
    }
}
