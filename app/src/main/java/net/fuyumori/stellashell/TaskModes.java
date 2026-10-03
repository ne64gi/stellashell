package net.fuyumori.stellashell;

/** Android PiP (2) is distinct from Shell freeform (5), but both can return to the main view. */
final class TaskModes {
    static boolean pictureInPicture(int mode){return mode==2;}
    static boolean canReturnToMain(int mode){return pictureInPicture(mode)||mode==5;}
    private TaskModes(){}
}
