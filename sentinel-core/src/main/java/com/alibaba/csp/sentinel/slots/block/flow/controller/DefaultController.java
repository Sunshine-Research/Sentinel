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
package com.alibaba.csp.sentinel.slots.block.flow.controller;

import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.node.OccupyTimeoutProperty;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.PriorityWaitException;
import com.alibaba.csp.sentinel.slots.block.flow.TrafficShapingController;
import com.alibaba.csp.sentinel.util.TimeUtil;

/**
 * 默认的流控控制器
 * 也就是立即拒绝策略
 */
public class DefaultController implements TrafficShapingController {

    private static final int DEFAULT_AVG_USED_TOKENS = 0;

    private double count;
    private int grade;

    public DefaultController(double count, int grade) {
        this.count = count;
        this.grade = grade;
    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
		// 判断未开启优先级策略的请求是否可以通过流控规则校验
        return canPass(node, acquireCount, false);
    }

    @Override
    public boolean canPass(Node node, int acquireCount, boolean prioritized) {
		// 当前已经被获取的token数量
        int curCount = avgUsedTokens(node);
		// 已经被获取的token数量+本次请求即将获取的数量超出了流控规则设置的阈值
        if (curCount + acquireCount > count) {
			// 如果请求开启了优先级策略，并且流控等级为QPS限流
            if (prioritized && grade == RuleConstant.FLOW_GRADE_QPS) {
                long currentTime;
                long waitInMs;
				// 获取当前时间戳
                currentTime = TimeUtil.currentTimeMillis();
				// 获取排队等待时间
                waitInMs = node.tryOccupyNext(currentTime, acquireCount, count);
				// 如果等待时间处于设置的可等待的时间范围内
                if (waitInMs < OccupyTimeoutProperty.getOccupyTimeout()) {
					// 进行等待
                    node.addWaitingRequest(currentTime + waitInMs, acquireCount);
                    node.addOccupiedPass(acquireCount);
                    sleep(waitInMs);

					// PriorityWaitException意味着请求将会在等待{@link @waitInMs}时间之后判定通过
                    throw new PriorityWaitException(waitInMs);
                }
            }
			// 否则判定不通过此流控规则
            return false;
        }
        return true;
    }

	/**
	 * 获取当前已经被获取的token数量
	 * @param node 给定数据统计节点
	 * @return 已经被获取的token数量
	 */
	private int avgUsedTokens(Node node) {
		if (node == null) {
			// 没有数据统计节点，则使用默认的平均使用token数量，默认为0
			return DEFAULT_AVG_USED_TOKENS;
		}
		// 否则根据流控等级进行选择
		// 线程数等级则选择数据统计节点的线程数，否则选择数据统计节点通过的QPS
		return grade == RuleConstant.FLOW_GRADE_THREAD ? node.curThreadNum() : (int)(node.passQps());
	}

	/**
	 * 线程进入睡眠状态
	 * @param timeMillis 线程需要睡眠的时长，单位ms
	 */
	private void sleep(long timeMillis) {
		try {
			Thread.sleep(timeMillis);
		} catch (InterruptedException e) {
			// 忽略中断异常
		}
	}
}
