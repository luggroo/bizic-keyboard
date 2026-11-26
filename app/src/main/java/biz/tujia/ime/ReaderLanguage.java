package biz.tujia.ime;

import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;

import java.util.Locale;

/** Localized resources for one native screen, without changing the active IME's locale. */
final class ReaderLanguage {
    private ReaderLanguage() { }

    static boolean english(Context context) {
        LocaleList locales = context.getResources().getConfiguration().getLocales();
        return !locales.isEmpty() && "en".equals(locales.get(0).getLanguage());
    }

    static Context localized(Context context, boolean english) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocales(new LocaleList(english ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE));
        return context.createConfigurationContext(config);
    }
}
