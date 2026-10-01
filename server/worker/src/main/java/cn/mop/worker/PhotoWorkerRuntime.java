package cn.mop.worker;

import cn.mop.domain.PhotoObjectStore;
import cn.mop.persistence.PostgresPlatformRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Polls only durable photo jobs. All other outboxes remain fail-closed and unclaimed. */
final class PhotoWorkerRuntime implements SmartLifecycle {
    private static final Logger LOG=LoggerFactory.getLogger(PhotoWorkerRuntime.class);
    private static final long IDLE_DELAY_MILLIS=1000,MAX_FAILURE_DELAY_MILLIS=30000,STOP_TIMEOUT_MILLIS=30000;
    @FunctionalInterface interface PhotoProcessor {boolean process(String workerId,PhotoObjectStore store);}
    private final BooleanSupplier repositoryReady;
    private final PhotoProcessor processor;
    private final PhotoObjectStore store;
    private final String workerId="photo-worker-"+UUID.randomUUID();
    private volatile boolean running;
    private Thread thread;

    PhotoWorkerRuntime(PostgresPlatformRepository repository,PhotoObjectStore store) {
        this(repository::fileWorkerReady,repository::processNextPhoto,store);
    }

    // Isolated lifecycle tests do not connect to external components.
    PhotoWorkerRuntime(BooleanSupplier repositoryReady,PhotoProcessor processor,PhotoObjectStore store) {
        this.repositoryReady=repositoryReady;this.processor=processor;this.store=store;
    }

    @Override public synchronized void start() {
        if(running)return;
        if(thread!=null&&thread.isAlive())
            throw new IllegalStateException("FAIL_CLOSED: previous file-only worker is still stopping");
        boolean ready;
        try{ready=repositoryReady.getAsBoolean()&&store.ready();}
        catch(RuntimeException unavailable){ready=false;}
        if(!ready)throw new IllegalStateException("FAIL_CLOSED: file-only worker requires the designated restricted PostgreSQL V5 photo schema and MinIO bucket to be ready; migrations must be approved and applied separately");
        running=true;
        thread=new Thread(this::poll,"mop-file-only-worker");
        thread.setDaemon(false);
        thread.start();
        LOG.info("File-only photo worker started; other outboxes remain fail-closed and unclaimed");
    }

    private void poll() {
        long failureDelay=IDLE_DELAY_MILLIS;
        try {
            while(running&&!Thread.currentThread().isInterrupted()) {
                try {
                    boolean processed=processor.process(workerId,store);
                    failureDelay=IDLE_DELAY_MILLIS;
                    if(!processed)pause(IDLE_DELAY_MILLIS);
                }catch(RuntimeException unavailable) {
                    LOG.warn("File-only photo worker dependency unavailable; claims paused for retry");
                    pause(failureDelay);
                    failureDelay=Math.min(MAX_FAILURE_DELAY_MILLIS,failureDelay*2);
                }
            }
        }finally{running=false;}
    }

    private void pause(long delay) {
        if(!running)return;
        try{Thread.sleep(delay);}
        catch(InterruptedException stopped){Thread.currentThread().interrupt();}
    }

    @Override public void stop() {
        Thread current;
        synchronized(this){running=false;current=thread;if(current!=null)current.interrupt();}
        if(current!=null&&current!=Thread.currentThread()) {
            try{current.join(STOP_TIMEOUT_MILLIS);}
            catch(InterruptedException stopped){Thread.currentThread().interrupt();}
            if(current.isAlive())LOG.warn("File-only photo worker shutdown is waiting on its in-flight operation; the lease remains fenced");
        }
    }

    @Override public void stop(Runnable callback){try{stop();}finally{callback.run();}}
    @Override public boolean isRunning(){return running;}
    @Override public boolean isAutoStartup(){return true;}
    @Override public int getPhase(){return Integer.MAX_VALUE;}
}
