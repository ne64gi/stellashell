package net.fuyumori.stellashell;

import android.graphics.Insets;
import android.graphics.Rect;
import android.view.WindowInsets;

/** Android-framework geometry regression; runnable with app_process, without app lifecycle/input. */
public final class WorkAreaInsetsChecks {
    private static final Rect SCREEN=new Rect(0,0,1920,1080);
    private static void bounds(Rect expected,WindowInsets insets,boolean phone,String scenario){
        Rect actual=WorkArea.usableBounds(SCREEN,insets,phone);
        if(!expected.equals(actual))throw new AssertionError(scenario+": "+actual+" != "+expected);
    }
    public static void main(String[] args){
        WindowInsets hidden=new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.navigationBars(),Insets.NONE)
                .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(),Insets.of(0,0,0,64))
                .setVisible(WindowInsets.Type.navigationBars(),false)
                .setInsets(WindowInsets.Type.mandatorySystemGestures(),Insets.of(0,0,0,64))
                .setVisible(WindowInsets.Type.mandatorySystemGestures(),true).build();
        bounds(SCREEN,hidden,false,"hidden external bar with persistent gesture region");
        bounds(new Rect(0,0,1920,1016),hidden,true,"phone retains gesture clearance");
        WindowInsets shown=new WindowInsets.Builder(hidden)
                .setInsets(WindowInsets.Type.navigationBars(),Insets.of(0,0,0,64))
                .setVisible(WindowInsets.Type.navigationBars(),true).build();
        bounds(new Rect(0,0,1920,1016),shown,false,"visible external bar remains excluded");
        WindowInsets keyboard=new WindowInsets.Builder(shown)
                .setInsets(WindowInsets.Type.ime(),Insets.of(0,0,0,300))
                .setVisible(WindowInsets.Type.ime(),true).build();
        bounds(new Rect(0,0,1920,780),keyboard,false,"IME and bar use maximum, not sum");
        WindowInsets cutout=new WindowInsets.Builder(hidden)
                .setInsets(WindowInsets.Type.displayCutout(),Insets.of(24,0,0,0))
                .setVisible(WindowInsets.Type.displayCutout(),true).build();
        bounds(new Rect(24,0,1920,1080),cutout,false,"display cutout retained");
        WorkArea area=new WorkArea(SCREEN,WorkArea.usableBounds(SCREEN,hidden,false),false,43,80);
        if(area.application.bottom!=1000||area.content.bottom!=1000)
            throw new AssertionError("Taskbar workspace reservation must occur once");
        System.out.println("PASS: 6 WorkArea insets regressions (hidden/visible nav, phone, IME, cutout, taskbar)");
    }
}
