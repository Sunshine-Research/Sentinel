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
package com.alibaba.csp.sentinel.slots.block.authority;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.util.StringUtil;

/**
 * {@link AuthorityRule}规则检查器
 */
final class AuthorityRuleChecker {

	private AuthorityRuleChecker() {
	}

	/**
	 * 检查{@link AuthorityRule}规则
	 * @param rule    {@link AuthorityRule}
	 * @param context 上线文
	 * @return 是否通过{@link AuthorityRule}校验
	 */
	static boolean passCheck(AuthorityRule rule, Context context) {
		// 获取当前请求的origin名称
		String requester = context.getOrigin();

		// origin名称为空或者没有限制应用，直接判断通过权限规则校验
		if (StringUtil.isEmpty(requester) || StringUtil.isEmpty(rule.getLimitApp())) {
			return true;
		}

		// 进行名称全匹配搜索
		int pos = rule.getLimitApp().indexOf(requester);
		boolean contain = pos > -1;

		// 如果包含，就会进行精确匹配搜索
		if (contain) {
			boolean exactlyMatch = false;
			String[] appArray = rule.getLimitApp().split(",");
			for (String app : appArray) {
				if (requester.equals(app)) {
					exactlyMatch = true;
					break;
				}
			}

			contain = exactlyMatch;
		}
		// 获取权限策略方式
		int strategy = rule.getStrategy();
		// 如果策略是黑名单方式，并且包含当前请求的origin，规则校验不通过，返回false
		if (strategy == RuleConstant.AUTHORITY_BLACK && contain) {
			return false;
		}
		// 如果策略是白名单方式，并且不包含当前请求的origin，规则校验不通过，返回false
		if (strategy == RuleConstant.AUTHORITY_WHITE && !contain) {
			return false;
		}
		// 其他情况下，通过规则校验，返回true
		return true;
	}
}
