/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.api.server.rule;

import com.google.common.io.Closeables;
import org.apache.commons.lang.StringUtils;
import org.codehaus.staxmate.SMInputFactory;
import org.codehaus.staxmate.in.SMHierarchicCursor;
import org.codehaus.staxmate.in.SMInputCursor;
import org.sonar.api.rule.RuleStatus;
import org.sonar.api.rule.Severity;
import org.sonar.check.Cardinality;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * @since 4.2
 */
class RuleDefinitionsFromXml {

  void loadRules(RulesDefinition.NewRepository repo, InputStream input, String encoding) {
    Reader reader = null;
    try {
      reader = new InputStreamReader(input, encoding);
      loadRules(repo, reader);

    } catch (IOException e) {
      throw new IllegalStateException("Fail to load XML file", e);

    } finally {
      Closeables.closeQuietly(reader);
    }
  }

  void loadRules(RulesDefinition.NewRepository repo, Reader reader) {
    XMLInputFactory xmlFactory = XMLInputFactory.newInstance();
    xmlFactory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
    xmlFactory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.FALSE);
    // just so it won't try to load DTD in if there's DOCTYPE
    xmlFactory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
    xmlFactory.setProperty(XMLInputFactory.IS_VALIDATING, Boolean.FALSE);
    SMInputFactory inputFactory = new SMInputFactory(xmlFactory);
    try {
      SMHierarchicCursor rootC = inputFactory.rootElementCursor(reader);
      rootC.advance(); // <rules>

      SMInputCursor rulesC = rootC.childElementCursor("rule");
      while (rulesC.getNext() != null) {
        // <rule>
        processRule(repo, rulesC);
      }

    } catch (XMLStreamException e) {
      throw new IllegalStateException("XML is not valid", e);
    }
  }

  private void processRule(RulesDefinition.NewRepository repo, SMInputCursor ruleC) throws XMLStreamException {
    String key = null, name = null, description = null, internalKey = null, severity = Severity.defaultSeverity(), status = null;
    Cardinality cardinality = Cardinality.SINGLE;
    List<ParamStruct> params = new ArrayList<ParamStruct>();
    List<String> tags = new ArrayList<String>();

    /* BACKWARD COMPATIBILITY WITH VERY OLD FORMAT */
    String keyAttribute = ruleC.getAttrValue("key");
    if (StringUtils.isNotBlank(keyAttribute)) {
      key = StringUtils.trim(keyAttribute);
    }
    String priorityAttribute = ruleC.getAttrValue("priority");
    if (StringUtils.isNotBlank(priorityAttribute)) {
      severity = StringUtils.trim(priorityAttribute);
    }

    SMInputCursor cursor = ruleC.childElementCursor();
    while (cursor.getNext() != null) {
      String nodeName = cursor.getLocalName();

      if (StringUtils.equalsIgnoreCase("name", nodeName)) {
        name = StringUtils.trim(cursor.collectDescendantText(false));

      } else if (StringUtils.equalsIgnoreCase("description", nodeName)) {
        description = StringUtils.trim(cursor.collectDescendantText(false));

      } else if (StringUtils.equalsIgnoreCase("key", nodeName)) {
        key = StringUtils.trim(cursor.collectDescendantText(false));

      } else if (StringUtils.equalsIgnoreCase("configKey", nodeName)) {
        // deprecated field, replaced by internalKey
        internalKey = StringUtils.trim(cursor.collectDescendantText(false));

      } else if (StringUtils.equalsIgnoreCase("internalKey", nodeName)) {
        internalKey = StringUtils.trim(cursor.collectDescendantText(false));

      } else if (StringUtils.equalsIgnoreCase("priority", nodeName)) {
        // deprecated field, replaced by severity
        severity = StringUtils.trim(cursor.collectDescendantText(false));

      } else if (StringUtils.equalsIgnoreCase("severity", nodeName)) {
        severity = StringUtils.trim(cursor.collectDescendantText(false));

      } else if (StringUtils.equalsIgnoreCase("cardinality", nodeName)) {
        cardinality = Cardinality.valueOf(StringUtils.trim(cursor.collectDescendantText(false)));

      } else if (StringUtils.equalsIgnoreCase("status", nodeName)) {
        status = StringUtils.trim(cursor.collectDescendantText(false));

      } else if (StringUtils.equalsIgnoreCase("param", nodeName)) {
        params.add(processParameter(cursor));

      } else if (StringUtils.equalsIgnoreCase("tag", nodeName)) {
        tags.add(StringUtils.trim(cursor.collectDescendantText(false)));
      }
    }
    RulesDefinition.NewRule rule = repo.createRule(key)
      .setHtmlDescription(description)
      .setSeverity(severity)
      .setName(name)
      .setInternalKey(internalKey)
      .setTags(tags.toArray(new String[tags.size()]))
      .setTemplate(cardinality == Cardinality.MULTIPLE);
    if (status != null) {
      rule.setStatus(RuleStatus.valueOf(status));
    }
    for (ParamStruct param : params) {
      rule.createParam(param.key)
        .setDefaultValue(param.defaultValue)
        .setType(param.type)
        .setDescription(param.description);
    }
  }

  private static class ParamStruct {
    String key, description, defaultValue;
    RuleParamType type = RuleParamType.STRING;
  }

  private ParamStruct processParameter(SMInputCursor ruleC) throws XMLStreamException {
    ParamStruct param = new ParamStruct();

    // BACKWARD COMPATIBILITY WITH DEPRECATED FORMAT
    String keyAttribute = ruleC.getAttrValue("key");
    if (StringUtils.isNotBlank(keyAttribute)) {
      param.key = StringUtils.trim(keyAttribute);
    }

    // BACKWARD COMPATIBILITY WITH DEPRECATED FORMAT
    String typeAttribute = ruleC.getAttrValue("type");
    if (StringUtils.isNotBlank(typeAttribute)) {
      param.type = RuleParamType.parse(typeAttribute);
    }

    SMInputCursor paramC = ruleC.childElementCursor();
    while (paramC.getNext() != null) {
      String propNodeName = paramC.getLocalName();
      String propText = StringUtils.trim(paramC.collectDescendantText(false));
      if (StringUtils.equalsIgnoreCase("key", propNodeName)) {
        param.key = propText;

      } else if (StringUtils.equalsIgnoreCase("description", propNodeName)) {
        param.description = propText;

      } else if (StringUtils.equalsIgnoreCase("type", propNodeName)) {
        param.type = RuleParamType.parse(propText);

      } else if (StringUtils.equalsIgnoreCase("defaultValue", propNodeName)) {
        param.defaultValue = propText;
      }
    }
    return param;
  }
}
