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
import com.alibaba.csp.sentinel.slots.block.flow.TrafficShapingController;
import com.alibaba.csp.sentinel.util.TimeUtil;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 匀速速率限制-流量整形控制器
 * 相当于令牌桶流控规则方式
 * 请求距离上个通过的请求时间间隔不小于预设值，则让当前请求通过
 * 否则，计算当前请求的预期通过时间，如果该请求的预期通过时间小于规则预设的maxQueueingTimeMs，则该请求会等待直到预设时间后才会通过
 * 若预期的通过实践超出maxQueueingTimeMs，则直接判定不通过
 */
public class RateLimiterController implements TrafficShapingController {

    private final int maxQueueingTimeMs;
    private final double count;

    private final AtomicLong latestPassedTime = new AtomicLong(-1);

    public RateLimiterController(int timeOut, double count) {
        this.maxQueueingTimeMs = timeOut;
        this.count = count;
    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        return canPass(node, acquireCount, false);
    }

    @Override
    public boolean canPass(Node node, int acquireCount, boolean prioritized) {
		// 如果请求的令牌是非正整数，直接通过
        if (acquireCount <= 0) {
            return true;
        }
		// 如果当前可获取的令牌数量是非正整数，判定不通过
		// 否则，costTime的计算将是最大的，并且在某些情况下，等待时间会溢出
        if (count <= 0) {
            return false;
        }
		// 获取当前的时间戳
        long currentTime = TimeUtil.currentTimeMillis();
		// 计算两次请求之间的时间差，把请求均匀分在1s上
        long costTime = Math.round(1.0 * (acquireCount) / count * 1000);

		// 当前请求预期通过的时间戳
        long expectedTime = costTime + latestPassedTime.get();
		// 如果预期通过的时间不是未来的时间戳
        if (expectedTime <= currentTime) {
			// 更新上一次通过的时间，本次请求通过
            latestPassedTime.set(currentTime);
            return true;
        } else {
			// 如果期待的时间是在未来的时间戳
			// 计算等待时间
            long waitTime = costTime + latestPassedTime.get() - TimeUtil.currentTimeMillis();
			// 如果等待时间大于等待时间，判定不通过
            if (waitTime > maxQueueingTimeMs) {
                return false;
            } else {
				// 上次时间+本次需要耗费的时间
                long oldTime = latestPassedTime.addAndGet(costTime);
                try {
					// 获取最新的等待时间
                    waitTime = oldTime - TimeUtil.currentTimeMillis();
					// 再次判断下是否超过最大等待时间
                    if (waitTime > maxQueueingTimeMs) {
						// 需要阻塞，将上次通过的时间改回去
						// 刚开始认为这是个bug，后来了解到
						// 每个请求在设置oldTime时都会通过addAndGet()原子操作lastestPassedTime，并赋值给oldTime
						// 这样每个线程的sleep()时间都不相同，线程也不会同时醒过来
						// costTime+latestPassedTime，大于当前时间就代表请求的比较频繁，需要等待一会才能继续请求
                        latestPassedTime.addAndGet(-costTime);
                        return false;
                    }
					// 在条件竞争中，等待时间很可能是非正整数，
                    if (waitTime > 0) {
						// 如果可以等待，则等待相应时间
                        Thread.sleep(waitTime);
                    }
					// 如果等待时间是非负整数，无需等待，直接判定通过
					// 判定通过
                    return true;
                } catch (InterruptedException e) {
                }
            }
        }
		// 其余情况，判定为不通过
        return false;
    }

}
