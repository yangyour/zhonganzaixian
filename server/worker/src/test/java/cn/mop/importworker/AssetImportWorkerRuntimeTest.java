package cn.mop.importworker;

import cn.mop.domain.assetimport.source.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure lifecycle tests; no PostgreSQL or storage integration is inferred. */
class AssetImportWorkerRuntimeTest {
    private static final AssetImportParser PARSER=(path,selection)->{throw new AssertionError("No external or fake-success parser in lifecycle tests");};
    private static AssetImportObjectStore store(boolean ready){return new AssetImportObjectStore(){public boolean ready(){return ready;}public void storeImmutable(String key,byte[] source,ImportFormat format,String hash){throw new AssertionError();}public byte[] read(String key,long size,String hash){throw new AssertionError();}};}
    @Test void allActualStartupDependenciesMustBeReadyBeforeAnyClaim(){
        for(boolean[] ready:new boolean[][]{{false,true,true},{true,false,true},{true,true,false}}){AtomicInteger claims=new AtomicInteger();var worker=new AssetImportWorkerRuntime(()->ready[0],(owner,storage,parser)->{claims.incrementAndGet();return true;},store(ready[1]),PARSER,()->ready[2],()->{});assertThrows(IllegalStateException.class,worker::start);assertEquals(0,claims.get());assertFalse(worker.isRunning());}
        var worker=new AssetImportWorkerRuntime(()->{throw new IllegalStateException("PRIVATE-ISOLATED-DEPENDENCY");},(owner,storage,parser)->true,store(true),PARSER,()->true,()->{});var failure=assertThrows(IllegalStateException.class,worker::start);assertFalse(failure.getMessage().contains("PRIVATE"));assertNull(failure.getCause());
    }
    @Test void drainsOnlyTheInjectedImportProcessorWithOneStableLeaseOwnerAndSerialCalls()throws Exception{
        CountDownLatch three=new CountDownLatch(3);List<String> owners=new CopyOnWriteArrayList<>();AtomicInteger calls=new AtomicInteger(),concurrent=new AtomicInteger(),maximum=new AtomicInteger(),closed=new AtomicInteger();var storage=store(true);
        var worker=new AssetImportWorkerRuntime(()->true,(owner,actualStore,actualParser)->{int active=concurrent.incrementAndGet();maximum.accumulateAndGet(active,Math::max);assertSame(storage,actualStore);assertSame(PARSER,actualParser);owners.add(owner);three.countDown();concurrent.decrementAndGet();return calls.incrementAndGet()<3;},storage,PARSER,()->true,closed::incrementAndGet);
        try{worker.start();assertTrue(three.await(3,TimeUnit.SECONDS));worker.stop();assertFalse(worker.isRunning());assertEquals(1,maximum.get());assertEquals(1,new HashSet<>(owners).size());assertTrue(owners.get(0).startsWith("asset-import-worker-"));assertTrue(owners.get(0).length()<=64);assertEquals(1,closed.get());}finally{worker.stop();}
    }
    @Test void dependencyFailureAndIdlePollsBackOffAndStopInterruptsDelay()throws Exception{
        CountDownLatch third=new CountDownLatch(3);List<Long> instants=new CopyOnWriteArrayList<>();AtomicInteger calls=new AtomicInteger();
        var worker=new AssetImportWorkerRuntime(()->true,(owner,storage,parser)->{instants.add(System.nanoTime());third.countDown();if(calls.incrementAndGet()==1)throw new IllegalStateException("PRIVATE-ISOLATED-ERROR");return false;},store(true),PARSER,()->true,()->{});
        try{worker.start();assertTrue(third.await(5,TimeUnit.SECONDS));assertTrue(instants.get(1)-instants.get(0)>=900_000_000);assertTrue(instants.get(2)-instants.get(1)>=200_000_000);long stopping=System.nanoTime();worker.stop();assertTrue(System.nanoTime()-stopping<1_000_000_000);assertFalse(worker.isRunning());}finally{worker.stop();}
    }
    @Test void boundedStopDoesNotAllowASecondConsumerWhileAnOldOperationIsStillFenced()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),finished=new CountDownLatch(1);AtomicInteger stopped=new AtomicInteger();
        var worker=new AssetImportWorkerRuntime(()->true,(owner,storage,parser)->{entered.countDown();boolean interrupted=false;while(release.getCount()>0)try{release.await();}catch(InterruptedException stop){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();finished.countDown();return false;},store(true),PARSER,()->true,stopped::incrementAndGet,100);
        try{worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));long before=System.nanoTime();worker.stop();assertTrue(System.nanoTime()-before<1_000_000_000);assertEquals(1,stopped.get());assertThrows(IllegalStateException.class,worker::start);release.countDown();assertTrue(finished.await(3,TimeUnit.SECONDS));}finally{release.countDown();worker.stop();}
    }
}
