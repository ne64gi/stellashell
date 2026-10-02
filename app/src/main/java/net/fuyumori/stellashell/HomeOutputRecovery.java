package net.fuyumori.stellashell;

import java.util.Collection;

/** Restore a connected saved output, without relying on the ephemeral active cache. */
final class HomeOutputRecovery {
    private boolean refreshing;
    void refresh(Runnable action){
        if(refreshing)return;
        refreshing=true;
        try{action.run();}finally{refreshing=false;}
    }
    static int target(int workspace,int preferred,Collection<Integer> connectedExternal){
        if(workspace>0&&connectedExternal.contains(workspace))return workspace;
        if(preferred>0&&connectedExternal.contains(preferred))return preferred;
        return 0;
    }
}
