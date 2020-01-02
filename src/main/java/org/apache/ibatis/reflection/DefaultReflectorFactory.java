/**
 *    Copyright ${license.git.copyrightYears} the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.reflection;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Mybatis提供的ReflectorFactory的默认实现，唯一实现。
 * 我们可以在mybatis-config.xml中配置自定义的ReflectorFactory实现类，从而实现功能上的拓展。
 * Mybatis初始化流程时，会涉及该拓展点
 */
public class DefaultReflectorFactory implements ReflectorFactory {

  /**
   * 该字段决定是否开启对Reflector对象的缓存
   */
  private boolean classCacheEnabled = true;

  /**
   * 使用ConcurrentMap集合实现对Reflector对象的缓存
   */
  private final ConcurrentMap<Class<?>, Reflector> reflectorMap = new ConcurrentHashMap<>();

  public DefaultReflectorFactory() {
  }

  @Override
  public boolean isClassCacheEnabled() {
    return classCacheEnabled;
  }

  @Override
  public void setClassCacheEnabled(boolean classCacheEnabled) {
    this.classCacheEnabled = classCacheEnabled;
  }

  @Override
  public Reflector findForClass(Class<?> type) {
    // 检测是否开启缓存
    if (classCacheEnabled) {
      // synchronized (type) removed see issue #461
      // 已删除同步（类型） - 问题参见 issue #461
      // Java8中可以通过"::"关键字访问类的构造方法，对象的方法，静态方法。
      // Reflector::new等同于new Reflector()
      // #todo：我们需要的是new Reflector(type)，Reflector::new是否等同于？参数是否有传入？
      // do：我们并不需要new Reflector(type)。computeIfAbsent()方法第一个参数是key，第二个参数是使用第一个参数计算value的方法（并不是value）。
      //     故type会自动的传入Reflector::new计算value。再将key和计算好的value存入集合中
      // 构建Reflector对象，添加缓存，并返回
      return reflectorMap.computeIfAbsent(type, Reflector::new);
    } else {
      // 构建Reflector对象，直接返回
      return new Reflector(type);
    }
  }

}
