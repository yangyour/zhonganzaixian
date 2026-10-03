package cn.mop.api;

import cn.mop.domain.DomainException;
import cn.mop.domain.contact.CustomerContactImportPreview;
import cn.mop.domain.contact.CustomerContactImportPreview.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CustomerContactImportPreviewTest {
    Row row(String name, List<String> roles, List<String> priorities, QuietInput quiet, Boolean enabled) {
        return new Row(name, roles, null, null, null, priorities, quiet, enabled);
    }
    Row valid(String name) { return row(name,List.of("REPORTER"),null,null,true); }
    Result preview(Row... rows) { return CustomerContactImportPreview.preview(Arrays.asList(rows)); }
    @Test void validPreviewIsNeverImportPermissionOrTenantDecisionCheck() {
        var r=preview(valid("synthetic")); assertEquals(1,r.validRows());assertEquals(0,r.invalidRows());
        assertFalse(r.canImport());assertFalse(r.writesPerformed());assertFalse(r.notificationDispatched());
        assertEquals("NOT_CHECKED",r.tenantAuthorization());assertEquals("NOT_RUN",r.existingContactCheck());
        assertEquals("NOT_RUN",r.lastDecisionMakerCheck());assertEquals("CONFIG_PENDING",r.notificationRoutingStatus());
        assertEquals("NEW_CONTACT_FORMAT_PREVIEW_ONLY",r.scope());
        assertNull(r.rows().get(0).contact().deliveryEligible());
    }
    @Test void absentEmptyAndOverLimitBatchFailBeforeAnyPartialResult() {
        assertThrows(DomainException.class,()->CustomerContactImportPreview.preview(null));
        assertThrows(DomainException.class,()->CustomerContactImportPreview.preview(List.of()));
        assertThrows(DomainException.class,()->CustomerContactImportPreview.preview(Collections.nCopies(101,valid("x"))));
        assertEquals(100,CustomerContactImportPreview.preview(Collections.nCopies(100,valid("x"))).validRows());
    }
    @Test void badRowsRetainOneBasedPositionWithoutDroppingLaterRowsOrEchoingInvalidPersonalData() {
        var r=preview(valid("one"),null,valid("bad\nname"),valid("four"));
        assertEquals(2,r.validRows());assertEquals(2,r.invalidRows());assertEquals(4,r.rows().size());
        assertEquals(List.of(1,2,3,4),r.rows().stream().map(RowResult::rowNumber).toList());
        for(int i:List.of(1,2)){assertEquals("INVALID",r.rows().get(i).status());assertEquals("VALIDATION_ERROR",r.rows().get(i).code());assertNull(r.rows().get(i).contact());}
        assertEquals("four",r.rows().get(3).contact().name());
    }
    @Test void explicitEnabledRequiredAndNotDefaultedFromDecisionRole() {
        var r=preview(row("x",List.of("DECISION_MAKER"),null,null,null),row("y",List.of("DECISION_MAKER"),null,null,false));
        assertEquals(1,r.invalidRows());assertEquals(false,r.rows().get(1).contact().deliveryEligible());
        assertFalse(r.rows().get(1).contact().enabledDecisionMaker());
    }
    @Test void duplicateNullUnknownAndEmptyRolesAreNotSilentlyNormalized() {
        for(var roles:Arrays.asList(null,List.<String>of(),List.of("REPORTER","REPORTER"),Arrays.asList("REPORTER",null),List.of("reporter"),List.of("padmin")))
            assertEquals(1,preview(row("x",roles,null,null,true)).invalidRows());
    }
    @Test void prioritiesPreserveUnknownAndExplicitEmptyAndRejectDuplicatesOrUnknown() {
        var r=preview(row("x",List.of("REPORTER"),null,null,true),row("x",List.of("REPORTER"),List.of(),null,true));
        assertNull(r.rows().get(0).contact().preferredPriorities());assertEquals(Set.of(),r.rows().get(1).contact().preferredPriorities());assertEquals(0,r.identicalRows());
        for(var priorities:Arrays.asList(List.of("P1","P1"),List.of("P5"),Arrays.asList("P1",null)))assertEquals(1,preview(row("x",List.of("REPORTER"),priorities,null,true)).invalidRows());
    }
    @Test void allQuietGroupMeaningsRemainDistinctAndCrossMidnightExplicit() {
        var r=preview(row("x",List.of("REPORTER"),null,null,true),row("x",List.of("REPORTER"),null,new QuietInput(null,null,null),true),row("x",List.of("REPORTER"),null,new QuietInput(false,null,null),true),row("x",List.of("REPORTER"),null,new QuietInput(true,"22:00","07:00"),true));
        assertEquals(4,r.validRows());assertEquals(0,r.identicalRows());assertNull(r.rows().get(0).contact().quietWindow());assertNull(r.rows().get(1).contact().quietWindow().enabled());assertFalse(r.rows().get(2).contact().quietWindow().enabled());assertTrue(r.rows().get(3).contact().quietWindow().crossesMidnight());
    }
    @Test void partialAmbiguousOrNonMinuteQuietWindowsStayInvalid() {
        for(var quiet:List.of(new QuietInput(true,"12:00","12:00"),new QuietInput(true,"7:00","08:00"),new QuietInput(true,null,"08:00"),new QuietInput(null,"12:00",null),new QuietInput(false,"12:00",null),new QuietInput(true,"24:00","08:00")))assertEquals(1,preview(row("x",List.of("REPORTER"),null,quiet,true)).invalidRows());
    }
    @Test void exactDuplicateWarningsAreNotAutomaticMergeSkipOrSameNameIdentityClaims() {
        var a=row("x",List.of("REPORTER","DECISION_MAKER"),List.of("P1","P2"),null,true);
        var b=row("x",List.of("DECISION_MAKER","REPORTER"),List.of("P2","P1"),null,true);
        var r=preview(a,b,a,valid("x"));assertEquals(4,r.validRows());assertEquals(2,r.identicalRows());
        assertNull(r.rows().get(0).identicalToRow());assertEquals(1,r.rows().get(1).identicalToRow());assertEquals(1,r.rows().get(2).identicalToRow());assertNull(r.rows().get(3).identicalToRow());
    }
    @Test void noTrimmingCountryGuessOrDeliveryCheckAndSharedNamesAllowed() {
        var good=new Row("  名称🙂  ",List.of("REPORTER"),"+8613800000000","Example+tag@example.test","synthetic-user",null,null,true);
        var bad=new Row("same",List.of("REPORTER"),"13800000000",null,null,null,null,true);
        var r=preview(good,bad,valid("same"));assertEquals(2,r.validRows());assertEquals(1,r.invalidRows());assertEquals("  名称🙂  ",r.rows().get(0).contact().name());assertEquals("NOT_VERIFIED",r.rows().get(0).contact().channelVerification());
    }
    @Test void inputAndOutputCollectionsAreDefensiveWithoutDiscardingInvalidNullMembers() {
        var roles=new ArrayList<>(List.of("REPORTER"));var prefs=new ArrayList<>(List.of("P1"));var input=row("x",roles,prefs,null,true);roles.clear();prefs.clear();
        var rows=new ArrayList<>(List.of(input));var result=CustomerContactImportPreview.preview(rows);rows.clear();assertEquals(1,result.rows().size());assertEquals(Set.of(cn.mop.domain.contact.CustomerContact.Role.REPORTER),result.rows().get(0).contact().roles());
        assertThrows(UnsupportedOperationException.class,()->input.roles().clear());assertThrows(UnsupportedOperationException.class,()->result.rows().clear());
    }
    @Test void invalidRowsDoNotParticipateInDuplicateWarnings() {
        var r=preview(valid("\n"),valid("x"),valid("\n"),valid("x"));assertEquals(2,r.invalidRows());assertEquals(1,r.identicalRows());assertEquals(2,r.rows().get(3).identicalToRow());
    }
}
