/*
 * Copyright 2021 Johan Haleby
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.occurrent.subscription.blocking.durable;

import io.cloudevents.CloudEvent;
import org.occurrent.subscription.StartAt;
import org.occurrent.subscription.SubscriptionFilter;
import org.occurrent.subscription.SubscriptionPosition;
import org.occurrent.subscription.api.blocking.*;

import javax.annotation.PreDestroy;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;
import static org.occurrent.subscription.PositionAwareCloudEvent.getSubscriptionPositionOrThrowIAE;
import static org.occurrent.subscription.util.predicate.EveryN.everyEvent;

/**
 * Combines  a {@link SubscriptionModel} and with a {@link SubscriptionPositionStorage} to automatically persist
 * the subscription position after each successful call to the "action" method
 * (i.e. when the consumer in this method {@link DurableSubscriptionModel#subscribe(String, Consumer)} has completed successfully),
 * thus making the subscription durable.
 *
 * <p>
 * Note that this implementation stores the subscription position after _every_ action. If you have a lot of events and duplication is not
 * that much of a deal, consider changing this behavior by supplying an instance of {@link DurableSubscriptionModelConfig}.
 */
public class DurableSubscriptionModel implements PositionAwareSubscriptionModel, DelegatingSubscriptionModel, SubscriptionModelShutdown, SubscriptionModelCancelSubscription {

    private final PositionAwareSubscriptionModel subscriptionModel;
    private final SubscriptionPositionStorage storage;
    private final DurableSubscriptionModelConfig config;

    /**
     * Create a subscription that combines a {@link PositionAwareSubscriptionModel} with a {@link SubscriptionPositionStorage} to automatically
     * store the subscription after each successful call to <code>action</code> (The "consumer" in {@link #subscribe(String, Consumer)}).
     *
     * @param subscriptionModel The subscription that will read events from the event store
     * @param storage           The {@link SubscriptionPositionStorage} that'll be used to persist the stream position
     */
    public DurableSubscriptionModel(PositionAwareSubscriptionModel subscriptionModel, SubscriptionPositionStorage storage) {
        this(subscriptionModel, storage, new DurableSubscriptionModelConfig(everyEvent()));
    }

    /**
     * Create a subscription that combines a {@link PositionAwareSubscriptionModel} with a {@link SubscriptionPositionStorage} to automatically
     * store the subscription when the predicate defined in {@link DurableSubscriptionModelConfig#persistCloudEventPositionPredicate} is fulfilled.
     *
     * @param subscriptionModel The subscription that will read events from the event store
     * @param storage           The {@link SubscriptionPositionStorage} that'll be used to persist the stream position
     */
    public DurableSubscriptionModel(PositionAwareSubscriptionModel subscriptionModel, SubscriptionPositionStorage storage,
                                    DurableSubscriptionModelConfig config) {
        requireNonNull(subscriptionModel, "subscription cannot be null");
        requireNonNull(storage, SubscriptionPositionStorage.class.getSimpleName() + " cannot be null");
        requireNonNull(config, DurableSubscriptionModelConfig.class.getSimpleName() + " cannot be null");

        this.storage = storage;
        this.subscriptionModel = subscriptionModel;
        this.config = config;
    }

    @Override
    public Subscription subscribe(String subscriptionId, SubscriptionFilter filter, Supplier<StartAt> startAtSupplier, Consumer<CloudEvent> action) {
        return subscriptionModel.subscribe(subscriptionId, filter, startAtSupplier, cloudEvent -> {
                    action.accept(cloudEvent);
                    if (config.persistCloudEventPositionPredicate.test(cloudEvent)) {
                        SubscriptionPosition subscriptionPosition = getSubscriptionPositionOrThrowIAE(cloudEvent);
                        storage.save(subscriptionId, subscriptionPosition);
                    }
                }
        );
    }

    @Override
    public Subscription subscribe(String subscriptionId, Consumer<CloudEvent> action) {
        return subscribe(subscriptionId, (SubscriptionFilter) null, action);
    }

    /**
     * Start listening to cloud events persisted to the event store.
     *
     * @param subscriptionId The id of the subscription, must be unique!
     * @param filter         The filter to apply for this subscription. Only events matching the filter will cause the <code>action</code> to be called.
     * @param action         This action will be invoked for each cloud event that is stored in the EventStore that matches the supplied <code>filter</code>.
     */
    @Override
    public Subscription subscribe(String subscriptionId, SubscriptionFilter filter, Consumer<CloudEvent> action) {
        Supplier<StartAt> startAtSupplier = () -> {
            // It's important that we find the document inside the supplier so that we lookup the latest resume token on retry
            SubscriptionPosition subscriptionPosition = storage.read(subscriptionId);
            if (subscriptionPosition == null) {
                subscriptionPosition = storage.save(subscriptionId, subscriptionModel.globalSubscriptionPosition());
            }
            return StartAt.subscriptionPosition(subscriptionPosition);
        };
        return subscribe(subscriptionId, filter, startAtSupplier, action);
    }

    /**
     * Cancel a subscription. This means that it'll no longer receive events as they are persisted to the event store.
     * The subscription position that is persisted in the {@link SubscriptionPositionStorage} will also be removed.
     *
     * @param subscriptionId The subscription id to cancel
     */
    @Override
    public void cancelSubscription(String subscriptionId) {
        if ((subscriptionModel instanceof SubscriptionModelCancelSubscription)) {
            ((SubscriptionModelCancelSubscription) subscriptionModel).cancelSubscription(subscriptionId);
        }
        storage.delete(subscriptionId);
    }

    @Override
    @PreDestroy
    public void shutdown() {
        if (subscriptionModel instanceof SubscriptionModelShutdown) {
            ((SubscriptionModelShutdown) subscriptionModel).shutdown();
        }
    }

    @Override
    public SubscriptionPosition globalSubscriptionPosition() {
        return subscriptionModel.globalSubscriptionPosition();
    }

    @Override
    public PositionAwareSubscriptionModel getDelegatedSubscriptionModel() {
        return subscriptionModel;
    }
}