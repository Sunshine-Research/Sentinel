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
 * <p>
 * The principle idea comes from Guava. However, the calculation of Guava is
 * rate-based, which means that we need to translate rate to QPS.
 * </p>
 *
 * <p>
 * Requests arriving at the pulse may drag down long idle systems even though it
 * has a much larger handling capability in stable period. It usually happens in
 * scenarios that require extra time for initialization, e.g. DB establishes a connection,
 * connects to a remote service, and so on. That’s why we need “warm up”.
 * </p>
 *
 * <p>
 * Sentinel's "warm-up" implementation is based on the Guava's algorithm.
 * However, Guava’s implementation focuses on adjusting the request interval,
 * which is similar to leaky bucket. Sentinel pays more attention to
 * controlling the count of incoming requests per second without calculating its interval,
 * which resembles token bucket algorithm.
 * </p>
 *
 * <p>
 * The remaining tokens in the bucket is used to measure the system utility.
 * Suppose a system can handle b requests per second. Every second b tokens will
 * be added into the bucket until the bucket is full. And when system processes
 * a request, it takes a token from the bucket. The more tokens left in the
 * bucket, the lower the utilization of the system; when the token in the token
 * bucket is above a certain threshold, we call it in a "saturation" state.
 * </p>
 *
 * <p>
 * Base on Guava’s theory, there is a linear equation we can write this in the
 * form y = m * x + b where y (a.k.a y(x)), or qps(q)), is our expected QPS
 * given a saturated period (e.g. 3 minutes in), m is the rate of change from
 * our cold (minimum) rate to our stable (maximum) rate, x (or q) is the
 * occupied token.
 * </p>
 * 假定permitsPerSecond=10，stableInterval=100ms，coldInterval=300ms(coldFactor=3)
 * 当达到maxPermits时，系统处于最冷状态，获取一个permit需要300ms，如果storedPermits小于thresholdPermits值时，只需要100ms。
 * 预热的时间是我们在构造的时候指定的
 * @author jialiang.linjl
 */
public class WarmUpController implements TrafficShapingController {

	protected double count;
	protected int warningToken = 0;
	protected double slope;
	protected AtomicLong storedTokens = new AtomicLong(0);
	protected AtomicLong lastFilledTime = new AtomicLong(0);
	private int coldFactor;
	private int maxToken;

	public WarmUpController(double count, int warmUpPeriodInSec, int coldFactor) {
		construct(count, warmUpPeriodInSec, coldFactor);
	}

	public WarmUpController(double count, int warmUpPeriodInSec) {
		construct(count, warmUpPeriodInSec, 3);
	}

	private void construct(double count, int warmUpPeriodInSec, int coldFactor) {

		if (coldFactor <= 1) {
			throw new IllegalArgumentException("Cold factor should be larger than 1");
		}

		this.count = count;

		this.coldFactor = coldFactor;
		// SmoothWarmingUp算法希望，token获取的速率跟它所需要的时间比例保持一致，所以梯形面积是长方形面积的2倍
		// 稳定情况下获取的token面积为x，那么预热区域内的token面积为(coldFactor - 1) * x
		// warmUpPeriodInSec是梯形面积，warningToken * (1/count) = warmUpPeriodInSec / 2
		// 为什么是2倍，coldFactor默认写死为3，超过稳定时间，获取token的最长时间是3*稳定时间
		warningToken = (int) (warmUpPeriodInSec * count) / (coldFactor - 1);
		// 根据梯形面积，我们可以求出maxToken的值
		// (maxToken-warningToken) * (coldFactor - 1) * (1 / count) = 2 * warmUpPeriodInSec
		maxToken = warningToken + (int) (2 * warmUpPeriodInSec * count / (1.0 + coldFactor));

		// 斜率计算公式
		// y1 = kx1 y2 = kx2 => k = (y1 - y2) / (x1 - x2)
		// coldFactor * (1 / count) - (1 / count) = k * (maxToken - warningToken)
		slope = (coldFactor - 1.0) / count / (maxToken - warningToken);
	}

	@Override
	public boolean canPass(Node node, int acquireCount) {
		return canPass(node, acquireCount, false);
	}

	@Override
	public boolean canPass(Node node, int acquireCount, boolean prioritized) {
		// 获取当前窗口通过的QPS
		long passQps = (long) node.passQps();
		// 获取上一个窗口通过的QPS
		long previousQps = (long) node.previousPassQps();
		// 同步最新的可用token数量，存储于storedTokens中
		syncToken(previousQps);

		long restToken = storedTokens.get();
		// 开始计算它的斜率
		// 如果进入了警戒线，开始调整他的qps
		if (restToken >= warningToken) {
			long aboveToken = restToken - warningToken;
			// 消耗的速度要比warning快
			// current interval = restToken*slope+1/count
			// 通过计算当前可用token和警戒线的距离，来计算当前的QPS
			// aboveToken * slope + 1.0 / count = 处于预热区内，当前比率下，获取token的时间
			// 1/获取token的时间，即为当前的QPS
			double warningQps = Math.nextUp(1.0 / (aboveToken * slope + 1.0 / count));
			// 如果没有超出预热的QPS，则判定通过
			if (passQps + acquireCount <= warningQps) {
				return true;
			}
		} else {
			// 如果处于热区域内，没有超过规则设定的阈值，则判定通过
			if (passQps + acquireCount <= count) {
				return true;
			}
		}
		// 否则，判定不通过
		return false;
	}

	protected void syncToken(long passQps) {
		// 获取当前的时间戳
		long currentTime = TimeUtil.currentTimeMillis();
		// 精确到秒的时间戳
		currentTime = currentTime - currentTime % 1000;
		// 获取上一次扩容的时间
		long oldLastFillTime = lastFilledTime.get();
		// 如果时间不对，不进行同步
		if (currentTime <= oldLastFillTime) {
			return;
		}
		// token数量旧值
		long oldValue = storedTokens.get();
		// 计算新的token数量
		long newValue = coolDownTokens(currentTime, passQps);
		// 更新token的值
		if (storedTokens.compareAndSet(oldValue, newValue)) {
			// 更新当前可用的值
			long currentValue = storedTokens.addAndGet(0 - passQps);
			if (currentValue < 0) {
				storedTokens.set(0L);
			}
			// 设置上一次扩容的时间戳
			lastFilledTime.set(currentTime);
		}
	}

	/**
	 * 计算新的token数量
	 * @param currentTime 当前时间戳
	 * @param passQps     通过的QPS
	 * @return 新token数量
	 */
	private long coolDownTokens(long currentTime, long passQps) {
		long oldValue = storedTokens.get();
		long newValue = oldValue;

		// 添加token的判断前提条件:
		// 当token的消耗程度远远低于警戒线的时候
		if (oldValue < warningToken) {
			// 处于长方形区域内，属于热区，正常添加token
			// 根据count数，每秒扩充token
			newValue = (long) (oldValue + (currentTime - lastFilledTime.get()) * count / 1000);
		} else if (oldValue > warningToken) {
			// 当前判定仍处于冷启动阶段
			// 太绕了，我佛了
			// 当前最慢的QPS计算公式：1 / (coldFactor * (1 / count)) => count / coldFactor
			// 当前的QPS已经比最慢的还慢了，需要添加token
			if (passQps < (int) count / coldFactor) {
				// 否则就需要添加token
				newValue = (long) (oldValue + (currentTime - lastFilledTime.get()) * count / 1000);
			}
		}
		// 取计算出的token新值和容量上限的二者的最小值
		return Math.min(newValue, maxToken);
	}

}
