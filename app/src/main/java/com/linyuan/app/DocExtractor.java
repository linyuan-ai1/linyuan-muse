package com.linyuan.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;

public class DocExtractor {

    public static final int MAX_DOC_CHARS = 400000;
    public static final int CHUNK_CHARS = 500;

    public static final int MAX_CHUNKS = 800;
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    public static class DocResult {
        public String text = "";
        public String error = null;
        public boolean scannedPdf = false;
        public long charCount = 0;

        public boolean truncated = false;
    }

    public static class Chunk {
        public String text;
        public int index;
        public Chunk(String text, int index) { this.text = text; this.index = index; }
    }

    public static DocResult extract(InputStream in, String fileName) {
        DocResult result = new DocResult();
        byte[] bytes;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_FILE_BYTES) {
                    result.error = "文件超过 5MB 上限，未导入";
                    return result;
                }
                bos.write(buf, 0, n);
            }
            bytes = bos.toByteArray();
        } catch (Exception e) {
            result.error = "读取失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return result;
        }
        if (bytes.length == 0) {
            result.error = "文件为空";
            return result;
        }
        String name = fileName == null ? "" : fileName.toLowerCase();

        if (isImageOrMediaFile(name, bytes)) {
            result.error = "这是图片/音视频等二进制文件，不能当文本文档导入。"
                    + "图片请在对话里点 📷 发送，我才能看到它并识别内容。";
            return result;
        }
        if (name.endsWith(".pdf")) {
            String pdfText = extractPdfText(bytes);
            if (pdfText == null || pdfText.trim().length() < 30) {
                result.scannedPdf = true;
                result.error = "PDF 无法提取文字（可能是扫描件/图片型 PDF）";
                return result;
            }
            result.text = pdfText;
        } else {

            result.text = decodeText(bytes);
            if (result.text.trim().isEmpty()) {
                result.error = "未能解码为文本（UTF-8/GBK 均失败）";
                return result;
            }
        }

        if (result.text != null && !result.text.isEmpty()) {
            int bad = 0;
            for (int i = 0; i < result.text.length(); i++) {
                if (result.text.charAt(i) == '\uFFFD') bad++;
            }
            if (bad * 20 > result.text.length()) {
                result.error = "这个文件不是文本（" + Math.round(bad * 100.0 / result.text.length())
                        + "% 的内容无法解码），不能导入。图片请用对话里的 📷 发送。";
                return result;
            }
        }
        result.charCount = result.text.length();
        if (result.text.length() > MAX_DOC_CHARS) {

            result.text = result.text.substring(0, MAX_DOC_CHARS);
            result.truncated = true;
        }
        return result;
    }

    public static List<Chunk> chunkText(String text) {
        List<Chunk> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return chunks;
        String t = text.trim();
        int start = 0;
        int idx = 0;
        while (start < t.length() && idx < MAX_CHUNKS) {
            int end = Math.min(start + CHUNK_CHARS, t.length());

            if (end < t.length()) {
                int cut = -1;
                for (int i = end; i > start + CHUNK_CHARS / 2; i--) {
                    char c = t.charAt(i - 1);
                    if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '；' || c == '.') { cut = i; break; }
                }
                if (cut > 0) end = cut;
            }
            String chunk = t.substring(start, end).trim();
            if (!chunk.isEmpty()) chunks.add(new Chunk(chunk, idx));
            start = end;
            idx++;
        }
        return chunks;
    }

    private static String decodeText(byte[] bytes) {

        int off = 0;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) off = 3;
        byte[] body = off > 0 ? java.util.Arrays.copyOfRange(bytes, off, bytes.length) : bytes;
        String utf8 = decodeStrict(body, Charset.forName("UTF-8"));
        if (utf8 != null) return utf8;
        String gbk = decodeStrict(body, Charset.forName("GBK"));
        if (gbk != null) return gbk;

        return new String(body, Charset.forName("UTF-8"));
    }

    private static String decodeStrict(byte[] bytes, Charset cs) {
        try {
            java.nio.CharBuffer cb = cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            String s = cb.toString();

            int nul = 0;
            for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\u0000') nul++;
            if (nul > s.length() / 20) return null;
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isImageOrMediaFile(String lowerName, byte[] b) {
        if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png")
                || lowerName.endsWith(".gif") || lowerName.endsWith(".webp") || lowerName.endsWith(".bmp")
                || lowerName.endsWith(".heic") || lowerName.endsWith(".heif") || lowerName.endsWith(".tif")
                || lowerName.endsWith(".tiff") || lowerName.endsWith(".ico") || lowerName.endsWith(".mp4")
                || lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") || lowerName.endsWith(".amr")
                || lowerName.endsWith(".m4a") || lowerName.endsWith(".zip") || lowerName.endsWith(".apk")) return true;
        if (b == null || b.length < 12) return false;
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return true;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return true;
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return true;
        if (b[0] == 'B' && b[1] == 'M') return true;
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return true;
        if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return true;
        return false;
    }

    private static String extractPdfText(byte[] bytes) {
        StringBuilder sb = new StringBuilder();

        int pos = 0;
        String s = new String(bytes, Charset.forName("ISO-8859-1"));
        while (true) {
            int si = s.indexOf("stream", pos);
            if (si < 0) break;

            int dataStart = si + 6;
            if (dataStart < s.length() && s.charAt(dataStart) == '\r') dataStart++;
            if (dataStart < s.length() && s.charAt(dataStart) == '\n') dataStart++;
            int ei = s.indexOf("endstream", dataStart);
            if (ei < 0) break;
            byte[] raw = java.util.Arrays.copyOfRange(bytes, dataStart, ei);
            byte[] inflated = inflate(raw);
            if (inflated != null) {
                String content = new String(inflated, Charset.forName("ISO-8859-1"));
                extractTextOperators(content, sb);
            }
            pos = ei + 9;
        }

        String text = sb.toString();
        text = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", " ");
        text = text.replaceAll("[ \\t]{2,}", " ");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.trim();
    }

    private static byte[] inflate(byte[] data) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(data);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length * 3);
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n <= 0) break;
                bos.write(buf, 0, n);
            }
            return bos.size() > 0 ? bos.toByteArray() : null;
        } catch (Exception e) {
            return null;
        } finally {

            inf.end();
        }
    }

    private static void extractTextOperators(String content, StringBuilder sb) {
        int i = 0;
        int len = content.length();
        boolean lastNewline = false;
        while (i < len) {
            char c = content.charAt(i);
            if (c == '(') {

                int close = findParenClose(content, i + 1);
                if (close > 0) {
                    String lit = unescapePdfString(content.substring(i + 1, close));
                    appendPdfText(sb, lit);
                    lastNewline = false;
                    i = close + 1;

                    continue;
                }
            } else if (c == '[') {

                int close = findBracketClose(content, i + 1);
                if (close > 0) {
                    String inner = content.substring(i + 1, close);
                    String lit = extractTjArray(inner);
                    appendPdfText(sb, lit);
                    lastNewline = false;
                    i = close + 1;
                    continue;
                }
            } else if (c == 'T' && i + 1 < len && content.charAt(i + 1) == 'J') {

                i += 2;
                if (!lastNewline) { sb.append(' '); }
                continue;
            } else if (c == 'T' && i + 1 < len && content.charAt(i + 1) == 'j') {
                i += 2;
                if (!lastNewline) { sb.append(' '); }
                continue;
            } else if (c == 'T' && i + 1 < len && content.charAt(i + 1) == '*' ) {

                i += 2;
                if (!lastNewline) { sb.append('\n'); lastNewline = true; }
                continue;
            }
            i++;
        }
    }

    private static void appendPdfText(StringBuilder sb, String lit) {
        if (lit == null || lit.isEmpty()) return;
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n' && sb.charAt(sb.length() - 1) != ' ') {
            sb.append(' ');
        }
        sb.append(lit);
    }

    private static int findParenClose(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '(') depth++;
            else if (c == ')') {
                if (depth == 0) return i;
                depth--;
            }
        }
        return -1;
    }

    private static int findBracketClose(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                if (depth == 0) return i;
                depth--;
            }
        }
        return -1;
    }

    private static String extractTjArray(String inner) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < inner.length()) {
            char c = inner.charAt(i);
            if (c == '(') {
                int close = findParenClose(inner, i + 1);
                if (close > 0) {
                    sb.append(unescapePdfString(inner.substring(i + 1, close)));
                    i = close + 1;
                    continue;
                }
            }
            i++;
        }
        return sb.toString();
    }

    private static String unescapePdfString(String raw) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char n = raw.charAt(i + 1);
                switch (n) {
                    case 'n': sb.append('\n'); i += 2; break;
                    case 'r': sb.append('\r'); i += 2; break;
                    case 't': sb.append('\t'); i += 2; break;
                    case '(': sb.append('('); i += 2; break;
                    case ')': sb.append(')'); i += 2; break;
                    case '\\': sb.append('\\'); i += 2; break;
                    default:
                        if (n >= '0' && n <= '7') {

                            int oct = 0, k = 0;
                            while (k < 3 && i + 1 + k < raw.length()) {
                                char o = raw.charAt(i + 1 + k);
                                if (o < '0' || o > '7') break;
                                oct = oct * 8 + (o - '0');
                                k++;
                            }
                            sb.append((char) (oct & 0xFF));
                            i += 1 + k;
                        } else {
                            sb.append(n);
                            i += 2;
                        }
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
