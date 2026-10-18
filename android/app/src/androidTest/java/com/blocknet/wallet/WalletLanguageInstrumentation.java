package com.blocknet.wallet;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import java.text.DateFormat;
import java.util.Locale;

/** Resource and locale checks for an isolated test installation; no wallet is opened. */
public final class WalletLanguageInstrumentation extends Instrumentation {
    private static void check(boolean value,String message){if(!value)throw new IllegalStateException(message);}
    private Bundle arguments;

    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);this.arguments=arguments;start();}

    @Override public void onStart(){
        Bundle result=new Bundle();
        try {
            Context base=getTargetContext();
            String requested=arguments==null?null:arguments.getString("set-choice");
            if(requested!=null){
                check(AppLanguage.set(base,requested),"Could not persist language choice");
                check(requested.equals(AppLanguage.choice(base)),"Language choice was not applied");
                result.putString("status","App language choice applied: "+requested);
                finish(Activity.RESULT_OK,result);return;
            }
            String expected=arguments==null?null:arguments.getString("expect-choice");
            if(expected!=null){
                check(expected.equals(AppLanguage.choice(base)),"Persisted app language choice changed");
                result.putString("status","Persisted app language choice: "+expected);
                finish(Activity.RESULT_OK,result);return;
            }
            Context english=AppLanguage.forChoice(base,AppLanguage.ENGLISH);
            Context chinese=AppLanguage.forChoice(base,AppLanguage.CHINESE);
            int cancel=GeneratedTranslations.EXACT.get("Cancel");
            int follow=GeneratedTranslations.EXACT.get("Follow phone language");
            check("Cancel".equals(english.getString(cancel)),"English resource missing");
            check("取消".equals(chinese.getString(cancel)),"Chinese resource missing");
            check("跟随手机语言".equals(chinese.getString(follow)),"Selector resource missing");
            check(Amounts.format(123456789L).equals("1.23456789"),"Amount format changed");
            String englishDate=AppLanguage.formatDate(english,1780000000000L,DateFormat.MEDIUM,DateFormat.SHORT);
            String chineseDate=AppLanguage.formatDate(chinese,1780000000000L,DateFormat.MEDIUM,DateFormat.SHORT);
            check(!englishDate.equals(chineseDate),"Date did not follow app locale");
            String address="KNNpZhPcJX92oAyvcVWJhQXJjsWRYsEiPHej6ZTM2LuSJBDS3VQebjUjUCzYqBnidt1JXYUiUgA69HeBoTuVuQYWwnhQN";
            check(address.equals(LocalizedText.get(chinese,address)),"Address changed in translation");
            Configuration french=new Configuration(base.getResources().getConfiguration());
            french.setLocale(Locale.FRENCH);
            Context systemFrench=AppLanguage.forChoice(base.createConfigurationContext(french),AppLanguage.SYSTEM);
            check("Cancel".equals(systemFrench.getString(cancel)),"English fallback missing");
            Configuration chineseSystem=new Configuration(base.getResources().getConfiguration());
            chineseSystem.setLocale(Locale.forLanguageTag("zh-Hans-CN"));
            Context systemChinese=AppLanguage.forChoice(base.createConfigurationContext(chineseSystem),AppLanguage.SYSTEM);
            check("取消".equals(systemChinese.getString(cancel)),"System Chinese selection missing");
            result.putString("status","English, Chinese, system locale and fallback resources passed");
            finish(Activity.RESULT_OK,result);
        } catch(Exception failure){
            result.putString("error",failure.toString());
            finish(Activity.RESULT_CANCELED,result);
        }
    }
}
