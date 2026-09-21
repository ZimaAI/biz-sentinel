/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.commerce.config;

import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.*;
import com.alibaba.cloud.ai.dataagent.commerce.ingestion.CommerceIngestionService;
import com.alibaba.cloud.ai.dataagent.commerce.persistence.CommerceStore;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommerceSubject;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@Profile("commerce")
public class CommerceBootstrap implements ApplicationRunner {
    private final Environment env;
    private final CommerceStore store;
    private final CommerceIngestionService ingestion;
    public CommerceBootstrap(Environment env,CommerceStore store,CommerceIngestionService ingestion) {this.env=env;this.store=store;this.ingestion=ingestion;}
    @Override public void run(ApplicationArguments arguments) {
        String password=env.getProperty("commerce.bootstrap.password","");
        if(password.isBlank())return;
        String username=env.getProperty("commerce.bootstrap.username","admin"),tenant=env.getProperty("commerce.bootstrap.tenant","t_demo");
        Set<String> stores=Set.copyOf(Arrays.asList(env.getProperty("commerce.bootstrap.stores","s1,s2,s3").split(",")));
        CommerceSubject subject=new CommerceSubject(tenant,username,stores,Set.of("TENANT_ADMIN"),1);
        if(store.findOne("login",username)==null)store.transaction(()-> {
            store.create("member",tenant+":"+username,tenant,object("tenantId",tenant,"subjectId",username,"displayName","经营管理员","storeIds",stores,"roles",subject.roles(),"authzVersion",1,"enabled",true));
            return store.create("login",username,tenant,object("tenantId",tenant,"subjectId",username,"passwordHash",new BCryptPasswordEncoder().encode(password)));
        });
        String directory=env.getProperty("commerce.bootstrap.fixture-directory","");
        if(!directory.isBlank()&&store.find("dataset",tenant).isEmpty()) {
            var dataset=ingestion.importDirectory(subject,Path.of(directory));
            if(!"PUBLISHED".equals(dataset.path("status").asText())) throw new IllegalStateException("Fixture import was rejected; inspect ingestion validation errors");
        }
    }
}
