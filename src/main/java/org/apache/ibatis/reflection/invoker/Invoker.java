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
package org.apache.ibatis.reflection.invoker;

import java.lang.reflect.InvocationTargetException;

/**
 * @author Clinton Begin
 */
public interface Invoker {

  /**
   * 调用获取指定字段的值或者执行指定的方法，通过阅读对Invoker接口实现，来理解
   * @param target
   * @param args
   * @return
   * @throws IllegalAccessException
   * @throws InvocationTargetException
   */
  Object invoke(Object target, Object[] args) throws IllegalAccessException, InvocationTargetException;

  /**
   * @return 返回属性相应的类型
   */
  Class<?> getType();
}
