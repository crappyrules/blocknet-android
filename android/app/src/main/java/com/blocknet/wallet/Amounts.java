package com.blocknet.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Exact conversion between coin decimals and the core's atomic integer units. */
public final class Amounts {
    private Amounts() {}
    public static long parse(String value) {
        if (value == null || value.length() > 40 || !value.matches("[0-9]+(?:\\.[0-9]{1,8})?"))
            throw new IllegalArgumentException("Enter an amount with up to 8 decimal places");
        try {
            long atoms = new BigDecimal(value).movePointRight(8).longValueExact();
            if (atoms <= 0) throw new IllegalArgumentException("Amount must be greater than zero");
            return atoms;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amount is too large", e);
        }
    }
    public static String format(long atoms) {
        return BigDecimal.valueOf(atoms, 8).setScale(8, RoundingMode.UNNECESSARY).toPlainString();
    }
}
