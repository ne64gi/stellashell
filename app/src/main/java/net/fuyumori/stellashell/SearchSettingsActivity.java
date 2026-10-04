package net.fuyumori.stellashell;

import net.fuyumori.stellashell.core.search.SearchEngine;
import net.fuyumori.stellashell.feature.search.WebSearchSettings;
import net.fuyumori.stellashell.feature.search.SearchSettings;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;

/** A small explicit-save editor for the search destination; it never sends a query. */
public final class SearchSettingsActivity extends Activity {
    private SearchSettings settings;
    private WebSearchSettings.Provider selected;
    private EditText name,url;
    private LinearLayout custom;

    static void open(Context context,int display){
        try{
            Displays.requireUiTarget(context,display);
            context.startActivity(new Intent(context,SearchSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());
        }catch(RuntimeException error){Ui.message(context,context.getString(net.fuyumori.stellashell.feature.search.R.string.web_search_settings_open_failed));}
    }
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);settings=WebSearchSettings.of(this);WebSearchSettings.Snapshot snapshot=settings.snapshot();
        selected=saved==null?snapshot.provider:WebSearchSettings.Provider.read(saved.getString("provider"));
        LinearLayout page=DashboardUi.page(this);
        page.addView(DashboardUi.action(this,getString(net.fuyumori.stellashell.feature.search.R.string.web_search_back),this::finish,false));
        DashboardUi.space(page,16);page.addView(DashboardUi.title(this,getString(net.fuyumori.stellashell.feature.search.R.string.web_search_settings_title),25));
        Ui.note(page,getString(net.fuyumori.stellashell.feature.search.R.string.web_search_settings_note));
        ScrollView scroll=new ScrollView(this);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=Ui.column(this);body.setPadding(0,0,0,Ui.dp(this,24));scroll.addView(body);
        LinearLayout card=DashboardUi.card(body);RadioGroup providers=new RadioGroup(this);card.addView(providers);
        for(WebSearchSettings.Provider provider:WebSearchSettings.Provider.values()){
            RadioButton button=new RadioButton(this);button.setId(View.generateViewId());button.setTag(provider);
            button.setText(provider==WebSearchSettings.Provider.NONE?getString(net.fuyumori.stellashell.feature.search.R.string.web_search_off):provider==WebSearchSettings.Provider.CUSTOM?getString(net.fuyumori.stellashell.feature.search.R.string.web_search_custom):provider.engine.name);
            button.setTextColor(Ui.TEXT);button.setTypeface(Appearance.face);button.setTextSize(16);button.setMinHeight(Ui.dp(this,52));
            providers.addView(button,new RadioGroup.LayoutParams(-1,-2));if(provider==selected)providers.check(button.getId());
        }
        custom=Ui.column(this);card.addView(custom);
        name=input(custom,net.fuyumori.stellashell.feature.search.R.string.web_search_custom_name,saved==null?snapshot.customName:saved.getString("custom_name"),60);
        url=input(custom,net.fuyumori.stellashell.feature.search.R.string.web_search_custom_url,saved==null?snapshot.customTemplate:saved.getString("custom_template"),2048);
        url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        Ui.note(custom,getString(net.fuyumori.stellashell.feature.search.R.string.web_search_template_note));
        providers.setOnCheckedChangeListener((group,id)->{selected=(WebSearchSettings.Provider)group.findViewById(id).getTag();customVisibility();});customVisibility();
        DashboardUi.space(page,12);page.addView(DashboardUi.action(this,getString(R.string.ui_save),this::save,true));
    }
    private EditText input(LinearLayout parent,int label,String value,int limit){
        parent.addView(Ui.text(this,getString(label),14,Ui.TEXT));EditText input=new EditText(this);
        input.setSingleLine();input.setTextColor(Ui.TEXT);input.setHintTextColor(Ui.MUTED);input.setTypeface(Appearance.face);
        input.setContentDescription(getString(label));input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});input.setText(value);
        parent.addView(input,new LinearLayout.LayoutParams(-1,Ui.dp(this,52)));return input;
    }
    private void customVisibility(){custom.setVisibility(selected==WebSearchSettings.Provider.CUSTOM?View.VISIBLE:View.GONE);}
    private void save(){
        String label=name.getText().toString(),template=url.getText().toString();
        if(selected==WebSearchSettings.Provider.CUSTOM){
            if(!SearchEngine.validName(label)){name.setError(getString(net.fuyumori.stellashell.feature.search.R.string.web_search_name_invalid));name.requestFocus();return;}
            try{new SearchEngine(label,template);}catch(IllegalArgumentException invalid){url.setError(getString(net.fuyumori.stellashell.feature.search.R.string.web_search_url_invalid));url.requestFocus();return;}
        }
        settings.save(selected,label,template);finish();
    }
    @Override protected void onSaveInstanceState(Bundle state){
        state.putString("provider",selected.key);state.putString("custom_name",name.getText().toString());state.putString("custom_template",url.getText().toString());super.onSaveInstanceState(state);
    }
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event){
        if(event.getKeyCode()==android.view.KeyEvent.KEYCODE_ESCAPE&&event.getAction()==android.view.KeyEvent.ACTION_UP){finish();return true;}
        return super.dispatchKeyEvent(event);
    }
}
