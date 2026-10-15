package com.blocknet.wallet;
import org.junit.Test;
import static org.junit.Assert.*;
public class AmountsTest {
    @Test public void exactAtomicConversion() {
        assertEquals(1L, Amounts.parse("0.00000001"));
        assertEquals(123456789L, Amounts.parse("1.23456789"));
        assertEquals(Long.MAX_VALUE, Amounts.parse("92233720368.54775807"));
        assertEquals("1.23456789", Amounts.format(123456789));
        assertEquals("0.00000000", Amounts.format(0));
    }
    @Test public void rejectsUnsafeAmounts() {
        for (String value : new String[]{"0", "0.00000000", "-1", "+1", "1e3", "NaN", " 1", "1 ", "1.", ".1", "1.000000001", "92233720368.54775808", "99999999999999999999999999999999999999999", "1,000", "", null}) {
            try { Amounts.parse(value); fail("Accepted: " + value); }
            catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void allPositiveLongsRoundTrip() {
        for (long value : new long[]{1, 9, 99999999L, 100000000L, 12300000001L, Long.MAX_VALUE})
            assertEquals(value, Amounts.parse(Amounts.format(value)));
    }
}
