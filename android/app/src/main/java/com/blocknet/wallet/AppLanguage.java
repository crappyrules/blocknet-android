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
        LocaleList current=base.getResources().getConfiguration().getLocales();
        // A matching first language does not prove the Context's resources are
        // current after an in-place upgrade or Activity recreation. In particular,
        // force an independent English Context on pre-Android 13 devices.
        if(CHINESE.equals(choice)&&current.size()==1&&
            "zh".equals(current.get(0).getLanguage())&&"Hans".equals(current.get(0).getScript()))return base;
        Configuration configuration=new Configuration(base.getResources().getConfiguration());
        configuration.setLocales(new LocaleList(locale));
        return base.createConfigurationContext(configuration);
    }
    static boolean set(Context context,String choice){
        if(!SYSTEM.equals(choice)&&!ENGLISH.equals(choice)&&!CHINESE.equals(choice))throw new IllegalArgumentException("Invalid app language");
        if(Build.VERSION.SDK_INT>=33){
            LocaleManager manager=context.getSystemService(LocaleManager.class);
            if(manager!=null){
                manager.setApplicationLocales(SYSTEM.equals(choice)?LocaleList.getEmptyLocaleList()
                    :LocaleList.forLanguageTags(CHINESE.equals(choice)?"zh-Hans-CN":"en"));
                // Some devices defer or reject a locale change. Do not report success
                // until the app locale service reads back the requested selection.
                return choice.equals(choice(context));
            }
        }
        if(!context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(CHOICE,choice).commit())return false;
        return choice.equals(choice(context));
    }
    static String formatDate(Context context,long millis,int dateStyle,int timeStyle){
        String selected=choice(context);
        Locale locale=ENGLISH.equals(selected)?Locale.ENGLISH:CHINESE.equals(selected)
            ?Locale.forLanguageTag("zh-Hans-CN"):context.getResources().getConfiguration().getLocales().get(0);
        return DateFormat.getDateTimeInstance(dateStyle,timeStyle,locale).format(new Date(millis));
    }
}
