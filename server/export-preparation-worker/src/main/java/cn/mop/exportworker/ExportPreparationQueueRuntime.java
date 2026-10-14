package cn.mop.exportworker;

import cn.mop.domain.DomainException;
import cn.mop.domain.assetexport.*;
import cn.mop.assetexport.AssetExportPreparationProcessor;
import java.time.*;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Serial durable queue loop; no publication, object-store client, local fallback queue or automatic migration. */
final class ExportPreparationQueueRuntime {
    @FunctionalInterface interface Step {boolean process()throws Exception;}
    @FunctionalInterface interface Pause {void sleep(long millis)throws InterruptedException;}
    private final BooleanSupplier ready;private final Step step;private final Pause pause;
    private volatile boolean running;private volatile boolean crashed;private Thread thread;
    ExportPreparationQueueRuntime(BooleanSupplier ready,Step step){this(ready,step,Thread::sleep);}
    ExportPreparationQueueRuntime(BooleanSupplier ready,Step step,Pause pause){this.ready=Objects.requireNonNull(ready);this.step=Objects.requireNonNull(step);this.pause=Objects.requireNonNull(pause);}
    static boolean processOne(AssetExportJobRepository jobs,AssetExportPreparationQueue queue,AssetExportSnapshotRepository source,String worker,Duration lease,Instant cutoff)throws Exception{
        var claimed=queue.claimNextUnprepared(worker,lease,cutoff);if(claimed.isEmpty())return false;
        try{var result=new AssetExportPreparationProcessor(jobs,source).prepare(claimed.get());if(result.prepared()==null)throw new DomainException(503,"UPSTREAM_UNAVAILABLE","Preparation did not return persisted bytes");}
        catch(DomainException failure){
            if(failure.status==413&&java.util.Set.of("EXPORT_ROW_LIMIT","EXPORT_ENCODING_LIMIT").contains(failure.code))queue.rejectPreparationInputLimit(claimed.get());
            else throw failure;
        }
        return true;
    }
    synchronized void start(){
        if(running)return;if(thread!=null&&thread.isAlive())throw new IllegalStateException("Previous export queue thread is still in flight");
        if(!ready.getAsBoolean())throw new IllegalStateException("Export queue dependencies are not ready");
        crashed=false;running=true;thread=new Thread(this::loop,"mop-export-preparation-queue");thread.setDaemon(false);thread.start();
    }
    private void loop(){long idle=250,failureDelay=1000;boolean reportedFailure=false;
        try{while(running&&!Thread.currentThread().isInterrupted()){
            try{
                if(!ready.getAsBoolean())throw new IllegalStateException("Dependency unavailable");
                boolean processed=step.process();reportedFailure=false;failureDelay=1000;
                if(processed)idle=250;else{pause.sleep(idle);idle=Math.min(5000,idle*2);}
            }catch(InterruptedException stopped){Thread.currentThread().interrupt();break;}
            catch(Exception failure){
                if(!running||Thread.currentThread().isInterrupted())break;
                if(!reportedFailure){System.err.println("{\"status\":\"EXPORT_QUEUE_WAITING_DEPENDENCY_OR_RECONCILIATION\",\"details\":\"REDACTED\"}");reportedFailure=true;}
                try{pause.sleep(failureDelay);}catch(InterruptedException stopped){Thread.currentThread().interrupt();break;}
                failureDelay=Math.min(30000,failureDelay*2);
            }
        }}catch(Error failure){crashed=true;System.err.println("{\"status\":\"EXPORT_QUEUE_TERMINATED\",\"details\":\"REDACTED\"}");}finally{running=false;}
    }
    void stop(){Thread t;synchronized(this){running=false;t=thread;if(t!=null)t.interrupt();}if(t!=null&&t!=Thread.currentThread())try{t.join(35000);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    void await()throws InterruptedException{Thread t; synchronized(this){t=thread;}if(t!=null)t.join();if(crashed)throw new IllegalStateException("Export preparation thread terminated unexpectedly");}
    boolean running(){return running;}
}
