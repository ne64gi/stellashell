package net.fuyumori.stellashell;

/** Invalidation only: never transports task names, screenshots or stale operation authority. */
oneway interface ITaskChangeListener {
    void onChanged();
}
