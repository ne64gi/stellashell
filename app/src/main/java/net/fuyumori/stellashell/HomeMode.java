package net.fuyumori.stellashell;

/** Normal HOME is independent of optional, primary-display desktop capabilities. */
final class HomeMode {
    static boolean extensions(boolean enabled,boolean primary,boolean bridge,boolean overlay){
        return enabled&&primary&&bridge&&overlay;
    }
}
