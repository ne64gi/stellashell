package net.fuyumori.stellashell.core.power;

/** Optional privileged capability state; base shell lifetime is deliberately not represented. */
public final class PowerSession {
    public enum Phase { OFF, WAITING, ACTIVE, RELEASING, RETRY }
    private boolean requested;
    private Phase phase;
    private long generation;
    public PowerSession(boolean requested) {this(requested,false);}
    public PowerSession(boolean requested,boolean cleanupPending) {
        this.requested=requested&&!cleanupPending;
        phase=cleanupPending?Phase.RETRY:this.requested?Phase.WAITING:Phase.OFF;
    }
    public synchronized boolean requested(){return requested;}
    public synchronized boolean active(){return requested&&phase==Phase.ACTIVE;}
    public synchronized boolean transition(){return phase==Phase.RELEASING||phase==Phase.RETRY;}
    public synchronized Phase phase(){return phase;}
    public synchronized long generation(){return generation;}
    public synchronized boolean permits(long token){return requested&&!transition()&&generation==token;}
    public synchronized boolean request(boolean value){
        if(transition()||requested==value)return false;
        requested=value;generation++;phase=value?Phase.WAITING:Phase.RELEASING;return true;
    }
    public synchronized void connected(boolean ready){
        if(requested&&!transition()){
            if(!ready&&phase==Phase.ACTIVE)generation++;
            phase=ready?Phase.ACTIVE:Phase.WAITING;
        }
    }
    public synchronized void retry(){if(!requested&&transition())phase=Phase.RELEASING;}
    public synchronized void released(boolean success){
        if(!requested)phase=success?Phase.OFF:Phase.RETRY;
    }
    /** Existing explicit intent wins; only durable legacy configuration opts in during migration. */
    public static boolean migrate(Boolean explicit, boolean legacyConfigured){
        return explicit!=null?explicit:legacyConfigured;
    }
}
