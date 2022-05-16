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
package com.alibaba.dubbo.common.extension;

import com.alibaba.dubbo.common.URL;

import java.lang.annotation.*;

/**
 * Activate. This annotation is useful for automatically activate certain extensions with the given criteria,
 * for examples: <code>@Activate</code> can be used to load certain <code>Filter</code> extension when there are
 * multiple implementations.
 * <ol>
 * <li>{@link Activate#group()} specifies group criteria. Framework SPI defines the valid group values.
 * <li>{@link Activate#value()} specifies parameter key in {@link URL} criteria.
 * </ol>
 * SPI provider can call {@link ExtensionLoader#getActivateExtension(URL, String, String)} to find out all activated
 * extensions with the given criteria.
 *
 * @see SPI
 * @see URL
 * @see ExtensionLoader
 *
 * 该注解可以标记在类，接口，枚举和方法上，主要使用在有多个扩展点实现，需要根据不同条件激活的场景中，
 * 比如说Filter需要多个同时激活
 * @Activate参数解释：
 * group 表示URL中的分组如果匹配的话就激活，可以设置多个
 * value 查找URL中如果含有该key值，就会激活
 * before 填写扩展点列表，表示哪些扩展点需要在本扩展点的前面
 * after 表示哪些扩展点需要在本扩展点的后面
 * order 排序信息
 *
 * @Activate 总结
 * 1.String[] group 这个属性是分组的，在我们服务提供者就是provider,然后服务消费者那边就是consumer,你没配置组也就算了，
 * 如果你配置了组，就会把不是你这个组的给过滤掉。
 * 2.String[] value,这个参数也是个数组形式，他会查找URL中如果包含该key值就会激活，咱们上面代码中也看到了判断中有个
 * isActive(activate,url),其实这个方法就是把activate的value值拿出来，跟URL的所有key做比较，相等或者是key,然后
 * URL对应的value还不能是null,这才返回true.
 * 3.String[] before,after这两个属性在排序的时候用到了，before就是标识哪些扩展点在本扩展点前面，
 * after就是标识哪些扩展点在本扩展点后面。
 * 4.int order这个也是在排序的时候使用，先按照before跟after排，这个排好了直接返回了，最后用这个order排序。
 * 这里有个点 就是用-这个符合标识就可以不被激活，在filter属性上使用-标识需要去掉的过滤器，
 * 比如<dubbo:provider filter="-monitor"/>，你也用-default来去掉所有。
 *
 *
 * 扩展点自动激活加载的注解，就是用条件来控制该扩展点实现是否被自动激活加载，
 * 在扩展实现类上面使用，<u>实现了扩展点自动激活的特性</u>，它可以设置两个参数，分别是group和value。
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Activate {
    /**
     * Activate the current extension when one of the groups matches. The group passed into
     * {@link ExtensionLoader#getActivateExtension(URL, String, String)} will be used for matching.
     *
     * @return group names to match
     * @see ExtensionLoader#getActivateExtension(URL, String, String)
     */
    String[] group() default {};

    /**
     * Activate the current extension when the specified keys appear in the URL's parameters.
     * <p>
     * For example, given <code>@Activate("cache, validation")</code>, the current extension will be return only when
     * there's either <code>cache</code> or <code>validation</code> key appeared in the URL's parameters.
     * </p>
     *
     * @return URL parameter keys
     * @see ExtensionLoader#getActivateExtension(URL, String)
     * @see ExtensionLoader#getActivateExtension(URL, String, String)
     */
    String[] value() default {};

    /**
     * Relative ordering info, optional
     *
     * @return extension list which should be put before the current one
     */
    String[] before() default {};

    /**
     * Relative ordering info, optional
     *
     * @return extension list which should be put after the current one
     */
    String[] after() default {};

    /**
     * Absolute ordering info, optional
     *
     * @return absolute ordering info
     */
    int order() default 0;
}