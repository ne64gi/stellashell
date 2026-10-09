package net.fuyumori.stellashell.core.layout;

/** Shared edge-handle placement and deliberate inward-pull policy; no Android state. */
public final class EdgeDockReveal {
    private EdgeDockReveal() {}

    public enum Method {
        SINGLE_TAP("single_tap"), DOUBLE_TAP("double_tap"), SWIPE("swipe");
        private final String stored;
        Method(String stored) { this.stored = stored; }
        public String storedValue() { return stored; }
        public static Method parse(String value) {
            for (Method method : values()) if (method.stored.equals(value)) return method;
            return SWIPE;
        }
    }

    /** Swipe needs room to catch a finger; click modes retain the narrower affordance. */
    public static int handleThickness(Method method) { return method == Method.SWIPE ? 32 : 16; }

    public static boolean landscape(int width, int height) {
        return width > 0 && height > 0 && width > height;
    }

    public static int[] handle(int left, int top, int right, int bottom, String selected,
            int position, int thickness, int length, int margin) {
        String edge = DockPlacement.edge(selected);
        boolean vertical = DockPlacement.vertical(edge);
        int width = Math.min(Math.max(1, vertical ? thickness : length), Math.max(1, right-left));
        int height = Math.min(Math.max(1, vertical ? length : thickness), Math.max(1, bottom-top));
        int[] value = DockPlacement.bounds(left, top, right, bottom, width, height,
                vertical ? ("left".equals(edge) ? 0 : 100) : position,
                vertical ? position : ("top".equals(edge) ? 0 : 100));
        int inset = Math.max(0, Math.min(margin,
                vertical ? Math.max(0, right-left-width) : Math.max(0, bottom-top-height)));
        if (vertical) { value[0] += "left".equals(edge) ? inset : -inset; value[2] = value[0]+width; }
        else { value[1] += "top".equals(edge) ? inset : -inset; value[3] = value[1]+height; }
        return value;
    }

    public static int[] panel(int left, int top, int right, int bottom, String selected,
            int position, int width, int height) {
        String edge = DockPlacement.edge(selected);
        boolean vertical = DockPlacement.vertical(edge);
        return DockPlacement.bounds(left, top, right, bottom, width, height,
                vertical ? ("left".equals(edge) ? 0 : 100) : position,
                vertical ? position : ("top".equals(edge) ? 0 : 100));
    }

    /** Event-time tap recognition. No timeout jobs or callbacks survive the handle. */
    public static final class Taps {
        private final boolean doubled;
        private final float slopSquared, doubleSlopSquared;
        private final long maxPress, doubleTimeout;
        private boolean pressed, moved, candidate, second;
        private float startX, startY, lastX, lastY;
        private long startTime, lastUp;
        public Taps(Method method, float slop, float doubleSlop, long maxPress, long doubleTimeout) {
            doubled = method == Method.DOUBLE_TAP;
            slopSquared = Math.max(1, slop) * Math.max(1, slop);
            doubleSlopSquared = Math.max(1, doubleSlop) * Math.max(1, doubleSlop);
            this.maxPress = Math.max(1, maxPress);
            this.doubleTimeout = Math.max(1, doubleTimeout);
        }
        public void begin(float x, float y, long time) {
            if (!Float.isFinite(x) || !Float.isFinite(y)) { cancel(); return; }
            float dx = x-lastX, dy = y-lastY;
            second = candidate && time >= lastUp && time-lastUp <= doubleTimeout
                    && dx*dx+dy*dy <= doubleSlopSquared;
            candidate = false; pressed = true; moved = false;
            startX = x; startY = y; startTime = time;
        }
        public void move(float x, float y) {
            if (!pressed) return;
            float dx = x-startX, dy = y-startY;
            if (!Float.isFinite(dx) || !Float.isFinite(dy) || dx*dx+dy*dy > slopSquared) moved = true;
        }
        public boolean finish(float x, float y, long time) {
            if (!pressed) return false;
            move(x,y); pressed = false;
            if (moved || time < startTime || time-startTime >= maxPress) { cancel(); return false; }
            if (!doubled || second) { cancel(); return true; }
            candidate = true; lastX = x; lastY = y; lastUp = time;
            return false;
        }
        public void cancel() { pressed = false; moved = false; candidate = false; second = false; }
    }

    /** One captured gesture. Light taps, outward/diagonal movement cannot capture. */
    public static final class Pull {
        private final String edge;
        private final float slop, travel;
        private boolean active;
        private float progress;
        public Pull(String selected, float slop, float travel) {
            edge = DockPlacement.edge(selected);
            this.slop = Math.max(1, slop);
            this.travel = Math.max(1, travel);
        }
        public float update(float dx, float dy) {
            float inward = "left".equals(edge) ? dx : "right".equals(edge) ? -dx
                    : "top".equals(edge) ? dy : -dy;
            float cross = DockPlacement.vertical(edge) ? dy : dx;
            if (!Float.isFinite(inward) || !Float.isFinite(cross)) { active=false;progress=0;return -1; }
            if (!active && (inward <= slop || inward <= Math.abs(cross)*1.2f)) return -1;
            active = true;
            progress = inward > Math.abs(cross)*1.2f ? Math.max(0, Math.min(1, inward/travel)) : 0;
            return progress;
        }
        public boolean active() { return active; }
        public boolean finish(boolean canceled) { return !canceled && active && progress >= .5f; }
    }
}
