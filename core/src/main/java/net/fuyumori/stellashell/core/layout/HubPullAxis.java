package net.fuyumori.stellashell.core.layout;

/** Primary-axis geometry for pulling Widgets from a Dock edge; screen coordinates in, no Android state. */
public final class HubPullAxis {
    public final String edge;
    public final boolean vertical, reverse;

    public HubPullAxis(String edge) {
        this.edge = DockPlacement.edge(edge);
        vertical = "top".equals(this.edge) || "bottom".equals(this.edge);
        reverse = "right".equals(this.edge) || "bottom".equals(this.edge);
    }

    public boolean vertical() { return vertical; }
    public boolean negative() { return reverse; }
    public float inward(float dx, float dy) { return (reverse ? -1 : 1) * (vertical ? dy : dx); }
    public float cross(float dx, float dy) { return vertical ? dx : dy; }

    /** Exposed boundary: left/top for negative inward travel, right/bottom otherwise. */
    public float leading(float start, float size) { return start + (reverse ? 0 : size); }

    public float range(float restingStart, float size, float edgeMargin, Float origin) {
        if (origin == null || !Float.isFinite(origin)) return Math.max(1, size + edgeMargin);
        float restingLeading = leading(restingStart, size);
        return Math.max(1, reverse ? origin - restingLeading : restingLeading - origin);
    }

    public float progress(float distance, float range) {
        return Float.isFinite(distance) ? Math.max(0, Math.min(1, distance / Math.max(1, range))) : 0;
    }

    /** Only apply this offset on the chosen axis; the tangential axis remains at rest. */
    public float translation(float range, float progress) { return (reverse ? 1 : -1) * range * (1 - progress); }
}
