package cn.mop.api;
import cn.mop.domain.*;
import cn.mop.domain.change.ChangeNoticeApprovalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ChangeNoticeApprovalReadinessTest {
 private static <T> ObjectProvider<T> provider(T value){ObjectProvider<T> p=mock(ObjectProvider.class);when(p.getIfAvailable()).thenReturn(value);return p;}
 private PlatformController controller(ChangeNoticeApprovalRepository approvals){
  var repo=mock(PlatformRepository.class);when(repo.metadata()).thenReturn(new RepositoryMetadata("remote_test","postgresql",true,"test_oidc"));when(repo.dependencyReady()).thenReturn(true);
  var identity=mock(TestOidcConfiguration.OidcDependencyProbe.class);when(identity.ready()).thenReturn(true);
  var photos=new PhotoConfiguration.PhotoDependencyProbe(false,null,null,null);
  var imports=new AssetImportConfiguration.ImportDependencyProbe(false,false,null,null);
  var result=new PlatformController(repo,new ObjectMapper(),provider(identity),provider(photos),provider(imports));ReflectionTestUtils.setField(result,"changeNoticeApprovals",approvals);return result;
 }
 @Test void missingApprovalSchemaIsNotReady(){var a=mock(ChangeNoticeApprovalRepository.class);when(a.readinessProblems()).thenReturn(List.of("missing"));var e=assertThrows(DomainException.class,()->controller(a).ready(new MockHttpServletRequest()));assertEquals(503,e.status);assertEquals("change_notice_approvals",((Map<?,?>)e.details.get(0)).get("component"));}
 @Test void dependencyErrorsAreSanitized(){var a=mock(ChangeNoticeApprovalRepository.class);when(a.readinessProblems()).thenThrow(new IllegalStateException("private-driver-token"));var e=assertThrows(DomainException.class,()->controller(a).ready(new MockHttpServletRequest()));assertEquals(503,e.status);assertFalse(e.toString().contains("private-driver-token"));assertFalse(e.details.toString().contains("private-driver-token"));}
 @Test void livenessNeverQueriesApprovalStorage(){var a=mock(ChangeNoticeApprovalRepository.class);controller(a).live(new MockHttpServletRequest());verifyNoInteractions(a);}
 @Test void readySchemaDoesNotClaimActivation(){var a=mock(ChangeNoticeApprovalRepository.class);when(a.readinessProblems()).thenReturn(List.of());Map<?,?> result=(Map<?,?>)controller(a).ready(new MockHttpServletRequest());Map<?,?> data=(Map<?,?>)result.get("data");Map<?,?> approval=(Map<?,?>)data.get("change_notice_approvals");assertEquals(true,approval.get("schema_access_ready"));assertEquals(false,approval.get("effective"));assertEquals(false,approval.get("device_execution_allowed"));assertEquals(false,data.get("production_ready"));}
}
