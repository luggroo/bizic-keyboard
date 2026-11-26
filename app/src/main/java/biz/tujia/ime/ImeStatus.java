package biz.tujia.ime;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

/** Only this IME's two visible switches may cross into the local reader. */
final class ImeStatus {
    final boolean enabled;
    final boolean selected;

    private ImeStatus(boolean enabled, boolean selected) {
        this.enabled = enabled;
        this.selected = selected;
    }

    static ImeStatus read(Context context) {
        ComponentName ime = new ComponentName(context, TujiaIme.class);
        InputMethodManager manager = context.getSystemService(InputMethodManager.class);
        boolean enabled = false;
        for (InputMethodInfo input : manager.getEnabledInputMethodList()) {
            if (ime.equals(input.getComponent())) {
                enabled = true;
                break;
            }
        }
        String current = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return new ImeStatus(enabled, current != null && ime.equals(ComponentName.unflattenFromString(current)));
    }

    String asJson() {
        return "{\"enabled\":" + enabled + ",\"selected\":" + selected + "}";
    }
}
