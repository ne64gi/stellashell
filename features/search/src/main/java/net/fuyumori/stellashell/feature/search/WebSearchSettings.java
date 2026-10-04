package net.fuyumori.stellashell.feature.search;

import net.fuyumori.stellashell.core.search.SearchEngine;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Sole preference window for Start's search engine; independent of shell/display settings. */
public final class WebSearchSettings implements SearchSettings {
    private final SharedPreferences preferences;
    private WebSearchSettings(SharedPreferences preferences){this.preferences=preferences;}
    public static WebSearchSettings of(Context context){return new WebSearchSettings(context.getSharedPreferences("web_search",Context.MODE_PRIVATE));}
    public static WebSearchSettings isolated(SharedPreferences preferences){return new WebSearchSettings(preferences);}
    @Override public Snapshot snapshot(){
        // One committed map, not a mixture of reads from two settings transactions.
        Map<String,?> values=preferences.getAll();
        return new Snapshot(Provider.read(text(values,"provider")),text(values,"custom_name"),text(values,"custom_template"));
    }
    private static String text(Map<String,?> values,String key){Object value=values.get(key);return value instanceof String?(String)value:"";}
    @Override public void save(Provider provider,String customName,String customTemplate){
        Objects.requireNonNull(provider);String name=SearchEngine.trim(customName),template=SearchEngine.trim(customTemplate);
        if(provider==Provider.CUSTOM)new SearchEngine(name,template);
        preferences.edit().putString("provider",provider.key).putString("custom_name",name).putString("custom_template",template).apply();
    }
    @Override public AutoCloseable observe(Consumer<Snapshot> consumer){
        Subscription subscription=new Subscription(consumer);preferences.registerOnSharedPreferenceChangeListener(subscription);return subscription;
    }
    private final class Subscription implements SharedPreferences.OnSharedPreferenceChangeListener,AutoCloseable {
        private Consumer<Snapshot> consumer;private Snapshot last=snapshot();
        Subscription(Consumer<Snapshot> consumer){this.consumer=Objects.requireNonNull(consumer);}
        @Override public void onSharedPreferenceChanged(SharedPreferences ignored,String key){
            if(consumer==null||!(key==null||key.equals("provider")||key.equals("custom_name")||key.equals("custom_template")))return;
            Snapshot current=snapshot();if(current.equals(last))return;last=current;consumer.accept(current);
        }
        @Override public void close(){preferences.unregisterOnSharedPreferenceChangeListener(this);consumer=null;}
    }
}
