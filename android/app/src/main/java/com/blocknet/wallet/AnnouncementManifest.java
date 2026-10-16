package com.blocknet.wallet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Exact HTTPS manifest contract authenticated by the signed on-chain digest. */
final class AnnouncementManifest {
    private static final String MANIFEST_PREFIX="https://raw.githubusercontent.com/crappyrules/blocknet-android/master/android/updates/";
    private static final String PAGE="https://github.com/crappyrules/blocknet-android/releases/tag/";
    private static final String DOWNLOAD="https://github.com/crappyrules/blocknet-android/releases/download/";
    private static final Set<String> FIELDS=new HashSet<>(Arrays.asList("schema","epoch","app","variant","channel",
        "sequence","version_code","tag","release_page","apk_url","apk_sha256","signing_cert_sha256","notes"));
    static final class Release {
        final String tag,page,notes,apkSha256;
        final long sequence;
        final int versionCode;
        Release(String tag,String page,String notes,String apkSha256,long sequence,int versionCode){
            this.tag=tag;this.page=page;this.notes=notes;this.apkSha256=apkSha256;
            this.sequence=sequence;this.versionCode=versionCode;
        }
    }

    static String urlForDigest(byte[] digest) throws IOException {
        if(digest==null||digest.length!=32)throw new IOException("Invalid announcement manifest digest.");
        StringBuilder path=new StringBuilder(MANIFEST_PREFIX);
        final char[] digits="0123456789abcdef".toCharArray();
        for(byte value:digest){path.append(digits[(value>>>4)&15]).append(digits[value&15]);}
        return path.append(".json").toString();
    }

    static Release parse(byte[] raw,byte[] expectedDigest,int epoch,long sequence,int versionCode,
                         int installedCode,String variant,String expectedCertificate) throws Exception {
        if(raw==null||raw.length==0||raw.length>32768||expectedDigest==null||expectedDigest.length!=32||
            !MessageDigest.isEqual(expectedDigest,MessageDigest.getInstance("SHA-256").digest(raw)))
            throw new IOException("Announcement manifest hash is invalid.");
        String json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString();
        final JsonObject data;
        try { data=JsonParser.parseString(json).getAsJsonObject(); }
        catch(Exception invalid) { throw new IOException("Announcement manifest is invalid.",invalid); }
        if(!data.keySet().equals(FIELDS))throw new IOException("Announcement manifest fields are invalid.");
        try {
            String tag=text(data,"tag"),page=text(data,"release_page");
            String apk=text(data,"apk_url"),hash=text(data,"apk_sha256");
            String cert=text(data,"signing_cert_sha256"),notes=text(data,"notes");
            if(number(data,"schema")!=1||number(data,"epoch")!=epoch||
                !text(data,"app").equals("blocknet-android")||
                !text(data,"variant").equals(variant)||
                !text(data,"channel").equals("preview")||
                number(data,"sequence")!=sequence||
                number(data,"version_code")!=versionCode||versionCode<=installedCode||
                !tag.matches("v[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}-preview\\.[0-9]{1,6}")||
                !page.equals(PAGE+tag)||
                !apk.equals(DOWNLOAD+tag+"/blocknet-wallet-arm64-"+variant+"-release.apk")||
                !hash.matches("[0-9a-f]{64}")||
                !cert.equalsIgnoreCase(expectedCertificate)||notes.length()>4000)
                throw new IOException("Announcement manifest does not match the signed release scope.");
            return new Release(tag,page,notes,hash,sequence,versionCode);
        } catch(IllegalStateException|NullPointerException|NumberFormatException malformed) {
            throw new IOException("Announcement manifest values are invalid.",malformed);
        }
    }
    private static String text(JsonObject object,String name) throws IOException {
        JsonElement item=object.get(name);
        if(item==null||!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isString())
            throw new IOException("Announcement manifest string field is invalid.");
        return item.getAsString();
    }
    private static long number(JsonObject object,String name) throws IOException {
        JsonElement item=object.get(name);
        if(item==null||!item.isJsonPrimitive()||!item.getAsJsonPrimitive().isNumber()||
            !item.getAsString().matches("0|[1-9][0-9]*"))
            throw new IOException("Announcement manifest number field is invalid.");
        try {return Long.parseLong(item.getAsString());}
        catch(NumberFormatException overflow) {throw new IOException("Announcement manifest number is too large.",overflow);}
    }
}
