package net.fuyumori.stellashell.core.launch;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AppLaunchDecisionTest {
    private static final AppLaunchProfile.Plan PROFILE=new AppLaunchProfile.Plan(AppLaunchProfile.Mode.WINDOWED,41,53,841,653);
    private static AppLaunchDecision.Request request(AppLaunchDecision.Choice choice,boolean newWindow){
        return new AppLaunchDecision.Request("example.app/.Main",0,newWindow,choice);
    }
    private static AppLaunchDecision.Inputs inputs(boolean phone,boolean compact,boolean secondary,boolean bridge,AppLaunchProfile.Plan plan){
        return new AppLaunchDecision.Inputs(phone,compact,secondary,bridge,plan,"example.app/.Alias",1920,1080,30,80,1830,980);
    }
    @Test public void ordinaryPhoneNeverReadsWorkspaceOrRequiresBridgeEvenForExistingNewWindowChoice(){
        for(AppLaunchDecision.Choice choice:new AppLaunchDecision.Choice[]{AppLaunchDecision.Choice.AUTO,AppLaunchDecision.Choice.PRIMARY}){
            AppLaunchDecision decision=AppLaunchDecision.choose(request(choice,true),true,()->{throw new AssertionError("Workspace must not be consulted");});
            assertEquals(AppLaunchDecision.Route.PUBLIC_LAUNCHER,decision.route);assertNull(decision.plan);
        }
    }
    @Test public void explicitPhoneWindowOverridesBasicHomeAndHonorsOffsetWorkArea(){
        AppLaunchDecision decision=AppLaunchDecision.choose(request(AppLaunchDecision.Choice.FLOATING,false),true,()->inputs(true,false,false,true,PROFILE));
        assertEquals(AppLaunchDecision.Route.BRIDGE_PROFILE,decision.route);assertTrue(decision.floating);
        assertEquals(330,decision.plan.left);assertEquals(230,decision.plan.top);assertEquals(1530,decision.plan.right);assertEquals(830,decision.plan.bottom);
    }
    @Test public void compactFirstAppIsPhysicalFullscreenAndFollowingAppIsInsetWindow(){
        AppLaunchDecision first=AppLaunchDecision.choose(request(AppLaunchDecision.Choice.AUTO,false),false,()->inputs(false,true,false,true,PROFILE));
        AppLaunchDecision following=AppLaunchDecision.choose(request(AppLaunchDecision.Choice.AUTO,false),false,()->inputs(false,true,true,true,PROFILE));
        assertEquals(1,first.plan.windowingMode);assertEquals(1920,first.plan.right);assertEquals(1080,first.plan.bottom);assertFalse(first.floating);
        assertEquals(5,following.plan.windowingMode);assertEquals(330,following.plan.left);assertTrue(following.floating);
    }
    @Test public void explicitPrimaryIgnoresInferredSecondaryButNonCompactPreservesSavedPlan(){
        AppLaunchDecision compact=AppLaunchDecision.choose(request(AppLaunchDecision.Choice.PRIMARY,false),false,()->inputs(false,true,true,true,PROFILE));
        AppLaunchDecision desktop=AppLaunchDecision.choose(request(AppLaunchDecision.Choice.PRIMARY,false),false,()->inputs(false,false,true,true,PROFILE));
        assertEquals(1,compact.plan.windowingMode);assertFalse(compact.floating);
        assertSame(PROFILE,desktop.plan);assertFalse(desktop.floating);assertEquals("example.app/.Alias",desktop.resolvedComponent);
    }
    @Test public void unavailableBridgeAllowsOnlyExistingFullscreenNotNewWindow(){
        AppLaunchProfile.Plan fullscreen=new AppLaunchProfile.Plan(AppLaunchProfile.Mode.FULLSCREEN,0,0,1920,1080);
        assertEquals(AppLaunchDecision.Route.ANDROID_FULLSCREEN,AppLaunchDecision.choose(request(AppLaunchDecision.Choice.AUTO,false),false,()->inputs(false,false,false,false,fullscreen)).route);
        assertEquals(AppLaunchDecision.Route.BRIDGE_REQUIRED,AppLaunchDecision.choose(request(AppLaunchDecision.Choice.AUTO,true),false,()->inputs(false,false,false,false,fullscreen)).route);
        assertEquals(AppLaunchDecision.Route.BRIDGE_REQUIRED,AppLaunchDecision.choose(request(AppLaunchDecision.Choice.AUTO,false),false,()->inputs(false,false,false,false,PROFILE)).route);
    }
    @Test public void workspaceFailurePropagatesWithoutFallbackToPhone(){
        IllegalStateException disconnected=new IllegalStateException("Disconnected");
        try{AppLaunchDecision.choose(request(AppLaunchDecision.Choice.AUTO,false),false,()->{throw disconnected;});fail();}
        catch(IllegalStateException actual){assertSame(disconnected,actual);}
    }
}
