/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.streams.kstream.internals.foreignkeyjoin;

import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.apache.kafka.streams.kstream.internals.Change;
import org.apache.kafka.streams.kstream.internals.KTableValueGetter;
import org.apache.kafka.streams.kstream.internals.KTableValueGetterSupplier;
import org.apache.kafka.streams.processor.To;
import org.apache.kafka.streams.state.ValueAndTimestamp;

import java.util.Objects;

/**
 * Receives {@code SubscriptionWrapper<K>} events and processes them according to their Instruction.
 * Depending on the results, {@code SubscriptionResponseWrapper}s are created, which will be propagated to
 * the {@code SubscriptionResolverJoinProcessorSupplier} instance.
 *
 * @param <K> Type of primary keys
 * @param <KO> Type of foreign key
 * @param <VO> Type of foreign value
 */
@SuppressWarnings("deprecation") // Old PAPI. Needs to be migrated.
public class SubscriptionJoinForeignProcessorSupplier<K, KO, VO>
    implements org.apache.kafka.streams.processor.ProcessorSupplier<CombinedKey<KO, K>, Change<ValueAndTimestamp<SubscriptionWrapper<K>>>> {

    private final KTableValueGetterSupplier<KO, VO> foreignValueGetterSupplier;

    public SubscriptionJoinForeignProcessorSupplier(final KTableValueGetterSupplier<KO, VO> foreignValueGetterSupplier) {
        this.foreignValueGetterSupplier = foreignValueGetterSupplier;
    }

    @Override
    public org.apache.kafka.streams.processor.Processor<CombinedKey<KO, K>, Change<ValueAndTimestamp<SubscriptionWrapper<K>>>> get() {

        return new org.apache.kafka.streams.processor.AbstractProcessor<CombinedKey<KO, K>, Change<ValueAndTimestamp<SubscriptionWrapper<K>>>>() {

            private KTableValueGetter<KO, VO> foreignValues;

            @Override
            public void init(final org.apache.kafka.streams.processor.ProcessorContext context) {
                super.init(context);
                foreignValues = foreignValueGetterSupplier.get();
                foreignValues.init(context);
            }

            @Override
            public void process(final CombinedKey<KO, K> combinedKey, final Change<ValueAndTimestamp<SubscriptionWrapper<K>>> change) {
                Objects.requireNonNull(combinedKey, "This processor should never see a null key.");
                Objects.requireNonNull(change, "This processor should never see a null value.");
                final ValueAndTimestamp<SubscriptionWrapper<K>> valueAndTimestamp = change.newValue;
                Objects.requireNonNull(valueAndTimestamp, "This processor should never see a null newValue.");
                final SubscriptionWrapper<K> value = valueAndTimestamp.value();

                if (value.getVersion() != SubscriptionWrapper.CURRENT_VERSION) {
                    //Guard against modifications to SubscriptionWrapper. Need to ensure that there is compatibility
                    //with previous versions to enable rolling upgrades. Must develop a strategy for upgrading
                    //from older SubscriptionWrapper versions to newer versions.
                    throw new UnsupportedVersionException("SubscriptionWrapper is of an incompatible version.");
                }

                final ValueAndTimestamp<VO> foreignValueAndTime = foreignValues.get(combinedKey.getForeignKey());

                final long resultTimestamp =
                    foreignValueAndTime == null ?
                        valueAndTimestamp.timestamp() :
                        Math.max(valueAndTimestamp.timestamp(), foreignValueAndTime.timestamp());

                switch (value.getInstruction()) {
                    case DELETE_KEY_AND_PROPAGATE:
                        context().forward(
                            combinedKey.getPrimaryKey(),
                            new SubscriptionResponseWrapper<VO>(value.getHash(), null),
                            To.all().withTimestamp(resultTimestamp)
                        );
                        break;
                    case PROPAGATE_NULL_IF_NO_FK_VAL_AVAILABLE:
                        //This one needs to go through regardless of LEFT or INNER join, since the extracted FK was
                        //changed and there is no match for it. We must propagate the (key, null) to ensure that the
                        //downstream consumers are alerted to this fact.
                        final VO valueToSend = foreignValueAndTime == null ? null : foreignValueAndTime.value();

                        context().forward(
                            combinedKey.getPrimaryKey(),
                            new SubscriptionResponseWrapper<>(value.getHash(), valueToSend),
                            To.all().withTimestamp(resultTimestamp)
                        );
                        break;
                    case PROPAGATE_ONLY_IF_FK_VAL_AVAILABLE:
                        if (foreignValueAndTime != null) {
                            context().forward(
                                combinedKey.getPrimaryKey(),
                                new SubscriptionResponseWrapper<>(value.getHash(), foreignValueAndTime.value()),
                                To.all().withTimestamp(resultTimestamp)
                            );
                        }
                        break;
                    case DELETE_KEY_NO_PROPAGATE:
                        break;
                    default:
                        throw new IllegalStateException("Unhandled instruction: " + value.getInstruction());
                }
            }
        };
    }
}