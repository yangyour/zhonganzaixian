package cn.mop.assetexport;
import cn.mop.domain.DomainException;
import cn.mop.domain.assetexport.*;
import cn.mop.domain.assetexport.AssetExportJobRepository.*;
import cn.mop.domain.assetexport.AssetExportJobState.Command;
import cn.mop.domain.assetimport.AssetImportPrechecker.Column;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
/** Actual selection -> XLSX -> immutable preparation. Deliberately no object-store or publication action. */
public final class AssetExportPreparationProcessor {
    private final AssetExportJobRepository jobs;private final AssetExportSnapshotRepository source;
    public AssetExportPreparationProcessor(AssetExportJobRepository jobs,AssetExportSnapshotRepository source){this.jobs=Objects.requireNonNull(jobs);this.source=Objects.requireNonNull(source);}
    public Work prepare(Claim offered)throws IOException{
        if(!validClaim(offered))throw invalid();
        Work first=bound(jobs.loadWork(offered),offered);
        if(first.prepared()!=null)return first;
        var selection=source.select(first.claim().command());
        if(selection==null||!selection.command().equals(first.claim().command()))throw corrupt();
        Work fresh=bound(jobs.loadWork(first.claim()),first.claim());
        if(fresh.databaseNow().isBefore(first.databaseNow()))throw corrupt();
        if(fresh.prepared()!=null)return fresh;
        var tenant=fresh.claim().command().acceptedAccess().tenantId();var rows=selection.rows().stream().map(row->AssetLedgerProjection.fromCurrent(tenant,row)).toList();
        String selected=selectionFingerprint(fresh.claim().command(),rows);
        var workbook=AssetLedgerWorkbook.encode(tenant,rows,true,fresh.databaseNow());
        var file=AssetExportPreparedFile.create(fresh.claim().command(),selected,workbook.dataRows(),fresh.databaseNow(),workbook.bytes());
        Work saved=bound(jobs.prepare(fresh.claim(),file),fresh.claim());
        if(saved.prepared()==null||!saved.prepared().sameContent(file)||saved.databaseNow().isBefore(fresh.databaseNow()))throw corrupt();return saved;
    }
    private static Work bound(Work work,Claim offered){
        if(work==null||!validClaim(work.claim())||work.databaseNow()==null||work.databaseNow().getNano()%1000000!=0)throw corrupt();var actual=work.claim();
        if(!Objects.equals(actual.jobId(),offered.jobId())||!Objects.equals(actual.worker(),offered.worker())||actual.fencingToken()!=offered.fencingToken()||actual.version()<offered.version()||!Objects.equals(actual.leaseUntil(),offered.leaseUntil())||!actual.command().equals(offered.command())||!work.databaseNow().isBefore(actual.leaseUntil())||actual.storageReconciliationRequired()&&work.prepared()==null)throw corrupt();
        if(work.prepared()!=null&&(actual.version()<=actual.fencingToken()||!work.prepared().commandFingerprint().equals(actual.command().fingerprint())||work.prepared().encoding().generatedAt().isAfter(work.databaseNow())))throw corrupt();return work;
    }
    /** Hashes original command and ordered exported row identities/cells, not a permission check or full hidden-field snapshot. */
    public static String selectionFingerprint(Command command,List<AssetLedgerWorkbook.Row> rows){
        if(command==null||rows==null||rows.stream().anyMatch(Objects::isNull))throw invalid();if(rows.size()>AssetLedgerWorkbook.MAX_ROWS)throw limit();var snapshot=List.copyOf(rows);var ids=new HashSet<UUID>();long bytes=0;
        for(var row:snapshot){if(!row.tenantId().equals(command.acceptedAccess().tenantId()))throw new DomainException(404,"OBJECT_NOT_FOUND","Export selection not found");if(!ids.add(row.assetId()))throw invalid();for(String value:row.cells().values())bytes+=size(value);bytes+=size(row.warrantyStatus());if(bytes>AssetLedgerWorkbook.MAX_INPUT_UTF8_BYTES)throw limit();}
        try{var digest=MessageDigest.getInstance("SHA-256");try(var out=new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(),digest))){field(out,"mop-rendered-export-selection-v1");field(out,AssetLedgerWorkbook.SCHEMA_VERSION);byte[] encoded=command.canonicalBytes();out.writeInt(encoded.length);out.write(encoded);out.writeInt(snapshot.size());for(var row:snapshot){field(out,row.tenantId().toString());field(out,row.assetId().toString());for(Column column:Column.values()){field(out,column.name());field(out,row.cells().get(column));}field(out,"WARRANTY_STATUS");field(out,row.warrantyStatus());}}return HexFormat.of().formatHex(digest.digest());}catch(IOException|NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static boolean validClaim(Claim c){return c!=null&&c.command()!=null&&c.jobId()!=null&&c.jobId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")&&c.worker()!=null&&c.worker().matches("[A-Za-z0-9._:-]{1,64}")&&c.fencingToken()>0&&c.fencingToken()<=AssetExportJobState.MAX_ATTEMPTS&&c.version()>=c.fencingToken()&&c.version()<=9007199254740991L&&c.leaseUntil()!=null&&c.leaseUntil().getNano()%1000000==0;}
    private static int size(String value){return value==null?0:value.getBytes(StandardCharsets.UTF_8).length;}
    private static void field(DataOutputStream output,String value)throws IOException{if(value==null)output.writeInt(-1);else{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);output.writeInt(bytes.length);output.write(bytes);}}
    private static DomainException invalid(){return new DomainException(400,"VALIDATION_ERROR","Explicit export command and complete rendered selection required");}
    private static DomainException limit(){return new DomainException(413,"EXPORT_ENCODING_LIMIT","Rendered selection exceeds technical encoding limit");}
    private static DomainException corrupt(){return new DomainException(503,"UPSTREAM_UNAVAILABLE","Export preparation stage returned inconsistent facts");}
}
