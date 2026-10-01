package cn.mop.worker;

import cn.mop.domain.PhotoObjectStore;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated lifecycle checks, not PostgreSQL/MinIO integration evidence. */
class PhotoWorkerRuntimeTest {
    private static PhotoObjectStore store(boolean ready) {
        return new PhotoObjectStore() {
            public boolean ready(){return ready;}
            public void storeImmutable(String key,byte[] content,String mediaType,String sha256){throw new AssertionError("No external storage in lifecycle checks");}
            public byte[] read(String key,long expectedSize,String sha256){throw new AssertionError("No external storage in lifecycle checks");}
        };
    }

    @Test void unavailableSchemaOrStorageCannotClaimAnyJob() {
        for(boolean[] readiness:new boolean[][]{{false,true},{true,false},{false,false}}) {
            AtomicInteger claims=new AtomicInteger();
            PhotoWorkerRuntime worker=new PhotoWorkerRuntime(()->readiness[0],(id,objectStore)->{claims.incrementAndGet();return true;},store(readiness[1]));
            assertThrows(IllegalStateException.class,worker::start);
            assertFalse(worker.isRunning());assertEquals(0,claims.get());
        }
        PhotoWorkerRuntime disconnected=new PhotoWorkerRuntime(()->{throw new IllegalStateException("private-isolated-error");},(id,objectStore)->true,store(true));
        IllegalStateException failure=assertThrows(IllegalStateException.class,disconnected::start);
        assertFalse(failure.getMessage().contains("private-isolated-error"));assertNull(failure.getCause());
    }

    @Test void drainsPhotoJobsWithAStableUniqueLeaseOwnerAndStops() throws InterruptedException {
        CountDownLatch firstJobs=new CountDownLatch(3),secondJob=new CountDownLatch(1);
        List<String> owners=new ArrayList<>();
        AtomicInteger calls=new AtomicInteger(),callbacks=new AtomicInteger();
        PhotoObjectStore firstStore=store(true);
        PhotoWorkerRuntime first=new PhotoWorkerRuntime(()->true,(id,objectStore)->{
            assertSame(firstStore,objectStore);owners.add(id);firstJobs.countDown();return calls.incrementAndGet()<3;
        },firstStore);
        PhotoWorkerRuntime second=new PhotoWorkerRuntime(()->true,(id,objectStore)->{
            owners.add(id);secondJob.countDown();return false;
        },store(true));
        try {
            first.start();assertTrue(firstJobs.await(3,TimeUnit.SECONDS));
            first.stop(callbacks::incrementAndGet);assertFalse(first.isRunning());assertEquals(1,callbacks.get());
            assertTrue(owners.get(0).length()<=64);
            assertTrue(owners.subList(0,3).stream().allMatch(owners.get(0)::equals));
            second.start();assertTrue(secondJob.await(3,TimeUnit.SECONDS));second.stop();
            assertNotEquals(owners.get(0),owners.get(owners.size()-1));
        }finally{first.stop();second.stop();}
    }

    @Test void aDependencyFailureIsRetriedWithoutTerminatingTheConsumer() throws InterruptedException {
        AtomicInteger calls=new AtomicInteger();CountDownLatch retry=new CountDownLatch(1);
        PhotoWorkerRuntime worker=new PhotoWorkerRuntime(()->true,(id,objectStore)->{
            if(calls.incrementAndGet()==1)throw new IllegalStateException("private-isolated-error");
            retry.countDown();return false;
        },store(true));
        try{worker.start();assertTrue(retry.await(3,TimeUnit.SECONDS));assertTrue(worker.isRunning());}
        finally{worker.stop();}
        assertFalse(worker.isRunning());
    }
}
