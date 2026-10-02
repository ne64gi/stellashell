package net.fuyumori.stellashell;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Shared Desktop/Compact footer. The dot means notification presence, not unread state. */
final class HubNavigation extends LinearLayout {
    private final Button widgets,notifications;
    private final View notificationDot;
    HubNavigation(Context context,Runnable showWidgets,Runnable showNotifications) {
        super(context);setGravity(Gravity.CENTER);setOrientation(HORIZONTAL);
        widgets=item(R.string.hub_widgets,R.drawable.ic_hub_widgets,showWidgets);
        notifications=item(R.string.hub_notifications,R.drawable.ic_hub_notifications,showNotifications);
        addView(widgets,new LayoutParams(0,Ui.dp(context,56),1));
        FrameLayout notificationSlot=new FrameLayout(context);
        notificationSlot.addView(notifications,new FrameLayout.LayoutParams(-1,-1));
        notificationDot=new View(context);
        notificationDot.setBackground(Ui.rounded(context,Ui.ACCENT,4));
        notificationDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams dot=new FrameLayout.LayoutParams(Ui.dp(context,8),Ui.dp(context,8),Gravity.END|Gravity.TOP);
        dot.setMarginEnd(Ui.dp(context,12));dot.topMargin=Ui.dp(context,6);
        notificationSlot.addView(notificationDot,dot);
        LayoutParams slot=new LayoutParams(0,Ui.dp(context,56),1);slot.setMarginStart(Ui.dp(context,8));addView(notificationSlot,slot);
        setNotificationPresence(false,false,null);select(false);
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
    void setNotificationPresence(boolean locked,boolean available,java.util.List<?> snapshot) {
        boolean present=!locked&&available&&snapshot!=null&&!snapshot.isEmpty();
        notificationDot.setVisibility(present?VISIBLE:GONE);
        notifications.setContentDescription(getContext().getString(present?
            R.string.hub_notifications_present:R.string.hub_notifications));
    }
}
