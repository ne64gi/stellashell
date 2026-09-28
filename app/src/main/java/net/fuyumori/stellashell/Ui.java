package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

final class Ui {
    static final int BG = Color.rgb(16,23,37), PANEL = Color.rgb(28,38,54), TEXT = Color.rgb(231,237,245), MUTED = Color.rgb(155,169,188), ACCENT = Color.rgb(110,231,200);
    static int dp(Context c, int n) { return Math.round(n*c.getResources().getDisplayMetrics().density); }
    static GradientDrawable rounded(Context c, int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c,radius)); return d;
    }
    static TextView text(Context c, String s, int size, int color) {
        TextView t=new TextView(c);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;
    }
    static LinearLayout column(Context c) {
        LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;
    }
    static Button button(Context c, String text, Runnable action) {
        Button b=new Button(c);b.setText(text);b.setTextColor(TEXT);b.setTextSize(14);b.setAllCaps(false);
        b.setBackground(rounded(c,PANEL,12));b.setMinHeight(dp(c,48));
        b.setPadding(dp(c,16),dp(c,8),dp(c,16),dp(c,8));b.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(c,10);b.setLayoutParams(p);return b;
    }
    static void heading(LinearLayout parent,String s) {
        TextView t=text(parent.getContext(),s,25,TEXT);t.setTypeface(null,Typeface.BOLD);parent.addView(t);
    }
    static void note(LinearLayout parent,String s) {
        TextView t=text(parent.getContext(),s,14,MUTED);t.setLineSpacing(dp(parent.getContext(),4),1);
        t.setPadding(0,dp(parent.getContext(),10),0,dp(parent.getContext(),14));parent.addView(t);
    }
    static void message(Context c,String s) { Toast.makeText(c,s,Toast.LENGTH_LONG).show(); }
}
