package net.fuyumori.stellashell.core.display;

/** Normal HOME is independent of optional, primary-display desktop capabilities. */
public final class HomeMode {
    private HomeMode(){}
    public static boolean extensions(boolean enabled,boolean primary,boolean bridge,boolean overlay){
        return enabled&&primary&&bridge&&overlay;
    }
}
