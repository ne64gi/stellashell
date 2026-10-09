package net.fuyumori.stellashell;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.view.Display;
import android.view.Menu;
import android.view.SubMenu;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.fuyumori.stellashell.feature.launch.PublicLauncher;

/** One app on one external screen; independent of the shell's workspace and bridge. */
final class ExternalAppLaunch {
    static final int MENU_ID=22001;
    private ExternalAppLaunch(){}

    static void addToMenu(Context context,Menu menu,String component,Runnable dismiss){
        addToMenu(context,menu,component,dismiss,()->true);
    }

    static void addToMenu(Context context,Menu menu,String component,Runnable dismiss,BooleanSupplier live){
        List<Display> targets=Displays.available(context);
        if(targets.isEmpty())return;
        if(targets.size()==1){
            Display target=targets.get(0);
            menu.add(0,MENU_ID,3,R.string.external_app_show).setOnMenuItemClickListener(item->{
                if(!live.getAsBoolean())return true;
                dismiss.run();launch(context,component,target);return true;
            });
        }else{
            SubMenu choices=menu.addSubMenu(0,MENU_ID,3,R.string.external_app_show);
            for(int i=0;i<targets.size();i++){
                Display target=targets.get(i);
                String name=target.getName();
                choices.add(context.getString(R.string.external_app_screen,i+1,name)).setOnMenuItemClickListener(item->{
                    if(!live.getAsBoolean())return true;
                    dismiss.run();launch(context,component,target);return true;
                });
            }
        }
    }

    private static void launch(Context context,String component,Display target){
        try{
            if(!target.isValid())throw new IllegalArgumentException("Display disconnected");
            new PublicLauncher(context).intent(component);
            ExternalAppActivity.open(context,component,target.getDisplayId());
        }catch(IllegalArgumentException disconnected){
            Ui.message(context,context.getString(R.string.ui_the_external_display_is_disconnected));
        }catch(ActivityNotFoundException missing){
            Ui.message(context,context.getString(R.string.external_app_missing));
        }catch(RuntimeException unavailable){
            Ui.message(context,context.getString(R.string.external_app_unavailable));
        }
    }
}
