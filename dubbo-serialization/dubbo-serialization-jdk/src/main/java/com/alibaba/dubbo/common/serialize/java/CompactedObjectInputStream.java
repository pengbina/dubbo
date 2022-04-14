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
package com.alibaba.dubbo.common.serialize.java;

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ClassHelper;
import com.alibaba.dubbo.common.utils.SerialDetector;

import java.io.*;

/**
 * Compacted java object input stream.
 */
public class CompactedObjectInputStream extends ObjectInputStream {
    private static final Logger logger = LoggerFactory.getLogger(CompactedObjectInputStream.class);
    private ClassLoader mClassLoader;

    public CompactedObjectInputStream(InputStream in) throws IOException {
        this(in, Thread.currentThread().getContextClassLoader());
    }

    public CompactedObjectInputStream(InputStream in, ClassLoader cl) throws IOException {
        super(in);
        mClassLoader = cl == null ? ClassHelper.getClassLoader() : cl;
    }

    @Override
    protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
        if (SerialDetector.isClassInBlacklist(desc)) {
            if (!SerialDetector.shouldCheck()) {
                // Reporting mode
                logger.info(String.format("Blacklist match: '%s'", desc.getName()));
            } else {
                // Blocking mode
                logger.error(String.format("Blocked by blacklist'. Match found for '%s'", desc.getName()));
                throw new InvalidClassException(desc.getName(), "Class blocked from deserialization (blacklist)");
            }
        }
        return super.resolveClass(desc);
    }

    @Override
    protected ObjectStreamClass readClassDescriptor() throws IOException, ClassNotFoundException {
        int type = read();
        if (type < 0)
            throw new EOFException();
        switch (type) {
            case 0:
                return super.readClassDescriptor();
            case 1:
                Class<?> clazz = loadClass(readUTF());
                return ObjectStreamClass.lookup(clazz);
            default:
                throw new StreamCorruptedException("Unexpected class descriptor type: " + type);
        }
    }

    private Class<?> loadClass(String className) throws ClassNotFoundException {
        return mClassLoader.loadClass(className);
    }
}