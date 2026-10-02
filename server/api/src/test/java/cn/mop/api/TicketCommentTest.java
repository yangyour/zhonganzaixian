package cn.mop.api;

import cn.mop.domain.*;
import cn.mop.domain.ticketcomment.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure text/visibility contract checks only; no real customer message is sent or persisted. */
class TicketCommentTest {
    Membership member(String role){return new Membership("actor","tenant",role,7,true);}
    @Test void preservesRawMultilineTextAndExactUtf8Fingerprint()throws Exception{String raw="  客户可见说明😀\n第二行\t \r\n";var comment=new TicketComment(raw);assertEquals(raw,comment.text());assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))),comment.sha256());assertNotEquals(new TicketComment("é").sha256(),new TicketComment("e\u0301").sha256());assertNotEquals(new TicketComment("x\n").sha256(),new TicketComment("x\r\n").sha256());}
    @Test void technicalLengthUsesCodePointsWithoutTruncation(){assertEquals(4000,new TicketComment("😀".repeat(2000)).text().length());assertEquals(400,assertThrows(DomainException.class,()->new TicketComment("😀".repeat(2001))).status);}
    @Test void rejectsMissingWhitespaceControlsAndMalformedUnicode(){for(String raw:new String[]{null,""," \u00a0\u202f\ufeff\n\t","x\u0000","x\u001b","x\u0085","x\ud800","x\udc00"})assertEquals(400,assertThrows(DomainException.class,()->new TicketComment(raw)).status);}
    @Test void onlyReporterCustomerOrCurrentTenantInternalRolesCanAccess(){for(String role:List.of("viewer","tadmin","msp","padmin"))assertDoesNotThrow(()->TicketCommentAccess.require(member(role),7,"tenant","actor"));for(String role:List.of("viewer","tadmin"))assertEquals(404,assertThrows(DomainException.class,()->TicketCommentAccess.require(member(role),7,"tenant","other-reporter")).status);for(String role:List.of("msp","padmin"))assertDoesNotThrow(()->TicketCommentAccess.require(member(role),7,"tenant","other-reporter"));}
    @Test void revocationStalePermissionAndForeignTenantFailClosed(){assertEquals(401,assertThrows(DomainException.class,()->TicketCommentAccess.require(null,7,"tenant","actor")).status);assertEquals(401,assertThrows(DomainException.class,()->TicketCommentAccess.require(new Membership("actor","tenant","viewer",7,false),7,"tenant","actor")).status);for(long version:List.of(0L,6L,8L))assertEquals(401,assertThrows(DomainException.class,()->TicketCommentAccess.require(member("msp"),version,"tenant","actor")).status);assertEquals(404,assertThrows(DomainException.class,()->TicketCommentAccess.require(member("padmin"),7,"foreign","actor")).status);assertEquals(403,assertThrows(DomainException.class,()->TicketCommentAccess.require(member("unknown"),7,"tenant","actor")).status);}
    @Test void unavailableReporterRelationshipCannotBeAssumed(){for(String reporter:new String[]{null,""," "})assertEquals(503,assertThrows(DomainException.class,()->TicketCommentAccess.require(member("msp"),7,"tenant",reporter)).status);}
    @Test void missingParentScopeAndEmptyActorNeverAuthorizeComments(){for(String tenant:new String[]{null,""," "})assertEquals(404,assertThrows(DomainException.class,()->TicketCommentAccess.require(new Membership("actor",tenant,"msp",7,true),7,tenant,"actor")).status);for(String actor:new String[]{null,""," "})assertEquals(401,assertThrows(DomainException.class,()->TicketCommentAccess.require(new Membership(actor,"tenant","viewer",7,true),7,"tenant","actor")).status);}
    @Test void publicAttributionNeverUsesAnInternalNameOrActorId(){for(String role:List.of("msp","padmin"))assertEquals("工程师",TicketCommentAccess.authorKind(role).publicLabel());for(String role:List.of("viewer","tadmin"))assertEquals("报障人",TicketCommentAccess.authorKind(role).publicLabel());assertThrows(DomainException.class,()->TicketCommentAccess.authorKind("unknown"));assertThrows(DomainException.class,()->TicketCommentAccess.authorKind(null));assertEquals(Set.of("id","tenantId","ticketId","observedTicketVersion","authorKind","comment","createdAt"),Arrays.stream(TicketCommentRepository.Entry.class.getRecordComponents()).map(c->c.getName()).collect(java.util.stream.Collectors.toSet()));}
}
