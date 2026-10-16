package com.linyuan.app;

public class RepeatGuard {

    public static class Params {

        public int minChars = 3000;

        public int repLen = 500;

        public int winChars = 2000;

        public int maxRep = 3;

        public Params() { }

        public Params(int minChars, int repLen, int winChars, int maxRep) {
            this.minChars = Math.max(100, minChars);
            this.repLen = Math.max(50, repLen);
            this.winChars = Math.max(this.repLen * 2, winChars);
            this.maxRep = Math.max(2, maxRep);
        }
    }

    private final Params p;
    private final StringBuilder buf = new StringBuilder();

    private int scannedUpTo = 0;

    private boolean tripped = false;

    private String hitSample = "";
    private int hitCount = 0;

    public RepeatGuard(Params params) {
        this.p = params == null ? new Params() : params;
    }

    public void reset() {
        this.buf.setLength(0);
        this.scannedUpTo = 0;
        this.tripped = false;
        this.hitSample = "";
        this.hitCount = 0;
    }

    public boolean isTripped() { return this.tripped; }

    public String hitSample() { return this.hitSample; }

    public int hitCount() { return this.hitCount; }

    public int length() { return this.buf.length(); }

    public boolean append(String delta) {
        if (delta == null || delta.isEmpty()) return false;
        this.buf.append(delta);
        if (this.tripped) return false;
        if (this.buf.length() < this.p.minChars) return false;

        int len = this.buf.length();
        if (len - this.scannedUpTo < this.p.repLen) return false;
        this.scannedUpTo = len;

        String tail = this.buf.substring(Math.max(0, len - this.p.winChars), len);
        if (!detectRepeat(tail)) return false;

        this.tripped = true;
        return true;
    }

    private boolean detectRepeat(String s) {
        int rep = this.p.repLen;
        if (s == null || s.length() < rep * 2) return false;

        int best = 0;
        String bestFrag = "";

        int step = Math.max(1, rep / 4);
        for (int i = 0; i + rep <= s.length(); i += step) {
            String frag = s.substring(i, i + rep);

            if (isLowInformation(frag)) continue;
            int c = countOccurrences(s, frag);
            if (c > best) { best = c; bestFrag = frag; }
        }
        if (best >= this.p.maxRep) {
            this.hitCount = best;
            this.hitSample = bestFrag.length() > 80 ? bestFrag.substring(0, 80) : bestFrag;
            return true;
        }
        return false;
    }

    private static int countOccurrences(String hay, String needle) {
        int c = 0, from = 0;
        while (true) {
            int i = hay.indexOf(needle, from);
            if (i < 0) return c;
            ++c;
            from = i + needle.length();
            if (c > 100000) return c;
        }
    }

    private static boolean isLowInformation(String frag) {
        int meaningful = 0;
        for (int i = 0; i < frag.length(); ++i) {
            char ch = frag.charAt(i);
            if (Character.isLetterOrDigit(ch)) ++meaningful;
        }

        return meaningful * 5 < frag.length();
    }
}
