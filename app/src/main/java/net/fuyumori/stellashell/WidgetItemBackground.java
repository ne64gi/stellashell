package net.fuyumori.stellashell;

import android.app.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.util.Locale;
import java.util.function.BiConsumer;

final class WidgetItemBackground {
    static int color(int rgb,int opacity){return (rgb&0xffffff)|(Math.round(Math.max(0,Math.min(100,opacity))*255f/100)<<24);}
    static AlertDialog show(Activity a,int rgb,int opacity,BiConsumer<Integer,Integer> save){
        LinearLayout form=Ui.column(a);form.setPadding(Ui.dp(a,20),Ui.dp(a,12),Ui.dp(a,20),0);
        form.addView(Ui.text(a,a.getString(R.string.appearance_background)+" (#RRGGBB)",14,Ui.TEXT));
        EditText input=new EditText(a);input.setSingleLine();input.setText(String.format(Locale.ROOT,"#%06X",rgb&0xffffff));input.setContentDescription(a.getString(R.string.appearance_background));form.addView(input);
        TextView label=Ui.text(a,"",14,Ui.TEXT);form.addView(label);
        SeekBar slider=new SeekBar(a);slider.setMax(100);slider.setProgress(opacity);slider.setContentDescription(a.getString(R.string.appearance_opacity));form.addView(slider,new LinearLayout.LayoutParams(-1,Ui.dp(a,48)));
        label.setText(a.getString(R.string.widget_opacity_label,opacity));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}public void onProgressChanged(SeekBar b,int n,boolean user){label.setText(a.getString(R.string.widget_opacity_label,n));}});
        form.addView(Ui.text(a,a.getString(R.string.widget_background_note),12,Ui.MUTED));
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle(R.string.widget_item_background).setView(form).setNegativeButton(R.string.ui_cancel,null).setPositiveButton(R.string.ui_apply,null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String text=input.getText().toString().trim();if(!text.matches("#[a-fA-F0-9]{6}")){input.setError(a.getString(R.string.appearance_invalid_color));return;}
            save.accept(Color.parseColor(text),slider.getProgress());dialog.dismiss();
        }));dialog.show();return dialog;
    }
}
