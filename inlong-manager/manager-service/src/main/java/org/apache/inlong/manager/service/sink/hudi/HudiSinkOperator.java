/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.manager.service.sink.hudi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.inlong.manager.common.enums.ErrorCodeEnum;
import org.apache.inlong.manager.common.consts.SinkType;
import org.apache.inlong.manager.common.exceptions.BusinessException;
import org.apache.inlong.manager.pojo.sink.SinkField;
import org.apache.inlong.manager.pojo.sink.SinkRequest;
import org.apache.inlong.manager.pojo.sink.StreamSink;
import org.apache.inlong.manager.pojo.sink.hudi.HudiSink;
import org.apache.inlong.manager.pojo.sink.hudi.HudiSinkDTO;
import org.apache.inlong.manager.pojo.sink.hudi.HudiSinkRequest;
import org.apache.inlong.manager.common.util.CommonBeanUtils;
import org.apache.inlong.manager.common.util.Preconditions;
import org.apache.inlong.manager.dao.entity.StreamSinkEntity;
import org.apache.inlong.manager.service.sink.AbstractSinkOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Hudi sink operator
 */
@Service
public class HudiSinkOperator extends AbstractSinkOperator {

    private static final Logger LOGGER = LoggerFactory.getLogger(HudiSinkOperator.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public Boolean accept(String sinkType) {
        return SinkType.HUDI.equals(sinkType);
    }

    @Override
    protected String getSinkType() {
        return SinkType.HUDI;
    }

    @Override
    protected void setTargetEntity(SinkRequest request, StreamSinkEntity targetEntity) {
        Preconditions.checkTrue(this.getSinkType().equals(request.getSinkType()),
                ErrorCodeEnum.SINK_TYPE_NOT_SUPPORT.getMessage() + ": " + getSinkType());
        HudiSinkRequest sinkRequest = (HudiSinkRequest) request;
        try {
            HudiSinkDTO dto = HudiSinkDTO.getFromRequest(sinkRequest);
            targetEntity.setExtParams(objectMapper.writeValueAsString(dto));
        } catch (Exception e) {
            LOGGER.error("parsing json string to sink info failed", e);
            throw new BusinessException(ErrorCodeEnum.SINK_SAVE_FAILED.getMessage());
        }
    }

    @Override
    public StreamSink getFromEntity(StreamSinkEntity entity) {
        HudiSink sink = new HudiSink();
        if (entity == null) {
            return sink;
        }

        HudiSinkDTO dto = HudiSinkDTO.getFromJson(entity.getExtParams());
        CommonBeanUtils.copyProperties(entity, sink, true);
        CommonBeanUtils.copyProperties(dto, sink, true);
        List<SinkField> sinkFields = super.getSinkFields(entity.getId());
        sink.setSinkFieldList(sinkFields);
        return sink;
    }
}
