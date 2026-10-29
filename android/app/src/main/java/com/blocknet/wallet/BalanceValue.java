package com.blocknet.wallet;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Display-only choice for the available balance; never changes wallet accounting. */
final class BalanceValue {
    static final long MAX_QUOTE_AGE_MS=30_000;
    final String amount,unit,action,hint;
    final boolean canToggle;

    private BalanceValue(String amount,String unit,String action,String hint,boolean canToggle){
        this.amount=amount;this.unit=unit;this.action=action;this.hint=hint;this.canToggle=canToggle;
    }

    static BalanceValue show(long atomic,boolean scanning,boolean liveMainnet,boolean showUsdt,
                             BigDecimal last,long quoteAgeMs,String priceError){
        boolean available=liveMainnet&&!scanning;
        if(!showUsdt||!liveMainnet){
            String coins=BigDecimal.valueOf(atomic,8).stripTrailingZeros().toPlainString();
            return new BalanceValue(scanning?"scanning…":coins,"BNT",
                "Show approximate USDT value",available?"Tap balance to show approximate USDT value":"",available);
        }
        if(scanning||last==null||last.signum()<=0||quoteAgeMs<0||quoteAgeMs>MAX_QUOTE_AGE_MS
            ||priceError!=null&&!priceError.isEmpty()){
            return new BalanceValue("—","USDT","Show BNT balance",
                scanning?"Approximate value unavailable while wallet scans":"Approximate value unavailable",true);
        }
        String estimate=last.multiply(BigDecimal.valueOf(atomic,8))
            .setScale(2,RoundingMode.HALF_UP).toPlainString();
        return new BalanceValue("≈ "+estimate,"USDT","Show BNT balance",
            "Indicative USDT value from the BNT/USDT last trade",true);
    }
}
