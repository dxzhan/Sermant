/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2021-2021. All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huawei.flowcontrol.adapte.cse.resolver;

import com.huawei.flowcontrol.adapte.cse.rule.RateLimitingRule;

/**
 * 限流解析类
 *
 * @author zhouss
 * @since 2021-11-16
 */
public class RateLimitingRuleResolver extends AbstractRuleResolver<RateLimitingRule> {
    /**
     * 限流配置 键
     */
    public static final String CONFIG_KEY = "servicecomb.rateLimiting";

    public RateLimitingRuleResolver() {
        super(CONFIG_KEY);
    }

    @Override
    protected Class<RateLimitingRule> getRuleClass() {
        return RateLimitingRule.class;
    }
}
