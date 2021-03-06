/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.keyvalue.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Ints;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.encoding.PtBytes;
import com.palantir.atlasdb.keyvalue.api.CandidateCellForSweeping;
import com.palantir.atlasdb.keyvalue.api.CandidateCellForSweepingRequest;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.keyvalue.api.ImmutableCandidateCellForSweeping;
import com.palantir.atlasdb.keyvalue.api.ImmutableCandidateCellForSweepingRequest;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.keyvalue.api.Value;
import com.palantir.common.base.ClosableIterator;

public abstract class AbstractGetCandidateCellsForSweepingTest {
    protected static final TableReference TEST_TABLE = TableReference.createFromFullyQualifiedName(
            "get_candidate_cells_for_sweeping.test_table");

    private static KeyValueService kvs = null;

    @Before
    public void setUp() {
        if (kvs == null) {
            kvs = createKeyValueService();
        }
        kvs.createTable(TEST_TABLE, AtlasDbConstants.GENERIC_TABLE_METADATA);
        kvs.truncateTable(TEST_TABLE);
    }

    @AfterClass
    public static void closeKvs() {
        if (kvs != null) {
            kvs.close();
            kvs = null;
        }
    }

    @Test
    public void returnCandidateIfPossiblyUncommittedTimestamp() {
        new TestDataBuilder().put(1, 1, 10L).store();
        assertThat(getAllCandidates(conservativeRequest(PtBytes.EMPTY_BYTE_ARRAY, 40L, 5L)))
                .containsExactly(ImmutableCandidateCellForSweeping.builder()
                        .cell(cell(1, 1))
                        .sortedTimestamps(new long[] { 10L })
                        .isLatestValueEmpty(false)
                        .numCellsTsPairsExamined(1)
                        .build());
    }

    @Test
    public void doNotReturnCandidateIfOnlyCommittedTimestamp() {
        new TestDataBuilder().put(1, 1, 10L).store();
        assertThat(getAllCandidates(conservativeRequest(PtBytes.EMPTY_BYTE_ARRAY, 40L, 30L))).isEmpty();
    }

    @Test
    public void returnCandidateIfTwoCommittedTimestamps() {
        new TestDataBuilder().put(1, 1, 10L).put(1, 1, 20L).store();
        assertThat(getAllCandidates(conservativeRequest(PtBytes.EMPTY_BYTE_ARRAY, 40L, 30L)))
                .containsExactly(ImmutableCandidateCellForSweeping.builder()
                        .cell(cell(1, 1))
                        .sortedTimestamps(new long[] { 10L, 20L })
                        .isLatestValueEmpty(false)
                        .numCellsTsPairsExamined(2)
                        .build());
    }

    @Test
    public void doNotReturnCandidateWithCommitedEmptyValueIfConservative() {
        new TestDataBuilder().putEmpty(1, 1, 10L).store();
        assertThat(getAllCandidates(conservativeRequest(PtBytes.EMPTY_BYTE_ARRAY, 40L, 30L))).isEmpty();
    }

    @Test
    public void returnCandidateWithCommitedEmptyValueIfThorough() {
        new TestDataBuilder().putEmpty(1, 1, 10L).store();
        assertThat(getAllCandidates(thoroughRequest(PtBytes.EMPTY_BYTE_ARRAY, 40L, 30L)))
                .containsExactly(ImmutableCandidateCellForSweeping.builder()
                        .cell(cell(1, 1))
                        .sortedTimestamps(new long[] { 10L })
                        .isLatestValueEmpty(true)
                        .numCellsTsPairsExamined(1)
                        .build());
    }

    @Test
    public void returnCellsInOrder() {
        new TestDataBuilder()
                .putEmpty(1, 1, 10L)
                .putEmpty(1, 2, 10L)
                .putEmpty(2, 2, 10L)
                .putEmpty(3, 1, 10L)
                .putEmpty(3, 2, 10L)
                .putEmpty(3, 3, 10L)
                .store();
        assertThat(getAllCandidates(conservativeRequest(PtBytes.EMPTY_BYTE_ARRAY, 30L, 5L))
                    .stream().map(CandidateCellForSweeping::cell).collect(Collectors.toList()))
                .containsExactly(cell(1, 1), cell(1, 2), cell(2, 2), cell(3, 1), cell(3, 2), cell(3, 3));
    }

    @Test
    public void startFromGivenRow() {
        new TestDataBuilder()
                .putEmpty(1, 1, 10L)
                .putEmpty(1, 2, 10L)
                .putEmpty(2, 1, 10L)
                .putEmpty(2, 2, 10L)
                .putEmpty(3, 1, 10L)
                .putEmpty(3, 2, 10L)
                .store();
        assertThat(getAllCandidates(conservativeRequest(cell(2, 2).getRowName(), 30L, 5L))
                .stream().map(CandidateCellForSweeping::cell).collect(Collectors.toList()))
                .containsExactly(cell(2, 1), cell(2, 2), cell(3, 1), cell(3, 2));
    }

    @Test
    public void largerTableWithSmallBatchSizeReturnsCorrectResults() {
        TestDataBuilder builder = new TestDataBuilder();
        List<Cell> expectedCells = Lists.newArrayList();
        for (int rowNum = 1; rowNum <= 50; ++rowNum) {
            for (int colNum = 1; colNum <= rowNum; ++colNum) {
                expectedCells.add(cell(rowNum, colNum));
                for (int ts = 0; ts < 1 + (colNum % 4); ++ts) {
                    builder.put(rowNum, colNum, 10 + ts);
                }
            }
        }
        assertEquals((1 + 50) * 50 / 2, expectedCells.size());
        builder.store();
        List<CandidateCellForSweeping> candidates = getAllCandidates(
                ImmutableCandidateCellForSweepingRequest.builder()
                    .startRowInclusive(PtBytes.EMPTY_BYTE_ARRAY)
                    .sweepTimestamp(40L)
                    .minUncommittedStartTimestamp(1L)
                    .shouldCheckIfLatestValueIsEmpty(false)
                    .timestampsToIgnore(Value.INVALID_VALUE_TIMESTAMP)
                    .batchSizeHint(1)
                    .build());
        assertEquals(expectedCells,
                candidates.stream().map(CandidateCellForSweeping::cell).collect(Collectors.toList()));
    }

    private List<CandidateCellForSweeping> getAllCandidates(CandidateCellForSweepingRequest request) {
        try (ClosableIterator<List<CandidateCellForSweeping>> iter =
                    kvs.getCandidateCellsForSweeping(TEST_TABLE, request)) {
            return ImmutableList.copyOf(
                    Iterators.filter(
                            Iterators.concat(Iterators.transform(iter, List::iterator)),
                            list -> list.sortedTimestamps().length > 0));
        }
    }

    private static CandidateCellForSweepingRequest conservativeRequest(byte[] startRow,
                                                                       long sweepTs,
                                                                       long minUncommittedTs) {
        return ImmutableCandidateCellForSweepingRequest.builder()
                .startRowInclusive(startRow)
                .sweepTimestamp(sweepTs)
                .minUncommittedStartTimestamp(minUncommittedTs)
                .shouldCheckIfLatestValueIsEmpty(false)
                .timestampsToIgnore(Value.INVALID_VALUE_TIMESTAMP)
                .build();
    }

    private static CandidateCellForSweepingRequest thoroughRequest(byte[] startRow,
                                                                   long sweepTs,
                                                                   long minUncommittedTs) {
        return ImmutableCandidateCellForSweepingRequest.builder()
                .startRowInclusive(startRow)
                .sweepTimestamp(sweepTs)
                .minUncommittedStartTimestamp(minUncommittedTs)
                .shouldCheckIfLatestValueIsEmpty(true)
                .timestampsToIgnore()
                .build();
    }

    private class TestDataBuilder {
        private Map<Long, Map<Cell, byte[]>> cellsByTimestamp = Maps.newHashMap();

        TestDataBuilder put(int row, int col, long ts) {
            return put(row, col, ts, new byte[] { 1, 2, 3 });
        }

        TestDataBuilder put(int row, int col, long ts, byte[] value) {
            cellsByTimestamp.computeIfAbsent(ts, key -> Maps.newHashMap())
                    .put(cell(row, col), value);
            return this;
        }

        TestDataBuilder putEmpty(int row, int col, long ts) {
            return put(row, col, ts, PtBytes.EMPTY_BYTE_ARRAY);
        }

        void store() {
            for (Map.Entry<Long, Map<Cell, byte[]>> e : cellsByTimestamp.entrySet()) {
                kvs.put(TEST_TABLE, e.getValue(), e.getKey());
            }
        }
    }

    private static Cell cell(int rowNum, int colNum) {
        return Cell.create(row(rowNum), row(colNum));
    }

    private static byte[] row(int rowNum) {
        return Ints.toByteArray(rowNum);
    }

    protected abstract KeyValueService createKeyValueService();
}
