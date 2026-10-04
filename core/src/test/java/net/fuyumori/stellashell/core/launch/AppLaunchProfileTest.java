package net.fuyumori.stellashell.core.launch;
import org.junit.Test;
import static org.junit.Assert.*;
public class AppLaunchProfileTest {
 private AppLaunchProfile profile(){return new AppLaunchProfile("com.example/com.example.Main");}
 @Test public void maximizedReservesShellButFullscreenDoesNot(){AppLaunchProfile p=profile();p.launchMode=AppLaunchProfile.Mode.MAXIMIZED;AppLaunchProfile.Plan b=p.plan(1600,900,32,60,0);assertEquals(5,b.windowingMode);assertEquals(32,b.top);assertEquals(840,b.bottom);p.launchMode=AppLaunchProfile.Mode.FULLSCREEN;b=p.plan(1600,900,32,60,0);assertEquals(1,b.windowingMode);assertEquals(0,b.top);assertEquals(900,b.bottom);}
 @Test public void restoresWindowGeometry(){AppLaunchProfile p=profile();p.launchMode=AppLaunchProfile.Mode.RESTORE_LAST;p.hasLastBounds=true;p.x=130;p.y=95;p.lastWidth=900;p.lastHeight=700;AppLaunchProfile.Plan b=p.plan(1600,900,32,60,0);assertEquals(130,b.left);assertEquals(95,b.top);assertEquals(1030,b.right);assertEquals(795,b.bottom);}
 @Test public void clampsSavedWindowOnSmallerDisplay(){AppLaunchProfile p=profile();p.launchMode=AppLaunchProfile.Mode.RESTORE_LAST;p.hasLastBounds=true;p.x=1700;p.y=800;p.lastWidth=900;p.lastHeight=700;AppLaunchProfile.Plan b=p.plan(800,600,32,60,0);assertEquals(0,b.left);assertEquals(32,b.top);assertEquals(800,b.right);assertEquals(540,b.bottom);}
 @Test public void customSizeAndLastPositionAreIndependent(){AppLaunchProfile p=profile();p.size=AppLaunchProfile.Size.CUSTOM;p.width=900;p.height=700;p.position=AppLaunchProfile.Position.LAST;p.hasLastBounds=true;p.x=50;p.y=60;p.lastWidth=300;p.lastHeight=200;AppLaunchProfile.Plan b=p.plan(1600,900,32,60,0);assertEquals(50,b.left);assertEquals(60,b.top);assertEquals(900,b.right-b.left);assertEquals(700,b.bottom-b.top);}
 @Test public void restoreLastFullscreenKeepsMode(){AppLaunchProfile p=profile();p.launchMode=AppLaunchProfile.Mode.RESTORE_LAST;p.lastState=AppLaunchProfile.Mode.FULLSCREEN;assertEquals(1,p.plan(1600,900,32,60,0).windowingMode);}
 @Test public void profilesDoNotShareState(){AppLaunchProfile a=profile(),b=new AppLaunchProfile("com.other/.Main");a.width=900;a.launchMode=AppLaunchProfile.Mode.FULLSCREEN;assertEquals(800,b.width);assertEquals(AppLaunchProfile.Mode.WINDOWED,b.launchMode);}
}
