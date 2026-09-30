package net.fuyumori.stellashell;

/** Presentation policy is independent of display backend and transient IME size. */
final class ShellPresentation {
    static boolean compact(String choice,float widthDp,float heightDp){
        if("desktop".equals(choice))return false;
        if("compact".equals(choice))return true;
        return !(Math.min(widthDp,heightDp)>=600 || (widthDp>=960 && heightDp>=480));
    }
}
