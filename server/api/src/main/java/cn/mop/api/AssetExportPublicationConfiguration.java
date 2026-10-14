package cn.mop.api;
import cn.mop.adapters.*;
import cn.mop.domain.assetexport.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.util.*;

@Configuration @Profile("remote_test")
public class AssetExportPublicationConfiguration {
    @Bean AssetExportPublicationRepository assetExportPublicationRepository(AssetExportJobRepository jobs){
        if(!(jobs instanceof AssetExportPublicationRepository publications))throw new IllegalStateException("Export publication requires durable PostgreSQL repository");
        return new AssetExportPublicationRepository(){
            public void authorizePublication(AssetExportJobRepository.Access a,String id){publications.authorizePublication(a,id);}
            public Publication publish(AssetExportJobRepository.Access a,String id,long version,String sha,VerifiedWriter writer){return publications.publish(a,id,version,sha,writer);}
            public Publication publication(AssetExportJobRepository.Access a,String id){return publications.publication(a,id);}
            public Publication publicationInCommand(AssetExportJobRepository.Access a,String id){return publications.publicationInCommand(a,id);}
        };
    }
    @Bean AssetExportPublicationSupport assetExportPublicationSupport(Environment env){
        String flag=env.getProperty("MOP_EXPORT_PUBLICATION_ENABLED","false");
        if("false".equals(flag))return new AssetExportPublicationSupport(false,null,null);
        try{
            if(!"true".equals(flag))throw new IllegalArgumentException();
            var settings=MinioStorageSettings.from(env::getProperty,MinioStorageSettings.Scope.EXPORTS);
            String key=env.getProperty("MOP_EXPORT_DOWNLOAD_SIGNING_KEY");if(key==null)throw new IllegalArgumentException();
            return new AssetExportPublicationSupport(true,new MinioAssetExportStore(settings),new AssetExportDownloadGrant(Base64.getDecoder().decode(key)));
        }catch(RuntimeException invalid){return new AssetExportPublicationSupport(true,null,null);}
    }
}
