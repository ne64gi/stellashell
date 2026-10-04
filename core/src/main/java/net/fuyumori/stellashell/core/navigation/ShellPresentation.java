package net.fuyumori.stellashell.core.navigation;

/** Presentation policy is independent of display backend and transient IME size. */
public final class ShellPresentation {
    private ShellPresentation(){}
    public static boolean compact(String choice,float widthDp,float heightDp){
        if("desktop".equals(choice))return false;
        if("compact".equals(choice))return true;
        return !(Math.min(widthDp,heightDp)>=600 || (widthDp>=960 && heightDp>=480));
    }
}
