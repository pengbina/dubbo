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

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.extension.support.ActivateComparator;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.ConfigUtils;
import com.alibaba.dubbo.common.utils.Holder;
import com.alibaba.dubbo.common.utils.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

/**
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see com.alibaba.dubbo.common.extension.SPI
 * @see com.alibaba.dubbo.common.extension.Adaptive
 * @see com.alibaba.dubbo.common.extension.Activate
 * <p>
 * 该类是扩展加载器，这是dubbo实现SPI扩展机制的核心，几乎所有实现的逻辑都被封装在ExtensionLoader中。
 * <p>
 * getExtensionLoader会对传进的接口进行校验，其中包括是否有 @SPI注解校验，这也是在接口上需加 @SPI的原因。
 * 然后从 EXTENSION_LOADERS缓存中获取该接口类型的 ExtensionLoader,如果获取不到，则创建一个该接口类型的 ExtensionLoader放入到缓存中，并返回该 ExtensionLoader。
 * <p>
 * 注意这里创建 ExtensionLoader对象的构造方法如下：
 * ExtensionLoader.getExtensionLoader获取ExtensionFactory接口的拓展类，
 * 再通过 getAdaptiveExtension从拓展类中获取目标拓展类。它会设置该接口对应的 objectFactory常量为 AdaptiveExtensionFactory。
 * 因为 AdaptiveExtensionFactory类上加了@Adaptive注解
 * <p>
 * 当通过 ExtensionLoader.getExtensionLoader取到接口的加载器Loader之后，在通过 getExtension方法获取需要拓展类对象。
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    //三个dubbo SPI默认扫描的路径
    //1.关于存放配置文件的路径变量：
    //是dubbo为了兼容jdk的SPI扩展机制思想而设存在的
    private static final String SERVICES_DIRECTORY = "META-INF/services/";

    //是为了给用户自定义的扩展实现配置文件存放
    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";

    //是dubbo内部提供的扩展的配置文件路径
    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    //扩展加载器集合，key为扩展接口，例如Protocol等
    //扩展类加载器缓存，就是扩展点ExtendsLoader实例缓存，key=扩展接口，value=扩展类加载器
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<Class<?>, ExtensionLoader<?>>();

    //扩展实现类集合，key为扩展实现类，value为扩展对象，例如key为Class<DubboProtocol>，value为DubboProtocol对象
    //扩展实例存入内存中缓存起来； key=扩展类 ； value=扩展类实例
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<Class<?>, Object>();

    // ==============================

    private final Class<?> type;

    private final ExtensionFactory objectFactory;

    //以下属性都是cache开头的，都是出于性能和资源的优化，才做的缓存，读取扩展配置后，会先进行缓存，
    //等到真正需要用到某个实现时，再对该实现类的对象进行初始化，然后对该对象也进行缓存。
    //以下提到的扩展名就是在配置文件中的key值，类似于“dubbo”等
    //缓存的扩展名与拓展类映射，和cachedClasses的key和value对换。
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<Class<?>, String>();
    //缓存的扩展实现类集合
    //扩展点Class缓存 key=扩展名 ，value=对应的class对象
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<Map<String, Class<?>>>();
    //扩展名与加有@Activate的自动激活类的映射
    private final Map<String, Activate> cachedActivates = new ConcurrentHashMap<String, Activate>();
    //缓存的扩展对象集合，key为扩展名，value为扩展对象
    //例如Protocol扩展，key为dubbo，value为DubboProcotol
    //扩展点实例缓存 key=扩展点名称，value=扩展实例的Holder实例
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<String, Holder<Object>>();
    //缓存的自适应( Adaptive )扩展对象，例如例如AdaptiveExtensionFactory类的对象
    private final Holder<Object> cachedAdaptiveInstance = new Holder<Object>();
    //缓存的自适应扩展对象的类，例如AdaptiveExtensionFactory类
    private volatile Class<?> cachedAdaptiveClass = null;
    //缓存的默认扩展名，就是@SPI中设置的值
    private String cachedDefaultName;
    //创建cachedAdaptiveInstance异常
    private volatile Throwable createAdaptiveInstanceError;
    //拓展Wrapper实现类集合
    /**
     * 这里提到了Wrapper类的概念。
     * 那我就解释一下：Wrapper类也实现了扩展接口，但是Wrapper类的用途是ExtensionLoader 返回扩展点时，
     * 包装在真正的扩展点实现外，<u>这实现了扩展点自动包装的特性</u>。
     * 通俗点说，就是一个接口有很多的实现类，这些实现类会有一些公共的逻辑，如果在每个实现类写一遍这个公共逻辑，
     * 那么代码就会重复，所以增加了这个Wrapper类来包装，把公共逻辑写到Wrapper类中，有点类似AOP切面编程思想。
     */
    private Set<Class<?>> cachedWrapperClasses;
    //拓展名与加载对应拓展类发生的异常的映射
    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<String, IllegalStateException>();

    private ExtensionLoader(Class<?> type) {
        this.type = type;
        //type通常不为ExtensionFactory类，
        //则objectFactory为ExtensionFactory接口的默认扩展类AdaptiveExtensionFactory
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    @SuppressWarnings("unchecked")
    /**
     * 根据扩展点接口来获得扩展加载器。
     */
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        // 扩展点接口为空，抛出异常
        //校验传进来的type类是否为空
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        //判断type是否是一个接口类
        //校验传进来的type类是否为接口
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type(" + type + ") is not interface!");
        }
        //判断是否为可扩展的接口
        //校验传进来的type类是否有@SPI注解
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type(" + type +
                    ") is not extension, because WITHOUT @" + SPI.class.getSimpleName() + " Annotation!");
        }
        //从扩展加载器集合中取出扩展接口对应的扩展加载器
        // 从ExtensionLoader缓存中查询是否已经存在对应类型的ExtensionLoader实例
        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        //如果为空，则创建该扩展接口的扩展加载器，并且添加到EXTENSION_LOADERS
        if (loader == null) {
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader;
    }

    private static ClassLoader findClassLoader() {
        return ExtensionLoader.class.getClassLoader();
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     * 获得符合自动激活条件的扩展实现类对象集合
     * <p>
     * 可以看到getActivateExtension重载了四个方法，其实最终的实现都是在最后一个重载方法，
     * 因为自动激活类的条件可以分为无条件、只有value以及有group和value三种，具体的可以回顾上述
     * <p>
     * 最后一个getActivateExtension方法有几个关键点：
     * <p>
     * group的值合法判断，因为group可选"provider"或"consumer"。
     * 判断该配置是否被移除。
     * 如果有自定义配置，并且需要放在自动激活扩展实现对象加载前，那么需要先存放自定义配置。
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        // 获得符合自动激活条件的拓展对象数组
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(com.alibaba.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        String value = url.getParameter(key);
        return getActivateExtension(url, value == null || value.length() == 0 ? null : Constants.COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see com.alibaba.dubbo.common.extension.Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> exts = new ArrayList<T>();
        List<String> names = values == null ? new ArrayList<String>(0) : Arrays.asList(values);
        //判断不存在配置 `"-name"` 。
        //例如，<dubbo:service filter="-default" /> ，代表移除所有默认过滤器。
        if (!names.contains(Constants.REMOVE_VALUE_PREFIX + Constants.DEFAULT_KEY)) {
            //获得扩展实现类数组，把扩展实现类放到cachedClasses中
            getExtensionClasses();
            for (Map.Entry<String, Activate> entry : cachedActivates.entrySet()) {
                String name = entry.getKey();
                Activate activate = entry.getValue();
                //判断group值是否存在所有自动激活类中group组中，匹配分组
                if (isMatchGroup(group, activate.group())) {
                    //不包含在自定义配置里。如果包含，会在下面的代码处理。
                    //判断是否配置移除。例如 <dubbo:service filter="-monitor" />，则 MonitorFilter 会被移除
                    //判断是否激活
                    if (!names.contains(name)
                            && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)
                            && isActive(activate, url)) {
                        //通过扩展名获得拓展对象
                        T ext = getExtension(name);
                        exts.add(ext);
                    }
                }
            }
            //排序
            Collections.sort(exts, ActivateComparator.COMPARATOR);
        }
        List<T> usrs = new ArrayList<T>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            //还是判断是否是被移除的配置
            //name没有-开头或者是names中没有-xxx 这种
            if (!name.startsWith(Constants.REMOVE_VALUE_PREFIX)
                    && !names.contains(Constants.REMOVE_VALUE_PREFIX + name)) {
                //在配置中把自定义的配置放在自动激活的扩展对象前面，可以让自定义的配置先加载
                //例如，<dubbo:service filter="demo,default,demo2" /> ，则 DemoFilter 就会放在默认的过滤器前面。
                if (Constants.DEFAULT_KEY.equals(name)) {
                    if (!usrs.isEmpty()) {
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                    T ext = getExtension(name);
                    usrs.add(ext);
                }
            }
        }
        if (!usrs.isEmpty()) {
            exts.addAll(usrs);
        }
        return exts;
    }

    private boolean isMatchGroup(String group, String[] groups) {
        if (group == null || group.length() == 0) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isActive(Activate activate, URL url) {
        String[] keys = activate.value();
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ConfigUtils.isNotEmpty(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        return (T) holder.get();
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<String>(cachedInstances.keySet()));
    }

    /**
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     * <p>
     * getExtension方法： 获得通过扩展名获得扩展对象
     * 这个方法中涉及到getDefaultExtension方法和createExtension方法，会在后面讲到。
     * 其他逻辑比较简单，就是从缓存中取，如果没有，就创建，然后放入缓存。
     * <p>
     * 获取接口拓展类实例
     * 1.检查缓存中是否存在
     * 2.创建并返回拓展类实例
     *
     * @param name 需要获取的配置文件中拓展类的key
     *             <p>
     *             首先检查缓存，缓存未命中则创建拓展对象。dubbo中包含了大量的扩展点缓存。这个就是典型的使用空间换时间的做法。也是Dubbo性能强劲的原因之一，包括
     *             <p>
     *             1.扩展点Class缓存 ，Dubbo SPI在获取扩展点时，会优先从缓存中读取，如果缓存中不存在则加载配置文件，根据配置将Class缓存到内存中，并不会直接初始化。
     *             2.扩展点实例缓存 ，Dubbo不仅会缓存Class,还会缓存Class的实例。每次取实例的时候会优先从缓存中取，取不到则从配置中加载，实例化并缓存到内存中。
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        //查找默认的扩展实现，也就是@SPI中的默认值作为key
        if ("true".equals(name)) {
            // 获取默认的拓展实现类,即@SPI注解上的默认实现类, 如@SPI("benz")
            return getDefaultExtension();
        }
        //缓存中获取对应的扩展对象
        // Holder，顾名思义，用于持有目标对象，从缓存中拿，没有则创建
        // 首先通过扩展名从扩展实例缓存中获取Holder对象
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            //如果没有获取到就new一个空的Holder实例存入缓存
            cachedInstances.putIfAbsent(name, new Holder<Object>());
            holder = cachedInstances.get(name);
        }
        //双重检查
        Object instance = holder.get();
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    //通过扩展名创建接口实现类的对象
                    //创建拓展实例
                    instance = createExtension(name);
                    //把创建的扩展对象放入缓存
                    //设置实例到holder中
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     * <p>
     * getDefaultExtension方法：查找默认的扩展实现
     * <p>
     * 这里涉及到getExtensionClasses方法，会在后面讲到。
     * 获得默认的扩展实现类对象就是通过缓存中默认的扩展名去获得实现类对象。
     */
    public T getDefaultExtension() {
        //获得扩展接口的实现类数组
        getExtensionClasses();
        if (null == cachedDefaultName || cachedDefaultName.length() == 0
                || "true".equals(cachedDefaultName)) {
            return null;
        }
        //又重新去调用了getExtension
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (name == null || name.length() == 0)
            throw new IllegalArgumentException("Extension name == null");
        try {
            this.getExtensionClass(name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<String>(clazzes.keySet()));
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     *                               <p>
     *                               addExtension方法：扩展接口的实现类
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes
        //该类是否是接口的本身或子类
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        //该类是否被激活
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }
        //判断是否为适配器
        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already existed(Extension " + type + ")!");
            }
            //把扩展名和扩展接口的实现类放入缓存
            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + "not implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + "can not be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " not existed(Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension not existed(Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    /**
     * getAdaptiveExtension方法：获得自适应扩展对象，也就是接口的适配器对象
     * <p>
     * 思路就是先从缓存中取适配器类的对象，如果没有，则创建一个适配器对象，
     * 然后放入缓存，createAdaptiveExtension方法解释在后面给出。
     *
     * @return
     */
    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        Object instance = cachedAdaptiveInstance.get();
        //先从缓存获取
        if (instance == null) {
            //判断之前有没有 ，有则报错
            if (createAdaptiveInstanceError == null) {
                synchronized (cachedAdaptiveInstance) {
                    instance = cachedAdaptiveInstance.get();
                    if (instance == null) {
                        try {
                            //创建适配器对象
                            //创建adaptive
                            instance = createAdaptiveExtension();
                            cachedAdaptiveInstance.set(instance);
                        } catch (Throwable t) {
                            //抛出异常就记录下来
                            createAdaptiveInstanceError = t;
                            throw new IllegalStateException("fail to create adaptive instance: " + t.toString(), t);
                        }
                    }
                }
            } else {
                throw new IllegalStateException("fail to create adaptive instance: " + createAdaptiveInstanceError.toString(), createAdaptiveInstanceError);
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    /**
     * createExtension方法：通过扩展名创建扩展接口实现类的对象
     * 这里运用到了两个扩展点的特性，分别是自动装配和自动包装。
     * <p>
     * 创建拓展类实例，包含如下步骤
     * 1. 通过 getExtensionClasses 获取所有的拓展类，从配置文件加载获取拓展类的map映射
     * 2. 通过反射创建拓展对象
     * 3. 向拓展对象中注入依赖（IOC）
     * 4. 将拓展对象包裹在相应的 Wrapper 对象中(AOP)
     *
     * @param name 需要获取的配置文件中拓展类的key
     * @return 拓展类实例
     * <p>
     * <p>
     * 创建拓展类对象步骤分别为：
     * <p>
     * 通过 getExtensionClasses 从配置文件中加载所有的拓展类，再通过名称获取目标拓展类
     * 通过反射创建拓展对象
     * 向拓展对象中注入依赖
     * 将拓展对象包裹在相应的 Wrapper 对象中
     */
    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        //获得扩展名对应的扩展实现类
        // 从配置文件中加载所有的拓展类，可得到“配置项名称”到“配置类”的map，
        // 再根据拓展项名称从map中取出相应的拓展类即可
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw findException(name);
        }
        try {
            //看缓存中是否有该类的对象
            //从扩展点缓存中获取对应实例对象
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                //如果缓存中不存在此类的扩展点，就通过反射创建实例,并存入缓存
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.newInstance());
                //然后从缓存中获取对应实例
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            //向对象中注入依赖的属性（自动装配）
            // 向实例中注入依赖,通过setter方法自动注入对应的属性实例
            injectExtension(instance);
            //创建 Wrapper 扩展对象（自动包装）
            //所有的wrapper缓存
            //从缓存中取出所有的包装类，形成包装链
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            if (wrapperClasses != null && !wrapperClasses.isEmpty()) {
                //包装wrapper
                // 循环创建 Wrapper 实例,形成Wrapper包装链
                for (Class<?> wrapperClass : wrapperClasses) {
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance));
                }
            }
            return instance;
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance(name: " + name + ", class: " +
                    type + ")  could not be instantiated: " + t.getMessage(), t);
        }
    }

    /**
     * injectExtension方法：向创建的拓展注入其依赖的属性
     * <p>
     * 思路就是是先通过反射获得类中的所有方法，然后找到set方法，找到需要依赖注入的属性，然后把对象注入进去。
     *
     * @param instance
     * @return
     */
    //注入扩展
    private T injectExtension(T instance) {
        try {
            if (objectFactory != null) {
                //反射获得该类中所有的方法
                for (Method method : instance.getClass().getMethods()) {
                    //如果是set方法
                    if (method.getName().startsWith("set")
                            && method.getParameterTypes().length == 1
                            && Modifier.isPublic(method.getModifiers())) {
                        /**
                         * Check {@link DisableInject} to see if we need auto injection for this property
                         *方法上面有@DisableInject注解，表示不想自动注入
                         */
                        if (method.getAnnotation(DisableInject.class) != null) {
                            continue;
                        }
                        Class<?> pt = method.getParameterTypes()[0];
                        try {
                            /**
                             * 判断 方法名的长度 > 3
                             * 然后将第4个字符转成小写然后拼接后面的字符
                             * 比如说setName
                             * 获取到的property就是name
                             *
                             * 获得属性，比如StubProxyFactoryWrapper类中有Protocol protocol属性，
                             */
                            String property = method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
                            //获得属性值，比如Protocol对象，也可能是Bean对象
                            Object object = objectFactory.getExtension(pt, property);
                            if (object != null) {
                                //注入依赖属性
                                method.invoke(instance, object);
                            }
                        } catch (Exception e) {
                            logger.error("fail to inject via method " + method.getName()
                                    + " of interface " + type.getName() + ": " + e.getMessage(), e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    /**
     * getExtensionClass方法：获得扩展名对应的扩展实现类
     *
     * @param name
     * @return
     */
    private Class<?> getExtensionClass(String name) {
        if (type == null)
            throw new IllegalArgumentException("Extension type == null");
        if (name == null)
            throw new IllegalArgumentException("Extension name == null");
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null)
            throw new IllegalStateException("No such extension \"" + name + "\" for " + type.getName() + "!");
        return clazz;
    }

    /**
     * getExtensionClasses方法：获得扩展实现类数组
     * <p>
     * 这里思路就是先从缓存中取，如果缓存为空，则从配置文件中读取扩展实现类
     *
     * @return 从配置文件中加载所有的拓展类
     * 在通过name获取拓展类之前，首先需要根据配置文件解析出拓展项名称与拓展类的映射map，之后再根据拓展项名称从map中取出相应的拓展类即可
     * <p>
     * 解析配置文件中接口的拓展项名称与拓展类的映射表map
     * <p>
     * 这里也是先检查缓存，若缓存未命中,则通过 loadExtensionClasses 加载拓展类，缓存避免了多次读取配置文件的耗时
     */
    private Map<String, Class<?>> getExtensionClasses() {
        //从缓存中获取已加载的拓展点class
        Map<String, Class<?>> classes = cachedClasses.get();
        //之前没有加载过
        //双重检查
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    //从配置文件中，加载扩展实现类数组
                    // 加载拓展类
                    classes = loadExtensionClasses();
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * loadExtensionClasses方法：从配置文件中，加载拓展实现类数组
     * <p>
     * 前一部分逻辑是在把SPI注解中的默认值放到缓存中去，加载实现类数组的逻辑是在后面几行，
     * 关键的就是loadDirectory方法（解析在下面给出），并且这里可以看出去找配置文件访问的资源路径顺序。
     *
     * @return loadExtensionClasses 方法总共做了两件事情。
     * 首先对 SPI 注解进行解析，获取并缓存接口的 @SPI注解上的默认拓展类在 cachedDefaultName。
     * 再调用 loadDirectory 方法加载指定文件夹配置文件。
     */
    // synchronized in getExtensionClasses
    private Map<String, Class<?>> loadExtensionClasses() {
        //获取到接口上面的spi 注解信息
        //获取并缓存接口的@SPI注解上的默认实现类,@SPI("value")中的value
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation != null) {
            //拿到spi注解上默认的值
            //@SPI内的默认值
            String value = defaultAnnotation.value();
            if ((value = value.trim()).length() > 0) {
                String[] names = NAME_SEPARATOR.split(value);
                //只允许有一个默认值
                if (names.length > 1) {
                    throw new IllegalStateException("more than 1 default extension name on extension " + type.getName()
                            + ": " + Arrays.toString(names));
                }
                //将默认实现类的名字缓存起来
                if (names.length == 1) cachedDefaultName = names[0];
            }
        }

        //从配置文件中加载实现类数组
        Map<String, Class<?>> extensionClasses = new HashMap<String, Class<?>>();
        // 加载指定文件夹下的配置文件,常量包含META-INF/dubbo/internal/，META-INF/dubbo/，META-INF/services/三个文件夹
        //去下面的这3个位置找扩展配置文件
        //META-INF/dubbo/internal/
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY);
        //META-INF/dubbo/
        loadDirectory(extensionClasses, DUBBO_DIRECTORY);
        //META-INF/services/ 兼容jdk spi
        loadDirectory(extensionClasses, SERVICES_DIRECTORY);
        return extensionClasses;
    }

    /**
     * loadDirectory方法：从一个配置文件中，加载拓展实现类数组
     * <p>
     * 这边的思路是先获得完整的文件名，遍历每一个文件，在loadResource方法中去加载每个文件的内容
     *
     * @param extensionClasses
     * @param dir
     */
    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir) {
        //拼接接口全限定名，得到完整的文件名
        String fileName = dir + type.getName();
        try {
            Enumeration<java.net.URL> urls;
            //获取ExtensionLoader类信息
            //获取classloader
            ClassLoader classLoader = findClassLoader();
            //使用类加载器获取Enumberation<java.net.URL> urls
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            if (urls != null) {
                //遍历文件
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();
                    // 解析并加载配置文件中配置的实现类到extensionClasses中去
                    loadResource(extensionClasses, classLoader, resourceURL);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    /**
     * loadResource方法：加载文件中的内容
     * <p>
     * 该类的主要的逻辑就是读取里面的内容，跳过“#”注释的内容，
     * 根据配置文件中的key=value的形式去分割，然后去加载value对应的类
     * <p>
     * <p>
     * 首先找到文件夹下的配置文件，文件名需为接口全限定名。
     * 利用类加载器获取文件资源链接，再解析配置文件中配置的实现类添加到 extensionClasses中。
     *
     * @param extensionClasses
     * @param classLoader
     * @param resourceURL      loadResource 方法用于读取和解析配置文件，按行读取配置文件，每行以等于号 = 为界，截取键与值，并通过反射加载类,
     *                         最后通过 loadClass方法加载扩展点实现类的class到map中，并对加载到的class进行分类缓存。
     */
    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, java.net.URL resourceURL) {
        try {
            // 使用reader读取文件，按照约定一行一行读取
            // 按行读取配置内容
            BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), "utf-8"));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    //跳过被#注释的内容
                    //处理注释问题
                    // 截取 # 之前的字符串，# 之后的内容为注释，需要忽略
                    final int ci = line.indexOf('#');
                    if (ci >= 0) line = line.substring(0, ci);
                    line = line.trim();
                    if (line.length() > 0) {
                        try {
                            String name = null;
                            int i = line.indexOf('=');
                            if (i > 0) {
                                //根据"="拆分key跟value
                                //分割name跟实现类
                                //名字
                                name = line.substring(0, i).trim();
                                //实现类
                                line = line.substring(i + 1).trim();
                            }
                            if (line.length() > 0) {
                                /**
                                 * 加载扩展类
                                 *
                                 * extensionClasses：存储扩展的map,整个查找扩展这块就是使用这个map
                                 * resourceURl:资源url
                                 * Class.forName(line,true,classLoader):实现类class
                                 * name:实现类的名字
                                 *
                                 */
                                // 通过反射加载类，并通过 loadClass 方法对类进行缓存
                                loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class(interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Throwable t) {
            logger.error("Exception when load extension class(interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    /**
     * loadClass方法：根据配置文件中的value加载扩展类
     * <p>
     * 重点关注该方法中兼容了jdk的SPI思想。因为jdk的SPI相关的配置文件中是xx.yyy.DemoFilter，
     * 并没有key，也就是没有扩展名的概念，
     * 所有为了兼容，通过xx.yyy.DemoFilter生成的扩展名为demo。
     *
     * @param extensionClasses 装载配置文件类的容器
     * @param resourceURL      配置文件资源URL
     * @param clazz            扩展点实现类的class
     * @param name             扩展点实现类的名称，配置文件一行中的key
     * @throws NoSuchMethodException 加载扩展点实现类的class到map中，并对加载到的class进行分类缓存
     *                               比如 cachedAdaptiveClass、cachedWrapperClasses 和 cachedNames 等等
     *
     * loadClass方法实现了扩展点的分类缓存功能，如包装类，自适应扩展点实现类，普通扩展点实现类等分别进行缓存。
     **/
    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name) throws NoSuchMethodException {
        //该类是否实现扩展接口
        //判断配置的实现类是否是实现了type接口
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error when load extension class(interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + "is not subtype of interface.");
        }
        //判断该类是否为扩展接口的适配器
        //缓存自适应拓展对象的类到cachedAdaptiveClass
        //根据配置中实现类的类型来分类缓存起来
        //检测目标类上是否有Adaptive注解，表示这个类就是一个自适应实现类，缓存到cachedAdaptiveClass
        //Adaptive注解是否在该类上面
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            if (cachedAdaptiveClass == null) {
                cachedAdaptiveClass = clazz;
                // Adaptive注解在类上面的时候，只允许一个实现类上面有@Adaptive注解
            } else if (!cachedAdaptiveClass.equals(clazz)) {
                throw new IllegalStateException("More than 1 adaptive class found: "
                        + cachedAdaptiveClass.getClass().getName()
                        + ", " + clazz.getClass().getName());
            }
            //检测clazz是否是Wrapper类型，判断依据是否有参数为该接口类的构造方法，
            // 缓存到cachedWrapperClasses
        } else if (isWrapperClass(clazz)) {
            Set<Class<?>> wrappers = cachedWrapperClasses;
            if (wrappers == null) {
                cachedWrapperClasses = new ConcurrentHashSet<Class<?>>();
                wrappers = cachedWrapperClasses;
            }
            wrappers.add(clazz);
        } else {
            //通过反射获得构造器对象
            //检测clazz是否有默认的构造方法，如果没有，则抛出异常
            clazz.getConstructor();
            //如果配置文件中key的name为空，则尝试从Extension注解中获取name,或使用小写的类名作为name.
            //已经弃用，就不再讨论这种方式了
            //未配置扩展名，自动生成，例如DemoFilter为 demo，主要用于兼容java SPI的配置。
            if (name == null || name.length() == 0) {
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }
            // 获得扩展名，可以是数组，有多个拓扩展名。
            //使用逗号将name分割为字符串数组
            String[] names = NAME_SEPARATOR.split(name);
            if (names != null && names.length > 0) {
                //如果扩展点配置的实现类使用了@Activate注解，就将对应的注解信息缓存起来
                Activate activate = clazz.getAnnotation(Activate.class);
                //如果是自动激活的实现类，则加入到缓存
                if (activate != null) {
                    cachedActivates.put(names[0], activate);
                }
                for (String n : names) {
                    //如果扩展点配置的实现类使用了@Activate注解，就将对应的注解信息缓存起来
                    if (!cachedNames.containsKey(clazz)) {
                        cachedNames.put(clazz, n);
                    }
                    //缓存扩展实现类
                    //最后将class存入extensionClasses
                    Class<?> c = extensionClasses.get(n);
                    if (c == null) {
                        extensionClasses.put(n, clazz);
                    } else if (c != clazz) {
                        throw new IllegalStateException("Duplicate extension " + type.getName() + " name " + n + " on " + c.getName() + " and " + clazz.getName());
                    }
                }
            }
        }
    }

    private boolean isWrapperClass(Class<?> clazz) {
        try {
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        com.alibaba.dubbo.common.Extension extension = clazz.getAnnotation(com.alibaba.dubbo.common.Extension.class);
        if (extension == null) {
            String name = clazz.getSimpleName();
            if (name.endsWith(type.getSimpleName())) {
                name = name.substring(0, name.length() - type.getSimpleName().length());
            }
            return name.toLowerCase();
        }
        return extension.value();
    }

    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            /**
             * 首先 获取class
             * 然后 创建对象
             */
            return injectExtension((T) getAdaptiveExtensionClass().newInstance());
        } catch (Exception e) {
            throw new IllegalStateException("Can not create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    //获取adaptiveExtension class
    private Class<?> getAdaptiveExtensionClass() {
        //获取extension class
        getExtensionClasses();
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        //创建自适应扩展实现类
        return cachedAdaptiveClass = createAdaptiveExtensionClass();
    }

    /**
     * 首先是通过createAdaptiveExtensionClassCode方法来拼装成自适应实现类的代码，
     * 接着就是使用spi获取编译器，然后进行编译，加载，返回自适应实现类的class对象
     * <p>
     * createAdaptiveExtensionClass方法：创建适配器类，
     * 类似于dubbo动态生成的Transporter$Adpative这样的类
     * <p>
     * <p>
     * 这个方法中就做了编译代码的逻辑，生成代码在createAdaptiveExtensionClassCode方法中，
     * createAdaptiveExtensionClassCode方法由于过长，
     * 我不在这边列出，下面会给出github的网址，读者可自行查看相关的源码解析。
     * createAdaptiveExtensionClassCode生成的代码逻辑可以对照我上述讲的（二）注解@Adaptive中的Transporter$Adpative类来看
     * <p>
     * https://github.com/CrazyHZM/incubator-dubbo/blob/analyze-2.6.x/dubbo-common/src/main/java/com/alibaba/dubbo/common/extension/ExtensionLoader.java
     *
     * @return
     */
    private Class<?> createAdaptiveExtensionClass() {
        //创建动态生成的适配器类代码
        //拼装代理类的java代码
        String code = createAdaptiveExtensionClassCode();
        ClassLoader classLoader = findClassLoader();
        com.alibaba.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(com.alibaba.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        //进行编译
        //编译代码，返回该类
        return compiler.compile(code, classLoader);
    }

    private String createAdaptiveExtensionClassCode() {
        StringBuilder codeBuilder = new StringBuilder();
        Method[] methods = type.getMethods();

        //查找方法上面有没有@Adaptive注解
        boolean hasAdaptiveAnnotation = false;
        for (Method m : methods) {
            if (m.isAnnotationPresent(Adaptive.class)) {
                hasAdaptiveAnnotation = true;
                break;
            }
        }
        // no need to generate adaptive class since there's no adaptive method found.
        if (!hasAdaptiveAnnotation)
            throw new IllegalStateException("No adaptive method on extension " + type.getName() + ", refuse to create the adaptive class!");

        codeBuilder.append("package ").append(type.getPackage().getName()).append(";");
        codeBuilder.append("\nimport ").append(ExtensionLoader.class.getName()).append(";");
        codeBuilder.append("\npublic class ").append(type.getSimpleName()).append("$Adaptive").append(" implements ").append(type.getCanonicalName()).append(" {");

        for (Method method : methods) {
            //返回值
            Class<?> rt = method.getReturnType();
            //参数类型
            Class<?>[] pts = method.getParameterTypes();
            //异常
            Class<?>[] ets = method.getExceptionTypes();

            Adaptive adaptiveAnnotation = method.getAnnotation(Adaptive.class);
            StringBuilder code = new StringBuilder(512);
            if (adaptiveAnnotation == null) {
                code.append("throw new UnsupportedOperationException(\"method ")
                        .append(method.toString()).append(" of interface ")
                        .append(type.getName()).append(" is not adaptive method!\");");
            } else {
                int urlTypeIndex = -1;
                //查找参数列表中有没有url
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].equals(URL.class)) {
                        urlTypeIndex = i;
                        break;
                    }
                }
                // found parameter in URL type
                //说明有
                if (urlTypeIndex != -1) {
                    // Null Point check
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"url == null\");",
                            urlTypeIndex);
                    code.append(s);

                    s = String.format("\n%s url = arg%d;", URL.class.getName(), urlTypeIndex);
                    code.append(s);
                }
                // did not find parameter in URL type
                else {//没有URL参数的
                    String attribMethod = null;

                    // find URL getter method
                    LBL_PTS:
                    for (int i = 0; i < pts.length; ++i) {
                        //查找参数里面有get方法并且返回值是URL的
                        Method[] ms = pts[i].getMethods();
                        for (Method m : ms) {
                            String name = m.getName();
                            if ((name.startsWith("get") || name.length() > 3)
                                    && Modifier.isPublic(m.getModifiers())
                                    && !Modifier.isStatic(m.getModifiers())
                                    && m.getParameterTypes().length == 0
                                    && m.getReturnType() == URL.class) {
                                urlTypeIndex = i;
                                attribMethod = name;
                                break LBL_PTS;
                            }
                        }
                    }
                    if (attribMethod == null) {
                        throw new IllegalStateException("fail to create adaptive class for interface " + type.getName()
                                + ": not found url parameter or url attribute in parameters of method " + method.getName());
                    }

                    // Null point check
                    //验证有get方法返回值是URL的参数 是否为null
                    String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"%s argument == null\");",
                            urlTypeIndex, pts[urlTypeIndex].getName());
                    code.append(s);
                    s = String.format("\nif (arg%d.%s() == null) throw new IllegalArgumentException(\"%s argument %s() == null\");",
                            urlTypeIndex, attribMethod, pts[urlTypeIndex].getName(), attribMethod);
                    code.append(s);

                    s = String.format("%s url = arg%d.%s();", URL.class.getName(), urlTypeIndex, attribMethod);
                    code.append(s);
                }

                String[] value = adaptiveAnnotation.value();
                // value is not set, use the value generated from class name as the key
                if (value.length == 0) { //没有值
                    char[] charArray = type.getSimpleName().toCharArray();
                    StringBuilder sb = new StringBuilder(128);
                    for (int i = 0; i < charArray.length; i++) {
                        if (Character.isUpperCase(charArray[i])) { //是大写字母
                            if (i != 0) {
                                sb.append(".");
                            }
                            sb.append(Character.toLowerCase(charArray[i]));
                        } else {
                            sb.append(charArray[i]);
                        }
                    }
                    value = new String[]{sb.toString()};
                }

                boolean hasInvocation = false;
                for (int i = 0; i < pts.length; ++i) {
                    if (pts[i].getName().equals("com.alibaba.dubbo.rpc.Invocation")) {
                        // Null Point check
                        String s = String.format("\nif (arg%d == null) throw new IllegalArgumentException(\"invocation == null\");", i);
                        code.append(s);
                        s = String.format("\nString methodName = arg%d.getMethodName();", i);
                        code.append(s);
                        hasInvocation = true;
                        break;
                    }
                }

                String defaultExtName = cachedDefaultName;
                String getNameCode = null;
                for (int i = value.length - 1; i >= 0; --i) {
                    if (i == value.length - 1) {
                        if (null != defaultExtName) {
                            if (!"protocol".equals(value[i]))
                                if (hasInvocation)
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    getNameCode = String.format("url.getParameter(\"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                getNameCode = String.format("( url.getProtocol() == null ? \"%s\" : url.getProtocol() )", defaultExtName);
                        } else {
                            if (!"protocol".equals(value[i]))
                                if (hasInvocation)
                                    getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                                else
                                    getNameCode = String.format("url.getParameter(\"%s\")", value[i]);
                            else
                                getNameCode = "url.getProtocol()";
                        }
                    } else {
                        if (!"protocol".equals(value[i]))
                            if (hasInvocation)
                                getNameCode = String.format("url.getMethodParameter(methodName, \"%s\", \"%s\")", value[i], defaultExtName);
                            else
                                getNameCode = String.format("url.getParameter(\"%s\", %s)", value[i], getNameCode);
                        else
                            getNameCode = String.format("url.getProtocol() == null ? (%s) : url.getProtocol()", getNameCode);
                    }
                }
                code.append("\nString extName = ").append(getNameCode).append(";");
                // check extName == null?
                String s = String.format("\nif(extName == null) " +
                                "throw new IllegalStateException(\"Fail to get extension(%s) name from url(\" + url.toString() + \") use keys(%s)\");",
                        type.getName(), Arrays.toString(value));
                code.append(s);

                //获取扩展
                s = String.format("\n%s extension = (%<s)%s.getExtensionLoader(%s.class).getExtension(extName);",
                        type.getName(), ExtensionLoader.class.getSimpleName(), type.getName());
                code.append(s);

                // return statement
                if (!rt.equals(void.class)) {
                    code.append("\nreturn ");
                }
                //调用 源方法
                s = String.format("extension.%s(", method.getName());
                code.append(s);
                //拼装调用的参数
                for (int i = 0; i < pts.length; i++) {
                    if (i != 0)
                        code.append(", ");
                    code.append("arg").append(i);
                }
                code.append(");");
            }
            //拼装代理类
            codeBuilder.append("\npublic ").append(rt.getCanonicalName()).append(" ").append(method.getName()).append("(");
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) {
                    codeBuilder.append(", ");
                }
                codeBuilder.append(pts[i].getCanonicalName());
                codeBuilder.append(" ");
                codeBuilder.append("arg").append(i);
            }
            codeBuilder.append(")");

            //异常处理
            if (ets.length > 0) {
                codeBuilder.append(" throws ");
                for (int i = 0; i < ets.length; i++) {
                    if (i > 0) {
                        codeBuilder.append(", ");
                    }
                    codeBuilder.append(ets[i].getCanonicalName());
                }
            }
            codeBuilder.append(" {");
            codeBuilder.append(code.toString());
            codeBuilder.append("\n}");
        }
        codeBuilder.append("\n}");
        if (logger.isDebugEnabled()) {
            logger.debug(codeBuilder.toString());
        }
        return codeBuilder.toString();
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}