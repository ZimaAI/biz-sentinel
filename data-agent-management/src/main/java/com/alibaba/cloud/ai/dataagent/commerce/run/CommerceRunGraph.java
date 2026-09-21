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
package com.alibaba.cloud.ai.dataagent.commerce.run;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/** Separate graph; it cannot reach the upstream NL2SQL, Python, filesystem or MCP nodes. */
final class CommerceRunGraph {
    private final CompiledGraph graph;

    CommerceRunGraph(CommerceRunService runs) {
        try {
            StateGraph definition = new StateGraph("commerce-diagnosis", () -> Map.of("runId", KeyStrategy.REPLACE,
                    "fence", KeyStrategy.REPLACE, "continue", KeyStrategy.REPLACE));
            definition.addNode("choose-legal-action", node_async(state -> Map.of("continue",
                    runs.select(state.value("runId", ""), state.value("fence", 0L)))));
            definition.addNode("execute-domain-tool", node_async(state -> {
                runs.executeStep(state.value("runId", ""), state.value("fence", 0L));
                return Map.of();
            }));
            definition.addEdge(StateGraph.START, "choose-legal-action");
            definition.addConditionalEdges("choose-legal-action", edge_async(state -> state.value("continue", false) ? "next" : "stop"),
                    Map.of("next", "execute-domain-tool", "stop", StateGraph.END));
            definition.addEdge("execute-domain-tool", "choose-legal-action");
            graph = definition.compile();
        }
        catch (GraphStateException ex) { throw new IllegalStateException("Commerce graph cannot be compiled", ex); }
    }

    void execute(String runId, long fence) { graph.invoke(Map.of("runId", runId, "fence", fence)); }
}
