package net.fuyumori.stellashell.core.settings;

import net.fuyumori.stellashell.core.launch.Policy;

public final class SettingTransaction {
    private SettingTransaction(){}
    static final String DESKTOP="force_desktop_mode_on_external_displays", FREEFORM="enable_freeform_support";
    public interface Store { String read(String key) throws Exception; void write(String key,String value) throws Exception; }
    public static void apply(Store store,String desktop,String freeform) throws Exception {
        Policy.setting(desktop);Policy.setting(freeform);
        String oldDesktop=store.read(DESKTOP),oldFreeform=store.read(FREEFORM);
        try { store.write(DESKTOP,desktop);store.write(FREEFORM,freeform); }
        catch(Exception failure) {
            // Attempt both restores even if the first restoration fails.
            try{store.write(DESKTOP,oldDesktop);}catch(Exception rollback){failure.addSuppressed(rollback);}
            try{store.write(FREEFORM,oldFreeform);}catch(Exception rollback){failure.addSuppressed(rollback);}
            throw failure;
        }
    }
}
