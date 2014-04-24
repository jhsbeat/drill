/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.planner.sql.parser;

import com.google.common.collect.Lists;
import com.google.common.collect.ImmutableList;
import net.hydromatic.optiq.tools.Planner;
import org.apache.drill.exec.ops.QueryContext;
import org.apache.drill.exec.planner.sql.handlers.SqlHandler;
import org.apache.drill.exec.planner.sql.handlers.ViewHandler;
import org.eigenbase.sql.*;
import org.eigenbase.sql.parser.SqlParserPos;

import java.util.List;

public class SqlCreateView extends DrillSqlCall {
  public static final SqlSpecialOperator OPERATOR = new SqlSpecialOperator("CREATE_VIEW", SqlKind.OTHER);

  private SqlIdentifier viewName;
  private SqlNodeList fieldList;
  private SqlNode query;
  private boolean replaceView;

  public SqlCreateView(SqlParserPos pos, SqlIdentifier viewName, SqlNodeList fieldList,
                       SqlNode query, boolean replaceView) {
    super(pos);
    this.viewName = viewName;
    this.query = query;
    this.replaceView = replaceView;
    this.fieldList = fieldList;
  }

  @Override
  public SqlOperator getOperator() {
    return OPERATOR;
  }

  @Override
  public List<SqlNode> getOperandList() {
    return ImmutableList.of(viewName, fieldList, query,
        SqlLiteral.createBoolean(replaceView, SqlParserPos.ZERO));
  }

  @Override
  public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
    writer.keyword("CREATE");
    if (replaceView) {
      writer.keyword("OR");
      writer.keyword("REPLACE");
    }
    writer.keyword("VIEW");
    viewName.unparse(writer, leftPrec, rightPrec);
    if (fieldList != null && fieldList.size() > 0) {
      writer.keyword("(");
      fieldList.get(0).unparse(writer, leftPrec, rightPrec);
      for(int i=1; i<fieldList.size(); i++) {
        writer.keyword(",");
        fieldList.get(i).unparse(writer, leftPrec, rightPrec);
      }
      writer.keyword(")");
    }
    writer.keyword("AS");
    query.unparse(writer, leftPrec, rightPrec);
  }

  @Override
  public SqlHandler getSqlHandler(Planner planner, QueryContext context) {
    return new ViewHandler.CreateView(planner, context);
  }

  public String getViewName() { return viewName.getSimple(); }

  public List<String> getFieldNames() {
    if (fieldList == null) return ImmutableList.of();

    List<String> fieldNames = Lists.newArrayList();
    for(SqlNode node : fieldList.getList()) {
      fieldNames.add(node.toString());
    }
    return fieldNames;
  }

  public SqlNode getQuery() { return query; }
  public boolean getReplace() { return replaceView; }
}
