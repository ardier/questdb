/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.griffin.model;

import io.questdb.std.LowerCaseCharSequenceObjHashMap;
import io.questdb.std.Mutable;
import io.questdb.std.ObjList;
import io.questdb.std.Sinkable;
import io.questdb.std.str.CharSink;

public class UpdateModel implements Mutable, ExecutionModel, QueryWithClauseModel, Sinkable {
    private CharSequence updateTableAlias;
    private final ObjList<CharSequence> updatedColumns = new ObjList<>();
    private final ObjList<ExpressionNode> updateColumnExpressions = new ObjList<>();
    private QueryModel fromModel;
    private int position;
    private CharSequence updateTableName;

    @Override
    public void addWithClause(CharSequence token, WithClauseModel wcm) {

    }

    public QueryModel getQueryModel() {
        return fromModel;
    }

    @Override
    public WithClauseModel getWithClause(CharSequence token) {
        return null;
    }

    @Override
    public LowerCaseCharSequenceObjHashMap<WithClauseModel> getWithClauses() {
        return null;
    }

    public void addWithClauses(LowerCaseCharSequenceObjHashMap<WithClauseModel> withClauses) {

    }

    @Override
    public void clear() {
        updatedColumns.clear();
        updateColumnExpressions.clear();
        updateTableName = null;
        updateTableAlias = null;
    }

    @Override
    public int getModelType() {
        return UPDATE;
    }

    public void setAlias(CharSequence updateTableAlias) {
        this.updateTableAlias = updateTableAlias;
    }

    public void setModelPosition(int position) {
        this.position = position;
    }

    public void setFromModel(QueryModel nestedModel) {
        for (int i = 0, n = updatedColumns.size(); i < n; i++) {
            nestedModel.setSelectModelType(QueryModel.SELECT_MODEL_CURSOR);
        }
        this.fromModel = nestedModel;
    }

    @Override
    public void toSink(CharSink sink) {
        sink.put("update ").put(this.updateTableName);
        if (this.updateTableAlias != null) {
            sink.put(" as ").put(this.updateTableAlias);
        }
        sink.put(" set ");
        for (int i = 0, n = updatedColumns.size(); i < n; i++) {
            sink.put(updatedColumns.get(i)).put(" = ");
            sink.put(updateColumnExpressions.get(i));
            if (i < n - 1) {
                sink.put(',');
            }
        }
        fromModel.toSink(sink);
//        if (fromModel != null) {
//            if (fromModel.getJoinModels().size() > 1) {
//                sink.put(" from ");
//                fromModel.toSink(sink);
////                // First item is the table itself
////                for(int i = 1, n = fromModel.getJoinModels().size(); i < n; i++) {
////                    int modelIndex = fromModel.getOrderedJoinModels().get(i);
////                    QueryModel queryModel = fromModel.getJoinModels().get(modelIndex);
////                    queryModel.toSink(sink);
////                }
//            }
//            if (fromModel.getWhereClause() != null) {
//                sink.put(" where ");
//                sink.put(fromModel.getWhereClause());
//            }
//        }
    }

    public void withSet(CharSequence col, ExpressionNode expr) {
        this.updatedColumns.add(col);
        this.updateColumnExpressions.add(expr);
    }

    public void withTableName(CharSequence tableName) {
        this.updateTableName = tableName;
    }
}
