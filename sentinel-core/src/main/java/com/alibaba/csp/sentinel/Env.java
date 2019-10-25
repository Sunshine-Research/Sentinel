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
package com.alibaba.csp.sentinel;

import com.alibaba.csp.sentinel.init.InitExecutor;

/**
 * Sentinel的使用环境，会触发Sentinel的所有市县
 * <p>
 * 需要注意的是：为了避免死锁，其他类的静态代码块或者静态字段需要永远不会指向Env这个类
 * </p>
 */
class Env {

	static final Sph sph = new CtSph();

    static {
		// 如果初始化失败，该进程会关闭
        InitExecutor.doInit();
    }

}
