package cn.mop.api;
import cn.mop.domain.DomainException;
import cn.mop.domain.monitoring.MetricSeriesProjection;
import cn.mop.domain.monitoring.MetricSeriesProjection.*;
import cn.mop.domain.monitoring.PortUtilizationDisplay;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class MetricSeriesProjectionTest {
    final Scope scope=new Scope(UUID.fromString("88000000-0000-4000-8000-000000000001"),UUID.fromString("88000000-0000-4000-8000-000000000002"),"interface.traffic.in");
    final Instant from=Instant.parse("2026-10-04T00:00:00Z"),to=Instant.parse("2026-10-05T00:00:00Z");
    final Conversion mapping=new Conversion("explicit-test-v1","bytes/s","bits/s","8","0");
    Point point(String id,String value){return new Point(scope,id,to,value,"bytes/s",value==null?MissingReason.NO_SAMPLE:null);}
    MetricSeriesProjection project(List<Point> points,Conversion conversion){return MetricSeriesProjection.project(scope,from,to,to,points,conversion);}
    @Test void exactUnitConversionRetainsOriginalValueAndSourceTimestamp(){var result=project(List.of(point("p1","9007199254740993.125")),mapping);var p=result.points().get(0);assertEquals("72057594037927945",p.value());assertEquals("9007199254740993.125",p.originalValue());assertEquals(to,p.sampledAt());assertEquals("bytes/s",p.originalUnit());assertEquals("bits/s",p.targetUnit());assertEquals(Quality.SAMPLE_PRESENT,p.quality());assertNull(p.missingReason());}
    @Test void decimalFractionAndOffsetAreExactNotBinaryFloatingPoint(){var c=new Conversion("v1","raw","degreesC","0.1","-40");var p=new Point(scope,"p",to,"400.01","raw",null);assertEquals("0.001",project(List.of(p),c).points().get(0).value());}
    @Test void missingMappingDoesNotAssumeIdentityEvenForMatchingUnitNames(){var p=project(List.of(point("p1","7")),null).points().get(0);assertNull(p.value());assertNull(p.targetUnit());assertNull(p.mappingVersion());assertEquals("7",p.originalValue());assertEquals("UNIT_MAPPING_UNAVAILABLE",p.missingReason());assertEquals(Quality.UNKNOWN,p.quality());}
    @Test void absentEmptyAndUnknownPointCollectionsRemainDistinct(){assertNull(project(null,mapping).points());assertEquals("READER_RESULT_UNAVAILABLE",project(null,mapping).seriesStatus());assertEquals(List.of(),project(List.of(),mapping).points());assertEquals("EXPLICIT_EMPTY_RESULT",project(List.of(),mapping).seriesStatus());var p=project(List.of(point("missing",null)),mapping).points().get(0);assertNull(p.value());assertEquals("NO_SAMPLE",p.missingReason());}
    @Test void unknownQualityRetainsSourceReasonAndNeverRepeatsPriorGoodValue(){var list=List.of(point("one","1"),point("two",null));var p=project(list,mapping);assertEquals("8",p.points().get(0).value());assertNull(p.points().get(1).value());assertFalse(p.interpolated());}
    @Test void everyUnknownReasonIsExplicitAndNotCombinedWithAKnownValue(){for(var reason:MissingReason.values()){var p=new Point(scope,"p",to,null,"bytes/s",reason);assertEquals(reason.name(),project(List.of(p),mapping).points().get(0).missingReason());assertThrows(DomainException.class,()->new Point(scope,"p",to,"1","bytes/s",reason));}assertThrows(DomainException.class,()->new Point(scope,"p",to,null,"bytes/s",null));}
    @Test void finitePlainDecimalOnlyAndNoNegativeZeroNormalization(){for(String value:List.of("NaN","Infinity","1e3","+1","01",".5","1."," 1","1\n","-0","-0.00"))assertThrows(DomainException.class,()->point("p",value));assertEquals("0",project(List.of(point("p","0.000")),mapping).points().get(0).value());}
    @Test void supportsExplicitNegativeMeasurementsWithoutAssigningHealth(){assertEquals("-12",project(List.of(point("p","-1.5")),mapping).points().get(0).value());assertEquals("UNKNOWN",project(List.of(point("p","-1.5")),mapping).availability());}
    @Test void boundedNumericPrecisionPreventsUnboundedExpansion(){String forty="9".repeat(40);assertNotNull(project(List.of(point("p",forty+"."+forty)),mapping).points().get(0).value());assertThrows(DomainException.class,()->point("p","9".repeat(41)));assertThrows(DomainException.class,()->point("p","0."+"1".repeat(41)));}
    @Test void invalidMappingsAndConflictingOriginalUnitsAreRejected(){for(String factor:List.of("0","-1","1e2"))assertThrows(DomainException.class,()->new Conversion("v","b","B",factor,"0"));assertThrows(DomainException.class,()->project(List.of(point("p","1")),new Conversion("v","other","bits/s","8","0")));}
    @Test void scopeMustMatchEachPointWithoutForeignTenantAssetOrMetricMixing(){for(var other:List.of(new Scope(UUID.randomUUID(),scope.assetId(),scope.metricKey()),new Scope(scope.tenantId(),UUID.randomUUID(),scope.metricKey()),new Scope(scope.tenantId(),scope.assetId(),"interface.traffic.out"))){var p=new Point(other,"p",to,"1","bytes/s",null);assertThrows(DomainException.class,()->project(List.of(p),mapping));}}
    @Test void endpointsAreInclusiveButFutureOutOfRangeAndReverseOrderFail(){var a=new Point(scope,"a",from,"1","bytes/s",null);assertEquals(2,project(List.of(a,point("b","2")),mapping).points().size());assertThrows(DomainException.class,()->project(List.of(point("b","2"),a),mapping));for(Instant time:List.of(from.minusMillis(1),to.plusMillis(1)))assertThrows(DomainException.class,()->project(List.of(new Point(scope,"p",time,"1","bytes/s",null)),mapping));}
    @Test void duplicateSourceIdsAreNotSilentlyDroppedButSameTimeDifferentIdsRemain(){assertThrows(DomainException.class,()->project(List.of(point("same","1"),point("same","1")),mapping));assertEquals(2,project(List.of(point("a","1"),point("b","1")),mapping).points().size());}
    @Test void immutableResultNeverMutatesInputOrderOrFillsMissingPoints(){var input=new ArrayList<>(List.of(point("a","1"),point("b",null)));var result=project(input,mapping);input.clear();assertEquals(2,result.points().size());assertThrows(UnsupportedOperationException.class,()->result.points().clear());assertEquals("a",result.points().get(0).sourcePointId());}
    @Test void explicit10000PointBoundRejectsInsteadOfSilentAggregation(){var points=new ArrayList<Point>();for(int i=0;i<10000;i++)points.add(point("p"+i,"1"));assertEquals(10000,project(points,mapping).points().size());points.add(point("overflow","1"));assertEquals(413,assertThrows(DomainException.class,()->project(points,mapping)).status);}
    @Test void observationAndWindowMustBeExplicitAndMilliseconds(){assertThrows(DomainException.class,()->MetricSeriesProjection.project(scope,from,to,to.minusMillis(1),null,mapping));assertThrows(DomainException.class,()->MetricSeriesProjection.project(scope,to,to,to,null,mapping));assertThrows(DomainException.class,()->MetricSeriesProjection.project(scope,from,to,null,null,mapping));assertThrows(DomainException.class,()->new Point(scope,"p",to.plusNanos(1),"1","bytes/s",null));}
    @Test void resultDoesNotEstablishAuthorizationMappingApprovalSourceOrAlertRules(){var r=project(List.of(point("p","1")),mapping);assertFalse(r.authorizationVerified());assertFalse(r.sourceVerified());assertFalse(r.mappingApprovalVerified());assertFalse(r.aggregationApplied());assertFalse(r.writesPerformed());assertNull(r.monitoringReady());assertNull(r.ruleVersion());assertNull(r.effectiveRule());assertEquals("BASE10_STRINGS",r.numericEncoding());assertEquals("EXPLICIT_ADAPTER_INPUT_NOT_VERIFIED_MONITORING",r.source());}
    @Test void trafficColorsUseStrictContractBoundariesWithoutCallingNeutralHealthy(){assertEquals(PortUtilizationDisplay.Highlight.UNKNOWN,PortUtilizationDisplay.highlight(null));for(String n:List.of("0","50","50.000"))assertEquals(PortUtilizationDisplay.Highlight.NO_HIGHLIGHT,PortUtilizationDisplay.highlight(n));for(String n:List.of("50.0000000000000000000000000000000000000001","80"))assertEquals(PortUtilizationDisplay.Highlight.YELLOW,PortUtilizationDisplay.highlight(n));for(String n:List.of("80.0000000000000000000000000000000000000001","101"))assertEquals(PortUtilizationDisplay.Highlight.RED,PortUtilizationDisplay.highlight(n));}
    @Test void trafficColorRejectsMissingUnitsEncodedAsStringsOrMalformedValues(){for(String n:List.of("80%","-1","NaN","1e2"," 80","81\n"))assertThrows(DomainException.class,()->PortUtilizationDisplay.highlight(n));}
    @Test void portTableHasEightImmutableContractOrderedColumns(){assertEquals(List.of(PortUtilizationDisplay.Column.values()),PortUtilizationDisplay.columns());assertEquals(8,PortUtilizationDisplay.columns().size());assertThrows(UnsupportedOperationException.class,()->PortUtilizationDisplay.columns().clear());}
    @Test void projectedPointConstructorRejectsContradictoryOrNoncanonicalFacts(){
        assertThrows(DomainException.class,()->new ProjectedPoint("p",to,"1","bytes/s","8.00","bits/s",Quality.SAMPLE_PRESENT,null,"v1"));
        assertThrows(DomainException.class,()->new ProjectedPoint("p",to,null,"bytes/s","0","bits/s",Quality.SAMPLE_PRESENT,null,"v1"));
        assertThrows(DomainException.class,()->new ProjectedPoint("p",to,"1","bytes/s",null,"bits/s",Quality.UNKNOWN,"UNIT_MAPPING_UNAVAILABLE","v1"));
        assertThrows(DomainException.class,()->new ProjectedPoint("p",to,null,"bytes/s",null,null,Quality.UNKNOWN,"UNIT_MAPPING_UNAVAILABLE",null));
    }
    @Test void scopeLabelsNullPointsAndMetadataHaveExplicitTechnicalBounds(){
        assertThrows(DomainException.class,()->new Scope(scope.tenantId(),scope.assetId(),"../provider.item"));
        assertThrows(DomainException.class,()->new Point(null,"p",to,"1","bytes/s",null));
        assertThrows(DomainException.class,()->new Point(scope,"p\n",to,"1","bytes/s",null));
        assertThrows(DomainException.class,()->new Conversion("x".repeat(129),"bytes/s","bits/s","8","0"));
        assertThrows(DomainException.class,()->project(Arrays.asList((Point)null),mapping));
    }

    @Test void originalUnicodeUnitsArePreservedWithoutGuessingOrNormalization(){
        var p=new Point(scope,"p",to,"123","°C",null);var c=new Conversion("v1","°C","摄氏度","0.1","0");
        var projected=project(List.of(p),c).points().get(0);assertEquals("°C",projected.originalUnit());assertEquals("摄氏度",projected.targetUnit());assertEquals("12.3",projected.value());
        for(String u:List.of("", " °C", "°C\n", "°C\u200B", "\uD800"))assertThrows(DomainException.class,()->new Point(scope,"p",to,"1",u,null));
    }

    @Test void missingSourceRecordTimeDoesNotMasqueradeAsANewMetricSample(){
        var point=project(List.of(point("gap",null)),mapping).points().get(0);
        assertEquals(to,point.sourceRecordedAt());assertNull(point.sampledAt());assertNull(point.value());
        assertEquals(to,project(List.of(point("known","1")),null).points().get(0).sampledAt());
    }

}
