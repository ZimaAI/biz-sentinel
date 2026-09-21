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
package com.alibaba.cloud.ai.dataagent.commerce;

import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.alibaba.cloud.ai.dataagent.commerce.anomaly.CommerceAnomalyService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommerceAnomalyTest {
    @Test void medianAndThresholdHandleZeroBaselineAndUnits() {
        assertThat(CommerceAnomalyService.median(List.of(new BigDecimal("100"),new BigDecimal("100"),new BigDecimal("100"),new BigDecimal("99999")))).isEqualByComparingTo("100");
        var rule=object("direction","DOWN","unit","CNY_CENT","absoluteThreshold","1000000","relativeThreshold","0.15");
        assertThat(CommerceAnomalyService.threshold(new BigDecimal("48620000"),new BigDecimal("60000000"),rule)).isTrue();
        assertThat(CommerceAnomalyService.threshold(BigDecimal.ZERO,BigDecimal.ZERO,rule)).isFalse();
        assertThat(CommerceAnomalyService.threshold(null,new BigDecimal("60000000"),rule)).isFalse();
        assertThat(CommerceAnomalyService.threshold(new BigDecimal("59000000"),new BigDecimal("60000000"),rule)).isFalse();
        var ratio=object("direction","DOWN","unit","PP","absoluteThreshold","0.5","relativeThreshold",null);
        assertThat(CommerceAnomalyService.threshold(new BigDecimal("0.034"),new BigDecimal("0.048"),ratio)).isTrue();
        assertThat(CommerceAnomalyService.threshold(new BigDecimal("0.044"),new BigDecimal("0.048"),ratio)).isFalse();
    }
}
