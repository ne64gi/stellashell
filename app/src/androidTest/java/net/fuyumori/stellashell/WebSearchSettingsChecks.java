package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.search.SearchEngine;
import net.fuyumori.stellashell.feature.search.WebSearchSettings;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Nonce settings only; no browser launches, production settings, or network queries. */
final class WebSearchSettingsChecks {
    static void run(Instrumentation test)throws Exception {
        Context actual=test.getTargetContext();String file="web_search_fixture_"+UUID.randomUUID();
        SharedPreferences preferences=actual.getSharedPreferences(file,Context.MODE_PRIVATE);
        WebSearchSettings settings=WebSearchSettings.isolated(preferences);List<WebSearchSettings.Snapshot> changes=new ArrayList<>();
        AutoCloseable subscription=settings.observe(changes::add);Throwable[] failure={null};
        try{
            test.runOnMainSync(()->{
                try{
                    check(settings.snapshot().provider==WebSearchSettings.Provider.NONE&&settings.snapshot().engine==null,"Default search must be off");
                    settings.save(WebSearchSettings.Provider.CUSTOM,"Fixture engine","https://example.invalid/search?q={query}");
                    check(changes.size()==1,"Multi-key save must publish one coherent search snapshot");
                    WebSearchSettings.Snapshot saved=changes.get(0);
                    check(saved.provider==WebSearchSettings.Provider.CUSTOM&&saved.engine!=null&&saved.customName.equals("Fixture engine"),"Custom snapshot was incomplete");
                    check(saved.engine.url("a & b").equals("https://example.invalid/search?q=a%20%26%20b"),"Custom URL did not encode the query");
                    Map<String,?> before=preferences.getAll();
                    try{settings.save(WebSearchSettings.Provider.CUSTOM,"Bad","intent://query/{query}");throw new AssertionError("Invalid URL was saved");}catch(IllegalArgumentException expected){}
                    check(preferences.getAll().equals(before)&&changes.size()==1,"Rejected settings changed persistence or listeners");
                    preferences.edit().putInt("unrelated",1).apply();check(changes.size()==1,"Unrelated preferences triggered search observers");
                    settings.save(WebSearchSettings.Provider.BING,saved.customName,saved.customTemplate);
                    check(settings.snapshot().engine==SearchEngine.BING&&changes.size()==2,"Preset selection failed");
                    check(saved.provider==WebSearchSettings.Provider.CUSTOM&&saved.engine.name.equals("Fixture engine"),"Old snapshot was mutated");
                    settings.save(WebSearchSettings.Provider.NONE,saved.customName,saved.customTemplate);
                    check(settings.snapshot().engine==null&&settings.snapshot().customTemplate.equals(saved.customTemplate),"Off must preserve custom configuration");
                    subscription.close();subscription.close();int count=changes.size();
                    settings.save(WebSearchSettings.Provider.GOOGLE,saved.customName,saved.customTemplate);check(changes.size()==count,"Closed subscription was notified");
                    preferences.edit().putString("provider","custom").putString("custom_template","javascript:{query}").commit();
                    check(settings.snapshot().engine==null,"Invalid stored settings must not produce a launch destination");
                    preferences.edit().putInt("provider",17).commit();check(settings.snapshot().provider==WebSearchSettings.Provider.NONE,"Wrong-typed provider must default to off");
                }catch(Throwable error){failure[0]=error;}
            });
            if(failure[0]!=null)throw new AssertionError(failure[0]);
        }finally{subscription.close();test.runOnMainSync(()->actual.deleteSharedPreferences(file));}
    }
    private static void check(boolean ok,String note){if(!ok)throw new AssertionError(note);}
}
