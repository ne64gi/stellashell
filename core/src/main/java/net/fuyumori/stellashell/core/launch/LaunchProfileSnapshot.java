package net.fuyumori.stellashell.core.launch;

/** Immutable persisted launch settings and observed geometry, in absolute display pixels. */
public final class LaunchProfileSnapshot {
    public final String packageName, activityName, preferredDisplay, resolvedComponent;
    public final AppLaunchProfile.Mode launchMode, lastState;
    public final AppLaunchProfile.Size size;
    public final AppLaunchProfile.Position position;
    public final int width, height, x, y, lastWidth, lastHeight;
    public final boolean rememberBounds, hasLastBounds;

    public LaunchProfileSnapshot(AppLaunchProfile profile) {
        packageName=profile.packageName;activityName=profile.activityName;
        launchMode=profile.launchMode;lastState=profile.lastState;size=profile.size;position=profile.position;
        width=profile.width;height=profile.height;x=profile.x;y=profile.y;
        lastWidth=profile.lastWidth;lastHeight=profile.lastHeight;
        rememberBounds=profile.rememberBounds;hasLastBounds=profile.hasLastBounds;
        preferredDisplay=profile.preferredDisplay;resolvedComponent=profile.resolvedComponent;
    }

    /** Detached legacy planning value; editing it never edits this snapshot or its store. */
    public AppLaunchProfile toMutable() {
        AppLaunchProfile profile=new AppLaunchProfile(packageName+"/"+activityName);
        profile.launchMode=launchMode;profile.lastState=lastState;profile.size=size;profile.position=position;
        profile.width=width;profile.height=height;profile.x=x;profile.y=y;
        profile.lastWidth=lastWidth;profile.lastHeight=lastHeight;
        profile.rememberBounds=rememberBounds;profile.hasLastBounds=hasLastBounds;
        profile.preferredDisplay=preferredDisplay;profile.resolvedComponent=resolvedComponent;
        return profile;
    }
}
