package cn.mop.api;
import cn.mop.domain.DomainException;
import cn.mop.domain.assetexport.*;
import cn.mop.domain.assetexport.AssetExportJobState.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class AssetExportPreparedFileTest {
    final Command command=new Command(new Access(UUID.randomUUID(),UUID.randomUUID(),1,"msp",true),new Filters(null,null,null,null));
    AssetExportPreparedFile file(byte[] value){return AssetExportPreparedFile.create(command,"a".repeat(64),1,Instant.EPOCH,value);}
    @Test void ownsInputAndEachReturnedArrayAndComputesActualHash(){byte[] input={1,2,3,4};var f=file(input);input[0]=9;byte[] returned=f.bytes();returned[1]=9;assertArrayEquals(new byte[]{1,2,3,4},f.bytes());assertEquals("9f64a747e1b97f131fabb6b447296c9b6f0201e79fb3c5356e6c77e89b6a806a",f.encoding().fileSha256());assertEquals(4,f.encoding().sizeBytes());assertFalse(f.published());}
    @Test void nullEmptyAndOversizeInputsAreRejected(){assertEquals(400,assertThrows(DomainException.class,()->file(null)).status);assertThrows(DomainException.class,()->file(new byte[0]));assertEquals(413,assertThrows(DomainException.class,()->file(new byte[AssetExportJobState.MAX_FILE_BYTES+1])).status);assertThrows(DomainException.class,()->AssetExportPreparedFile.create(null,"a".repeat(64),0,Instant.EPOCH,new byte[]{1}));}
    @Test void descriptorsRetainStrictHashRowAndMillisecondBoundaries(){assertThrows(DomainException.class,()->AssetExportPreparedFile.create(command,"bad",1,Instant.EPOCH,new byte[]{1}));assertThrows(DomainException.class,()->AssetExportPreparedFile.create(command,"a".repeat(64),1001,Instant.EPOCH,new byte[]{1}));assertThrows(DomainException.class,()->AssetExportPreparedFile.create(command,"a".repeat(64),1,Instant.EPOCH.plusNanos(1),new byte[]{1}));}
    @Test void restoreRequiresActualSizeAndHashToMatchPersistedDescriptor(){var f=file(new byte[]{1,2});assertTrue(f.sameContent(AssetExportPreparedFile.restore(command,f.encoding(),f.bytes())));assertEquals(409,assertThrows(DomainException.class,()->AssetExportPreparedFile.restore(command,f.encoding(),new byte[]{1,3})).status);assertEquals(409,assertThrows(DomainException.class,()->AssetExportPreparedFile.restore(command,f.encoding(),new byte[]{1})).status);assertThrows(DomainException.class,()->AssetExportPreparedFile.restore(command,null,new byte[]{1}));}
    @Test void sameBytesUnderDifferentCommandCannotBeSubstituted(){var f=file(new byte[]{1});var other=new Command(command.acceptedAccess(),new Filters(null,null,null,"different"));assertFalse(f.sameContent(AssetExportPreparedFile.create(other,"a".repeat(64),1,Instant.EPOCH,new byte[]{1})));assertFalse(f.sameContent(null));}
    @Test void identicalBytesStillBindSelectionRowsAndGeneratedTime(){var f=file(new byte[]{1});assertFalse(f.sameContent(AssetExportPreparedFile.create(command,"b".repeat(64),1,Instant.EPOCH,new byte[]{1})));assertFalse(f.sameContent(AssetExportPreparedFile.create(command,"a".repeat(64),0,Instant.EPOCH,new byte[]{1})));assertFalse(f.sameContent(AssetExportPreparedFile.create(command,"a".repeat(64),1,Instant.EPOCH.plusMillis(1),new byte[]{1})));}
    @Test void zeroDataRowsCanDescribeNonemptyHeaderOnlyFileWithoutPublication(){var f=AssetExportPreparedFile.create(command,"a".repeat(64),0,Instant.EPOCH,new byte[]{1});assertEquals(0,f.encoding().rowCount());assertFalse(f.published());assertEquals(command.fingerprint(),f.commandFingerprint());}
}
