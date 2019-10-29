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

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.node.metric.MetricNode;
import com.alibaba.csp.sentinel.slots.statistic.metric.DebugSupport;

import java.util.Map;

/**
 * 存储resource实时数据的数据分析节点
 */
public interface Node extends OccupySupport, DebugSupport {

    /**
	 * 获取每分钟进入的请求数量
	 * 计算公式：{@code pass + block}
	 * @return 每分钟总请求数
     */
    long totalRequest();

    /**
	 * @return 每分钟通过请求数
     * @since 1.5.0
     */
    long totalPass();

    /**
	 * @return 每分钟资源释放释放数量
     */
    long totalSuccess();

    /**
     * Get blocked request count per minute (totalBlockRequest).
     *
     * @return total blocked request count per minute
     */
    long blockRequest();

    /**
	 * @return 每分钟异常数
     */
    long totalException();

    /**
	 * @return 校验通过的QPS
     */
    double passQps();

    /**
	 * @return 请求阻塞的QPS
     */
    double blockQps();

    /**
	 * 计算公式：{@link #passQps()} + {@link #blockQps()}
	 * @return 总QPS
     */
    double totalQps();

    /**
     * Get {@link Entry#exit()} request per second.
     *
     * @return QPS of completed requests
     */
    double successQps();

    /**
	 * @return 估算的最大已完成的QPS
     */
    double maxSuccessQps();

    /**
	 * @return 发生异常的QPS
     */
    double exceptionQps();

    /**
	 * @return 每秒的平均响应时间
     */
    double avgRt();

    /**
	 * @return 记录的最短响应时间
     */
    double minRt();

    /**
	 * @return 当前活跃线程数
     */
    int curThreadNum();

    /**
	 * @return 获取上一秒的阻塞QPS
     */
    double previousBlockQps();

    /**
	 * @return 获取上移滑动窗口的QPS
     */
    double previousPassQps();

    /**
	 * @return 获取所有resource合法数据统计节点
     */
    Map<Long, MetricNode> metrics();

    /**
	 * 添加通过数量
	 * @param count 需要添加的通过数量
     */
    void addPassRequest(int count);

    /**
	 * 添加响应时间和成功数量
	 * @param rt      响应时间
	 * @param success 需要添加的响应成功数量
     */
    void addRtAndSuccess(long rt, int success);

    /**
	 * 添加阻塞数量
	 * @param count 需要添加的阻塞数量
     */
    void increaseBlockQps(int count);

    /**
	 * 添加出现的业务异常数量
     * @param count count to add
     */
    void increaseExceptionQps(int count);

    /**
	 * 添加当前并发线程数
     */
    void increaseThreadNum();

    /**
	 * 减少当前并发线程数
     */
    void decreaseThreadNum();

    /**
	 * 重置内部计数器
	 * 在{@link IntervalProperty#INTERVAL}或{@link SampleCountProperty#SAMPLE_COUNT}发生改变时需要进行重置
     */
    void reset();
}
