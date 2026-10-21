package cn.mop.adapters.cmdb;

import cn.mop.domain.assetimport.source.ParsedArtifact;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Server-side source dictionary checks only. No database facts, credential access, normalization or import permission. */
public final class CmdbSourcePreflight {
 public record Finding(long record,int sourceRow,Integer column,String level,String code,String message){}
 public record Result(String status,int records,int errorRows,int warningRows,List<Finding> findings){
  public Result{findings=List.copyOf(findings);}
  public boolean canImport(){return false;}
 }
 private final CustomerCmdbDictionary dictionary;
 public CmdbSourcePreflight(CustomerCmdbDictionary dictionary){this.dictionary=Objects.requireNonNull(dictionary);}
 public Result check(ParsedArtifact source){
  Objects.requireNonNull(source);
  if(source.state()!=ParsedArtifact.State.PARSED)throw new IllegalArgumentException("Source selection is incomplete");
  var headers=new ArrayList<String>();dictionary.document().path("import_columns").elements().forEachRemaining(n->headers.add(n.asText()));
  if(source.columns().size()!=33)throw new IllegalArgumentException("Expected source-backed 33 columns");
  var indexed=new HashMap<Integer,String>();source.columns().forEach(c->indexed.put(c.index(),c.header()));
  for(int i=0;i<33;i++)if(!headers.get(i).equals(indexed.get(i)))throw new IllegalArgumentException("Source header differs from reviewed template");
  var out=new ArrayList<Finding>();var names=new HashMap<String,Long>();var addresses=new HashMap<String,Long>();
  var parents=new HashMap<String,String>();dictionary.document().path("primary_types").forEach(n->{parents.put(n.path("code").asText(),n.path("code").asText());parents.put(n.path("label").asText(),n.path("code").asText());});
  for(var row:source.rows()){
   var cells=new HashMap<Integer,ParsedArtifact.SourceCell>();row.cells().forEach(c->cells.put(c.columnIndex(),c));
   String[] values=new String[33];Arrays.fill(values,"");
   for(var cell:row.cells()){
    if(cell.columnIndex()>=33)throw new IllegalArgumentException("Unexpected source position");
    values[cell.columnIndex()]=Objects.toString(cell.lexical(),"");
    if(cell.kind()==ParsedArtifact.CellKind.ERROR)add(out,row,cell.columnIndex(),"ERROR","SOURCE_CELL_ERROR","源单元格为错误值，请在原文件修正。");
    if(!values[cell.columnIndex()].equals(values[cell.columnIndex()].strip()))add(out,row,cell.columnIndex(),"WARNING","WHITESPACE","首尾空白保留，未自动规范化。");
   }
   for(int col:new int[]{0,1,2})if(values[col].isBlank())add(out,row,col,"ERROR","REQUIRED","客户模板标记为必填，当前为空。");
   String parent=parents.get(values[1]);
   if(!values[1].isEmpty()&&parent==null)add(out,row,1,"ERROR","TYPE_NOT_ALLOWED","主类型不在已核验字典内。");
   var child=dictionary.subtypes().stream().filter(s->s.code().equals(values[16])||s.label().equals(values[16])).findFirst();
   if(values[16].isEmpty())add(out,row,16,"WARNING","SUBTYPE_BEFORE_SERVICE","进入服务前须补齐并核验子类型。");
   else if(child.isEmpty())add(out,row,16,"ERROR","SUBTYPE_NOT_ALLOWED","子类型不在已核验字典内。");
   else if(parent!=null&&!child.get().parent().equals(parent))add(out,row,16,"ERROR","SUBTYPE_PARENT_MISMATCH","主类型与子类型不匹配。");
   else if(!child.get().monitoringMappingResolved())add(out,row,16,"WARNING","MAPPING_PENDING","监控映射待确认，不会套用模板。");
   if(!values[13].isEmpty()&&dictionary.collectionMethod(values[13]).isEmpty())add(out,row,13,"WARNING","COLLECTION_PENDING","采集方式存在来源冲突或未定义，不能启用。");
   for(int col:new int[]{10,11,12,17}){
    if(values[col].isEmpty())continue;
    var cell=cells.get(col);
    if(cell.kind()==ParsedArtifact.CellKind.NUMERIC){add(out,row,col,"WARNING","EXCEL_DATE_POLICY_PENDING","数值日期和工作簿日期基准保留，未擅自转换 Excel 日期。");continue;}
    if(!values[col].matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")){add(out,row,col,"WARNING","DATE_FORMAT_PENDING","日期格式需要已发布解析规则，当前保留原文。");continue;}
    try{LocalDate.parse(values[col]);}catch(DateTimeParseException bad){add(out,row,col,"ERROR","DATE_INVALID","日期不存在，请检查原值。");}
   }
   duplicate(out,row,0,values[0],names);duplicate(out,row,2,values[2],addresses);
  }
  int errors=(int)out.stream().filter(f->f.level().equals("ERROR")).map(Finding::record).distinct().count();
  int warnings=(int)out.stream().filter(f->f.level().equals("WARNING")).map(Finding::record).distinct().count();
  return new Result(errors>0?"ERROR":"CONFIG_PENDING",source.rows().size(),errors,warnings,out);
 }
 private static void duplicate(List<Finding> out,ParsedArtifact.SourceRow row,int col,String value,Map<String,Long> seen){if(value.isEmpty())return;Long prior=seen.putIfAbsent(value,row.logicalRecordNumber());if(prior!=null)add(out,row,col,"WARNING","EXACT_DUPLICATE_IN_SOURCE","与来源记录 "+prior+" 原文相同；未执行数据库去重。");}
 private static void add(List<Finding> out,ParsedArtifact.SourceRow row,int col,String level,String code,String message){out.add(new Finding(row.logicalRecordNumber(),row.sourceRowNumber(),col,level,code,message));}
}
