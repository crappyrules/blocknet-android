package com.blocknet.wallet;
import org.junit.Test;
import static org.junit.Assert.*;
public class PaymentRequestTest {
    private static final String ADDRESS = "A".repeat(92);
    @Test public void parsesRawAddressAndLinks() {
        assertEquals(ADDRESS, PaymentRequest.parse(ADDRESS).address);
        PaymentRequest p = PaymentRequest.parse("blocknet://" + ADDRESS + "?amount=0.00000001&memo=Coffee%20%2B%20cake");
        assertEquals(ADDRESS, p.address);
        assertEquals("0.00000001", p.amount);
        assertEquals("Coffee + cake", p.memo);
        assertEquals(ADDRESS, PaymentRequest.parse("BLOCKNET:" + ADDRESS).address);
        assertEquals("$rock", PaymentRequest.parse("blocknet://$rock?amount=100").address);
        assertEquals("@alice", PaymentRequest.parse("blocknet:@alice").address);
    }
    @Test public void rejectsAmbiguousAndForeignRequests() {
        for (String value : new String[]{null,"", "https://evil.example/" + ADDRESS, "bitcoin:" + ADDRESS,
                "blocknet://" + ADDRESS + "?amount=1&amount=2", "blocknet://" + ADDRESS + "?memo=x&memo=y",
                "blocknet://" + ADDRESS + "?%61mount=1&amount=2", "blocknet://" + ADDRESS + "?amount=1e2",
                "blocknet://" + ADDRESS + "?amount=0", "blocknet://" + ADDRESS + "?amount=-1",
                "blocknet://" + ADDRESS + "?req-unknown=x", "blocknet://" + ADDRESS + "#fragment",
                "blocknet://" + ADDRESS + "/path", "blocknet://user@" + ADDRESS,
                "blocknet://" + ADDRESS + "?memo=%ZZ", "blocknet://" + ADDRESS + "?",
                "blocknet://" + ADDRESS + "?memo=%00", "blocknet://" + ADDRESS + "?memo=" + "é".repeat(63)}) {
            try { PaymentRequest.parse(value); fail("Accepted: " + value); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
