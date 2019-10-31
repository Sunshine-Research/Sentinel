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
package com.alibaba.csp.sentinel.node;

import com.alibaba.csp.sentinel.node.metric.MetricNode;
import com.alibaba.csp.sentinel.slots.statistic.metric.ArrayMetric;
import com.alibaba.csp.sentinel.slots.statistic.metric.Metric;
import com.alibaba.csp.sentinel.util.TimeUtil;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 数据分析节点保存了三种实时数据统计的度量标准
 * 秒级度量标准({@code rollingCounterInSecond})
 * 分钟度量标准({@code rollingCounterInMinute})
 * 线程数量
 *
 * Sentinel使用滑动窗口的方式记录和计算resource的实时数据分析
 * 滑动窗口的基础结构在{@link ArrayMetric}之后，是{@code LeapArray}
 * case 1: 当第一个请求进入时，Sentinel会创建一个指定时间间隔的新窗口来存储运行时数据，比如总的响应时间，QPS，阻塞请求数等
 * 	0      100ms
 *  +-------+--→ Sliding Windows
 * 	    ^
 * 	    |
 * 	  request
 * Sentinel使用使用桶内的静态值来决定请求是否可以通过，比如，如果一个规则声明只有100个请求可以通过，它会统计桶内的所有QPS之后，并和规则中声明的阈值进行比较
 *
 * case 2: 持续请求
 *  0    100ms    200ms    300ms
 *  +-------+-------+-------+-----→ Sliding Windows
 *                      ^
 *                      |
 *                   request
 *
 * case 3: 请求持续进入，但是前一个桶已经不可用了
 *  0    100ms    200ms	  800ms	   900ms  1000ms    1300ms
 *  +-------+-------+ ...... +-------+-------+ ...... +-------+-----→ Sliding Windows
 *                                                      ^
 *                                                      |
 *                                                    request
 * 滑动窗口需要编程
 * 300ms     800ms  900ms  1000ms  1300ms
 *  + ...... +-------+ ...... +-------+-----→ Sliding Windows
 *                                                      ^
 *                                                      |
 *                                                    request
 */
public class StatisticNode implements Node {

    /**
	 * 每秒的滑动窗口
	 * 滑动窗口的时间间隔是由总时间间隔，和自定义的滑动窗口数量决定的
     */
    private transient volatile Metric rollingCounterInSecond = new ArrayMetric(SampleCountProperty.SAMPLE_COUNT,
        IntervalProperty.INTERVAL);

    /**
	 * 每分钟的滑动窗口，滑动窗口时间间隔为1s
     */
    private transient Metric rollingCounterInMinute = new ArrayMetric(60, 60 * 1000, false);

    /**
	 * 线程数量计数器
     */
    private AtomicInteger curThreadNum = new AtomicInteger(0);

    /**
	 * 上一次获取度量标准的时间戳
     */
    private long lastFetchTime = -1;

    @Override
    public Map<Long, MetricNode> metrics() {
		// 由于是在一个single-thread调度池里运行，拉取操作是线程安全的
        long currentTime = TimeUtil.currentTimeMillis();
        currentTime = currentTime - currentTime % 1000;
        Map<Long, MetricNode> metrics = new ConcurrentHashMap<>();
        List<MetricNode> nodesOfEverySecond = rollingCounterInMinute.details();
        long newLastFetchTime = lastFetchTime;
        // Iterate metrics of all resources, filter valid metrics (not-empty and up-to-date).
        for (MetricNode node : nodesOfEverySecond) {
            if (isNodeInTime(node, currentTime) && isValidMetricNode(node)) {
                metrics.put(node.getTimestamp(), node);
                newLastFetchTime = Math.max(newLastFetchTime, node.getTimestamp());
            }
        }
        lastFetchTime = newLastFetchTime;

        return metrics;
    }

    private boolean isNodeInTime(MetricNode node, long currentTime) {
        return node.getTimestamp() > lastFetchTime && node.getTimestamp() < currentTime;
    }

    private boolean isValidMetricNode(MetricNode node) {
        return node.getPassQps() > 0 || node.getBlockQps() > 0 || node.getSuccessQps() > 0
            || node.getExceptionQps() > 0 || node.getRt() > 0 || node.getOccupiedPassQps() > 0;
    }

    @Override
    public void reset() {
        rollingCounterInSecond = new ArrayMetric(SampleCountProperty.SAMPLE_COUNT, IntervalProperty.INTERVAL);
    }

    @Override
    public long totalRequest() {
        long totalRequest = rollingCounterInMinute.pass() + rollingCounterInMinute.block();
        return totalRequest;
    }

    @Override
    public long blockRequest() {
        return rollingCounterInMinute.block();
    }

    @Override
    public double blockQps() {
        return rollingCounterInSecond.block() / rollingCounterInSecond.getWindowIntervalInSec();
    }

    @Override
    public double previousBlockQps() {
        return this.rollingCounterInMinute.previousWindowBlock();
    }

    @Override
    public double previousPassQps() {
        return this.rollingCounterInMinute.previousWindowPass();
    }

    @Override
    public double totalQps() {
        return passQps() + blockQps();
    }

    @Override
    public long totalSuccess() {
        return rollingCounterInMinute.success();
    }

    @Override
    public double exceptionQps() {
        return rollingCounterInSecond.exception() / rollingCounterInSecond.getWindowIntervalInSec();
    }

    @Override
    public long totalException() {
        return rollingCounterInMinute.exception();
    }

    @Override
    public double passQps() {
        return rollingCounterInSecond.pass() / rollingCounterInSecond.getWindowIntervalInSec();
    }

    @Override
    public long totalPass() {
        return rollingCounterInMinute.pass();
    }

    @Override
    public double successQps() {
        return rollingCounterInSecond.success() / rollingCounterInSecond.getWindowIntervalInSec();
    }

    @Override
    public double maxSuccessQps() {
        return rollingCounterInSecond.maxSuccess() * rollingCounterInSecond.getSampleCount();
    }

    @Override
    public double occupiedPassQps() {
        return rollingCounterInSecond.occupiedPass() / rollingCounterInSecond.getWindowIntervalInSec();
    }

    @Override
    public double avgRt() {
		// 获取每秒成功请求数量
        long successCount = rollingCounterInSecond.success();
        if (successCount == 0) {
            return 0;
        }
		// 每秒的请求时间/请求成功数量
        return rollingCounterInSecond.rt() * 1.0 / successCount;
    }

    @Override
    public double minRt() {
        return rollingCounterInSecond.minRt();
    }

    @Override
    public int curThreadNum() {
        return curThreadNum.get();
    }

    @Override
    public void addPassRequest(int count) {
        rollingCounterInSecond.addPass(count);
        rollingCounterInMinute.addPass(count);
    }

    @Override
    public void addRtAndSuccess(long rt, int successCount) {
        rollingCounterInSecond.addSuccess(successCount);
        rollingCounterInSecond.addRT(rt);

        rollingCounterInMinute.addSuccess(successCount);
        rollingCounterInMinute.addRT(rt);
    }

    @Override
    public void increaseBlockQps(int count) {
        rollingCounterInSecond.addBlock(count);
        rollingCounterInMinute.addBlock(count);
    }

    @Override
    public void increaseExceptionQps(int count) {
        rollingCounterInSecond.addException(count);
        rollingCounterInMinute.addException(count);
    }

    @Override
    public void increaseThreadNum() {
        curThreadNum.incrementAndGet();
    }

    @Override
    public void decreaseThreadNum() {
        curThreadNum.decrementAndGet();
    }

    @Override
    public void debug() {
        rollingCounterInSecond.debug();
    }

    @Override
    public long tryOccupyNext(long currentTime, int acquireCount, double threshold) {
		// 获取当前最大的count，根据阈值*时间建个计算
        double maxCount = threshold * IntervalProperty.INTERVAL / 1000;
		// 获取当前已经处于等待的资源
        long currentBorrow = rollingCounterInSecond.waiting();
		// 如果当前窗口已经超额被占领，返回占领需要的等待桑建
        if (currentBorrow >= maxCount) {
            return OccupyTimeoutProperty.getOccupyTimeout();
        }
		// 获取滑动窗口的时间总
        int windowLength = IntervalProperty.INTERVAL / SampleCountProperty.SAMPLE_COUNT;
		// 计算当前时间窗口为周期，开始窗口的起点
		//
        long earliestTime = currentTime - currentTime % windowLength + windowLength - IntervalProperty.INTERVAL;

        int idx = 0;
        /*
		 * 注意：此处{@code currentPass}可能小于实际值，因为调用rollingCounterInSecond.pass()会产生时间差异
		 * 所以在高并发情况下，下面的代码可能会导致多占领token
         */
		// 当前窗口已经通过的请求数量
        long currentPass = rollingCounterInSecond.pass();
		// 必须在开始窗口的时间起点小于当前时间戳时进行计算
        while (earliestTime < currentTime) {
			// 等待时间=
            long waitInMs = idx * windowLength + windowLength - currentTime % windowLength;
			// 如果等待时间超过了设置的占领等待时间
            if (waitInMs >= OccupyTimeoutProperty.getOccupyTimeout()) {
				// 则使用设置的占领等待时间
                break;
            }
            long windowPass = rollingCounterInSecond.getWindowPass(earliestTime);
            if (currentPass + currentBorrow + acquireCount - windowPass <= maxCount) {
                return waitInMs;
            }
            earliestTime += windowLength;
            currentPass -= windowPass;
            idx++;
        }

        return OccupyTimeoutProperty.getOccupyTimeout();
    }

    @Override
    public long waiting() {
        return rollingCounterInSecond.waiting();
    }

    @Override
    public void addWaitingRequest(long futureTime, int acquireCount) {
        rollingCounterInSecond.addWaiting(futureTime, acquireCount);
    }

    @Override
    public void addOccupiedPass(int acquireCount) {
        rollingCounterInMinute.addOccupiedPass(acquireCount);
        rollingCounterInMinute.addPass(acquireCount);
    }
}
