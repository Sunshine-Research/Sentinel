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
package com.alibaba.csp.sentinel.slots.block;

import com.alibaba.csp.sentinel.slots.block.authority.AuthorityRule;

/**
 * @author youji.zj
 * @author jialiang.linjl
 */
public final class RuleConstant {

	/**
	 * 流控策略
	 * 并发线程数量
	 */
	public static final int FLOW_GRADE_THREAD = 0;
	/**
	 * 流控策略
	 * QPS限制
	 */
	public static final int FLOW_GRADE_QPS = 1;

	/**
	 * 降级策略
	 * 平均响应时间
	 */
	public static final int DEGRADE_GRADE_RT = 0;
	/**
	 * 降级策略
	 * 每秒钟业务异常比率
     */
    public static final int DEGRADE_GRADE_EXCEPTION_RATIO = 1;
	/**
	 * 降级策略
	 * 每分钟业务异常数量
     */
    public static final int DEGRADE_GRADE_EXCEPTION_COUNT = 2;

	/**
	 * {@link AuthorityRule}白名单策略
	 */
	public static final int AUTHORITY_WHITE = 0;
	/**
	 * {@link AuthorityRule}黑名单策略
	 */
	public static final int AUTHORITY_BLACK = 1;

	/**
	 * 流控策略——直接流控控制
	 */
	public static final int STRATEGY_DIRECT = 0;
	/**
	 * 流控策略——关联流量控制
	 */
	public static final int STRATEGY_RELATE = 1;
	/**
	 * 流控策略——链路流量控制
	 */
	public static final int STRATEGY_CHAIN = 2;

	/**
	 * 流量整形控制策略——默认
	 */
	public static final int CONTROL_BEHAVIOR_DEFAULT = 0;
	/**
	 * 流量整形控制策略——预热
	 */
	public static final int CONTROL_BEHAVIOR_WARM_UP = 1;
	/**
	 * 流量整形控制策略——令牌限流
	 */
	public static final int CONTROL_BEHAVIOR_RATE_LIMITER = 2;
	/**
	 * 流量整形控制策略——预热和令牌限流
	 */
	public static final int CONTROL_BEHAVIOR_WARM_UP_RATE_LIMITER = 3;

    public static final String LIMIT_APP_DEFAULT = "default";
    public static final String LIMIT_APP_OTHER = "other";

    public static final int DEFAULT_SAMPLE_COUNT = 2;
    public static final int DEFAULT_WINDOW_INTERVAL_MS = 1000;

    private RuleConstant() {}
}
