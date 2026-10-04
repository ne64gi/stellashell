package net.fuyumori.stellashell.core.launch;

import java.util.Objects;
import java.util.function.Consumer;

/** Commands merge only their own fields into the latest persisted snapshot. */
public final class LaunchProfileOwner {
    private final LaunchProfileStore store;
    public LaunchProfileOwner(LaunchProfileStore store) {this.store=Objects.requireNonNull(store);}
    public LaunchProfileSnapshot snapshot(String component) {
        synchronized(store.lockIdentity()){return store.read(component);}
    }
    /** Explicit stored profiles take precedence over a matching resolved alias. */
    public String requestedComponent(String canonicalComponent) {
        synchronized(store.lockIdentity()){
            if(store.contains(canonicalComponent))return canonicalComponent;
            for(String candidate:store.storedComponents())if(canonicalComponent.equals(store.read(candidate).resolvedComponent))return candidate;
            return canonicalComponent;
        }
    }
    /** Observations prefer the requested alias even when the resolved activity has its own profile. */
    public String observedComponent(String canonicalFallback,String observedComponent) {
        synchronized(store.lockIdentity()){
            for(String candidate:store.storedComponents())if(observedComponent.equals(store.read(candidate).resolvedComponent))return candidate;
            return canonicalFallback;
        }
    }
    private void update(String component,Consumer<AppLaunchProfile> command) {
        synchronized(store.lockIdentity()){
            AppLaunchProfile latest=store.read(component).toMutable();
            command.accept(latest);
            store.write(component,new LaunchProfileSnapshot(latest));
        }
    }
    public void setMode(String component,AppLaunchProfile.Mode mode) {
        Objects.requireNonNull(mode);update(component,p->p.launchMode=mode);
    }
    public void setSize(String component,AppLaunchProfile.Size size) {
        Objects.requireNonNull(size);update(component,p->p.size=size);
    }
    public void setPosition(String component,AppLaunchProfile.Position position) {
        Objects.requireNonNull(position);update(component,p->p.position=position);
    }
    public void setRememberBounds(String component,boolean remember) {
        update(component,p->p.rememberBounds=remember);
    }
    public void setCustomSize(String component,int width,int height) {
        if(width<240||width>16384||height<160||height>16384)throw new IllegalArgumentException("Invalid custom size");
        update(component,p->{p.width=width;p.height=height;p.size=AppLaunchProfile.Size.CUSTOM;});
    }
    public void setResolvedComponent(String component,String resolved) {
        Objects.requireNonNull(resolved);update(component,p->p.resolvedComponent=resolved);
    }
    /** Task observation never changes the user's next-launch choices. Coordinates stay absolute. */
    public void observe(String component,AppLaunchProfile.Mode state,int x,int y,int width,int height) {
        Objects.requireNonNull(state);
        update(component,p->{
            p.lastState=state;
            if(p.rememberBounds&&state==AppLaunchProfile.Mode.WINDOWED&&width>0&&height>0){
                p.x=x;p.y=y;p.lastWidth=width;p.lastHeight=height;p.hasLastBounds=true;
            }
        });
    }
}
