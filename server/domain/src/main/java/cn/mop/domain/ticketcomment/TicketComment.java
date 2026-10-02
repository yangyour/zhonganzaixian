package cn.mop.domain.ticketcomment;

import cn.mop.domain.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/** Explicitly customer-visible discussion, separate from immutable INTERNAL notes and formal actions. */
public record TicketComment(String text) {
    public static final int MAX_CODE_POINTS=2000;
    public TicketComment {
        if(text==null||text.isEmpty()||text.codePointCount(0,text.length())>MAX_CODE_POINTS||text.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)||text.codePoints().anyMatch(cp->Character.getType(cp)==Character.SURROGATE||Character.isISOControl(cp)&&cp!='\n'&&cp!='\r'&&cp!='\t'))throw new DomainException(400,"VALIDATION_ERROR","客户可见评论必须为1至2000个Unicode字符的非空白文字，允许换行和制表，不接受非文本控制符或无效Unicode");
    }
    /** Preserve exactly what was submitted; neither Unicode normalization nor newline rewriting. */
    public String sha256(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException unavailable){throw new IllegalStateException("SHA-256 unavailable");}}
}
