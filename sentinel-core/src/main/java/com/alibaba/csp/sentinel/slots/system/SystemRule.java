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

	/**
	 * 获取设置的QPS阈值
	 * @return 设置的QPS阈值
	 */
	public double getQps() {
		return qps;
	}

	/**
	 * 设置最大的总QPS
	 * 在高并发条件下，真是通过的QPS可能会高于最大QPS的设置
	 * 真实通过的QPS近乎于满足以下公式
	 * 真是通过QPS=设置的QPS值+当前线程的并发数
	 * @param qps 最大的总QPS，如果≤0，用于清除阈值
     */
    public void setQps(double qps) {
        this.qps = qps;
	}

	/**
	 * 获取设置的最大线程数
	 * @return 设置的最大线程数
	 */
	public long getMaxThread() {
		return maxThread;
	}

	/**
	 * 设置最大并发工作线程数
	 * 当前并发线程数＞{@code maxThread}时，仅有最大线程数将并行运行
	 * @param maxThread 最大并发线程数，如果≤0，用于清除阈值
     */
    public void setMaxThread(long maxThread) {
        this.maxThread = maxThread;
	}

	/**
	 * 获取设置的平均响应时间
	 * @return 设置的平均响应时间
	 */
	public long getAvgRt() {
		return avgRt;
	}

	/**
	 * 设置最大的平均响应时间
	 * @param avgRt 最大的平均响应时间，如果≤0，用于清除阈值
     */
    public void setAvgRt(long avgRt) {
        this.avgRt = avgRt;
	}

	/**
	 * 获取设置的最大系统负载
	 * @return 设置的最大系统负载
	 */
	public double getHighestSystemLoad() {
		return highestSystemLoad;
	}

	/**
	 * 设置最高的系统负载，负载不同于Linux的系统负载，因为它不够严谨
	 * 计算负载，包括Linux系统负载，当前全局的相应时间和全局QPS都会考虑到
	 * 这意味着需要协调{@link #setAvgRt(long)}和{@link #setQps(double)}
	 * 需要注意的是，参数仅会在类Unix的系统上可用
	 * @param highestSystemLoad 最大的系统负载, 如果≤0，用于清除阈值
     * @see SystemRuleManager
     */
    public void setHighestSystemLoad(double highestSystemLoad) {
        this.highestSystemLoad = highestSystemLoad;
    }

	/**
	 * 获取设置的最高CPU使用率
	 * CPU使用率的范围在[0, 1]
	 * @return 设置的最高CPU使用率
     */
    public double getHighestCpuUsage() {
        return highestCpuUsage;
    }

	/**
	 * 设置最高的CPU使用率
	 * CPU的使用率范围：[0, 1]
	 * @param highestCpuUsage CPU使用率
     */
    public void setHighestCpuUsage(double highestCpuUsage) {
        this.highestCpuUsage = highestCpuUsage;
    }

    @Override
    public boolean passCheck(Context context, DefaultNode node, int count, Object... args) {
		// 默认情况下，通过策略
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
