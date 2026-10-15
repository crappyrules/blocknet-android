package com.blocknet.wallet;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** A payment request is input to review, never authorization to send funds. */
public final class PaymentRequest {
    public final String address;
    public final String amount;
    public final String memo;
    private PaymentRequest(String address, String amount, String memo) {
        this.address = address; this.amount = amount; this.memo = memo;
    }
    public static PaymentRequest parse(String raw) {
        if (raw == null || raw.length() > 4096) throw invalid();
        String value = raw.trim();
        if (value.isEmpty() || value.indexOf('#') >= 0) throw invalid();
        boolean uri = value.toLowerCase(Locale.ROOT).startsWith("blocknet:");
        if (uri) {
            value = value.substring(9);
            if (value.startsWith("//")) value = value.substring(2);
        } else if (value.indexOf(':') >= 0 || value.indexOf('?') >= 0) throw invalid();
        int query = value.indexOf('?');
        String address = query < 0 ? value : value.substring(0, query);
        if (!address.matches("[1-9A-HJ-NP-Za-km-z]{64,100}") && !address.matches("[@$][A-Za-z0-9_.-]{1,64}")) throw invalid();
        String amount = "", memo = "";
        Set<String> seen = new HashSet<>();
        if (query >= 0) {
            String parameters = value.substring(query + 1);
            if (parameters.isEmpty()) throw invalid();
            for (String pair : parameters.split("&", -1)) {
                int eq = pair.indexOf('=');
                if (eq <= 0) throw invalid();
                String key = decode(pair.substring(0, eq));
                String item = decode(pair.substring(eq + 1));
                if (!seen.add(key)) throw new IllegalArgumentException("Duplicate payment request field");
                switch (key) {
                    case "amount": Amounts.parse(item); amount = item; break;
                    case "memo":
                        if (item.getBytes(StandardCharsets.UTF_8).length > 124 || item.indexOf('\u0000') >= 0)
                            throw new IllegalArgumentException("Memo is too long");
                        memo = item; break;
                    default: throw new IllegalArgumentException("Unsupported payment request field");
                }
            }
        }
        return new PaymentRequest(address, amount, memo);
    }
    private static String decode(String value) {
        try { return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8"); }
        catch (Exception e) { throw invalid(); }
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Use a Blocknet address or blocknet payment link");
    }
}
