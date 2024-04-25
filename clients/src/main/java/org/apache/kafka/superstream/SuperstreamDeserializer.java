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

import org.apache.kafka.common.serialization.Deserializer;

import com.google.protobuf.Descriptors;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

public class SuperstreamDeserializer<T> implements Deserializer<T>{
    private Deserializer<T> originalDeserializer;
    private Superstream superstreamConnection;
    

    public SuperstreamDeserializer() {
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
            String originalDeserializerClassName = configs.get(Consts.originalDeserializer)!= null ? (String) configs.get(Consts.originalDeserializer) : null;
            if (originalDeserializerClassName == null) {
                throw new Exception("original deserializer is required");
            }
            Class<?> originalDeserializerClass = Class.forName(originalDeserializerClassName);
            @SuppressWarnings("unchecked")
            Deserializer<T> originalDeserializerT = (Deserializer<T>) originalDeserializerClass.getDeclaredConstructor().newInstance();
            originalDeserializer = originalDeserializerT;
            originalDeserializer.configure(configs, isKey);
            Superstream superstreamConn = new Superstream(token, superstreamHost, learningFactor, "consumer", configs);
            superstreamConn.init();
            superstreamConnection = superstreamConn;
        } catch (Exception e) {
            String errMsg = String.format("superstream: error initializing superstream: %s", e.getMessage());
            if (superstreamConnection != null) {
                superstreamConnection.handleError(errMsg);
            }
            System.out.println(errMsg);
        }
    }

    @Override
    public T deserialize(String topic, byte[] data) {
        T deserializedData = originalDeserializer.deserialize(topic, data);
        return deserializedData;
    }

    @Override
    public T deserialize(String topic, Headers headers, byte[] data) {
        String schemaId = null;
        byte[] dataToDesrialize = data;
        if (superstreamConnection != null){
            superstreamConnection.clientCounters.incrementTotalBytesAfterReduction(data.length);
        }
        Header header = headers.lastHeader("superstream_schema");
        if (header != null) {
            schemaId = new String(header.value(), StandardCharsets.UTF_8);
        }
        if (schemaId != null) {
            Descriptors.Descriptor desc = superstreamConnection.SchemaIDMap.get(schemaId);
            if (desc == null){
                superstreamConnection.sendGetSchemaRequest(schemaId);
                desc = superstreamConnection.SchemaIDMap.get(schemaId);
                if (desc == null) {
                    superstreamConnection.handleError("error getting schema with id: " + schemaId);
                    System.out.println("superstream: shcema not found");
                    return null;
                }
            }
            try {
                byte[] supertstreamDeserialized = superstreamConnection.protoToJson(data, desc);
                dataToDesrialize = supertstreamDeserialized;
                superstreamConnection.clientCounters.incrementTotalBytesBeforeReduction(supertstreamDeserialized.length);
                superstreamConnection.clientCounters.incrementTotalMessagesSuccessfullyConsumed();
            } catch (Exception e) {
                superstreamConnection.handleError(String.format("error deserializing data: %s", e.getMessage()));
                return null;
            }
        } else {
            superstreamConnection.clientCounters.incrementTotalBytesBeforeReduction(data.length);
            superstreamConnection.clientCounters.incrementTotalMessagesFailedConsume();
        }
        T deserializedData = originalDeserializer.deserialize(topic, dataToDesrialize);
        return deserializedData;
    }

    @Override
    public void close() {
        originalDeserializer.close();
        superstreamConnection.close();
    }
}