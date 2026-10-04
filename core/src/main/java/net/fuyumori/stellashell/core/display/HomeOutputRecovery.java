package net.fuyumori.stellashell.core.display;

import java.util.Collection;

/** Restore a connected saved output, without relying on the ephemeral active cache. */
public final class HomeOutputRecovery {
    public HomeOutputRecovery(){}
    private boolean refreshing;
    public void refresh(Runnable action){
        if(refreshing)return;
        refreshing=true;
        try{action.run();}finally{refreshing=false;}
    }
    public static int target(int workspace,int preferred,Collection<Integer> connectedExternal){
        if(workspace>0&&connectedExternal.contains(workspace))return workspace;
        if(preferred>0&&connectedExternal.contains(preferred))return preferred;
        return 0;
    }
}
