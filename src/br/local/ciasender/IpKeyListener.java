package br.local.ciasender;

import android.text.InputType;
import android.text.Spanned;
import android.text.method.NumberKeyListener;

/**
 * Key listener for an IPv4 address field.
 *
 * It asks the keyboard for the numeric keypad (with a decimal separator key), but,
 * unlike the stock decimal number input, it accepts as many dots as needed.
 * A comma (the decimal separator on some locales) is converted to a dot.
 */
final class IpKeyListener extends NumberKeyListener {
    private static final char[] ACCEPTED = "0123456789.,".toCharArray();

    @Override
    protected char[] getAcceptedChars() {
        return ACCEPTED;
    }

    @Override
    public int getInputType() {
        return InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
    }

    @Override
    public CharSequence filter(CharSequence source, int start, int end,
                               Spanned dest, int dstart, int dend) {
        CharSequence filtered = super.filter(source, start, end, dest, dstart, dend);
        CharSequence text = (filtered != null) ? filtered : source.subSequence(start, end);
        String s = text.toString();
        if (s.indexOf(',') >= 0) return s.replace(',', '.');
        return filtered;
    }
}
