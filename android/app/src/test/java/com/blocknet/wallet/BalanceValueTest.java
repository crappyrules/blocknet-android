package com.blocknet.wallet;

import org.junit.Test;
import java.math.BigDecimal;
import static org.junit.Assert.*;

public class BalanceValueTest {
    @Test public void repeatedToggleUsesSameQuoteAndAvailableBalance(){
        BigDecimal quote=new BigDecimal("0.2822");
        BalanceValue bnt=BalanceValue.show(1_000_000_000L,false,true,false,quote,0,"");
        BalanceValue usdt=BalanceValue.show(1_000_000_000L,false,true,true,quote,0,"");
        assertEquals("10",bnt.amount);assertEquals("BNT",bnt.unit);
        assertEquals("≈ 2.82",usdt.amount);assertEquals("USDT",usdt.unit);
        assertEquals(bnt.amount,BalanceValue.show(1_000_000_000L,false,true,false,quote,0,"").amount);
        assertEquals(usdt.amount,BalanceValue.show(1_000_000_000L,false,true,true,quote,0,"").amount);
    }

    @Test public void zeroIsDistinctFromMissingOrStalePrice(){
        BigDecimal quote=new BigDecimal("0.5");
        assertEquals("≈ 0.00",BalanceValue.show(0,false,true,true,quote,0,"").amount);
        assertEquals("—",BalanceValue.show(0,false,true,true,null,0,"").amount);
        assertEquals("—",BalanceValue.show(0,false,true,true,quote,30_001,"").amount);
        assertEquals("—",BalanceValue.show(0,false,true,true,quote,0,"Price feed HTTP 403").amount);
        assertEquals("—",BalanceValue.show(0,true,true,true,quote,0,"").amount);
    }

    @Test public void offlineTestnetOrLockedModeCannotShowEstimate(){
        BigDecimal quote=new BigDecimal("0.5");
        for(boolean preferred:new boolean[]{false,true}){
            BalanceValue value=BalanceValue.show(1_000_000_000L,false,false,preferred,quote,0,"");
            assertEquals("10",value.amount);assertEquals("BNT",value.unit);assertFalse(value.canToggle);
        }
    }
}
