package cn.mop.domain.ticketnote;

import cn.mop.domain.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/** Raw internal discussion text only: never a customer-visible message, formal action or SLA fact. */
public record InternalTicketNote(String text) {
    public static final int MAX_CODE_POINTS=2000;
    public InternalTicketNote {
        if(text==null||text.isEmpty()||text.codePointCount(0,text.length())>MAX_CODE_POINTS||text.codePoints().allMatch(cp->Character.isWhitespace(cp)||Character.isSpaceChar(cp)||cp==0xfeff)||text.codePoints().anyMatch(cp->Character.getType(cp)==Character.SURROGATE||Character.isISOControl(cp)&&cp!='\n'&&cp!='\r'&&cp!='\t'))throw invalid();
    }
    /** Exact UTF-8 bytes. Different line endings or normalization remain different author-supplied text. */
    public String sha256(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException unavailable){throw new IllegalStateException("SHA-256 unavailable");}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","内部备注必须为1至2000个Unicode字符的非空白文字，允许换行和制表，不接受非文本控制符或无效Unicode");}
}
