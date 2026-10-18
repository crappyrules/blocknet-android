package com.blocknet.wallet;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import java.util.regex.Matcher;

/** Resolves visible copy from bundled Android resources; unknown values remain unchanged. */
final class LocalizedText {
    private LocalizedText(){}
    static String get(Context context,String source){
        if(source==null||source.isEmpty())return source;
        // Every lookup starts from English source copy. Returning it directly
        // keeps an explicit English choice English even if an OEM holds a stale
        // Chinese Resources instance across an in-place update or recreation.
        if(AppLanguage.ENGLISH.equals(AppLanguage.choice(context)))return source;
        Context localized=AppLanguage.wrap(context);
        Integer id=GeneratedTranslations.EXACT.get(source);
        if(id!=null)return localized.getString(id);
        for(GeneratedTranslations.Template item:GeneratedTranslations.TEMPLATES){
            Matcher match=item.pattern.matcher(source);
            if(match.matches()){
                Object[] fields=new String[match.groupCount()];
                for(int i=0;i<fields.length;i++)fields[i]=match.group(i+1);
                return localized.getString(item.id,fields);
            }
        }
        return source;
    }
    static class Dialog extends AlertDialog.Builder {
        Dialog(Context context){super(context);}
        @Override public Dialog setTitle(CharSequence title){super.setTitle(get(getContext(),title.toString()));return this;}
        @Override public Dialog setMessage(CharSequence message){super.setMessage(get(getContext(),message.toString()));return this;}
        @Override public Dialog setPositiveButton(CharSequence text,DialogInterface.OnClickListener listener){super.setPositiveButton(get(getContext(),text.toString()),listener);return this;}
        @Override public Dialog setNegativeButton(CharSequence text,DialogInterface.OnClickListener listener){super.setNegativeButton(get(getContext(),text.toString()),listener);return this;}
        @Override public Dialog setNeutralButton(CharSequence text,DialogInterface.OnClickListener listener){super.setNeutralButton(get(getContext(),text.toString()),listener);return this;}
        @Override public Dialog setItems(CharSequence[] items,DialogInterface.OnClickListener listener){CharSequence[] translated=new CharSequence[items.length];for(int i=0;i<items.length;i++)translated[i]=get(getContext(),items[i].toString());super.setItems(translated,listener);return this;}
    }
}
