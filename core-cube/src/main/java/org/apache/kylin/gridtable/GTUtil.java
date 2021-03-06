/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kylin.gridtable;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.apache.kylin.common.util.ByteArray;
import org.apache.kylin.common.util.BytesUtil;
import org.apache.kylin.metadata.filter.ColumnTupleFilter;
import org.apache.kylin.metadata.filter.CompareTupleFilter;
import org.apache.kylin.metadata.filter.ConstantTupleFilter;
import org.apache.kylin.metadata.filter.IFilterCodeSystem;
import org.apache.kylin.metadata.filter.TupleFilter;
import org.apache.kylin.metadata.filter.TupleFilterSerializer;
import org.apache.kylin.metadata.model.ColumnDesc;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.model.TblColRef;

import com.google.common.collect.Sets;

public class GTUtil {

    static final TableDesc MOCKUP_TABLE = TableDesc.mockup("GT_MOCKUP_TABLE");

    static TblColRef tblColRef(int col, String datatype) {
        ColumnDesc desc = ColumnDesc.mockup(MOCKUP_TABLE, col + 1, "" + col, datatype);
        return new TblColRef(desc);
    }

    public static byte[] serializeGTFilter(TupleFilter gtFilter, GTInfo info) {
        IFilterCodeSystem<ByteArray> filterCodeSystem = wrap(info.codeSystem.getComparator());
        return TupleFilterSerializer.serialize(gtFilter, filterCodeSystem);
    }

    public static TupleFilter deserializeGTFilter(byte[] bytes, GTInfo info) {
        IFilterCodeSystem<ByteArray> filterCodeSystem = wrap(info.codeSystem.getComparator());
        return TupleFilterSerializer.deserialize(bytes, filterCodeSystem);
    }

    public static TupleFilter convertFilterUnevaluatable(TupleFilter rootFilter, GTInfo info, //
            Set<TblColRef> unevaluatableColumnCollector) {
        return convertFilter(rootFilter, info, null, false, unevaluatableColumnCollector);
    }

    public static TupleFilter convertFilterColumnsAndConstants(TupleFilter rootFilter, GTInfo info, //
            List<TblColRef> colMapping, Set<TblColRef> unevaluatableColumnCollector) {
        return convertFilter(rootFilter, info, colMapping, true, unevaluatableColumnCollector);
    }

    // converts TblColRef to GridTable column, encode constants, drop unEvaluatable parts
    private static TupleFilter convertFilter(TupleFilter rootFilter, final GTInfo info, //
            final List<TblColRef> colMapping, final boolean encodeConstants, //
            final Set<TblColRef> unevaluatableColumnCollector) {

        IFilterCodeSystem<ByteArray> filterCodeSystem = wrap(info.codeSystem.getComparator());

        byte[] bytes = TupleFilterSerializer.serialize(rootFilter, new TupleFilterSerializer.Decorator() {
            @Override
            public TupleFilter onSerialize(TupleFilter filter) {
                if (filter == null)
                    return null;

                // In case of NOT(unEvaluatableFilter), we should immediately replace it as TRUE,
                // Otherwise, unEvaluatableFilter will later be replace with TRUE and NOT(unEvaluatableFilter)
                // will always return FALSE.
                if (filter.getOperator() == TupleFilter.FilterOperatorEnum.NOT && !TupleFilter.isEvaluableRecursively(filter)) {
                    TupleFilter.collectColumns(filter, unevaluatableColumnCollector);
                    return ConstantTupleFilter.TRUE;
                }

                // shortcut for unEvaluatable filter
                if (!filter.isEvaluable()) {
                    TupleFilter.collectColumns(filter, unevaluatableColumnCollector);
                    return ConstantTupleFilter.TRUE;
                }

                // map to column onto grid table
                if (colMapping != null && filter instanceof ColumnTupleFilter) {
                    ColumnTupleFilter colFilter = (ColumnTupleFilter) filter;
                    int gtColIdx = colMapping.indexOf(colFilter.getColumn());
                    return new ColumnTupleFilter(info.colRef(gtColIdx));
                }

                // encode constants
                if (encodeConstants && filter instanceof CompareTupleFilter) {
                    return encodeConstants((CompareTupleFilter) filter);
                }

                return filter;
            }

            @SuppressWarnings({ "rawtypes", "unchecked" })
            private TupleFilter encodeConstants(CompareTupleFilter oldCompareFilter) {
                // extract ColumnFilter & ConstantFilter
                TblColRef externalCol = oldCompareFilter.getColumn();

                if (externalCol == null) {
                    return oldCompareFilter;
                }

                Collection constValues = oldCompareFilter.getValues();
                if (constValues == null || constValues.isEmpty()) {
                    return oldCompareFilter;
                }

                CompareTupleFilter newCompareFilter = new CompareTupleFilter(oldCompareFilter.getOperator());
                newCompareFilter.addChild(new ColumnTupleFilter(externalCol));

                Object firstValue = constValues.iterator().next();
                int col = colMapping == null ? externalCol.getColumnDesc().getZeroBasedIndex() : colMapping.indexOf(externalCol);

                TupleFilter result;
                ByteArray code;

                // translate constant into code
                switch (newCompareFilter.getOperator()) {
                case EQ:
                case IN:
                    Set newValues = Sets.newHashSet();
                    for (Object value : constValues) {
                        code = translate(col, value, 0);
                        if (code != null)
                            newValues.add(code);
                    }
                    if (newValues.isEmpty()) {
                        result = ConstantTupleFilter.FALSE;
                    } else {
                        newCompareFilter.addChild(new ConstantTupleFilter(newValues));
                        result = newCompareFilter;
                    }
                    break;
                case NEQ:
                    code = translate(col, firstValue, 0);
                    if (code == null) {
                        result = ConstantTupleFilter.TRUE;
                    } else {
                        newCompareFilter.addChild(new ConstantTupleFilter(code));
                        result = newCompareFilter;
                    }
                    break;
                case LT:
                    code = translate(col, firstValue, 1);
                    if (code == null) {
                        result = ConstantTupleFilter.TRUE;
                    } else {
                        newCompareFilter.addChild(new ConstantTupleFilter(code));
                        result = newCompareFilter;
                    }
                    break;
                case LTE:
                    code = translate(col, firstValue, -1);
                    if (code == null) {
                        result = ConstantTupleFilter.FALSE;
                    } else {
                        newCompareFilter.addChild(new ConstantTupleFilter(code));
                        result = newCompareFilter;
                    }
                    break;
                case GT:
                    code = translate(col, firstValue, -1);
                    if (code == null) {
                        result = ConstantTupleFilter.TRUE;
                    } else {
                        newCompareFilter.addChild(new ConstantTupleFilter(code));
                        result = newCompareFilter;
                    }
                    break;
                case GTE:
                    code = translate(col, firstValue, 1);
                    if (code == null) {
                        result = ConstantTupleFilter.FALSE;
                    } else {
                        newCompareFilter.addChild(new ConstantTupleFilter(code));
                        result = newCompareFilter;
                    }
                    break;
                default:
                    throw new IllegalStateException("Cannot handle operator " + newCompareFilter.getOperator());
                }
                return result;
            }

            transient ByteBuffer buf = ByteBuffer.allocate(info.getMaxColumnLength());

            private ByteArray translate(int col, Object value, int roundingFlag) {
                try {
                    buf.clear();
                    info.codeSystem.encodeColumnValue(col, value, roundingFlag, buf);
                    return ByteArray.copyOf(buf.array(), 0, buf.position());
                } catch (IllegalArgumentException ex) {
                    return null;
                }
            }
        }, filterCodeSystem);

        return TupleFilterSerializer.deserialize(bytes, filterCodeSystem);
    }

    public static IFilterCodeSystem<ByteArray> wrap(final IGTComparator comp) {
        return new IFilterCodeSystem<ByteArray>() {

            @Override
            public int compare(ByteArray o1, ByteArray o2) {
                return comp.compare(o1, o2);
            }

            @Override
            public boolean isNull(ByteArray code) {
                return comp.isNull(code);
            }

            @Override
            public void serialize(ByteArray code, ByteBuffer buffer) {
                if (code == null)
                    BytesUtil.writeByteArray(null, 0, 0, buffer);
                else
                    BytesUtil.writeByteArray(code.array(), code.offset(), code.length(), buffer);
            }

            @Override
            public ByteArray deserialize(ByteBuffer buffer) {
                return new ByteArray(BytesUtil.readByteArray(buffer));
            }
        };
    }
}
