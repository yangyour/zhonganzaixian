package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.ticketnote.InternalTicketNote;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InternalTicketNoteTest {
    @Test void preservesRawMultilineTextAndDoesNotCreateCustomerOrWorkflowFacts(){String text="  内部调查说明\n下一行\t未核验 \r\n";var note=new InternalTicketNote(text);assertEquals(text,note.text());assertEquals(64,note.sha256().length());}
    @Test void usesUnicodeCodePointsForTheTechnicalBound(){assertDoesNotThrow(()->new InternalTicketNote("😀".repeat(2000)));assertEquals(400,assertThrows(DomainException.class,()->new InternalTicketNote("😀".repeat(2001))).status);}
    @Test void unknownOrUnicodeWhitespaceCannotBecomeAValidNote(){for(String text:new String[]{null,""," ","\ufeff","\u00a0\u202f\u2003\ufeff\n\t"})assertThrows(DomainException.class,()->new InternalTicketNote(text));}
    @Test void rejectsControlsAndMalformedSurrogatesButAllowsHumanLineBreaks(){for(String text:new String[]{"x\u0000","x\u001b","x\u0085","x\ud800","x\udc00"})assertThrows(DomainException.class,()->new InternalTicketNote(text));assertDoesNotThrow(()->new InternalTicketNote("x\n\r\t说明"));}
    @Test void fingerprintsExactUtf8BytesWithoutNormalizing()throws Exception{String text="说明😀\n原文";assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))),new InternalTicketNote(text).sha256());assertNotEquals(new InternalTicketNote("é").sha256(),new InternalTicketNote("e\u0301").sha256());assertNotEquals(new InternalTicketNote("x\n").sha256(),new InternalTicketNote("x\r\n").sha256());}
}
