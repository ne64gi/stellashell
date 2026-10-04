package net.fuyumori.stellashell.core.tasks;

/** Android PiP (2) is distinct from Shell freeform (5), but both can return to the main view. */
public final class TaskModes {
    public static boolean pictureInPicture(int mode){return mode==2;}
    public static boolean canReturnToMain(int mode){return pictureInPicture(mode)||mode==5;}
    private TaskModes(){}
}
