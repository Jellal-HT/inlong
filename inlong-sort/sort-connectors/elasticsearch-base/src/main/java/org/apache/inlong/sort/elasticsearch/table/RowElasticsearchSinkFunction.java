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

package org.apache.inlong.sort.elasticsearch.table;

import org.apache.flink.api.common.functions.RuntimeContext;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.inlong.sort.elasticsearch.ElasticsearchSinkFunction;
import org.apache.flink.streaming.connectors.elasticsearch.RequestIndexer;
import org.apache.flink.table.api.TableException;
import org.apache.flink.table.data.RowData;
import org.apache.flink.util.Preconditions;
import org.apache.inlong.sort.base.metric.SinkMetricData;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.common.xcontent.XContentType;

import javax.annotation.Nullable;

import java.util.Objects;
import java.util.function.Function;

/** Sink function for converting upserts into Elasticsearch {@link ActionRequest}s. */
public class RowElasticsearchSinkFunction implements ElasticsearchSinkFunction<RowData> {

    private static final long serialVersionUID = 1L;

    private final IndexGenerator indexGenerator;
    private final String docType;
    private final SerializationSchema<RowData> serializationSchema;
    private final XContentType contentType;
    private final RequestFactory requestFactory;
    private final Function<RowData, String> createKey;
    private final String inLongMetric;

    private final Function<RowData, String> createRouting;

    private transient  RuntimeContext runtimeContext;

    private SinkMetricData sinkMetricData;
    private Long dataSize = 0L;
    private Long rowSize = 0L;

    public RowElasticsearchSinkFunction(
            IndexGenerator indexGenerator,
            @Nullable String docType, // this is deprecated in es 7+
            SerializationSchema<RowData> serializationSchema,
            XContentType contentType,
            RequestFactory requestFactory,
            Function<RowData, String> createKey,
            @Nullable Function<RowData, String> createRouting,
            String inLongMetric) {
        this.indexGenerator = Preconditions.checkNotNull(indexGenerator);
        this.docType = docType;
        this.serializationSchema = Preconditions.checkNotNull(serializationSchema);
        this.contentType = Preconditions.checkNotNull(contentType);
        this.requestFactory = Preconditions.checkNotNull(requestFactory);
        this.createKey = Preconditions.checkNotNull(createKey);
        this.createRouting = createRouting;
        this.inLongMetric = inLongMetric;
    }

    @Override
    public void open(RuntimeContext ctx) {
        indexGenerator.open();
        this.runtimeContext = ctx;
        if (inLongMetric != null && !inLongMetric.isEmpty()) {
            String[] inLongMetricArray = inLongMetric.split("&");
            String groupId = inLongMetricArray[0];
            String streamId = inLongMetricArray[1];
            String nodeId = inLongMetricArray[2];
            sinkMetricData = new SinkMetricData(groupId, streamId, nodeId, runtimeContext.getMetricGroup());
            sinkMetricData.registerMetricsForDirtyBytes();
            sinkMetricData.registerMetricsForDirtyRecords();
            sinkMetricData.registerMetricsForNumBytesOut();
            sinkMetricData.registerMetricsForNumRecordsOut();
            sinkMetricData.registerMetricsForNumBytesOutPerSecond();
            sinkMetricData.registerMetricsForNumRecordsOutPerSecond();
        }
    }

    @Override
    public void process(RowData element, RuntimeContext ctx, RequestIndexer indexer) {
        switch (element.getRowKind()) {
            case INSERT:
            case UPDATE_AFTER:
                processUpsert(element, indexer);
                break;
            case UPDATE_BEFORE:
            case DELETE:
                processDelete(element, indexer);
                break;
            default:
                throw new TableException("Unsupported message kind: " + element.getRowKind());
        }
    }

    private void processUpsert(RowData row, RequestIndexer indexer) {
        final byte[] document = serializationSchema.serialize(row);
        final String key = createKey.apply(row);
        if (sinkMetricData.getNumBytesOut() != null) {
            sinkMetricData.getNumBytesOut().inc(document.length);
        }
        if (key != null) {
            final UpdateRequest updateRequest =
                    requestFactory.createUpdateRequest(
                            indexGenerator.generate(row), docType, key, contentType, document);
            addRouting(updateRequest, row);
            indexer.add(updateRequest);
        } else {
            final IndexRequest indexRequest =
                    requestFactory.createIndexRequest(
                            indexGenerator.generate(row), docType, key, contentType, document);
            addRouting(indexRequest, row);
            indexer.add(indexRequest);
        }
    }

    private void processDelete(RowData row, RequestIndexer indexer) {
        final String key = createKey.apply(row);
        final DeleteRequest deleteRequest =
                requestFactory.createDeleteRequest(indexGenerator.generate(row), docType, key);
        addRouting(deleteRequest, row);
        indexer.add(deleteRequest);
    }

    private void addRouting(DocWriteRequest<?> request, RowData row) {
        if (null != createRouting) {
            String routing = createRouting.apply(row);
            request.routing(routing);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RowElasticsearchSinkFunction that = (RowElasticsearchSinkFunction) o;
        return Objects.equals(indexGenerator, that.indexGenerator)
                && Objects.equals(docType, that.docType)
                && Objects.equals(serializationSchema, that.serializationSchema)
                && contentType == that.contentType
                && Objects.equals(requestFactory, that.requestFactory)
                && Objects.equals(createKey, that.createKey)
                && Objects.equals(inLongMetric, that.inLongMetric);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                indexGenerator,
                docType,
                serializationSchema,
                contentType,
                requestFactory,
                createKey,
                inLongMetric);
    }
}
