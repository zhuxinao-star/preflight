package com.preflight.exec;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 金证 KJDP 前端同款线路编码（第 10 轮登录）：work_app 的 /redis_login 只认
 * 「ka.encrypt 变换过的密码 + DES 加密过的 XML 报文」，两侧都用 des.js/ka.js 的同一套算法，
 * 这里逐位复刻，否则自动登录永远只能靠人工粘 Cookie。
 */
public final class KjdpWireCodec {

    /** 前端默认加密因子（index.js 里写死的 '888888'） */
    public static final String DEFAULT_KEY = "888888";

    private KjdpWireCodec() {
    }

    /**
     * ka.js 的 ka.encrypt(密码, 因子)：取前 10 字符、按因子做异或链，再 8→5 位转置成 20 个 'A'..'P'。
     * 前端就是这么发给服务端的密码形状，长度不足 10 时以 '1234567890' 与 "_<^@]?\>[=" 补齐。
     *
     * <p>第四十八轮补 >10 位那条分支（前端同一份 utils 里的 {@code _encryptLong}）：以前一律按前 10 位截断，
     * 12 位的密码会被混淆成服务端解不开的形状，界面只会回一句"账号或密码错误"，人查不出是自己截断了。
     * 现在照前端语义完整实现。
     */
    public static String kaEncrypt(String value, String factor) {
        String src = value == null ? "" : value;
        if (src.length() > 10) return kaEncryptLong(src, factor);
        String f = factor == null ? "" : factor;

        String jmyz1 = padRight(f.substring(0, Math.min(10, f.length())), "1234567890");
        String srmm1 = reverse(src.substring(0, Math.min(10, src.length()))) + "_<^@]?\\>[=";
        srmm1 = reverse(srmm1.substring(0, 10));

        StringBuilder bits = new StringBuilder(80);
        for (int i = 0; i < 10; i++) {
            int d = srmm1.charAt(i) ^ jmyz1.charAt(i);
            int h1 = i == 0 ? 237 : i == 1 ? 88 : srmm1.charAt(i - 1);
            int h2 = i == 0 ? 222 : i == 1 ? 125 : srmm1.charAt(i - 2);
            d = ((d ^ h1) ^ h2) & 0xFF;
            for (int j = 7; j >= 0; j--) bits.append(((d >> j) & 1) == 0 ? '0' : '1');
        }
        // 与 ka.js 一致：80 位按 16×5 转置，再每 4 位还原成一个 'A'..'P' 字符，共 20 位
        StringBuilder transposed = new StringBuilder(80);
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 5; j++) transposed.append(bits.charAt(j * 16 + i));
        }
        StringBuilder out = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            int d = 65;
            for (int j = 0; j < 4; j++) {
                if (transposed.charAt(i * 4 + j) == '1') d += 1 << (3 - j);
            }
            out.append((char) d);
        }
        return out.toString();
    }

    /**
     * 长密码分支（前端的 {@code _encryptLong}）：步骤与十位版一致，只是矩阵按 {@code ceil(len/2)} 行走，
     * 不足一格补 '0'，输出长度 = 行数 × 4。len==10 时与十位版逐字符相同（已由对齐脚本验过）。
     */
    private static String kaEncryptLong(String src, String factor) {
        int len = src.length();
        int rows = (len + 1) / 2;
        String f = factor == null ? "" : factor;
        StringBuilder fs = new StringBuilder(f.substring(0, Math.min(10, f.length())));
        while (fs.length() < len) fs.append("1234567890");
        String key = fs.substring(0, len);

        String padded = reverse(src) + "_<^@]?\\>[=";
        String pw = reverse(padded.substring(0, len));

        StringBuilder bits = new StringBuilder(rows * 16);
        for (int i = 0; i < len; i++) {
            int d = pw.charAt(i) ^ key.charAt(i);
            int h1 = i == 0 ? 237 : i == 1 ? 88 : pw.charAt(i - 1);
            int h2 = i == 0 ? 222 : i == 1 ? 125 : pw.charAt(i - 2);
            d = ((d ^ h1) ^ h2) & 0xFF;
            for (int j = 7; j >= 0; j--) bits.append(((d >> j) & 1) == 0 ? '0' : '1');
        }
        while (bits.length() < rows * 16) bits.append('0');

        StringBuilder transposed = new StringBuilder(rows * 16);
        for (int col = 0; col < 16; col++) {
            for (int row = 0; row < rows; row++) transposed.append(bits.charAt(row * 16 + col));
        }
        StringBuilder out = new StringBuilder(rows * 4);
        for (int i = 0; i < rows * 4; i++) {
            int d = 65;
            for (int j = 0; j < 4; j++) {
                if (transposed.charAt(i * 4 + j) == '1') d += 1 << (3 - j);
            }
            out.append((char) d);
        }
        return out.toString();
    }

    private static String padRight(String s, String filler) {        StringBuilder b = new StringBuilder(s);
        int need = 10 - b.length();
        for (int i = 0; b.length() < 10 && i < need && i < filler.length(); i++) b.append(filler.charAt(i));
        return b.toString();
    }

    private static String reverse(String s) {
        return new StringBuilder(s).reverse().toString();
    }

    /** des.js 的 des.encrypt：DES/ECB + NUL 补齐 + 小写 hex */
    public static String desHex(String message) {
        return desHex(message, DEFAULT_KEY);
    }

    /**
     * 与 des.js 保持同一分块口径：每个 64 位块由 4 个 UTF-16 码元（各占 2 字节）组成，
     * 不足一块用 NUL 补，块数 = ceil(长度/4)。
     */
    public static String desHex(String message, String key) {
        if (message == null || message.isEmpty()) return "";
        byte[] kb = new byte[8];
        String k = key == null ? DEFAULT_KEY : key;
        for (int i = 0; i < 8 && i < k.length(); i++) kb[i] = (byte) (k.charAt(i) & 0xFF);
        try {
            Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kb, "DES"));
            int blocks = (message.length() + 3) / 4;
            byte[] out = new byte[blocks * 8];
            for (int b = 0; b < blocks; b++) {
                byte[] blk = new byte[8];
                for (int c = 0; c < 4; c++) {
                    int ch = b * 4 + c < message.length() ? message.charAt(b * 4 + c) : 0;
                    blk[c * 2] = (byte) ((ch >> 8) & 0xFF);
                    blk[c * 2 + 1] = (byte) (ch & 0xFF);
                }
                System.arraycopy(cipher.doFinal(blk), 0, out, b * 8, 8);
            }
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte x : out) hex.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("KJDP DES 编码失败: " + e.getMessage(), e);
        }
    }

    /** des.js 的解密方向（只给内置模拟被测服务自检线路用：验证我们发出的报文服务端解得开） */
    public static String desDecodeHex(String hex, String key) {
        if (hex == null || hex.length() % 16 != 0) return "";
        byte[] kb = new byte[8];
        String k = key == null || key.isBlank() ? DEFAULT_KEY : key;
        for (int i = 0; i < 8 && i < k.length(); i++) kb[i] = (byte) (k.charAt(i) & 0xFF);
        try {
            byte[] in = new byte[hex.length() / 2];
            for (int i = 0; i < in.length; i++) {
                in[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            Cipher cipher = Cipher.getInstance("DES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kb, "DES"));
            StringBuilder sb = new StringBuilder();
            for (int b = 0; b + 8 <= in.length; b += 8) {
                byte[] blk = cipher.doFinal(in, b, 8);
                for (int c = 0; c + 1 < 8; c += 2) {
                    int ch = ((blk[c] & 0xFF) << 8) | (blk[c + 1] & 0xFF);
                    if (ch != 0) sb.append((char) ch);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("KJDP DES 解码失败: " + e.getMessage(), e);
        }
    }

    /** ka.js 的 makeXmlRequestStr：{req:[{K:"V",...}]} 包进 CDATA，键不加引号、值一律加引号 */
    public static String xmlRequest(java.util.List<java.util.Map<String, String>> rows) {
        StringBuilder a = new StringBuilder();
        for (java.util.Map<String, String> row : rows) {
            StringBuilder temp = new StringBuilder("{");
            for (java.util.Map.Entry<String, String> en : row.entrySet()) {
                if (temp.length() > 1) temp.append(',');
                temp.append(en.getKey()).append(":\"").append(en.getValue() == null ? "" : en.getValue()).append('"');
            }
            temp.append('}');
            a.append(a.length() == 0 ? temp : "," + temp);
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><requests><![CDATA[{req:[" + a + "]}]]></requests>";
    }

    /**
     * 登录报文一步到位：{@code passwordField} 先过 ka.encrypt，整体再走 makeXmlRequestStr + DES，
     * 与 index.js 里 {@code des.encrypt(ka.makeXmlRequestStr([params]))} 同序同结果。
     */
    public static String loginBody(java.util.Map<String, String> fields, String passwordField, String factor) {
        String k = factor == null || factor.isBlank() ? DEFAULT_KEY : factor;
        java.util.Map<String, String> row = new java.util.LinkedHashMap<>(fields);
        if (passwordField != null && !passwordField.isBlank() && row.containsKey(passwordField)) {
            row.put(passwordField, kaEncrypt(row.get(passwordField), k));
        }
        return desHex(xmlRequest(java.util.List.of(row)), k);
    }
}
