package net.fuyumori.stellashell;
import org.junit.Test;
import static org.junit.Assert.*;
public class ShellPresentationTest {
 @Test public void phonesStayCompactInBothOrientations(){assertTrue(ShellPresentation.compact("auto",374,800));assertTrue(ShellPresentation.compact("auto",800,374));}
 @Test public void tabletsDoNotDependOnDisplayId(){assertFalse(ShellPresentation.compact("auto",800,1200));assertFalse(ShellPresentation.compact("auto",1200,800));}
 @Test public void desktopVirtualResolutionsRemainDesktop(){assertFalse(ShellPresentation.compact("auto",1920,1080));assertFalse(ShellPresentation.compact("auto",2560,1440));}
 @Test public void wideLowDensityDisplayCanUseDesktop(){assertFalse(ShellPresentation.compact("auto",960,540));assertTrue(ShellPresentation.compact("auto",960,400));}
 @Test public void foldingCanChangePresentation(){assertTrue(ShellPresentation.compact("auto",360,800));assertFalse(ShellPresentation.compact("auto",720,800));}
 @Test public void overrideWinsOverSize(){assertFalse(ShellPresentation.compact("desktop",320,480));assertTrue(ShellPresentation.compact("compact",1920,1080));}
}
