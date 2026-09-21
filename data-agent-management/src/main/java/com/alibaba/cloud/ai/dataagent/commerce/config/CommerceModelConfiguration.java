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

import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Same LlmService contract and Spring AI provider used by the upstream model registry. */
@Configuration(proxyBeanMethods=false)
@Profile("commerce")
@ConditionalOnProperty(name="commerce.planner",havingValue="model")
public class CommerceModelConfiguration {
    @Bean
    public LlmService commerceLlmService(Environment env) {
        OpenAiApi api=OpenAiApi.builder().apiKey(env.getRequiredProperty("commerce.model.api-key"))
            .baseUrl(env.getRequiredProperty("commerce.model.base-url"))
            .completionsPath(env.getProperty("commerce.model.completions-path","/v1/chat/completions")).build();
        ChatClient client=ChatClient.builder(OpenAiChatModel.builder().openAiApi(api)
            .defaultOptions(OpenAiChatOptions.builder().model(env.getRequiredProperty("commerce.model.name")).temperature(0.0).maxTokens(1600).build()).build()).build();
        return new LlmService() {
            public Flux<ChatResponse> call(String system,String user) {return Mono.fromCallable(()->client.prompt().system(system).user(user).call().chatResponse()).subscribeOn(Schedulers.boundedElastic()).flux();}
            public Flux<ChatResponse> call(String system,String user,Class<?> type) {return call(system,user);}
            public Flux<ChatResponse> callSystem(String system) {return call(system,"");}
            public Flux<ChatResponse> callUser(String user) {return call("",user);}
            public Flux<ChatResponse> callUser(String user,Class<?> type) {return call("",user);}
        };
    }
}
