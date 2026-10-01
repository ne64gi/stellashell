package net.fuyumori.stellashell;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

/** Opaque, readable app surfaces; desktop transparency does not weaken dashboard contrast. */
final class DashboardUi {
    static int dp(Context c,int value){return Ui.dp(c,value);}
    static void space(LinearLayout parent,int height){parent.addView(new View(parent.getContext()),new LinearLayout.LayoutParams(1,dp(parent.getContext(),height)));}
    static TextView text(Context c,String value,int size,int color){TextView v=Ui.text(c,value,size,color);v.setLineSpacing(dp(c,2),1);return v;}
    static TextView title(Context c,String value,int size){TextView v=text(c,value,size,Ui.TEXT);v.setTypeface(Appearance.face,Typeface.BOLD);return v;}
    static LinearLayout page(Activity a){
        a.getWindow().setStatusBarColor(Ui.BG);a.getWindow().setNavigationBarColor(Ui.BG);
        LinearLayout root=Ui.column(a);root.setBackgroundColor(Ui.BG);root.setFitsSystemWindows(true);
        FrameLayout frame=new FrameLayout(a);root.addView(frame,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout content=Ui.column(a);content.setPadding(dp(a,22),dp(a,22),dp(a,22),0);
        frame.addView(content,new FrameLayout.LayoutParams(-1,-1,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        frame.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{int width=Math.min(r-l,dp(a,680));FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)content.getLayoutParams();if(p.width!=width){p.width=width;content.setLayoutParams(p);}});
        a.setContentView(root);return content;
    }
    static LinearLayout card(LinearLayout parent){
        Context c=parent.getContext();LinearLayout card=Ui.column(c);card.setPadding(dp(c,20),dp(c,20),dp(c,20),dp(c,20));
        GradientDrawable background=Ui.rounded(c,Ui.PANEL,20);background.setStroke(dp(c,1),(Ui.TEXT&0xffffff)|0x18000000);card.setBackground(background);
        parent.addView(card,new LinearLayout.LayoutParams(-1,-2));return card;
    }
    static Button action(Context c,String label,Runnable click,boolean primary){
        Button b=Ui.button(c,label,click);b.setBackgroundTintList(null);b.setStateListAnimator(null);b.setMinHeight(dp(c,52));b.setTextSize(15);b.setTypeface(Appearance.face,Typeface.BOLD);
        if(primary){
            android.graphics.drawable.StateListDrawable bg=new android.graphics.drawable.StateListDrawable();
            bg.addState(new int[]{-android.R.attr.state_enabled},Ui.rounded(c,(Ui.TEXT&0xffffff)|0x22000000,14));
            bg.addState(new int[]{android.R.attr.state_pressed},Ui.rounded(c,(Ui.ACCENT&0xffffff)|0xbb000000,14));bg.addState(new int[]{},Ui.rounded(c,Ui.ACCENT,14));b.setBackground(bg);
            b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{Ui.MUTED,Appearance.foreground(Ui.ACCENT)}));
        }else{b.setBackground(Ui.toolbarBackground(c,14));b.setTextColor(Ui.TEXT);}
        b.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));return b;
    }
    static TextView chip(Context c){TextView v=text(c,"",12,Ui.MUTED);v.setGravity(Gravity.CENTER_VERTICAL);v.setMinHeight(dp(c,48));v.setPadding(dp(c,12),dp(c,9),dp(c,12),dp(c,9));v.setBackground(Ui.rounded(c,(Ui.TEXT&0xffffff)|0x0d000000,30));return v;}
    static void state(TextView chip,String label,boolean good){chip.setText(chip.getContext().getString(good?R.string.dashboard_status_on:R.string.dashboard_status_off,label));chip.setTextColor(good?Ui.ACCENT:Ui.MUTED);chip.setContentDescription(label);}
    static View row(Context c,int icon,String title,String description,Runnable action){
        LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,dp(c,14),0,dp(c,14));row.setMinimumHeight(dp(c,72));row.setBackground(Ui.toolbarBackground(c,12));
        ImageView glyph=new ImageView(c);glyph.setImageResource(icon);glyph.setImageTintList(ColorStateList.valueOf(Ui.ACCENT));glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(glyph,new LinearLayout.LayoutParams(dp(c,24),dp(c,24)));
        LinearLayout copy=Ui.column(c);copy.setPadding(dp(c,14),0,dp(c,8),0);copy.addView(title(c,title,16));if(!description.isEmpty())copy.addView(text(c,description,12,Ui.MUTED));row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=text(c,"›",24,Ui.MUTED);arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);row.addView(arrow);
        row.setFocusable(true);row.setClickable(true);row.setContentDescription(title+". "+description);row.setOnClickListener(v->action.run());return row;
    }
    static void divider(LinearLayout parent){View line=new View(parent.getContext());line.setBackgroundColor((Ui.TEXT&0xffffff)|0x16000000);parent.addView(line,new LinearLayout.LayoutParams(-1,dp(parent.getContext(),1)));}
    static void section(LinearLayout parent,String label){space(parent,24);TextView t=title(parent.getContext(),label,13);t.setTextColor(Ui.MUTED);parent.addView(t);space(parent,10);}
}
