/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.protocol.injvm;

import com.alibaba.dubbo.rpc.Exporter;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.protocol.AbstractExporter;

import java.util.Map;

/**
 * InjvmExporter
 *
 * 最终是将key=接口全类名，value=this(也就是InjvmExporter对象)put到exporterMap中了。
 * 再回到ServiceConfig的exportLocal方法中。
 * 还有最后一句exporters.add(exporter);
 * 这里是将上面生成的InjvmExporter对象缓存了起来。
 * 到这里我们这个服务本地暴露(injvm)就解析完成了。
 */
class InjvmExporter<T> extends AbstractExporter<T> {

    private final String key;

    private final Map<String, Exporter<?>> exporterMap;

    InjvmExporter(Invoker<T> invoker, String key, Map<String, Exporter<?>> exporterMap) {
        super(invoker);
        this.key = key;
        this.exporterMap = exporterMap;
        exporterMap.put(key, this);
    }

    @Override
    public void unexport() {
        super.unexport();
        exporterMap.remove(key);
    }

}
