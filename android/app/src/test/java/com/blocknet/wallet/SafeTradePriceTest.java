package com.blocknet.wallet;

import org.junit.Test;
import java.io.IOException;
import java.math.BigDecimal;
import static org.junit.Assert.*;

public class SafeTradePriceTest {
    @Test public void acceptsOnlyBlocknetUsdtWithUsableLastTrade() throws Exception {
        SafeTradePrice.Quote quote=SafeTradePrice.parseFields("bntusdt","bnt","usdt","BNT/USDT","0.2822","55534.8",123);
        assertEquals(0,new BigDecimal("2.822").compareTo(quote.estimatedUsdt(1_000_000_000)));
        assertEquals(123,quote.fetchedElapsed);
        for(String[] fields:new String[][]{
            {"bntusdt","bnt","btc","BNT/USDT","0.28","5"},
            {"bntusdt","bnt","usdt","BNT/USDT","0","5"},
            {"bntusdt","bnt","usdt","BNT/USDT","NaN","5"},
            {"bntusdt","bnt","usdt","BNT/USDT","0.28","0"},
            {"bntusdt","bnt","usdt","BNT/USDT","9999999999","5"}}){
            try{SafeTradePrice.parseFields(fields[0],fields[1],fields[2],fields[3],fields[4],fields[5],123);fail("invalid quote accepted");}
            catch(IOException expected){}
        }
    }
}
