package wtb.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public class Format {

    /**
     * Truncate-to-cent and format with a leading $.
     *
     * <p>Uses {@code BigDecimal(Double.toString(value))} as the source so that
     * the canonical decimal string (e.g. {@code "19.99"}) is used for arithmetic,
     * not the full IEEE 754 mantissa expansion ({@code 19.989999999999998...}).
     * The previous {@code Math.floor(value * 100) / 100.0} approach operated on
     * the raw double representation and silently rounded 573 of 10 000 common
     * two-decimal prices down by one cent (e.g. $19.99 displayed as $19.98).
     *
     * <p>Uses Locale.US so the decimal separator is always '.' regardless of
     * the server's system locale (avoids "1,00" on French/German systems).
     * Returns "$0.00" for NaN / Infinity instead of crashing.
     */
    public static String money(double value) {
        if (!Double.isFinite(value)) return "$0.00";
        BigDecimal bd = new BigDecimal(Double.toString(value))
                .setScale(2, RoundingMode.FLOOR);
        return String.format(Locale.US, "$%.2f", bd);
    }
}