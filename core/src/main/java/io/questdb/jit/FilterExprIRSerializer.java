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
package io.questdb.jit;

import io.questdb.cairo.ColumnType;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.cairo.vm.api.MemoryA;
import io.questdb.griffin.PostOrderTreeTraversalAlgo;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlKeywords;
import io.questdb.griffin.engine.functions.constants.NullConstant;
import io.questdb.griffin.model.ExpressionNode;
import io.questdb.std.Chars;
import io.questdb.std.Mutable;
import io.questdb.std.Numbers;
import io.questdb.std.NumericException;

public class FilterExprIRSerializer implements Mutable {

    static final byte MEM_I1 = 0;
    static final byte MEM_I2 = 1;
    static final byte MEM_I4 = 2;
    static final byte MEM_I8 = 3;
    static final byte MEM_F4 = 4;
    static final byte MEM_F8 = 5;

    static final byte IMM_I1 = 6;
    static final byte IMM_I2 = 7;
    static final byte IMM_I4 = 8;
    static final byte IMM_I8 = 9;
    static final byte IMM_F4 = 10;
    static final byte IMM_F8 = 11;

    static final byte NEG = 12;               // -a
    static final byte NOT = 13;               // !a
    static final byte AND = 14;               // a && b
    static final byte OR = 15;                // a || b
    static final byte EQ = 16;                // a == b
    static final byte NE = 17;                // a != b
    static final byte LT = 18;                // a <  b
    static final byte LE = 19;                // a <= b
    static final byte GT = 20;                // a >  b
    static final byte GE = 21;                // a >= b
    static final byte ADD = 22;               // a + b
    static final byte SUB = 23;               // a - b
    static final byte MUL = 24;               // a * b
    static final byte DIV = 25;               // a / b
    static final byte MOD = 26;               // a % b
    static final byte JZ = 27;                // if a == 0 jp b
    static final byte JNZ = 28;               // if a != 0 jp b
    static final byte JP = 29;                // jp a
    static final byte RET = 30;               // ret a
    static final byte IMM_NULL = 31;          // generic null const

    private static final byte UNDEFINED_CODE = -1;

    private MemoryA memory;
    private RecordMetadata metadata;
    private final ArithmeticExpressionContext exprContext = new ArithmeticExpressionContext();

    // TODO: FunctionParser
    //  type tagging
    //  const folding: long_col < (1 + 3) + 1.5 => long_col < 5.5
    //  filter const expressions: 3 > 13 => exception?
    //  encode null: long_col <> null - store null const => DONE
    //    memory.putByte(IMM_I8);
    //    memory.putDouble(Long.MIN_VALUE);

    public FilterExprIRSerializer of(MemoryA memory, RecordMetadata metadata) {
        this.memory = memory;
        this.metadata = metadata;
        exprContext.setMetadata(metadata);
        return this;
    }

    public void serialize(ExpressionNode node) throws SqlException {
        postOrder(node);
        memory.putByte(RET);
    }

    @Override
    public void clear() {
        memory = null;
        metadata = null;
        exprContext.clear();
    }

    public void postOrder(ExpressionNode node) throws SqlException {
        if (node != null) {
            exprContext.onNodeEntered(node);

            // TODO right -> left here and in JIT??? - PostOrderTreeTraversalAlgo, WhereClauseParserTest
            postOrder(node.lhs);
            postOrder(node.rhs);

            int argCount = node.paramCount;
            if (argCount == 0) {
                switch (node.type) {
                    case ExpressionNode.LITERAL:
                        serializeColumn(node.position, node.token);
                        break;
                    case ExpressionNode.CONSTANT:
                        serializeConstant(node.position, node.token);
                        break;
                    default:
                        throw SqlException.position(node.position)
                                .put("unsupported token")
                                .put(node.token);
                }
            } else {
                serializeOperator(node.position, node.token, argCount);
            }

            exprContext.onNodeLeft(node);
        }
    }

    private void serializeColumn(int position, final CharSequence token) throws SqlException {
        final int index = metadata.getColumnIndexQuiet(token);
        if (index == -1) {
            throw SqlException.invalidColumn(position, token);
        }

        int columnType = metadata.getColumnType(index);
        byte typeCode = columnTypeCode(columnType);
        if (typeCode == UNDEFINED_CODE) {
            throw SqlException.position(position)
                    .put("unsupported column type ")
                    .put(ColumnType.nameOf(columnType));
        }
        memory.putByte(typeCode);
        memory.putLong(index);
    }

    private void serializeConstant(int position, final CharSequence token) throws SqlException {
        if (SqlKeywords.isNullKeyword(token)) {
            if (!exprContext.isActive()) {
                throw SqlException.position(position).put("null outside of arithmetic expression");
            }
            byte exprNullCode = exprContext.nullCode;
            memory.putByte(exprNullCode);
            switch (exprNullCode) {
                case IMM_I1:
                    memory.putLong(NullConstant.NULL.getByte(null));
                    break;
                case IMM_I2:
                    memory.putLong(NullConstant.NULL.getShort(null));
                    break;
                case IMM_I4:
                    memory.putLong(NullConstant.NULL.getInt(null));
                    break;
                case IMM_I8:
                    memory.putLong(NullConstant.NULL.getLong(null));
                    break;
                case IMM_F4:
                    memory.putDouble(NullConstant.NULL.getFloat(null));
                    break;
                case IMM_F8:
                    memory.putDouble(NullConstant.NULL.getDouble(null));
                    break;
                default:
                    throw SqlException.position(position).put("unexpected null type ").put(exprNullCode);
            }
            return;
        }

//        if (Chars.isQuoted(token)) {
//            final int len = token.length();
//            if (len == 3) {
//                // this is 'x' - char
//                memory.putByte(IMM_I2);
//                memory.putChar(token.charAt(1));
//                return;
//            }
//
//            if (len == 2) {
//                // empty
//                memory.putByte(IMM_I2);
//                memory.putChar((char)0);
//                return;
//            }
//            throw SqlException.position(position).put("invalid constant: ").put(token);
//        }
//
        if (SqlKeywords.isTrueKeyword(token)) {
            memory.putByte(IMM_I1);
            memory.putLong(1);
            return;
        }

        if (SqlKeywords.isFalseKeyword(token)) {
            memory.putByte(IMM_I1);
            memory.putLong(0);
            return;
        }

//        try {
//            final int n = Numbers.parseInt(token);
//            memory.putByte(IMM_I4);
//            memory.putLong(n);
//            return;
//        } catch (NumericException ignore) {
//        }

        try {
            final long n = Numbers.parseLong(token);
            memory.putByte(IMM_I8);
            memory.putLong(n);
            return;
        } catch (NumericException ignore) {
        }

//        try {
//            final float n = Numbers.parseFloat(token);
//            memory.putByte(IMM_F4);
//            memory.putDouble(n);
//            return;
//        } catch (NumericException ignore) {
//        }

        try {
            final double n = Numbers.parseDouble(token);
            memory.putByte(IMM_F8);
            memory.putDouble(n);
            return;
        } catch (NumericException ignore) {
        }

        throw SqlException.position(position).put("invalid constant: ").put(token);
    }

    private void serializeOperator(int position, final CharSequence token, int argCount) throws SqlException {
        if (SqlKeywords.isNotKeyword(token)) {
            memory.putByte(NOT);
            return;
        }
        if (SqlKeywords.isAndKeyword(token)) {
            memory.putByte(AND);
            return;
        }
        if (SqlKeywords.isOrKeyword(token)) {
            memory.putByte(OR);
            return;
        }
        if (Chars.equals(token, "=")) {
            memory.putByte(EQ);
            return;
        }
        if (Chars.equals(token, "<>") || Chars.equals(token, "!=")) {
            memory.putByte(NE);
            return;
        }
        if (Chars.equals(token, "<")) {
            memory.putByte(LT);
            return;
        }
        if (Chars.equals(token, "<=")) {
            memory.putByte(LE);
            return;
        }
        if (Chars.equals(token, ">")) {
            memory.putByte(GT);
            return;
        }
        if (Chars.equals(token, ">=")) {
            memory.putByte(GE);
            return;
        }
        if (Chars.equals(token, "+")) {
            if (argCount == 2) {
                memory.putByte(ADD);
            } // ignore unary
            return;
        }
        if (Chars.equals(token, "-")) {
            if (argCount == 2) {
                memory.putByte(SUB);
            } else if (argCount == 1) {
                memory.putByte(NEG);
            }
            return;
        }
        if (Chars.equals(token, "*")) {
            memory.putByte(MUL);
            return;
        }
        if (Chars.equals(token, "/")) {
            memory.putByte(DIV);
            return;
        }
        throw SqlException.position(position).put("invalid operator: ").put(token);
    }

    private static byte columnTypeCode(int columnType) {
        switch (ColumnType.tagOf(columnType)) {
            case ColumnType.BOOLEAN:
            case ColumnType.BYTE:
            case ColumnType.GEOBYTE:
                return MEM_I1;
            case ColumnType.SHORT:
            case ColumnType.GEOSHORT:
            case ColumnType.CHAR:
                return MEM_I2;
            case ColumnType.INT:
            case ColumnType.GEOINT:
            case ColumnType.SYMBOL:
                return MEM_I4;
            case ColumnType.FLOAT:
                return MEM_F4;
            case ColumnType.LONG:
            case ColumnType.GEOLONG:
            case ColumnType.DATE:
            case ColumnType.TIMESTAMP:
                return MEM_I8;
            case ColumnType.DOUBLE:
                return MEM_F8;
            default:
                return UNDEFINED_CODE;
        }
    }

    private static class ArithmeticExpressionContext implements PostOrderTreeTraversalAlgo.Visitor, Mutable {

        private final PostOrderTreeTraversalAlgo traverseAlgo = new PostOrderTreeTraversalAlgo();
        private ExpressionNode rootNode;
        private RecordMetadata metadata;
        byte nullCode; // stands for widest column type in expression

        public void setMetadata(RecordMetadata metadata) {
            this.metadata = metadata;
        }

        @Override
        public void clear() {
            rootNode = null;
            metadata = null;
        }

        public void onNodeEntered(ExpressionNode node) throws SqlException {
            if (rootNode == null && isTopLevelArithmeticOperation(node)) {
                rootNode = node;
                nullCode = UNDEFINED_CODE;
                traverseAlgo.traverse(node, this);
            }
        }

        public void onNodeLeft(ExpressionNode node) {
            if (rootNode != null && rootNode == node) {
                rootNode = null;
                nullCode = UNDEFINED_CODE;
            }
        }

        public boolean isActive() {
            return rootNode != null;
        }

        @Override
        public void visit(ExpressionNode node) throws SqlException {
            if (node.type == ExpressionNode.LITERAL) {
                visitColumn(node);
            }
        }

        private void visitColumn(ExpressionNode node) throws SqlException {
            final int index = metadata.getColumnIndexQuiet(node.token);
            if (index == -1) {
                throw SqlException.invalidColumn(node.position, node.token);
            }

            int columnType = metadata.getColumnType(index);
            byte code = columnTypeCode(columnType);
            if (code == UNDEFINED_CODE) {
                throw SqlException.position(node.position)
                        .put("unsupported column type ")
                        .put(ColumnType.nameOf(columnType));
            }

            switch (code) {
                case MEM_I1:
                    if (nullCode == UNDEFINED_CODE) {
                        nullCode = IMM_I1;
                    }
                    break;
                case MEM_I2:
                    if (nullCode < IMM_I2) {
                        nullCode = IMM_I2;
                    }
                    break;
                case MEM_I4:
                    if (nullCode < IMM_I4) {
                        nullCode = IMM_I4;
                    }
                    break;
                case MEM_I8:
                    if (nullCode < IMM_I8) {
                        nullCode = IMM_I8;
                    }
                    if (nullCode == IMM_F4) {
                        nullCode = IMM_F8;
                    }
                    break;
                case MEM_F4:
                    if (nullCode <= IMM_I4) {
                        nullCode = IMM_F4;
                    }
                    if (nullCode == IMM_I8) {
                        nullCode = IMM_F8;
                    }
                    break;
                case MEM_F8:
                    nullCode = IMM_F8;
                    break;
                default:
                    throw SqlException.position(node.position)
                            .put("unexpected numeric type for column ")
                            .put(ColumnType.nameOf(columnType));
            }
        }

        private boolean isTopLevelArithmeticOperation(ExpressionNode node) {
            if (node.paramCount < 2) {
                return false;
            }
            final CharSequence token = node.token;
            if (Chars.equals(token, "=")) {
                return true;
            }
            if (Chars.equals(token, "<>") || Chars.equals(token, "!=")) {
                return true;
            }
            if (Chars.equals(token, "<")) {
                return true;
            }
            if (Chars.equals(token, "<=")) {
                return true;
            }
            if (Chars.equals(token, ">")) {
                return true;
            }
            return Chars.equals(token, ">=");
        }
    }
}
