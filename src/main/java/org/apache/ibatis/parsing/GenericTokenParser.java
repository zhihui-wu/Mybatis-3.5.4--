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
package org.apache.ibatis.parsing;

/**
 * @author Clinton Begin
 * 通用的占位符解析器
 *
 * GenericTokenParser不仅仅只是用于这里的默认值解析，还会用于后面对于动态SQL语句的解析。
 * GenericTokenParser只是查找到指定的占位符，而具体的解析行为会根据其持有的TokenHandler实现的不同而有所不同，
 * 这有点策略模式的意思。
 */
public class GenericTokenParser {

  /**
   * 占位符的开始标志
   */
  private final String openToken;

  /**
   * 占位符的结束标志
   */
  private final String closeToken;

  /**
   * TokenHandler接口的实现会按照一定的逻辑解析占位符
   */
  private final TokenHandler handler;

  public GenericTokenParser(String openToken, String closeToken, TokenHandler handler) {
    this.openToken = openToken;
    this.closeToken = closeToken;
    this.handler = handler;
  }

  /**
   * 顺序查找openToken和closeToken，解析得到占位符的字面值，
   * 并交给TokenHandler处理，然后将解析结果重新拼装成字符串并返回
   */
  public String parse(String text) {
    // 检查text是否为空
    if (text == null || text.isEmpty()) {
      return "";
    }
    // search open token
    // 查找开始标记
    int start = text.indexOf(openToken);
    // 检测start是否为-1
    if (start == -1) {
      return text;
    }
    char[] src = text.toCharArray();
    int offset = 0;
    // 用来记录解析后的字符串
    final StringBuilder builder = new StringBuilder();
    // 用来记录解析后的字面值
    StringBuilder expression = null;
    while (start > -1) {
      if (start > 0 && src[start - 1] == '\\') {
        // this open token is escaped. remove the backslash and continue.
        // 遇到转义的开始标记，则直接将前面的字符串以及开始标记追加到builder中，继续往下匹配
        builder.append(src, offset, start - offset - 1).append(openToken);
        offset = start + openToken.length();
      } else {
        // found open token. let's search close token.
        // 查找开始标记，且未被转义
        if (expression == null) {
          expression = new StringBuilder();
        } else {
          expression.setLength(0);
        }
        // 将前面的字符串追加到builder中
        builder.append(src, offset, start - offset);
        offset = start + openToken.length();
        int end = text.indexOf(closeToken, offset);
        while (end > -1) {
          if (end > offset && src[end - 1] == '\\') {
            // this close token is escaped. remove the backslash and continue.
            // 处理转义的结束标志
            expression.append(src, offset, end - offset - 1).append(closeToken);
            offset = end + closeToken.length();
            end = text.indexOf(closeToken, offset);
          } else {
            // 将开始标志和结束标志之间的字符串追加到expression中保存
            expression.append(src, offset, end - offset);
            break;
          }
        }
        if (end == -1) {
          // close token was not found.
          // 未找到结束标志
          builder.append(src, start, src.length - start);
          offset = src.length;
        } else {
          // 将占位符的字面值交给TokenHandler处理，并将处理结果追加到builder中保存
          // 最终拼凑出解析后的完整内容
          builder.append(handler.handleToken(expression.toString()));
          offset = end + closeToken.length();
        }
      }
      // 移动start
      start = text.indexOf(openToken, offset);
    }
    // 将剩余内容拼上
    if (offset < src.length) {
      builder.append(src, offset, src.length - offset);
    }
    return builder.toString();
  }
}
