package cn.mop.api;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compiled controller route regression. Does not instantiate the app or contact external services. */
class IntegratedControllerRoutesTest {
    @Test void remoteControllersHaveNoStructurallyDuplicateMethodAndPath() throws Exception {
        var root=Path.of(MopApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("cn/mop/api");
        var routes=new TreeMap<String,String>();
        int controllers=0;
        try(var files=Files.list(root)) {
            for(Path file:files.filter(p->p.getFileName().toString().endsWith("Controller.class")).sorted().toList()) {
                String simple=file.getFileName().toString().replace(".class","");
                Class<?> type=Class.forName("cn.mop.api."+simple,false,getClass().getClassLoader());
                if(!AnnotatedElementUtils.hasAnnotation(type,RestController.class))continue;
                Profile profile=AnnotatedElementUtils.findMergedAnnotation(type,Profile.class);
                if(profile!=null&&!Arrays.asList(profile.value()).contains("remote_test"))continue;
                controllers++;
                RequestMapping prefix=AnnotatedElementUtils.findMergedAnnotation(type,RequestMapping.class);
                for(var method:type.getDeclaredMethods()) {
                    RequestMapping mapping=AnnotatedElementUtils.findMergedAnnotation(method,RequestMapping.class);
                    if(mapping==null)continue;
                    String owner=type.getSimpleName()+"."+method.getName();
                    for(String base:paths(prefix))for(String suffix:paths(mapping)) {
                        String path=(base+"/"+suffix).replaceAll("/+","/").replaceAll("\\{[^}]+}","{}").replaceAll("/$","");
                        RequestMethod[] methods=mapping.method().length==0?RequestMethod.values():mapping.method();
                        for(RequestMethod verb:methods) {
                            String key=verb+" "+path;
                            String previous=routes.putIfAbsent(key,owner);
                            assertTrue(previous==null||previous.equals(owner),()->"Ambiguous route "+key+": "+previous+" and "+owner);
                        }
                    }
                }
            }
        }
        assertTrue(controllers>20,"Must inspect the actual compiled remote controllers");
        assertTrue(routes.containsKey("GET /api/v1/tickets/{}/sla"));
        assertTrue(routes.containsKey("GET /api/v1/tickets/{}/workflow/sla"));
    }
    private static List<String> paths(RequestMapping mapping) {
        if(mapping==null)return List.of("");
        String[] paths=mapping.path().length==0?mapping.value():mapping.path();
        return paths.length==0?List.of(""):Arrays.asList(paths);
    }
}
