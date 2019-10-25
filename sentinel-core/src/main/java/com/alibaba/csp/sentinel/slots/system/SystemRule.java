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
package com.alibaba.csp.sentinel.slots.system;

import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slots.block.AbstractRule;

/**
 * 系统保护规则
 * Sentinel的系统保护规则用于管理入口和容量限制
 * 它有平均响应时间、入口总QPS、请求并发线程数、只在Linux上可用的系统负载
 *
 * 为了合理的设定阈值，需要进行性能测试
 * @see SystemRuleManager
 */
public class SystemRule extends AbstractRule {

    /**
	 * 系统最大的load
	 * 负数表示没有阈值设定
     */
    private double highestSystemLoad = -1;
    /**
	 * CPU使用率
	 * 负数表示没有阈值设定
     */
	private double highestCpuUsage = -1;
	/**
	 * 所有入口资源的QPS
	 * 负数表示没有阈值设定
	 */
	private double qps = -1;
	/**
	 * 所有入口流量的平均响应时间
	 * 负数表示没有阈值设定
	 */
    private long avgRt = -1;
	/**
	 * 入口流量的最大线程并发数
	 * 负数表示没有阈值设定
	 */
	private long maxThread = -1;

    public double getQps() {
        return qps;
    }

    /**
     * Set max total QPS. In a high concurrency condition, real passed QPS may be greater than max QPS set.
     * The real passed QPS will nearly satisfy the following formula:<br/>
     *
     * <pre>real passed QPS = QPS set + concurrent thread number</pre>
     *
     * @param qps max total QOS, values <= 0 are special for clearing the threshold.
     */
    public void setQps(double qps) {
        this.qps = qps;
    }

    public long getMaxThread() {
        return maxThread;
    }

    /**
     * Set max PARALLEL working thread. When concurrent thread number is greater than {@code maxThread} only
     * maxThread will run in parallel.
     *
     * @param maxThread max parallel thread number, values <= 0 are special for clearing the threshold.
     */
    public void setMaxThread(long maxThread) {
        this.maxThread = maxThread;
    }

    public long getAvgRt() {
        return avgRt;
    }

    /**
     * Set max average RT(response time) of all passed requests.
     *
     * @param avgRt max average response time, values <= 0 are special for clearing the threshold.
     */
    public void setAvgRt(long avgRt) {
        this.avgRt = avgRt;
    }

    public double getHighestSystemLoad() {
        return highestSystemLoad;
    }

    /**
     * <p>
     * Set highest load. The load is not same as Linux system load, which is not sensitive enough.
     * To calculate the load, both Linux system load, current global response time and global QPS will be considered,
     * which means that we need to coordinate with {@link #setAvgRt(long)} and {@link #setQps(double)}
     * </p>
     * <p>
     * Note that this parameter is only available on Unix like system.
     * </p>
     *
     * @param highestSystemLoad highest system load, values <= 0 are special for clearing the threshold.
     * @see SystemRuleManager
     */
    public void setHighestSystemLoad(double highestSystemLoad) {
        this.highestSystemLoad = highestSystemLoad;
    }

    /**
     * Get highest cpu usage. Cpu usage is between [0, 1]
     *
     * @return highest cpu usage
     */
    public double getHighestCpuUsage() {
        return highestCpuUsage;
    }

    /**
     * set highest cpu usage. Cpu usage is between [0, 1]
     *
     * @param highestCpuUsage the value to set.
     */
    public void setHighestCpuUsage(double highestCpuUsage) {
        this.highestCpuUsage = highestCpuUsage;
    }

    @Override
    public boolean passCheck(Context context, DefaultNode node, int count, Object... args) {
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SystemRule)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        SystemRule that = (SystemRule)o;

        if (Double.compare(that.highestSystemLoad, highestSystemLoad) != 0) {
            return false;
        }
        if (Double.compare(that.highestCpuUsage, highestCpuUsage) != 0) {
            return false;
        }

        if (Double.compare(that.qps, qps) != 0) {
            return false;
        }

        if (avgRt != that.avgRt) {
            return false;
        }
        return maxThread == that.maxThread;
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        long temp;
        temp = Double.doubleToLongBits(highestSystemLoad);
        result = 31 * result + (int)(temp ^ (temp >>> 32));

        temp = Double.doubleToLongBits(highestCpuUsage);
        result = 31 * result + (int)(temp ^ (temp >>> 32));

        temp = Double.doubleToLongBits(qps);
        result = 31 * result + (int)(temp ^ (temp >>> 32));

        result = 31 * result + (int)(avgRt ^ (avgRt >>> 32));
        result = 31 * result + (int)(maxThread ^ (maxThread >>> 32));
        return result;
    }

    @Override
    public String toString() {
        return "SystemRule{" +
            "highestSystemLoad=" + highestSystemLoad +
            ", highestCpuUsage=" + highestCpuUsage +
            ", qps=" + qps +
            ", avgRt=" + avgRt +
            ", maxThread=" + maxThread +
            "}";
    }
}
