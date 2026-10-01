package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Shared Desktop/Compact footer. Badge remains hidden until an unread source exists. */
final class HubNavigation extends LinearLayout {
    private final Button widgets,notifications;
    private final TextView badge;
    HubNavigation(Context context,Runnable showWidgets,Runnable showNotifications) {
        super(context);setGravity(Gravity.CENTER);setOrientation(HORIZONTAL);
        widgets=item(R.string.hub_widgets,R.drawable.ic_hub_widgets,showWidgets);
        notifications=item(R.string.hub_notifications,R.drawable.ic_hub_notifications,showNotifications);
        addView(widgets,new LayoutParams(0,Ui.dp(context,56),1));
        FrameLayout notificationSlot=new FrameLayout(context);
        notificationSlot.addView(notifications,new FrameLayout.LayoutParams(-1,-1));
        badge=Ui.text(context,"",10,Ui.TEXT);badge.setGravity(Gravity.CENTER);
        badge.setPadding(Ui.dp(context,4),0,Ui.dp(context,4),0);
        badge.setMinWidth(Ui.dp(context,18));
        badge.setBackground(Ui.rounded(context,Ui.PANEL,9));
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams count=new FrameLayout.LayoutParams(-2,Ui.dp(context,18),Gravity.END|Gravity.TOP);
        count.setMarginEnd(Ui.dp(context,8));count.topMargin=Ui.dp(context,2);
        notificationSlot.addView(badge,count);
        LayoutParams slot=new LayoutParams(0,Ui.dp(context,56),1);slot.setMarginStart(Ui.dp(context,8));addView(notificationSlot,slot);
        setUnreadCount(0);select(false);
    }
    private Button item(int label,int icon,Runnable action) {
        Context c=getContext();Button button=Ui.toolbarButton(c,c.getString(label),action);
        button.setTextSize(12);button.setPadding(Ui.dp(c,8),Ui.dp(c,4),Ui.dp(c,8),Ui.dp(c,4));
        button.setMinWidth(0);button.setMinimumWidth(0);
        Drawable drawable=c.getDrawable(icon).mutate();drawable.setBounds(0,0,Ui.dp(c,20),Ui.dp(c,20));
        button.setCompoundDrawables(null,drawable,null,null);button.setCompoundDrawablePadding(Ui.dp(c,2));
        return button;
    }
    void select(boolean notificationPage) {
        style(widgets,!notificationPage);style(notifications,notificationPage);
    }
    private void style(Button button,boolean selected) {
        button.setSelected(selected);button.setTextColor(selected?Ui.ACCENT:Ui.MUTED);
        button.getCompoundDrawables()[1].setTint(selected?Ui.ACCENT:Ui.MUTED);
    }
    void setUnreadCount(int count) {
        count=Math.max(0,count);badge.setVisibility(count==0?GONE:VISIBLE);
        badge.setText(count>99?"99+":Integer.toString(count));
        notifications.setContentDescription(count==0?getContext().getString(R.string.hub_notifications):
            getContext().getString(R.string.hub_unread_count,count));
    }
}
