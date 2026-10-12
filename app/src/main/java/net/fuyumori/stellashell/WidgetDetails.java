package net.fuyumori.stellashell;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.*;
import java.util.function.IntFunction;

/** Detailed tools are modal and categorized; they never own widget state. */
final class WidgetDetails {
    private final AlertDialog dialog;
    private final ScrollView scroll;
    private final Button[] tabs=new Button[3];
    private final IntFunction<View> content;
    private int selected;
    WidgetDetails(Activity activity,String title,IntFunction<View> content,Runnable dismissed){
        this.content=content;
        LinearLayout body=Ui.column(activity),bar=new LinearLayout(activity);
        int[] labels={R.string.widget_section_geometry,R.string.widget_section_appearance,R.string.widget_section_management};
        for(int i=0;i<tabs.length;i++){
            final int tab=i;tabs[i]=Ui.toolbarButton(activity,activity.getString(labels[i]),()->select(tab));
            tabs[i].setMinWidth(0);tabs[i].setMinimumWidth(0);tabs[i].setTextSize(13);tabs[i].setPadding(0,0,0,0);
            bar.addView(tabs[i],new LinearLayout.LayoutParams(0,Ui.dp(activity,48),1));
        }
        body.addView(bar);scroll=new ScrollView(activity);body.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
        dialog=new AlertDialog.Builder(activity).setTitle(title).setView(body).setNegativeButton(R.string.ui_close,null).create();
        dialog.setOnDismissListener(d->dismissed.run());select(0);DesktopBackdrop.showDialog(activity,dialog);
    }
    private void select(int tab){selected=tab;refresh();scroll.scrollTo(0,0);}
    void refresh(){
        for(int i=0;i<tabs.length;i++){tabs[i].setSelected(selected==i);tabs[i].setTextColor(selected==i?Ui.ACCENT:Ui.MUTED);}
        scroll.removeAllViews();scroll.addView(content.apply(selected));
    }
    void close(){dialog.dismiss();}
}
