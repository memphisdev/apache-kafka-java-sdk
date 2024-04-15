/*
 * Copyright 2022 [Your Name] or [Your Company]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.superstream;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.Serializer;

public class SuperstreamSerializer<T> implements Serializer<T> {
    private Serializer<T> originalSerializer;
    private Superstream superstreamConnection;

    public SuperstreamSerializer() {
    }
    
    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        try {
            String token  = configs.get(Consts.superstreamTokenKey) != null ? (String) configs.get(Consts.superstreamTokenKey) : null;
            if (token == null) {
                throw new Exception("token is required");
            }
            String superstreamHost = configs.get(Consts.superstreamHostKey) != null ? (String) configs.get(Consts.superstreamHostKey) : Consts.superstreamDefaultHost;
            if (superstreamHost == null) {
                superstreamHost = Consts.superstreamDefaultHost;
            }
            int learningFactor = configs.get(Consts.superstreamLearningFactorKey) != null ? (Integer) configs.get(Consts.superstreamLearningFactorKey) : Consts.superstreamDefaultLearningFactor;
            String originalSerializerClassName = configs.get(Consts.originalSerializer) != null ? (String) configs.get(Consts.originalSerializer) : null;
            if (originalSerializerClassName == null) {
                throw new Exception("original serializer is required");
            }
            try {
                Class<?> originalSerializerClass = Class.forName(originalSerializerClassName);
                @SuppressWarnings("unchecked")
                Serializer<T> originalSerializerT = (Serializer<T>) originalSerializerClass.getDeclaredConstructor().newInstance();
                originalSerializer = originalSerializerT;
                originalSerializer.configure(configs, isKey);
                Superstream superstreamConn = new Superstream(token, superstreamHost, learningFactor, "producer", configs);
                superstreamConn.init();
                superstreamConnection = superstreamConn;
            } catch (Exception e) {
                throw e;
            }
        } catch (Exception e) {
            String errMsg = String.format("superstream: error initializing superstream: %s", e.getMessage());
            if (superstreamConnection != null) {
                superstreamConnection.handleError(errMsg);
            }
            System.out.println(errMsg);
        }
    }

    @Override
    public byte[] serialize(String topic, T data) {
        byte[] serializedData = originalSerializer.serialize(topic, data);
        return serializedData;
    }

    @Override
    public byte[] serialize(String topic, Headers headers, T data) {
        byte[] serializedData = originalSerializer.serialize(topic, data);
        byte[] serializedResult;
        if (superstreamConnection != null && superstreamConnection.reductionEnabled) {
            if (superstreamConnection.descriptor != null){
                try {
                    Header header = new RecordHeader("superstream_schema",  superstreamConnection.ProducerSchemaID.getBytes(StandardCharsets.UTF_8));
                    headers.add(header);
                    byte[] superstreamSerialized = superstreamConnection.jsonToProto(serializedData);
                    superstreamConnection.clientCounters.incrementTotalBytesBeforeReduction(serializedData.length);
                    superstreamConnection.clientCounters.incrementTotalBytesAfterReduction(superstreamSerialized.length);
                    superstreamConnection.clientCounters.incrementTotalMessagesSuccessfullyProduce();
                    return superstreamSerialized;
                } catch (Exception e) {
                    serializedResult = serializedData;
                    superstreamConnection.handleError(String.format("error serializing data: ", e.getMessage()));
                    superstreamConnection.clientCounters.incrementTotalBytesAfterReduction(serializedData.length);
                    superstreamConnection.clientCounters.incrementTotalMessagesFailedProduce();
                }
            } else {
                serializedResult = serializedData;
                superstreamConnection.clientCounters.incrementTotalBytesAfterReduction(serializedData.length);
                if (superstreamConnection.learningFactorCounter <= superstreamConnection.learningFactor) {
                    superstreamConnection.sendLearningMessage(serializedResult);
                    superstreamConnection.learningFactorCounter++;
                } else if (!superstreamConnection.learningRequestSent) {
                    superstreamConnection.sendRegisterSchemaReq();
                }
            }
        } else {
            return serializedData;
        }
        return serializedResult;
    }

    @Override
    public void close() {
        originalSerializer.close();
        if (superstreamConnection != null) {
            superstreamConnection.close();
        }
    }
}
