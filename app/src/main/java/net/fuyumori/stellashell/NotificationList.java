package net.fuyumori.stellashell;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Keyed, live-only cards. Hub still checks a fresh snapshot before performing any action. */
final class NotificationList {
    private final Context context;
    private final LinearLayout rows;
    private final Consumer<StatusBarNotification> open,dismiss;
    private final Set<String> expanded=new HashSet<>();
    private final Map<String,Section> sections=new LinkedHashMap<>();
    private View empty;
    private final NotificationScrollAnchor scrollAnchor;

    NotificationList(LinearLayout rows,Consumer<StatusBarNotification> open,Consumer<StatusBarNotification> dismiss){
        this.rows=rows;context=rows.getContext();this.open=open;this.dismiss=dismiss;
        scrollAnchor=new NotificationScrollAnchor(rows);
    }
    ArrayList<String> expandedGroups(){return new ArrayList<>(expanded);}
    void restoreExpandedGroups(List<String> keys){expanded.clear();if(keys!=null)expanded.addAll(keys);}
    private int dp(int value){return Ui.dp(context,value);}

    /** Privacy boundary: erase live content, callbacks and pending view references immediately. */
    void clear(){
        scrollAnchor.cancel();
        for(Section section:sections.values())section.clear();
        sections.clear();rows.removeAllViews();empty=null;
        // Only package/user expansion identifiers may survive a temporary lock/access loss.
    }

    void render(List<StatusBarNotification> snapshot){
        preservePosition();
        List<NotificationGroups.Group> groups=NotificationGroups.collect(snapshot);
        Set<String> present=new HashSet<>();for(NotificationGroups.Group group:groups)present.add(group.key);
        expanded.retainAll(present);
        sections.entrySet().removeIf(entry->{
            if(present.contains(entry.getKey()))return false;
            rows.removeView(entry.getValue().view);entry.getValue().clear();return true;
        });
        List<View> ordered=new ArrayList<>();
        for(NotificationGroups.Group group:groups){
            Section section=sections.get(group.key);
            if(section==null){section=new Section(group);sections.put(group.key,section);}
            section.bind(group);ordered.add(section.view);
        }
        if(groups.isEmpty()){
            if(empty==null){empty=Ui.text(context,context.getString(R.string.hub_no_notifications),14,Ui.MUTED);empty.setPadding(dp(8),dp(12),dp(8),dp(12));}
            ordered.add(empty);
        }else empty=null;
        sync(rows,ordered);
    }

    /** Minute/clock changes only reformat existing live metadata; never fetch or replace cards. */
    void refreshTimes(){refreshTimes(System.currentTimeMillis());}
    void refreshTimes(long now){
        for(Section section:sections.values())for(Card card:section.cardViews.values()){
            String formatted=card.content.formatTime(context,now);
            if(!TextUtils.equals(card.time.getText(),formatted)){preservePosition();card.bindTime(formatted);}
        }
    }

    /** Leave already-positioned views attached, including their focus and accessibility identity. */
    private static void sync(LinearLayout parent,List<View> ordered){
        Set<View> wanted=new HashSet<>(ordered);
        for(int i=parent.getChildCount()-1;i>=0;i--)if(!wanted.contains(parent.getChildAt(i)))parent.removeViewAt(i);
        for(int i=0;i<ordered.size();i++){
            View child=ordered.get(i);
            if(parent.indexOfChild(child)==i)continue;
            if(child.getParent()==parent)parent.removeView(child);
            parent.addView(child,i);
        }
    }

    private final class Section {
        final String key,label;
        final LinearLayout view,header,cards;
        final TextView count,arrow;
        final Button more;
        final Map<String,Card> cardViews=new LinkedHashMap<>();
        NotificationGroups.Group group;
        Section(NotificationGroups.Group initial){
            key=initial.key;String app=initial.packageName;Drawable icon=null;
            PackageManager pm=context.getPackageManager();
            try{
                ApplicationInfo info=pm.getApplicationInfo(initial.packageName,0);
                app=pm.getUserBadgedLabel(pm.getApplicationLabel(info),initial.user).toString();
                icon=pm.getUserBadgedIcon(pm.getApplicationIcon(info),initial.user);
            }catch(PackageManager.NameNotFoundException|RuntimeException ignored){}
            label=app;
            view=Ui.column(context);view.setPadding(dp(6),dp(4),dp(6),dp(6));view.setBackground(Ui.rounded(context,Ui.PANEL,14));
            LinearLayout.LayoutParams box=new LinearLayout.LayoutParams(-1,-2);box.topMargin=dp(10);view.setLayoutParams(box);
            header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(8),dp(8),dp(8),dp(8));header.setMinimumHeight(dp(56));header.setBackground(Ui.toolbarBackground(context,10));header.setFocusable(true);
            if(icon!=null){ImageView image=new ImageView(context);image.setImageDrawable(icon);LinearLayout.LayoutParams iconBox=new LinearLayout.LayoutParams(dp(28),dp(28));iconBox.setMarginEnd(dp(10));header.addView(image,iconBox);}
            TextView name=Ui.text(context,label,14,Ui.ACCENT);name.setMaxLines(1);name.setEllipsize(TextUtils.TruncateAt.END);header.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            count=Ui.text(context,"",12,Ui.MUTED);count.setPadding(dp(8),0,dp(8),0);header.addView(count);
            arrow=Ui.text(context,"",16,Ui.MUTED);header.addView(arrow);
            for(int i=0;i<header.getChildCount();i++)header.getChildAt(i).setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            view.addView(header,new LinearLayout.LayoutParams(-1,-2));
            cards=Ui.column(context);view.addView(cards,new LinearLayout.LayoutParams(-1,-2));
            more=Ui.toolbarButton(context,"",this::toggle);view.addView(more,new LinearLayout.LayoutParams(-1,dp(48)));
            header.setOnClickListener(unused->toggle());
            header.setAccessibilityDelegate(new View.AccessibilityDelegate(){
                @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){
                    super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName(Button.class.getName());
                    if(group!=null&&group.items.size()>1)info.addAction(expanded.contains(key)?AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE:AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
                }
                @Override public boolean performAccessibilityAction(View host,int action,Bundle args){
                    if(group!=null&&group.items.size()>1&&action==(expanded.contains(key)?AccessibilityNodeInfo.ACTION_COLLAPSE:AccessibilityNodeInfo.ACTION_EXPAND))return host.performClick();
                    return super.performAccessibilityAction(host,action,args);
                }
            });
        }
        void toggle(){
            if(group==null||group.items.size()<2)return;
            preservePosition();if(!expanded.remove(key))expanded.add(key);bind(group);
        }
        void bind(NotificationGroups.Group current){
            group=current;boolean all=expanded.contains(key);
            String quantity=context.getResources().getQuantityString(R.plurals.hub_notification_count,current.items.size(),current.items.size());
            text(count,quantity);header.setContentDescription(label+", "+quantity);
            header.setClickable(current.items.size()>1);
            header.setStateDescription(context.getString(all?R.string.hub_group_expanded:R.string.notification_list_latest));
            text(arrow,current.items.size()>1?(all?"▾":"▸"):"");
            int visible=all?current.items.size():Math.min(1,current.items.size());
            Set<String> wanted=new HashSet<>();for(int i=0;i<visible;i++)wanted.add(current.items.get(i).getKey());
            cardViews.entrySet().removeIf(entry->{if(wanted.contains(entry.getKey()))return false;cards.removeView(entry.getValue().view);entry.getValue().clear();return true;});
            List<View> ordered=new ArrayList<>();
            for(int i=0;i<visible;i++){
                StatusBarNotification item=current.items.get(i);Card card=cardViews.get(item.getKey());
                if(card==null){card=new Card();cardViews.put(item.getKey(),card);}
                card.bind(item,label);ordered.add(card.view);
            }
            sync(cards,ordered);
            more.setVisibility(current.items.size()>1?View.VISIBLE:View.GONE);
            text(more,all?context.getString(R.string.notification_list_show_latest):context.getResources().getQuantityString(R.plurals.notification_list_more,current.items.size()-1,current.items.size()-1));
        }
        void clear(){
            group=null;for(Card card:cardViews.values())card.clear();cardViews.clear();cards.removeAllViews();
            header.setOnClickListener(null);header.setAccessibilityDelegate(null);more.setOnClickListener(null);
        }
    }

    private final class Card {
        final LinearLayout view,top,metadata;
        final TextView title,body,sender,time;
        final Button close;
        StatusBarNotification current;
        NotificationContent content;
        Card(){
            view=Ui.column(context);view.setPadding(dp(8),dp(6),dp(8),dp(12));view.setBackground(Ui.toolbarBackground(context,10));view.setLayoutParams(new LinearLayout.LayoutParams(-1,-2));
            top=new LinearLayout(context);top.setGravity(Gravity.CENTER_VERTICAL);
            title=Ui.text(context,"",16,Ui.TEXT);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
            close=Ui.toolbarButton(context,"×",()->{if(current!=null)dismiss.accept(current);});close.setContentDescription(context.getString(R.string.hub_dismiss));close.setPadding(0,0,0,0);
            view.addView(top,new LinearLayout.LayoutParams(-1,-2));
            metadata=new LinearLayout(context);metadata.setGravity(Gravity.CENTER_VERTICAL);
            sender=Ui.text(context,"",12,Ui.ACCENT);sender.setMaxLines(1);sender.setEllipsize(TextUtils.TruncateAt.END);metadata.addView(sender,new LinearLayout.LayoutParams(0,-2,1));
            time=Ui.text(context,"",12,Ui.MUTED);time.setMaxLines(1);time.setEllipsize(TextUtils.TruncateAt.END);time.setPadding(dp(8),0,0,0);metadata.addView(time,new LinearLayout.LayoutParams(-2,-2));view.addView(metadata,new LinearLayout.LayoutParams(-1,-2));
            body=Ui.text(context,"",14,Ui.MUTED);body.setMaxLines(5);body.setEllipsize(TextUtils.TruncateAt.END);view.addView(body,new LinearLayout.LayoutParams(-1,-2));
            for(TextView child:new TextView[]{title,body,sender,time})child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        void bind(StatusBarNotification item,String app){
            current=item;content=NotificationContent.from(item);
            String heading=content.title.isEmpty()?app:content.title;
            text(title,heading);text(body,content.body);body.setVisibility(content.body.isEmpty()?View.GONE:View.VISIBLE);
            text(sender,content.sender);bindTime(content.formatTime(context));
            if(item.isClearable()){if(close.getParent()==null)top.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));}
            else top.removeView(close);
            boolean actionable=item.getNotification().contentIntent!=null;
            view.setOnClickListener(actionable?unused->{if(current!=null)open.accept(current);}:null);view.setClickable(actionable);view.setFocusable(actionable);
        }
        void bindTime(String formattedTime){
            text(time,formattedTime);metadata.setVisibility(content.sender.isEmpty()&&formattedTime.isEmpty()?View.GONE:View.VISIBLE);
            String description=title.getText().toString();
            if(!content.sender.isEmpty())description+=", "+context.getString(R.string.notification_list_sender,content.sender);
            if(!formattedTime.isEmpty())description+=", "+formattedTime;
            if(!content.body.isEmpty())description+=", "+content.body;
            if(!TextUtils.equals(view.getContentDescription(),description))view.setContentDescription(description);
        }
        void clear(){
            current=null;content=null;view.setOnClickListener(null);close.setOnClickListener(null);view.setContentDescription(null);
            for(TextView child:new TextView[]{title,body,sender,time})child.setText("");
        }
    }
    private static void text(TextView view,String value){if(!TextUtils.equals(view.getText(),value))view.setText(value);}

    private void preservePosition(){scrollAnchor.preserve(this::readingCandidates);}

    /** Renderer owns visual reading order; the anchor helper never knows about groups/content. */
    private List<View> readingCandidates(){
        List<View> candidates=new ArrayList<>();
        for(int i=0;i<rows.getChildCount();i++){
            View child=rows.getChildAt(i);Section section=null;
            for(Section candidate:sections.values())if(candidate.view==child){section=candidate;break;}
            if(section==null)continue;
            candidates.add(section.header);
            for(int j=0;j<section.cards.getChildCount();j++)candidates.add(section.cards.getChildAt(j));
            if(section.more.getVisibility()==View.VISIBLE)candidates.add(section.more);
        }
        return candidates;
    }
}
