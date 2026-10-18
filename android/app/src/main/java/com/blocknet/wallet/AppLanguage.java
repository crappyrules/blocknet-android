package com.blocknet.wallet;

import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** App language choice. The system locale remains the default. */
final class AppLanguage {
    static final String SYSTEM="system",ENGLISH="en",CHINESE="zh-Hans";
    private static final String PREFS="app-language",CHOICE="choice";
    private AppLanguage(){}
    static String choice(Context context){
        if(Build.VERSION.SDK_INT>=33){
            LocaleManager manager=context.getSystemService(LocaleManager.class);
            if(manager!=null){LocaleList locales=manager.getApplicationLocales();return locales.isEmpty()?SYSTEM:locales.get(0).getLanguage().equals("zh")?CHINESE:ENGLISH;}
        }
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(CHOICE,SYSTEM);
    }
    static Context wrap(Context base){
        return forChoice(base,choice(base));
    }
    static Context forChoice(Context base,String choice){
        if(SYSTEM.equals(choice))return base;
        Locale locale=CHINESE.equals(choice)?Locale.forLanguageTag("zh-Hans-CN"):Locale.ENGLISH;
        Locale current=base.getResources().getConfiguration().getLocales().get(0);
        if(current.getLanguage().equals(locale.getLanguage())&&
            (!CHINESE.equals(choice)||"Hans".equals(current.getScript())))return base;
        Configuration configuration=new Configuration(base.getResources().getConfiguration());
        configuration.setLocale(locale);
        return base.createConfigurationContext(configuration);
    }
    static boolean set(Context context,String choice){
        if(!SYSTEM.equals(choice)&&!ENGLISH.equals(choice)&&!CHINESE.equals(choice))throw new IllegalArgumentException("Invalid app language");
        if(Build.VERSION.SDK_INT>=33){
            LocaleManager manager=context.getSystemService(LocaleManager.class);
            if(manager!=null){manager.setApplicationLocales(SYSTEM.equals(choice)?LocaleList.getEmptyLocaleList():LocaleList.forLanguageTags(CHINESE.equals(choice)?"zh-Hans-CN":"en"));return true;}
        }
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(CHOICE,choice).commit();
    }
    static String formatDate(Context context,long millis,int dateStyle,int timeStyle){
        Locale locale=context.getResources().getConfiguration().getLocales().get(0);
        return DateFormat.getDateTimeInstance(dateStyle,timeStyle,locale).format(new Date(millis));
    }
}
