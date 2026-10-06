package dev.cloudlite.s3.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public final class Md5 {

    private Md5() {
    }

    public static String hex(byte[] data) {
        return HexFormat.of().formatHex(digest().digest(data));
    }

    // AWS multipart ETag: md5 of the concatenated binary part digests, then "-<part count>".
    public static String multipartEtag(List<String> partMd5Hex) {
        MessageDigest digest = digest();
        for (String hex : partMd5Hex) {
            digest.update(HexFormat.of().parseHex(hex));
        }
        return HexFormat.of().formatHex(digest.digest()) + "-" + partMd5Hex.size();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
