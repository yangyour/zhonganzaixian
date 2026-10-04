package cn.mop.persistence;
import cn.mop.domain.inspection.*;
import cn.mop.domain.DomainException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class InspectionWorksheetRepositoryContentTest {
    @Test void requestOwnsDetachedImmutableMap(){var map=new HashMap<String,InspectionWorksheet.Observation>();map.put("a",new InspectionWorksheet.Observation(InspectionWorksheet.Result.NORMAL,null,List.of()));var value=new InspectionWorksheetRepository.Content(map,null);map.clear();assertEquals(1,value.observations().size());assertThrows(UnsupportedOperationException.class,()->value.observations().clear());}
    @Test void nonemptyUnverifiedEvidenceCannotBecomePersistedBinding(){assertThrows(DomainException.class,()->new InspectionWorksheetRepository.Content(Map.of("a",new InspectionWorksheet.Observation(InspectionWorksheet.Result.ATTENTION,null,List.of(UUID.randomUUID()))),null));}
    @Test void nullMapKeyAndAnswerFailExplicitly(){assertThrows(DomainException.class,()->new InspectionWorksheetRepository.Content(null,null));var key=new HashMap<String,InspectionWorksheet.Observation>();key.put(null,new InspectionWorksheet.Observation(InspectionWorksheet.Result.NORMAL,null,List.of()));assertThrows(DomainException.class,()->new InspectionWorksheetRepository.Content(key,null));var answer=new HashMap<String,InspectionWorksheet.Observation>();answer.put("a",null);assertThrows(DomainException.class,()->new InspectionWorksheetRepository.Content(answer,null));}
}
