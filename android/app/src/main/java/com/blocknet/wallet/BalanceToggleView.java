package com.blocknet.wallet;

import android.content.Context;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The visible available-balance control. The caller supplies a display-only quote. */
final class BalanceToggleView {
    final LinearLayout row;
    final TextView amount,unit,hint;
    private final Context context;
    private boolean canToggle;

    BalanceToggleView(WalletTheme ui,Runnable onToggle){
        context=ui.context;
        row=ui.row();
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.setOnClickListener(v->{if(canToggle)onToggle.run();});
        row.setAccessibilityDelegate(new View.AccessibilityDelegate(){
            @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){
                super.onInitializeAccessibilityNodeInfo(host,info);
                info.setClassName(canToggle?Button.class.getName():TextView.class.getName());
            }
        });
        amount=ui.text("",36,WalletTheme.TEXT);
        amount.setSingleLine(true);
        amount.setAutoSizeTextTypeUniformWithConfiguration(16,36,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        amount.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(amount,new LinearLayout.LayoutParams(0,-2,1));
        unit=ui.text("BNT",15,WalletTheme.LIME);
        unit.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(unit);
        hint=ui.text("",11,WalletTheme.MUTED);
    }

    void show(BalanceValue value){
        canToggle=value.canToggle;
        amount.setText(LocalizedText.get(context,value.amount));
        unit.setText(value.unit);
        hint.setText(LocalizedText.get(context,value.hint));
        hint.setVisibility(value.hint.isEmpty()?View.GONE:View.VISIBLE);
        row.setClickable(canToggle);
        row.setFocusable(canToggle);
        String spoken=LocalizedText.get(context,value.amount+" "+value.unit);
        row.setContentDescription(canToggle?spoken+". "+LocalizedText.get(context,value.action):spoken);
    }
}
