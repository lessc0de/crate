/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.operation.projectors;

import com.carrotsearch.hppc.*;
import com.carrotsearch.hppc.cursors.IntCursor;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.crate.analyze.symbol.FetchReference;
import io.crate.analyze.symbol.InputColumn;
import io.crate.analyze.symbol.Reference;
import io.crate.analyze.symbol.Symbol;
import io.crate.core.collections.Bucket;
import io.crate.core.collections.CollectionBucket;
import io.crate.core.collections.Row;
import io.crate.core.collections.Row1;
import io.crate.metadata.*;
import io.crate.metadata.table.ColumnPolicy;
import io.crate.operation.RowUpstream;
import io.crate.operation.projectors.fetch.FetchOperation;
import io.crate.operation.projectors.fetch.FetchProjector;
import io.crate.operation.projectors.fetch.FetchProjectorContext;
import io.crate.planner.node.fetch.FetchSource;
import io.crate.test.integration.CrateUnitTest;
import io.crate.testing.CollectingRowReceiver;
import io.crate.testing.TestingHelpers;
import io.crate.types.LongType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.*;

public class FetchProjectorTest extends CrateUnitTest {

    private static final TableIdent USER_TABLE_IDENT = new TableIdent(Schemas.DEFAULT_SCHEMA_NAME, "users");
    private ExecutorService executorService;

    @Before
    public void before() throws Exception {
        executorService = Executors.newFixedThreadPool(2);
    }

    @After
    public void after() throws Exception {
        executorService.shutdown();
        executorService.awaitTermination(1, TimeUnit.SECONDS);
    }

    @Test
    public void testMultipleFetchRequests() throws Throwable {
        CollectingRowReceiver rowReceiver = new CollectingRowReceiver();

        // dummy FetchOperation that returns buckets for each reader-id where each row contains a column that is the same as the docId
        FetchOperation fetchOperation = new FetchOperation() {
            @Override
            public ListenableFuture<IntObjectMap<? extends Bucket>> fetch(String nodeId, IntObjectMap<? extends IntContainer> toFetch, boolean closeContext) {
                IntObjectHashMap<Bucket> readerToBuckets = new IntObjectHashMap<>();
                for (IntObjectCursor<? extends IntContainer> cursor : toFetch) {
                    List<Object[]> rows = new ArrayList<>();
                    for (IntCursor docIdCursor : cursor.value) {
                        rows.add(new Object[] { docIdCursor.value });
                    }
                    readerToBuckets.put(cursor.key, new CollectionBucket(rows));
                }
                return Futures.<IntObjectMap<? extends Bucket>>immediateFuture(readerToBuckets);
            }
        };
        RowUpstream upstream = mock(RowUpstream.class);

        int fetchSize = 3;
        FetchProjector fetchProjector = prepareFetchProjector(fetchSize, rowReceiver, fetchOperation, upstream);
        long i;
        for (i = 1; i <= 10; i++) {
            Row row = new Row1(i);
            fetchProjector.setNextRow(row);

            if (i % fetchSize == 0) {
                verify(upstream, timeout(1000).times((int)i / fetchSize)).pause();
                verify(upstream, timeout(1000).times((int)i / fetchSize)).resume(false);
            }
        }
        assertThat(i, is(11L));
        fetchProjector.finish();

        Bucket projected = rowReceiver.result();
        assertThat(projected.size(), is(10));

        int iterateLength = Iterables.size(rowReceiver.result());
        assertThat(iterateLength, is(10));
    }


    private FetchProjector prepareFetchProjector(int fetchSize,
                                                 CollectingRowReceiver rowReceiver,
                                                 FetchOperation fetchOperation,
                                                 RowUpstream upstream) {
        FetchProjector pipe =
            new FetchProjector(
                fetchOperation,
                executorService,
                TestingHelpers.getFunctions(),
                buildOutputSymbols(),
                buildFetchProjectorContext(),
                fetchSize
            );
        pipe.setUpstream(upstream);
        pipe.downstream(rowReceiver);
        pipe.prepare();
        return pipe;
    }

    private FetchProjectorContext buildFetchProjectorContext() {
        Map<String, IntSet> nodeToReaderIds = new HashMap<>(2);
        IntSet nodeReadersNodeOne = new IntHashSet();
        nodeReadersNodeOne.add(0);
        IntSet nodeReadersNodeTwo = new IntHashSet();
        nodeReadersNodeTwo.add(2);
        nodeToReaderIds.put("nodeOne", nodeReadersNodeOne);
        nodeToReaderIds.put("nodeTwo", nodeReadersNodeTwo);

        TreeMap<Integer, String> readerIndices = new TreeMap<>();
        readerIndices.put(0, "t1");

        Map<String, TableIdent> indexToTable = new HashMap<>(1);
        indexToTable.put("t1", USER_TABLE_IDENT);

        ReferenceIdent referenceIdent = new ReferenceIdent(USER_TABLE_IDENT, "id");
        ReferenceInfo referenceInfo = new ReferenceInfo(referenceIdent,
            RowGranularity.DOC,
            LongType.INSTANCE,
            ColumnPolicy.STRICT,
            ReferenceInfo.IndexType.NOT_ANALYZED);

        Map<TableIdent, FetchSource> tableToFetchSource = new HashMap<>(2);
        FetchSource fetchSource = new FetchSource(Collections.<ReferenceInfo>emptyList(),
            Collections.singletonList(new InputColumn(0)),
            Collections.singletonList(new Reference(referenceInfo))
            );
        tableToFetchSource.put(USER_TABLE_IDENT, fetchSource);

        return new FetchProjectorContext(
            tableToFetchSource,
            nodeToReaderIds,
            readerIndices,
            indexToTable
        );
    }

    private List<Symbol> buildOutputSymbols() {
        List<Symbol> outputSymbols = new ArrayList<>(2);

        InputColumn inputColumn = new InputColumn(0);
        ReferenceIdent referenceIdent = new ReferenceIdent(USER_TABLE_IDENT, "id");
        ReferenceInfo referenceInfo = new ReferenceInfo(referenceIdent,
            RowGranularity.DOC,
            LongType.INSTANCE,
            ColumnPolicy.STRICT,
            ReferenceInfo.IndexType.NOT_ANALYZED);

        outputSymbols.add(new FetchReference(inputColumn, new Reference(referenceInfo)));
        return outputSymbols;
    }
}
