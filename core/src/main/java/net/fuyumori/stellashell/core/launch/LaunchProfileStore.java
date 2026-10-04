package net.fuyumori.stellashell.core.launch;

import java.util.Collections;
import java.util.Set;

/** Platform persistence port. Component keys are canonicalized by its caller. */
public interface LaunchProfileStore {
    LaunchProfileSnapshot read(String component);
    void write(String component, LaunchProfileSnapshot snapshot);
    /** Wrappers over the same backing store must return the same identity. */
    default Object lockIdentity() {return this;}
    /** Detached, read-only stored keys; reading defaults must not add keys. */
    default Set<String> storedComponents() {return Collections.emptySet();}
    default boolean contains(String component) {return storedComponents().contains(component);}
}
