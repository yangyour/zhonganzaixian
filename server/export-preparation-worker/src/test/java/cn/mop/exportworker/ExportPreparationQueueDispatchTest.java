package cn.mop.exportworker;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetexport.*;
import cn.mop.domain.assetexport.AssetExportJobRepository.*;
import cn.mop.domain.assetexport.AssetExportJobState.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExportPreparationQueueDispatchTest {
    final Instant now=Instant.parse("2026-10-14T00:00:00Z");
    final Claim claim=new Claim(UUID.randomUUID().toString(),"queue-test",1,1,now.plusSeconds(30),new Command(new AssetExportJobState.Access(UUID.randomUUID(),UUID.randomUUID(),1,"padmin",true),new Filters(null,null,null,null)),false);
    class Queue implements AssetExportPreparationQueue {int rejected;boolean empty;public Optional<Claim> claimNextUnprepared(String w,Duration l,Instant c){assertEquals("queue-test",w);assertEquals(now,c);return empty?Optional.empty():Optional.of(claim);}public void rejectPreparationInputLimit(Claim c){assertEquals(claim,c);rejected++;}}
    AssetExportJobRepository jobs(){return (AssetExportJobRepository)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{AssetExportJobRepository.class},(proxy,method,args)->{if(method.getName().equals("loadWork"))return new Work(claim,now,null);throw new AssertionError("Unexpected worker side effect: "+method.getName());});}
    boolean process(Queue q,AssetExportSnapshotRepository source)throws Exception{return ExportPreparationQueueRuntime.processOne(jobs(),q,source,"queue-test",Duration.ofSeconds(30),now);}
    @Test void emptyQueueDoesNotReadSourceOrPublish()throws Exception{var q=new Queue();q.empty=true;assertFalse(process(q,c->{throw new AssertionError();}));assertEquals(0,q.rejected);}
    @Test void onlyRecognizedConfirmedInputLimitsBecomeTerminalFailure()throws Exception{for(String code:List.of("EXPORT_ROW_LIMIT","EXPORT_ENCODING_LIMIT")){var q=new Queue();assertTrue(process(q,c->{throw new DomainException(413,code,"synthetic input limit");}));assertEquals(1,q.rejected);}}
    @Test void unknownOrTransientErrorsNeverBecomeFakeInputFailures(){for(var e:List.of(new DomainException(503,"UPSTREAM_UNAVAILABLE","synthetic storage uncertainty"),new DomainException(409,"LEASE_LOST","lost"),new DomainException(413,"UNKNOWN_LIMIT","unknown"),new DomainException(401,"SESSION_EXPIRED","revoked"))){var q=new Queue();assertSame(e,assertThrows(DomainException.class,()->process(q,c->{throw e;})));assertEquals(0,q.rejected);}}
}
