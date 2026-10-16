package com.linyuan.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

final class MarkdownRenderer {

    private MarkdownRenderer() { }

    private static final Parser PARSER = Parser.builder().build();

    static CharSequence render(String md, int textColor, int mutedColor, int accentColor, boolean mono) {
        if (md == null || md.isEmpty()) return "";
        if (!looksLikeMarkdown(md)) return md;
        try {
            Node doc = PARSER.parse(md);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            MdVisitor v = new MdVisitor(sb, textColor, mutedColor, accentColor, mono);
            doc.accept(v);
            return trim(sb);
        } catch (Exception e) {

            return md;
        }
    }

    private static boolean looksLikeMarkdown(String s) {
        if (s.contains("**")) return true;
        if (s.contains("```")) return true;
        if (s.contains("](") && s.contains("[")) return true;
        if (s.startsWith("# ") || s.startsWith("## ")) return true;

        if (s.contains("\n# ") || s.contains("\n## ") || s.contains("\n### ")) return true;

        if (s.contains("\n- ") || s.contains("\n* ") || s.startsWith("- ")) return true;
        if (s.contains("\n> ") || s.startsWith("> ")) return true;
        if (s.contains("\n---") || s.contains("\n***")) return true;
        return false;
    }

    private static CharSequence trim(SpannableStringBuilder sb) {
        int n = sb.length();
        while (n > 0 && (sb.charAt(n - 1) == '\n' || sb.charAt(n - 1) == ' ')) n--;
        if (n < sb.length()) sb.delete(n, sb.length());
        return sb;
    }

    private static final class MdVisitor extends AbstractVisitor {
        private final SpannableStringBuilder out;
        private final int textColor, mutedColor, accentColor;
        private final boolean mono;
        private boolean firstBlock = true;

        private boolean suppressBlockBreak = false;

        MdVisitor(SpannableStringBuilder out, int textColor, int mutedColor, int accentColor, boolean mono) {
            this.out = out;
            this.textColor = textColor;
            this.mutedColor = mutedColor;
            this.accentColor = accentColor;
            this.mono = mono;
        }

        private void startBlock() {
            if (!firstBlock) out.append("\n");
            firstBlock = false;
        }

        private void span(Object what, int start, int flags) {
            if (out.length() > start) out.setSpan(what, start, out.length(), flags);
        }

        @Override public void visit(Heading h) {
            startBlock();
            int start = out.length();
            visitChildren(h);
            float scale = h.getLevel() == 1 ? 1.32f : (h.getLevel() == 2 ? 1.18f : 1.08f);
            span(new RelativeSizeSpan(scale), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span(new StyleSpan(Typeface.BOLD), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span(new ForegroundColorSpan(textColor), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        @Override public void visit(Paragraph p) {
            if (suppressBlockBreak) {
                suppressBlockBreak = false;
            } else {
                startBlock();
            }
            int start = out.length();
            visitChildren(p);
            span(new ForegroundColorSpan(textColor), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        @Override public void visit(Text t) {
            int start = out.length();
            out.append(t.getLiteral());
            span(new ForegroundColorSpan(textColor), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        @Override public void visit(StrongEmphasis e) {
            int start = out.length();
            visitChildren(e);
            span(new StyleSpan(Typeface.BOLD), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        @Override public void visit(Emphasis e) {
            int start = out.length();
            visitChildren(e);
            span(new StyleSpan(Typeface.ITALIC), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        @Override public void visit(Code c) {
            int start = out.length();
            out.append(c.getLiteral());
            span(new TypefaceSpan("monospace"), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span(new BackgroundColorSpan(0x22808080), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span(new ForegroundColorSpan(accentColor), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        @Override public void visit(FencedCodeBlock cb) {
            startBlock();
            int start = out.length();
            out.append(cb.getLiteral() == null ? "" : cb.getLiteral().trim());
            span(new TypefaceSpan("monospace"), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span(new BackgroundColorSpan(0x22808080), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span(new ForegroundColorSpan(mutedColor), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span(new LeadingMarginSpan.Standard(24), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        @Override public void visit(IndentedCodeBlock cb) {
            startBlock();
            int start = out.length();
            out.append(cb.getLiteral() == null ? "" : cb.getLiteral().trim());
            span(new TypefaceSpan("monospace"), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span(new ForegroundColorSpan(mutedColor), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span(new LeadingMarginSpan.Standard(24), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        @Override public void visit(BulletList l) {
            startBlock();
            visitChildren(l);
        }

        @Override public void visit(OrderedList l) {
            startBlock();
            visitChildren(l);
        }

        @Override public void visit(ListItem li) {

            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append("\n");
            int start = out.length();
            suppressBlockBreak = true;
            visitChildren(li);
            suppressBlockBreak = false;

            span(new BulletSpan(20, accentColor), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span(new LeadingMarginSpan.Standard(28), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        @Override public void visit(BlockQuote q) {
            startBlock();
            int start = out.length();
            visitChildren(q);
            span(new LeadingMarginSpan.Standard(24), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span(new ForegroundColorSpan(mutedColor), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        @Override public void visit(ThematicBreak tb) {
            startBlock();
            int start = out.length();
            out.append("────────────");
            span(new ForegroundColorSpan(mutedColor), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        @Override public void visit(Link link) {
            int start = out.length();
            visitChildren(link);
            span(new UnderlineSpan(), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span(new ForegroundColorSpan(accentColor), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        @Override public void visit(Image img) {

            int start = out.length();
            out.append("🖼 ");
            visitChildren(img);
            span(new ForegroundColorSpan(mutedColor), start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        @Override public void visit(SoftLineBreak b) { out.append(" "); }
        @Override public void visit(HardLineBreak b) { out.append("\n"); }

        @Override public void visit(HtmlInline h) {  }
        @Override public void visit(HtmlBlock h) {  }
    }
}
