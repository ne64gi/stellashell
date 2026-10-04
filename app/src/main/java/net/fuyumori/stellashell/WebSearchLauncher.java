package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.search.SearchEngine;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** Explicit user action only. Never falls back to another display or bypasses Android resolution. */
final class WebSearchLauncher {
    static boolean open(Context context,int display,SearchEngine engine,String query){
        if(engine==null||SearchEngine.trim(query).isEmpty())return false;
        try{
            Displays.requireUiTarget(context,display);
            Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse(engine.url(query)))
                    .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent,ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());
            return true;
        }catch(RuntimeException error){
            // Do not persist the user's query or URL as a diagnostic error.
            Ui.message(context,context.getString(net.fuyumori.stellashell.feature.search.R.string.web_search_open_failed));return false;
        }
    }
}
