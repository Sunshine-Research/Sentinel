/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.block.flow.param;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;

import java.util.List;

/**
 * 热点参数流控processor slot
 */
public class ParamFlowSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count,
                      boolean prioritized, Object... args) throws Throwable {
        if (!ParamFlowRuleManager.hasRules(resourceWrapper.getName())) {
            fireEntry(context, resourceWrapper, node, count, prioritized, args);
            return;
        }

        checkFlow(resourceWrapper, count, args);
        fireEntry(context, resourceWrapper, node, count, prioritized, args);
    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        fireExit(context, resourceWrapper, count, args);
    }

	/**
	 * 设置真实参数索引
	 * @param rule   详细规则
	 * @param length 参数长度
	 */
	void applyRealParamIdx(/*@NonNull*/ ParamFlowRule rule, int length) {
		// 获取需要进行规则校验的参数索引
		int paramIdx = rule.getParamIdx();
		if (paramIdx < 0) {
			// 如果设置的索引值＜0
			// 如果取反后索引的值没有超过给定的长度，设置新的索引值
			if (-paramIdx <= length) {
				rule.setParamIdx(length + paramIdx);
			} else {
				// 如果已经超过给定长度，设置为非法的索引，将会给定一个非法的正整数值，在接下来的规则校验中会直接通过
				rule.setParamIdx(-paramIdx);
			}
		}
	}

    void checkFlow(ResourceWrapper resourceWrapper, int count, Object... args) throws BlockException {
		// 如果请求没有带任何自定义参数，直接返回
        if (args == null) {
            return;
		}
		// 没有对应resource的热点参数规则，直接返回
        if (!ParamFlowRuleManager.hasRules(resourceWrapper.getName())) {
            return;
		}
		// 获取对应resource的规则，逐条进行遍历
        List<ParamFlowRule> rules = ParamFlowRuleManager.getRulesOfResource(resourceWrapper.getName());

        for (ParamFlowRule rule : rules) {
			// 设置真实参数索引
            applyRealParamIdx(rule, args.length);

			// 实例化参数的度量标准
            ParameterMetricStorage.initParamMetricsFor(resourceWrapper, rule);

			// 使用热点参数流控检查器判断当前规则是否通过
            if (!ParamFlowChecker.passCheck(resourceWrapper, rule, count, args)) {
                String triggeredParam = "";
                if (args.length > rule.getParamIdx()) {
                    Object value = args[rule.getParamIdx()];
                    triggeredParam = String.valueOf(value);
                }
                throw new ParamFlowException(resourceWrapper.getName(), triggeredParam, rule);
            }
        }
    }
}
