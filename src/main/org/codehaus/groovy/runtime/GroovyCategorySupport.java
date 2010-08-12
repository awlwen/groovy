/*
 * Copyright 2003-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.codehaus.groovy.runtime;

import groovy.lang.Closure;
import java.lang.ref.SoftReference;
import org.codehaus.groovy.reflection.CachedClass;
import org.codehaus.groovy.reflection.CachedMethod;
import org.codehaus.groovy.reflection.ReflectionCache;
import org.codehaus.groovy.runtime.metaclass.NewInstanceMetaMethod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author sam
 * @author Paul King
 * @author Alex Tkachman
 */
public class GroovyCategorySupport {

    private static AtomicInteger categoriesInUse = new AtomicInteger();

    public static class CategoryMethodList extends ArrayList<CategoryMethod> {
        public final int level;
        final CategoryMethodList previous;
        final AtomicInteger usage;

        public CategoryMethodList(String name, int level, CategoryMethodList previous) {
            this.level = level;
            this.previous = previous;
            if (previous != null) {
                addAll(previous);
                usage = previous.usage;
            }
            else {
                usage = getCategoryNameUsage (name);
            }
        }

        public boolean add(CategoryMethod o) {
            usage.incrementAndGet();
            return super.add(o);
        }
    }

    public static class ThreadCategoryInfo extends HashMap<String, CategoryMethodList>{
        int level;

        private void newScope () {
            categoriesInUse.incrementAndGet();
            level++;
        }

        private void endScope () {
            for (Iterator<Map.Entry<String, CategoryMethodList>> it = entrySet().iterator(); it.hasNext(); ) {
                final Map.Entry<String, CategoryMethodList> e = it.next();
                final CategoryMethodList list = e.getValue();
                if (list.level == level) {
                    final CategoryMethodList prev = list.previous;
                    if (prev == null) {
                      it.remove();
                      list.usage.addAndGet(-list.size());
                    }
                    else {
                      e.setValue(prev);
                      list.usage.addAndGet(prev.size()-list.size());
                    }
                }
            }
            level--;
            categoriesInUse.getAndDecrement();
            if (level == 0) {
                THREAD_INFO.remove();
            }
        }

        private <T> T use(Class categoryClass, Closure<T> closure) {
            newScope();
            try {
                use(categoryClass);
                return closure.call();
            } finally {
                endScope();
            }
        }

        public <T> T use(List<Class> categoryClasses, Closure<T> closure) {
            newScope();
            try {
                for (Class categoryClass : categoryClasses) {
                    use(categoryClass);
                }
                return closure.call();
            } finally {
                endScope();
            }
        }
        
        private void applyUse(CachedClass cachedClass) {
            CachedMethod[] methods = cachedClass.getMethods();
            for (CachedMethod cachedMethod : methods) {
                if (cachedMethod.isStatic() && cachedMethod.isPublic()) {
                    CachedClass[] paramTypes = cachedMethod.getParameterTypes();
                    if (paramTypes.length > 0) {
                        CachedClass metaClass = paramTypes[0];
                        CategoryMethod mmethod = new CategoryMethod(cachedMethod, metaClass.getTheClass());
                        final String name = cachedMethod.getName();
                        CategoryMethodList list = get(name);
                        if (list == null || list.level != level) {
                            list = new CategoryMethodList(name, level, list);
                            put(name, list);
                        }
                        list.add(mmethod);
                        Collections.sort(list);
                    }
                }
            }
        }

        private void use(Class categoryClass) {
            CachedClass cachedClass = ReflectionCache.getCachedClass(categoryClass);
            LinkedList<CachedClass> classStack = new LinkedList<CachedClass>();
            for (CachedClass superClass = cachedClass; superClass.getTheClass()!=Object.class; superClass = superClass.getCachedSuperClass()) {
                classStack.add(superClass);
            }
            
            while (!classStack.isEmpty()) {
                CachedClass klazz = classStack.removeLast();
                applyUse(klazz);
            }
        }

        public CategoryMethodList getCategoryMethods(String name) {
            return level == 0 ? null : get(name);
        }
    }

    private static final MyThreadLocal THREAD_INFO = new MyThreadLocal();

    private static class CategoryMethod extends NewInstanceMetaMethod implements Comparable {
        private final Class metaClass;

        public CategoryMethod(CachedMethod metaMethod, Class metaClass) {
            super(metaMethod);
            this.metaClass = metaClass;
        }

        public boolean isCacheable() { return false; }

        /**
         * Sort by most specific to least specific.
         *
         * @param o the object to compare against
         */
        public int compareTo(Object o) {
            CategoryMethod thatMethod = (CategoryMethod) o;
            Class thisClass = metaClass;
            Class thatClass = thatMethod.metaClass;
            if (thisClass == thatClass) return 0;
            if (isChildOfParent(thisClass, thatClass)) return -1;
            if (isChildOfParent(thatClass, thisClass)) return 1;
            return 0;
        }

        private boolean isChildOfParent(Class candidateChild, Class candidateParent) {
            Class loop = candidateChild;
            while(loop != null && loop != Object.class) {
                loop = loop.getSuperclass();
                if (loop == candidateParent) {
                    return true;
                }
            }
            return false;
        }
    }

    public static AtomicInteger getCategoryNameUsage (String name) {
        return THREAD_INFO.getUsage (name);
    }

    /**
     * Create a scope based on given categoryClass and invoke closure within that scope.
     *
     * @param categoryClass the class containing category methods
     * @param closure the closure during which to make the category class methods available
     * @return the value returned from the closure
     */
    public static <T> T use(Class categoryClass, Closure<T> closure) {
        return THREAD_INFO.getInfo().use(categoryClass, closure);
    }

    /**
     * Create a scope based on given categoryClasses and invoke closure within that scope.
     *
     * @param categoryClasses the list of classes containing category methods
     * @param closure the closure during which to make the category class methods available
     * @return the value returned from the closure
     */
    public static <T> T use(List<Class> categoryClasses, Closure<T> closure) {
        return THREAD_INFO.getInfo().use(categoryClasses, closure);
    }

    public static boolean hasCategoryInCurrentThread() {
        if (categoriesInUse.get() == 0) {
            return false;
        }
        final ThreadCategoryInfo infoNullable = THREAD_INFO.getInfoNullable();
        return infoNullable != null && infoNullable.level != 0;
    }

    public static boolean hasCategoryInAnyThread() {
        return categoriesInUse.get() != 0;
    }

    /**
     * This method is used to pull all the new methods out of the local thread context with a particular name.
     *
     * @param name the method name of interest
     * @return the list of methods
     */
    public static CategoryMethodList getCategoryMethods(String name) {
        final ThreadCategoryInfo categoryInfo = THREAD_INFO.getInfoNullable();
        return categoryInfo == null ? null : categoryInfo.getCategoryMethods(name);
    }

    private static class MyThreadLocal extends ThreadLocal<SoftReference> {

        ConcurrentHashMap<String,AtomicInteger> usage = new ConcurrentHashMap<String,AtomicInteger> ();

        public ThreadCategoryInfo getInfo() {
            final SoftReference reference = get();
            ThreadCategoryInfo tcinfo;
            if (reference != null) {
                tcinfo = (ThreadCategoryInfo) reference.get();
                if( tcinfo == null ) {
                    tcinfo = new ThreadCategoryInfo();
                    set(new SoftReference(tcinfo));
                }
            }
            else {
                tcinfo = new ThreadCategoryInfo();
                set(new SoftReference(tcinfo));
            }
            return tcinfo;
        }

        public ThreadCategoryInfo getInfoNullable() {
            final SoftReference reference = get();
            return reference == null ? null : (ThreadCategoryInfo) reference.get();
        }

        public AtomicInteger getUsage (String name) {
            AtomicInteger u = usage.get(name);
            if (u != null) {
                return u;
            }

            final AtomicInteger ai = new AtomicInteger();
            final AtomicInteger prev = usage.putIfAbsent(name, ai);
            return prev == null ? ai : prev;
        }
    }
}
