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
package com.alibaba.csp.sentinel.init;

import com.alibaba.csp.sentinel.log.RecordLog;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 加载注册的init functions，并按照优先级执行
 */
public final class InitExecutor {

    private static AtomicBoolean initialized = new AtomicBoolean(false);

    /**
	 * 如果一个InitFunc抛出了异常，初始化进行会立即中断，应用程序会退出
	 * 初始化进程仅会执行一次
     */
    public static void doInit() {
		// 尝试获取乐观锁
        if (!initialized.compareAndSet(false, true)) {
            return;
        }
        try {
			// 通过SPI加载InitFunc接口
            ServiceLoader<InitFunc> loader = ServiceLoader.load(InitFunc.class);
            List<OrderWrapper> initList = new ArrayList<OrderWrapper>();
            for (InitFunc initFunc : loader) {
                RecordLog.info("[InitExecutor] Found init func: " + initFunc.getClass().getCanonicalName());
				// 调整有衔接
                insertSorted(initList, initFunc);
            }
			// 按照优先级进行初始化
            for (OrderWrapper w : initList) {
                w.func.init();
                RecordLog.info(String.format("[InitExecutor] Executing %s with order %d",
                    w.func.getClass().getCanonicalName(), w.order));
            }
        } catch (Exception ex) {
			// 异常处理
            RecordLog.warn("[InitExecutor] WARN: Initialization failed", ex);
            ex.printStackTrace();
        } catch (Error error) {
			// 错误处理
            RecordLog.warn("[InitExecutor] ERROR: Initialization failed with fatal error", error);
            error.printStackTrace();
        }
    }

    private static void insertSorted(List<OrderWrapper> list, InitFunc func) {
        int order = resolveOrder(func);
        int idx = 0;
        for (; idx < list.size(); idx++) {
            if (list.get(idx).getOrder() > order) {
                break;
            }
        }
        list.add(idx, new OrderWrapper(order, func));
    }

    private static int resolveOrder(InitFunc func) {
        if (!func.getClass().isAnnotationPresent(InitOrder.class)) {
            return InitOrder.LOWEST_PRECEDENCE;
        } else {
            return func.getClass().getAnnotation(InitOrder.class).value();
        }
    }

    private InitExecutor() {}

    private static class OrderWrapper {
        private final int order;
        private final InitFunc func;

        OrderWrapper(int order, InitFunc func) {
            this.order = order;
            this.func = func;
        }

        int getOrder() {
            return order;
        }

        InitFunc getFunc() {
            return func;
        }
    }
}
