package net.fuyumori.stellashell;
/** Only availability and a completed gesture; never a raw input stream. */
oneway interface IDockGestureListener {
    void onAvailability(boolean available);
    void onGesture(float xFraction, float yFraction, boolean towardRight, int rotation);
}
