package com.blocknet.wallet;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.Gravity;
import android.widget.*;
import android.text.InputType;
import android.text.GetChars;
import android.text.method.PasswordTransformationMethod;
import java.util.Arrays;

/** Native view vocabulary matching blocknetcrypto.com's black, lime and monospace theme. */
final class WalletTheme {
    private static final class Label extends TextView {
        Label(Context context){super(context);}
        @Override public void setText(CharSequence value,BufferType type){super.setText(LocalizedText.get(getContext(),value==null?null:value.toString()),type);}
    }
    private static final class Action extends Button {
        Action(Context context){super(context);}
        @Override public void setText(CharSequence value,BufferType type){super.setText(LocalizedText.get(getContext(),value==null?null:value.toString()),type);}
    }
    /** Never exposes the last typed character, even when Android SHOW_PASSWORD is enabled. */
    private static final PasswordTransformationMethod SECRET_MASK = new PasswordTransformationMethod() {
        @Override public CharSequence getTransformation(CharSequence source,View view) {
            return new MaskedText(source);
        }
    };
    private static final class MaskedText implements CharSequence,GetChars {
        private final CharSequence source;
        MaskedText(CharSequence source) { this.source=source; }
        public int length() { return source.length(); }
        public char charAt(int index) { if(index<0||index>=length())throw new IndexOutOfBoundsException();return '\u2022'; }
        public CharSequence subSequence(int start,int end) { checkRange(start,end);return bullets(end-start); }
        public void getChars(int start,int end,char[] destination,int offset) {
            checkRange(start,end);Arrays.fill(destination,offset,offset+end-start,'\u2022');
        }
        public String toString() { return bullets(length()); }
        private void checkRange(int start,int end) { if(start<0||end<start||end>length())throw new IndexOutOfBoundsException(); }
        private static String bullets(int count) { char[] masked=new char[count];Arrays.fill(masked,'\u2022');return new String(masked); }
    }
    static final int BG=0xff000000, SURFACE=0xff0c0f0a, BORDER=0xff252b20;
    static final int LIME=0xffaaff00, TEXT=0xffeeeeee, MUTED=0xffa0a697, ERROR=0xffffb45b;
    final Context context;
    WalletTheme(Context context) { this.context=context; }
    int dp(float value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
    TextView text(String value,int size,int color) {
        return styledText(new Label(context),value,size,color);
    }
    TextView rawText(String value,int size,int color){return styledText(new TextView(context),value,size,color);}
    private TextView styledText(TextView v,String value,int size,int color){v.setText(value);v.setTextSize(size);v.setTextColor(color);
        v.setTypeface(Typeface.MONOSPACE);v.setLineSpacing(dp(3),1);v.setPadding(0,dp(5),0,dp(5));
        return v;
    }
    GradientDrawable box(int color) {
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(9));d.setStroke(dp(1),BORDER);return d;
    }
    LinearLayout column() { LinearLayout v=new LinearLayout(context);v.setOrientation(LinearLayout.VERTICAL);return v; }
    LinearLayout row() { LinearLayout v=new LinearLayout(context);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v; }
    LinearLayout card() { LinearLayout v=column();v.setPadding(dp(18),dp(15),dp(18),dp(15));v.setBackground(box(SURFACE));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(14);v.setLayoutParams(p);return v; }
    Button button(String title,boolean primary,Runnable action) {
        return styledButton(new Action(context),title,primary,action);
    }
    Button rawButton(String title,boolean primary,Runnable action){return styledButton(new Button(context),title,primary,action);}
    private Button styledButton(Button b,String title,boolean primary,Runnable action){b.setText(title);b.setAllCaps(false);b.setTypeface(Typeface.MONOSPACE);b.setTextSize(14);
        b.setTextColor(primary?BG:TEXT);b.setBackgroundTintList(ColorStateList.valueOf(primary?LIME:0xff171c13));
        b.setMinHeight(dp(50));b.setPadding(dp(12),dp(9),dp(12),dp(9));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(7);p.bottomMargin=dp(3);b.setLayoutParams(p);
        b.setOnClickListener(v->action.run());return b;
    }
    EditText field(String hint,boolean secret,boolean multiline) {
        EditText v=new EditText(context);v.setTextColor(TEXT);v.setHintTextColor(MUTED);v.setTextSize(14);v.setTypeface(Typeface.MONOSPACE);
        v.setHint(LocalizedText.get(context,hint));v.setContentDescription(LocalizedText.get(context,hint));v.setPadding(dp(13),dp(12),dp(13),dp(12));v.setBackground(box(0xff11150e));
        v.setSingleLine(!multiline);
        v.setInputType(InputType.TYPE_CLASS_TEXT|(secret?InputType.TYPE_TEXT_VARIATION_PASSWORD:0)|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));
        if(secret)v.setTransformationMethod(SECRET_MASK);
        v.setSelectAllOnFocus(false);
        if(secret){v.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);v.setSaveEnabled(false);v.setFreezesText(false);v.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);v.setTag("wallet-secret");}
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(7);p.bottomMargin=dp(7);v.setLayoutParams(p);return v;
    }
    void section(LinearLayout parent,String label) { TextView v=text("# "+LocalizedText.get(context,label),16,LIME);v.setPadding(0,dp(16),0,dp(12));parent.addView(v); }
    TextView line(LinearLayout parent,String label,String value) { LinearLayout r=row();TextView l=text(label,12,MUTED),v=rawText(value,13,TEXT);v.setGravity(Gravity.END);r.addView(l,new LinearLayout.LayoutParams(0,-2,1));r.addView(v,new LinearLayout.LayoutParams(0,-2,1));parent.addView(r);return v; }
}
