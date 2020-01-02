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

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.AmbiguousMethodInvoker;
import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * 该类中缓存了反射操作需要使用的类的元信息
 *
 * Reflector是Mybatis反射模块的基础
 *
 * @author Clinton Begin
 */
public class Reflector {

  /**
   * 对应的Class类型
   */
  private final Class<?> type;

  /**
   * 可读属性的名称集合，可读属性就是存在相应的getter方法的属性，初始值为空数组
   */
  private final String[] readablePropertyNames;

  /**
   * 可写属性的名称集合，可写属性就是存在相应的setter方法的属性，初始值为空数组
   */
  private final String[] writablePropertyNames;

  /**
   * 记录了属性相应的setter方法，key是属性名称，value是Invoker对象，它是对setter方法对应Method对象的封装
   */
  private final Map<String, Invoker> setMethods = new HashMap<>();

  /**
   * 记录了属性相应的getter方法，key是属性名称，value是Invoker对象，是对getter方法对应Method对象的封装
   */
  private final Map<String, Invoker> getMethods = new HashMap<>();

  /**
   * 记录了属性相应的setter方法的参数值类型，key是属性名称，value是setter方法的参数类型
   */
  private final Map<String, Class<?>> setTypes = new HashMap<>();

  /**
   * 记录了属性相应的getter方法的返回值类型，key是属性名称，value是getter方法的返回类型
   */
  private final Map<String, Class<?>> getTypes = new HashMap<>();

  /**
   * 记录了默认构造方法
   */
  private Constructor<?> defaultConstructor;

  /**
   * 记录了所有属性名称的集合
   */
  private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

  /**
   * 在构造方法中解析指定的Class对象，并填充以上集合
   * @param clazz Class对象
   */
  public Reflector(Class<?> clazz) {
    // 初始化type字段
    type = clazz;
    // 查找clazz的默认构造方法（无参构造方法），具体实现是通过反射遍历所有构造方法
    addDefaultConstructor(clazz);

    // 以下三个方法中，调用的add*Method()方法和add*Field()方法在对应集合添加元素时，
    // 会将getter/setter方法对应的Method对象以及字段对应的Field对象统一封装成Invoker对象

    // 处理clazz中的getter方法，填充getMethods集合和getTypes集合
    addGetMethods(clazz);
    // 处理clazz中的setter方法，填充setMethods集合和setTypes集合
    addSetMethods(clazz);
    // 处理没有getter/setter方法的字段，追加填充getMethods集合、getTypes集合、setMethods集合和setTypes集合
    addFields(clazz);

    // 根据getMethods/setMethods集合，初始化可读/写属性的名称集合
    readablePropertyNames = getMethods.keySet().toArray(new String[0]);
    writablePropertyNames = setMethods.keySet().toArray(new String[0]);

    // 初始化caseInsensitivePropertyMap集合，其中记录了所有大写格式的属性名称
    for (String propName : readablePropertyNames) {
      // key大写
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writablePropertyNames) {
      // key大写
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  private void addDefaultConstructor(Class<?> clazz) {
    // 获取所有声明的构造函数
    Constructor<?>[] constructors = clazz.getDeclaredConstructors();
    // 筛选出无参默认函数
    Arrays.stream(constructors).filter(constructor -> constructor.getParameterTypes().length == 0)
      .findAny().ifPresent(constructor -> this.defaultConstructor = constructor);
  }

  private void addGetMethods(Class<?> clazz) {
    // conflictingGetters集合的key是属性名称，value是对应的getter方法集合，
    // 因为子类可能覆盖父类的getter方法，所以同一属性名称可能会存在多个getter方法
    Map<String, List<Method>> conflictingGetters = new HashMap<>();
    // 步骤一：获取当前类以及其父类和接口中定义的所有方法的唯一签名以及相应的Method对象
    Method[] methods = getClassMethods(clazz);
    // 步骤二：按照JavaBean规范从methods中查找该类中定义的getter方法，将其记录到conflictingGetters集合中
    // 方法的参数列表为空且方法名符合规范
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 0 && PropertyNamer.isGetter(m.getName()))
      .forEach(m -> addMethodConflict(conflictingGetters, PropertyNamer.methodToProperty(m.getName()), m));
    // 对conflictingGetters集合的覆写情况进行处理，并填充getMethods集合和填充getTypes集合
    resolveGetterConflicts(conflictingGetters);
  }

  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
    // 遍历conflictingGetters集合
    for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
      // 最适合的getter方法
      Method winner = null;
      // 获取属性名称
      String propName = entry.getKey();
      // 是否有二义性
      boolean isAmbiguous = false;
      // 遍历属性的getter方法集合
      for (Method candidate : entry.getValue()) {
        // 将第一个getter方法设为最适合的getter方法，跳过本次循环
        if (winner == null) {
          winner = candidate;
          continue;
        }
        // 对比覆写的getter方法的返回类型进行比较
        Class<?> winnerType = winner.getReturnType();
        Class<?> candidateType = candidate.getReturnType();
        if (candidateType.equals(winnerType)) {
          // 返回值类型相同
          // 返回类型相同时，只能是boolean类型，否则有误 todo：对此处的类型相同分支下的内容不理解
          if (!boolean.class.equals(candidateType)) {
            // 如果返回值类型不为boolean，二义性
            isAmbiguous = true;
            break;
          } else if (candidate.getName().startsWith("is")) {
            // 如果当前方法的方法名以"is"开头，则设当前方法为最适合方法
            winner = candidate;
          }
        } else if (candidateType.isAssignableFrom(winnerType)) {
          // 当前最适合的方法的返回值是当前方法返回值的子类，什么都不做，当前最适合的方法
          // OK getter type is descendant
        } else if (winnerType.isAssignableFrom(candidateType)) {
          // 当前方法的返回值是当前最适合的方法的返回值的子类，
          // 更新临时变量getter，当前getter方法为最适合的getter方法
          winner = candidate;
        } else {
          isAmbiguous = true;
          break;
        }
      }
      // 填充getMethod集合
      addGetMethod(propName, winner, isAmbiguous);
    }
  }

  private void addGetMethod(String name, Method method, boolean isAmbiguous) {
    // 如果isAmbiguous为true，则构造的invoker为反射异常专用invoker，反射调用时，会直接抛出此处设置的异常
    // todo:反射的原理，jdk源代码需学习，学习了解invoker的底层实现
    MethodInvoker invoker = isAmbiguous
        ? new AmbiguousMethodInvoker(method, MessageFormat.format(
            "Illegal overloaded getter method with ambiguous type for property ''{0}'' in class ''{1}''. This breaks the JavaBeans specification and can cause unpredictable results.",
            name, method.getDeclaringClass().getName()))
        : new MethodInvoker(method);
    // 将属性名以及对应的MethodInvoker对象添加到getMethods集合
    getMethods.put(name, invoker);
    // 获取返回值的Type
    // Type是Java编程语言中所有类型的公共超类接口，包括：基本类型、数组类型、原始类型、泛型（参数化类型）、Class
    Type returnType = TypeParameterResolver.resolveReturnType(method, type);
    // 将属性名称及其getter方法的返回值类型添加到getTypes集合中保存
    getTypes.put(name, typeToClass(returnType));
  }

  private void addSetMethods(Class<?> clazz) {
    // conflictingSetters集合的key是属性名称，value是对应的setter方法集合，
    // 因为子类可能覆盖父类的setter方法，所以同一属性名称可能会存在多个setter方法
    Map<String, List<Method>> conflictingSetters = new HashMap<>();
    // 步骤一：获取当前类以及其父类和接口中定义的所有方法的唯一签名以及相应的Method对象
    Method[] methods = getClassMethods(clazz);
    // 步骤二：按照JavaBean规范从methods中查找该类中定义的setter方法，将其记录到conflictingSetters集合中
    // 方法的参数个数为1个且方法名符合规范
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 1 && PropertyNamer.isSetter(m.getName()))
      .forEach(m -> addMethodConflict(conflictingSetters, PropertyNamer.methodToProperty(m.getName()), m));
    // 对conflictingSetters集合的覆写情况进行处理，并填充setMethods集合和填充setTypes集合
    resolveSetterConflicts(conflictingSetters);
  }

  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    // 将属性名与getter方法的对应关系记录到conflictingMethods集合中
    // 验证属性名是否合法
    if (isValidPropertyName(name)) {
      // 数组中是否有key为变量name的键值对，有则返回其value即List<Method>数组，无则返回空数组
      List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
      // 在数组中插入Method方法
      list.add(method);
    }
  }

  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    for (String propName : conflictingSetters.keySet()) {
      List<Method> setters = conflictingSetters.get(propName);
      // 指定属性在getType集合（getter方法返回类型集合）中存在的类型
      Class<?> getterType = getTypes.get(propName);
      boolean isGetterAmbiguous = getMethods.get(propName) instanceof AmbiguousMethodInvoker;
      boolean isSetterAmbiguous = false;
      Method match = null;
      for (Method setter : setters) {
        // 如果属性名对应setter方法的第一个参数类型等于属性名对应的getter方法的返回值类型
        // 且属性名对应getter方法没有二义性（错误）
        // 则该setter方法为最适合的setter方法
        if (!isGetterAmbiguous && setter.getParameterTypes()[0].equals(getterType)) {
          // should be the best match
          match = setter;
          break;
        }
        // 不满足上一个if条件
        // 则setter方法有二义性，需挑选最适合的setter方法
        if (!isSetterAmbiguous) {
          match = pickBetterSetter(match, setter, propName);
          isSetterAmbiguous = match == null;
        }
      }
      // 匹配到最适合的setter，填充setMethods集合
      if (match != null) {
        addSetMethod(propName, match);
      }
    }
  }

  private Method pickBetterSetter(Method setter1, Method setter2, String property) {
    // 如果setter2为第一个setter方法，则直接返回
    if (setter1 == null) {
      return setter2;
    }
    Class<?> paramType1 = setter1.getParameterTypes()[0];
    Class<?> paramType2 = setter2.getParameterTypes()[0];
    if (paramType1.isAssignableFrom(paramType2)) {
      // 如果paramType2是paramType1的子类或者子接口，则返回setter2
      return setter2;
    } else if (paramType2.isAssignableFrom(paramType1)) {
      // 如果paramType1是paramType2的子类或者子接口，则返回setter1
      return setter1;
    }
    // 如果不满足以上情况，则有误
    MethodInvoker invoker = new AmbiguousMethodInvoker(setter1,
        MessageFormat.format(
            "Ambiguous setters defined for property ''{0}'' in class ''{1}'' with types ''{2}'' and ''{3}''.",
            property, setter2.getDeclaringClass().getName(), paramType1.getName(), paramType2.getName()));
    // 填充setter方法集合时，将给setter的invoker设为专用于二义异常的invoker
    setMethods.put(property, invoker);
    // 将setter1的参数类型解析为实际运行时类型，并使用第一个参数类型填充setType集合
    Type[] paramTypes = TypeParameterResolver.resolveParamTypes(setter1, type);
    setTypes.put(property, typeToClass(paramTypes[0]));
    return null;
  }

  private void addSetMethod(String name, Method method) {
    // 填充setMethods集合和setTypes集合
    MethodInvoker invoker = new MethodInvoker(method);
    setMethods.put(name, invoker);
    // 解析为实际运行时类型
    Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
    setTypes.put(name, typeToClass(paramTypes[0]));
  }

  private Class<?> typeToClass(Type src) {
    // Class<?> 和 Class 是一个意思， Class<?>中的？表示对象类型不确定
    // todo:对于泛型、Class、T、？、instanceof、ParameterizedType、GenericArrayType 等需再做深入了解
    // 将Type转换为Class
    Class<?> result = null;
    if (src instanceof Class) {
      // 如果src也是Class类型，则转换
      result = (Class<?>) src;
    } else if (src instanceof ParameterizedType) {
      // 如果是泛型（参数化类型），返回承载该泛型信息的对象，如Map<String, String>，承载泛型信息的对象是Map，返回Map的Type对象。
      // 再强转为Class对象
      result = (Class<?>) ((ParameterizedType) src).getRawType();
    } else if (src instanceof GenericArrayType) {
      // 如果是泛型数组，获得这个数组脱去最右的[]后剩余类型。如T[], 返回T。如A<T>[],返回A<T>
      Type componentType = ((GenericArrayType) src).getGenericComponentType();
      if (componentType instanceof Class) {
        // 如果类型为Class类型，转为Class后，构造数组
        result = Array.newInstance((Class<?>) componentType, 0).getClass();
      } else {
        // 否则递归处理
        Class<?> componentClass = typeToClass(componentType);
        result = Array.newInstance(componentClass, 0).getClass();
      }
    }
    // 如果result是空，则返回Object的Class类型
    if (result == null) {
      result = Object.class;
    }
    return result;
  }

  private void addFields(Class<?> clazz) {
    // 获取clazz中定义的所有字段
    Field[] fields = clazz.getDeclaredFields();
    for (Field field : fields) {
      // 如果setMethods不是否包含该属性名
      if (!setMethods.containsKey(field.getName())) {
        // issue #379 - removed the check for final because JDK 1.5 allows
        // modification of final fields through reflection (JSR-133). (JGB)
        // pr #16 - final static can only be set by the classloader
        // 问题 #379 - 删除了final的检查，因为JDK1.5允许通过反射修改fianl字段（JSR-133）。
        // pr #16 - 最终的静态只能由类加载器来设置
        // getModifiers()方法，以整数形式返回此字段的Java语言修饰符，应使用修饰词类来解码修饰词
        int modifiers = field.getModifiers();
        // 过滤掉fianl和static修饰的字段
        if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
          // addSetField()方法的主要功能是填充setMethods集合和setTypes集合，
          // 与addSetMethod()方法类似
          addSetField(field);
        }
      }
      // 如果getMethods不包含该属性名
      if (!getMethods.containsKey(field.getName())) {
        // addGetField()方法的主要功能是填充getMethods集合和getTypes集合
        // 与addGetMethod()方法类似
        addGetField(field);
      }
    }
    // 处理父类中定义的字段，递归
    if (clazz.getSuperclass() != null) {
      addFields(clazz.getSuperclass());
    }
  }

  private void addSetField(Field field) {
    // 如果属性名合法
    if (isValidPropertyName(field.getName())) {
      // 构造该属性对应的setter方法，填充setMethods集合
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      // 解析为实际运行时类型，填充setTypes集合
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      setTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private void addGetField(Field field) {
    // 同addSetField()方法类似
    if (isValidPropertyName(field.getName())) {
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      getTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private boolean isValidPropertyName(String name) {
    // 判断该属性名符合规范，不能是以"$"开头，或不能等于"serialVersionUID"和"class"
    // # todo 疑问：为什么不能等于"serialVersionUID"和"class"？
    return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
  }

  /**
   * This method returns an array containing all methods
   * declared in this class and any superclass.
   * We use this method, instead of the simpler <code>Class.getMethods()</code>,
   * because we want to look for private methods as well.
   *
   * 该方法获取指定类以及其父类和接口中定义的所有方法的唯一签名以及相应的Method对象
   * 用于代替Java反射中的Class.getMethods()方法，因为我们希望把私有方法也获取到。
   *
   * @param clazz The class
   * @return An array containing all methods in this class
   */
  private Method[] getClassMethods(Class<?> clazz) {
    // 用于记录指定类中定义的全部方法的唯一签名以及对应的Method对象
    Map<String, Method> uniqueMethods = new HashMap<>();
    Class<?> currentClass = clazz;
    while (currentClass != null && currentClass != Object.class) {
      // 记录currentClass这个类中定义的全部方法
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods -
      // because the class may be abstract
      // 记录接口中定义的方法，因为该类可能是一个抽象类
      // #todo 不理解，是否抽象类使用上述currentClass.getDeclaredMethods()方法无法获取获取到方法，要和接口一起处理
      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }
      // 获取父类，继续while循环
      currentClass = currentClass.getSuperclass();
    }

    Collection<Method> methods = uniqueMethods.values();
    // 转换成Methods数组返回 #todo 疑问：new Method[0]是否应该是new Method[methods.size()]
    return methods.toArray(new Method[0]);
  }

  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    for (Method currentMethod : methods) {
      // 检查方法是不是桥接方法
      // 桥接方法是为了使Java的泛型方法生成的字节码和1.5版本前的字节码相兼容，由编译器自动生成的方法
      // 可参考：https://www.cnblogs.com/zsg88/p/7588929.html
      if (!currentMethod.isBridge()) {
        // 通过getSignature()方法得到的方法签名是：返回值类型#方法名称:参数类型列表。
        // 例如：Reflector.getSignature(Method)方法的唯一签名是：
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        if (!uniqueMethods.containsKey(signature)) {
          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }

  private String getSignature(Method method) {
    // 拼接Method的唯一签名
    StringBuilder sb = new StringBuilder();
    Class<?> returnType = method.getReturnType();
    if (returnType != null) {
      sb.append(returnType.getName()).append('#');
    }
    sb.append(method.getName());
    Class<?>[] parameters = method.getParameterTypes();
    for (int i = 0; i < parameters.length; i++) {
      sb.append(i == 0 ? ':' : ',').append(parameters[i].getName());
    }
    return sb.toString();
  }

  /**
   * Checks whether can control member accessible.
   *
   * @return If can control member accessible, it return {@literal true}
   * @since 3.5.0
   */
  public static boolean canControlMemberAccessible() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /**
   * Gets the name of the class the instance provides information for.
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    } else {
      throw new ReflectionException("There is no default constructor for " + type);
    }
  }

  /**
   * 是否有默认构造函数
   * @return 有默认构造函数，返回true
   */
  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /**
   * Gets the type for a property setter.
   * 获取属性对应setter方法的参数值类型
   *
   * @param propertyName - the name of the property
   * @return The Class of the property setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets the type for a property getter.
   * 获取属性对应getter方法的返回值类型
   *
   * @param propertyName - the name of the property
   * @return The Class of the property getter
   */
  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets an array of the readable properties for an object.
   * 获取有对应getter方法的属性集合
   *
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /**
   * Gets an array of the writable properties for an object.
   * 获取有对应setter方法的属性集合
   *
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writablePropertyNames;
  }

  /**
   * Check to see if a class has a writable property by name.
   * 检查类是否具有该属性的setter方法
   *
   * @param propertyName - the name of the property to check
   *                       属性名
   * @return True if the object has a writable property by the name
   * 、      如果存在对应setter方法，返回true
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.keySet().contains(propertyName);
  }

  /**
   * Check to see if a class has a readable property by name.
   * 检查类是否具有该属性的getter方法
   *
   * @param propertyName - the name of the property to check
   *                       属性名
   * @return True if the object has a readable property by the name
   *         如果存在对应getter方法，返回true
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.keySet().contains(propertyName);
  }

  /**
   * 不区分大小写，获取对应属性名
   * @param name 不区分大小写的属性名
   * @return 获取属性名
   */
  public String findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }
}
