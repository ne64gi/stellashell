package net.fuyumori.stellashell;

import android.content.Context;
import android.view.Gravity;
import android.widget.*;

/** Explicit page controls leave provider scrolling and swiping untouched. */
@android.annotation.SuppressLint("ViewConstructor") // Programmatic owner dependencies, not XML inflation.
final class HubPageNavigation extends LinearLayout {
    private final DesktopWidgets widgets;
    private final Button previous,number,next;
    HubPageNavigation(Context context,DesktopWidgets widgets,boolean compact){
        super(context);this.widgets=widgets;setGravity(Gravity.CENTER);
        previous=Ui.toolbarButton(context,"‹",()->widgets.selectPage(widgets.currentPage()-1));
        previous.setContentDescription(context.getString(R.string.widget_previous_page));
        number=Ui.toolbarButton(context,"",this::choose);
        next=Ui.toolbarButton(context,"›",()->widgets.selectPage(widgets.currentPage()+1));
        next.setContentDescription(context.getString(R.string.widget_next_page));
        for(Button button:new Button[]{previous,number,next}){button.setMinWidth(0);button.setMinimumWidth(0);button.setPadding(0,0,0,0);}
        if(!compact)addView(previous,new LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
        addView(number,new LayoutParams(Ui.dp(context,56),Ui.dp(context,48)));
        if(!compact)addView(next,new LayoutParams(Ui.dp(context,48),Ui.dp(context,48)));
        if(compact){
            number.setOnClickListener(v->{if(widgets.pageCount()==1)choose();else widgets.selectPage((widgets.currentPage()+1)%widgets.pageCount());});
            number.setOnLongClickListener(v->{choose();return true;});
        }
        refresh();
    }
    void refresh(){
        number.setText(getContext().getString(R.string.widget_page_fraction,widgets.currentPage()+1,widgets.pageCount()));
        number.setContentDescription(getContext().getString(R.string.widget_page_status,widgets.currentPage()+1,widgets.pageCount()));
        number.setTooltipText(getContext().getString(R.string.widget_pages));
        previous.setEnabled(widgets.currentPage()>0);next.setEnabled(widgets.currentPage()+1<widgets.pageCount());
    }
    void choose(){
        PopupMenu menu=new PopupMenu(getContext(),number);
        for(int i=0;i<widgets.pageCount();i++){
            final int page=i;
            menu.getMenu().add(getContext().getString(R.string.widget_page_number,i+1)).setCheckable(true).setChecked(page==widgets.currentPage()).setOnMenuItemClickListener(item->{widgets.selectPage(page);return true;});
        }
        menu.getMenu().add(R.string.widget_add_page).setEnabled(widgets.pageCount()<net.fuyumori.stellashell.core.layout.WidgetPages.LIMIT).setOnMenuItemClickListener(item->{widgets.addPage();return true;});
        if(widgets.pageCount()>1&&widgets.emptyPage())menu.getMenu().add(R.string.widget_remove_page).setOnMenuItemClickListener(item->{widgets.removeEmptyPage();return true;});
        menu.show();
    }
}
