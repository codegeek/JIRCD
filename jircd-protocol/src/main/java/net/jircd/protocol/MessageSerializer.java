/*
 * Copyright 2026 Guillermo Castro
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package net.jircd.protocol;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Serializes a {@link Message} back into a raw wire line (without CR-LF). */
public final class MessageSerializer {

  private MessageSerializer() {}

  public static String serialize(Message message) {
    StringBuilder line = new StringBuilder();

    if (!message.tags().isEmpty()) {
      line.append('@');
      Iterator<Map.Entry<String, String>> it = message.tags().entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<String, String> entry = it.next();
        line.append(entry.getKey());
        if (!entry.getValue().isEmpty()) {
          line.append('=').append(escapeTagValue(entry.getValue()));
        }
        if (it.hasNext()) {
          line.append(';');
        }
      }
      line.append(' ');
    }

    if (message.prefix() != null) {
      line.append(':').append(message.prefix()).append(' ');
    }

    line.append(message.rawCommand() != null ? message.rawCommand() : message.command().name());

    List<String> params = message.params();
    for (int i = 0; i < params.size(); i++) {
      String param = params.get(i);
      boolean isLast = i == params.size() - 1;
      boolean needsTrailingForm =
          isLast && (param.isEmpty() || param.contains(" ") || param.startsWith(":"));
      line.append(' ');
      if (needsTrailingForm) {
        line.append(':');
      }
      line.append(param);
    }

    return line.toString();
  }

  private static String escapeTagValue(String value) {
    StringBuilder result = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case ';' -> result.append("\\:");
        case ' ' -> result.append("\\s");
        case '\\' -> result.append("\\\\");
        case '\r' -> result.append("\\r");
        case '\n' -> result.append("\\n");
        default -> result.append(c);
      }
    }
    return result.toString();
  }
}
