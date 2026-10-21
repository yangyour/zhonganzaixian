package cn.mop.adapters.cmdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;

/** Source-backed classification only. Never rewrites asset identities, publishes configuration or starts collection. */
public final class CustomerCmdbDictionary {
    public static final String VERSION="customer-cmdb-v1-20261021";
    public static final String RESOURCE="/cmdb/customer-cmdb-dictionary-v1.json";
    public static final String SHA256="f0816e44f53a33888c6514378bbd17332b5e0ae35d20512156dc79ba7fbb3108";
    private final JsonNode document;
    private final Set<String> primaryTypes;
    private final Map<String,Subtype> subtypes;
    private final Map<String,String> collectionChoices;
    public record Subtype(String code,String label,String parent,String sourceSheet,int sourceRow,
                          String billingWeight,boolean monitoringMappingResolved) {}
    public record Issue(String code,String field) {}
    public record Classification(boolean classificationValid,boolean monitoringMappingResolved,List<Issue> issues) {
        public Classification { issues=List.copyOf(issues); }
        public boolean assetCreated(){return false;}
        public boolean collectionStarted(){return false;}
        public boolean productionApproved(){return false;}
    }
    private CustomerCmdbDictionary(JsonNode document) {
        this.document=document.deepCopy();
        var parents=new LinkedHashSet<String>();
        for(JsonNode row:document.path("primary_types"))if(!parents.add(row.path("code").asText()))throw invalid();
        var children=new LinkedHashMap<String,Subtype>();
        for(JsonNode row:document.path("subtypes")){
            String code=row.path("code").asText(),parent=row.path("parent").asText();
            if(!parents.contains(parent))throw invalid();
            var subtype=new Subtype(code,row.path("label").asText(),parent,row.path("source").path("sheet").asText(),
                row.path("source").path("row").asInt(),row.path("billing_weight").asText(),!"fw_adc".equals(code));
            if(children.put(code,subtype)!=null)throw invalid();
        }
        if(parents.size()!=9||children.size()!=16||document.path("attributes").size()!=95
                ||!VERSION.equals(document.path("catalog_version").asText())||document.path("activated").asBoolean(true)
                ||document.path("production_approved").asBoolean(true))throw invalid();
        this.primaryTypes=Collections.unmodifiableSet(parents);
        this.subtypes=Collections.unmodifiableMap(children);
        var choices=new LinkedHashMap<String,String>();
        for(JsonNode row:document.path("enum_values"))if("collect_method".equals(row.path("field").asText())){
            String code=row.path("code").asText();choices.put(code,code);choices.put(row.path("label").asText(),code);
        }
        this.collectionChoices=Collections.unmodifiableMap(choices);
    }
    public static CustomerCmdbDictionary load(ObjectMapper mapper) {
        Objects.requireNonNull(mapper);
        try(var stream=CustomerCmdbDictionary.class.getResourceAsStream(RESOURCE)){
            if(stream==null)throw invalid();
            byte[] bytes=stream.readNBytes(1024*1024+1);
            if(bytes.length>1024*1024||!SHA256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw invalid();
            return new CustomerCmdbDictionary(mapper.readTree(bytes));
        }catch(IOException|java.security.NoSuchAlgorithmException error){throw invalid();}
    }
    public JsonNode document(){return document.deepCopy();}
    public Set<String> primaryTypes(){return primaryTypes;}
    public List<Subtype> subtypes(){return List.copyOf(subtypes.values());}
    public Optional<Subtype> subtype(String code){return Optional.ofNullable(code==null?null:subtypes.get(code));}
    public List<Subtype> subtypesFor(String parent){return subtypes.values().stream().filter(s->s.parent().equals(parent)).toList();}
    public Optional<String> collectionMethod(String exactCodeOrLabel){return Optional.ofNullable(exactCodeOrLabel==null?null:collectionChoices.get(exactCodeOrLabel));}
    public Classification classify(String parent,String child,boolean enteringService) {
        var issues=new ArrayList<Issue>();
        if(parent==null||!primaryTypes.contains(parent))issues.add(new Issue("TYPE_NOT_ALLOWED","device_type"));
        Subtype subtype=child==null||child.isEmpty()?null:subtypes.get(child);
        if(child==null||child.isEmpty()){
            if(enteringService)issues.add(new Issue("SUBTYPE_REQUIRED","device_subtype"));
        }else if(subtype==null)issues.add(new Issue("SUBTYPE_NOT_ALLOWED","device_subtype"));
        else if(!subtype.parent().equals(parent))issues.add(new Issue("SUBTYPE_PARENT_MISMATCH","device_subtype"));
        boolean valid=issues.isEmpty();
        if(valid&&subtype!=null&&!subtype.monitoringMappingResolved())issues.add(new Issue("MONITORING_MAPPING_PENDING","device_subtype"));
        return new Classification(valid,valid&&subtype!=null&&subtype.monitoringMappingResolved(),issues);
    }
    private static IllegalStateException invalid(){return new IllegalStateException("CMDB customer dictionary resource is unavailable or differs from the reviewed source");}
}
