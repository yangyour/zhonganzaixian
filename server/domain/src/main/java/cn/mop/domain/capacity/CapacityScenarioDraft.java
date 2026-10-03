package cn.mop.domain.capacity;
import java.util.Locale;
import java.util.regex.Pattern;

/** Immutable proposal input, never a saved/effective claim until a real repository commits it. */
public record CapacityScenarioDraft(String label,String note,CapacityCalculation calculation,BenchmarkReference benchmarkReference) {
    public CapacityScenarioDraft {CapacityCalculation.text(label,100,false);CapacityCalculation.text(note,2000,true);if(calculation==null)throw CapacityCalculation.invalid();}
    public record BenchmarkReference(String reference,String title,String sha256) {
        private static final Pattern SENSITIVE=Pattern.compile("token|password|secret|credential|private[_:-]?key",Pattern.CASE_INSENSITIVE);
        public BenchmarkReference {
            CapacityCalculation.text(reference,160,false);CapacityCalculation.text(title,200,false);
            if(!reference.matches("document-ref:[A-Za-z0-9._:-]+")||SENSITIVE.matcher(reference).find()||reference.substring("document-ref:".length()).matches("[A-Za-z]:.*")||sha256==null||!sha256.matches("[0-9a-fA-F]{64}"))throw CapacityCalculation.invalid();
            sha256=sha256.toLowerCase(Locale.ROOT);
        }
    }
    public String lifecycleStatus(){return "DRAFT";}
    public String benchmarkVerification(){return benchmarkReference==null?"NOT_PROVIDED":"REFERENCE_ONLY_UNVERIFIED";}
    public boolean effective(){return false;}
    public boolean productionCapacityVerified(){return false;}
    public boolean pocExecuted(){return false;}
}
