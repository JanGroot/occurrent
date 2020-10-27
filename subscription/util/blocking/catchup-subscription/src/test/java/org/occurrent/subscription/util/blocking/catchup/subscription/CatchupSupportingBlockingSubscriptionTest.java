/*
 * Copyright 2020 Johan Haleby
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

package org.occurrent.subscription.util.blocking.catchup.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.occurrent.domain.DomainEvent;
import org.occurrent.domain.NameDefined;
import org.occurrent.domain.NameWasChanged;
import org.occurrent.eventstore.mongodb.nativedriver.EventStoreConfig;
import org.occurrent.eventstore.mongodb.nativedriver.MongoEventStore;
import org.occurrent.mongodb.timerepresentation.TimeRepresentation;
import org.occurrent.subscription.StartAt;
import org.occurrent.subscription.SubscriptionPosition;
import org.occurrent.subscription.mongodb.nativedriver.blocking.BlockingSubscriptionForMongoDB;
import org.occurrent.subscription.mongodb.nativedriver.blocking.BlockingSubscriptionPositionStorageForMongoDB;
import org.occurrent.subscription.mongodb.nativedriver.blocking.RetryStrategy;
import org.occurrent.subscription.util.blocking.catchup.subscription.CatchupPositionPersistenceConfig.PersistSubscriptionPositionDuringCatchupPhase;
import org.occurrent.testsupport.mongodb.FlushMongoDBExtension;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.time.ZoneOffset.UTC;
import static java.time.temporal.ChronoUnit.MILLIS;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.awaitility.Durations.FIVE_SECONDS;
import static org.occurrent.filter.Filter.type;
import static org.occurrent.functional.CheckedFunction.unchecked;
import static org.occurrent.subscription.OccurrentSubscriptionFilter.filter;
import static org.occurrent.time.TimeConversion.toLocalDateTime;

@Testcontainers
@Timeout(15000)
public class CatchupSupportingBlockingSubscriptionTest {

    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:4.2.8");

    @RegisterExtension
    FlushMongoDBExtension flushMongoDBExtension = new FlushMongoDBExtension(new ConnectionString(mongoDBContainer.getReplicaSetUrl()));

    private MongoEventStore mongoEventStore;
    private CatchupSupportingBlockingSubscription subscription;
    private ObjectMapper objectMapper;
    private MongoClient mongoClient;
    private ExecutorService subscriptionExecutor;
    private MongoDatabase database;
    private MongoCollection<Document> eventCollection;
    private BlockingSubscriptionPositionStorageForMongoDB storage;

    @BeforeEach
    void create_mongo_event_store() {
        ConnectionString connectionString = new ConnectionString(mongoDBContainer.getReplicaSetUrl() + ".events");
        mongoClient = MongoClients.create(connectionString);
        TimeRepresentation timeRepresentation = TimeRepresentation.DATE;
        EventStoreConfig config = new EventStoreConfig(timeRepresentation);
        database = mongoClient.getDatabase(requireNonNull(connectionString.getDatabase()));
        eventCollection = database.getCollection(requireNonNull(connectionString.getCollection()));
        mongoEventStore = new MongoEventStore(mongoClient, connectionString.getDatabase(), connectionString.getCollection(), config);
        subscriptionExecutor = Executors.newCachedThreadPool();
        storage = new BlockingSubscriptionPositionStorageForMongoDB(database, "storage");
        subscription = newCatchupSubscription(database, eventCollection, timeRepresentation, new CatchupSupportingBlockingSubscriptionConfig(100, new PersistSubscriptionPositionDuringCatchupPhase(storage, 1)));
        objectMapper = new ObjectMapper();
    }


    @AfterEach
    void shutdown() throws InterruptedException {
        subscription.shutdown();
        subscriptionExecutor.shutdown();
        subscriptionExecutor.awaitTermination(10, SECONDS);
        mongoClient.close();
    }

    @Test
    void catchup_subscription_reads_historic_events() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        NameDefined nameDefined1 = new NameDefined(UUID.randomUUID().toString(), now, "name1");
        NameDefined nameDefined2 = new NameDefined(UUID.randomUUID().toString(), now.plusSeconds(2), "name2");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(10), "name3");

        mongoEventStore.write("1", 0, serialize(nameDefined1));
        mongoEventStore.write("2", 0, serialize(nameDefined2));
        mongoEventStore.write("1", 1, serialize(nameWasChanged1));

        CopyOnWriteArrayList<CloudEvent> state = new CopyOnWriteArrayList<>();

        // When
        subscription.subscribe(UUID.randomUUID().toString(), StartAt.subscriptionPosition(TimeBasedSubscriptionPosition.beginningOfTime()), state::add).waitUntilStarted();

        // Then
        await().atMost(FIVE_SECONDS).with().pollInterval(Duration.of(20, MILLIS)).untilAsserted(() -> assertThat(state).hasSize(3));
    }

    @Test
    void catchup_subscription_reads_historic_events_with_filter() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        NameDefined nameDefined1 = new NameDefined(UUID.randomUUID().toString(), now, "name1");
        NameDefined nameDefined2 = new NameDefined(UUID.randomUUID().toString(), now.plusSeconds(2), "name2");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(10), "name3");

        mongoEventStore.write("1", 0, serialize(nameDefined1));
        mongoEventStore.write("2", 0, serialize(nameDefined2));
        mongoEventStore.write("1", 1, serialize(nameWasChanged1));

        CopyOnWriteArrayList<CloudEvent> state = new CopyOnWriteArrayList<>();

        // When
        subscription.subscribe(UUID.randomUUID().toString(), filter(type(NameDefined.class.getName())), StartAt.subscriptionPosition(TimeBasedSubscriptionPosition.beginningOfTime()), state::add).waitUntilStarted();

        // Then
        await().atMost(FIVE_SECONDS).with().pollInterval(Duration.of(20, MILLIS)).untilAsserted(() -> {
            assertThat(state).hasSize(2);
            assertThat(state).extracting(CloudEvent::getType).containsOnly(NameDefined.class.getName());
        });
    }

    @Test
    void catchup_subscription_reads_historic_events_and_then_switches_to_new_events() throws InterruptedException {
        // Given
        LocalDateTime now = LocalDateTime.now();
        NameDefined nameDefined1 = new NameDefined(UUID.randomUUID().toString(), now, "name1");
        NameDefined nameDefined2 = new NameDefined(UUID.randomUUID().toString(), now.plusSeconds(2), "name2");
        NameDefined nameDefined3 = new NameDefined(UUID.randomUUID().toString(), now.plusSeconds(6), "name5");
        NameDefined nameDefined4 = new NameDefined(UUID.randomUUID().toString(), now.plusSeconds(7), "name6");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(10), "name3");

        mongoEventStore.write("1", 0, serialize(nameDefined1));
        mongoEventStore.write("2", 0, serialize(nameDefined2));
        mongoEventStore.write("1", 1, serialize(nameWasChanged1));

        CopyOnWriteArrayList<CloudEvent> state = new CopyOnWriteArrayList<>();

        CountDownLatch writeFirstEvent = new CountDownLatch(1);
        CountDownLatch writeSecondEvent = new CountDownLatch(1);

        Thread thread = new Thread(() -> {
            awaitLatch(writeFirstEvent);
            mongoEventStore.write("3", 0, serialize(nameDefined3));

            awaitLatch(writeSecondEvent);
            mongoEventStore.write("4", 0, serialize(nameDefined4));
        });
        thread.start();

        // When
        subscription.subscribe(UUID.randomUUID().toString(), StartAt.subscriptionPosition(TimeBasedSubscriptionPosition.beginningOfTime()), e -> {
            state.add(e);
            switch (state.size()) {
                case 2:
                    writeFirstEvent.countDown();
                    break;
                case 3:
                    writeSecondEvent.countDown();
                    break;
            }
        }).waitUntilStarted();

        // Then
        await().atMost(FIVE_SECONDS).with().pollInterval(Duration.of(100, MILLIS)).untilAsserted(() ->
                assertThat(state).hasSize(5).extracting(this::deserialize).containsExactly(nameDefined1, nameDefined2, nameWasChanged1, nameDefined3, nameDefined4));

        thread.join();
    }

    @Test
    void catchup_subscription_continues_where_it_left_off() {
        // Given
        LocalDateTime now = LocalDateTime.now();
        NameDefined nameDefined1 = new NameDefined(UUID.randomUUID().toString(), now, "name1");
        NameDefined nameDefined2 = new NameDefined(UUID.randomUUID().toString(), now.plusSeconds(2), "name2");
        NameDefined nameDefined3 = new NameDefined(UUID.randomUUID().toString(), now.plusSeconds(6), "name5");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(10), "name3");

        mongoEventStore.write("1", 0, serialize(nameDefined1));
        mongoEventStore.write("2", 0, serialize(nameDefined2));
        mongoEventStore.write("1", 1, serialize(nameWasChanged1));
        mongoEventStore.write("3", 0, serialize(nameDefined3));

        CountDownLatch cancelSubscriberLatch = new CountDownLatch(1);
        CountDownLatch waitUntilCancelled = new CountDownLatch(1);
        CountDownLatch waitUntilSecondEventProcessed = new CountDownLatch(1);
        CopyOnWriteArrayList<CloudEvent> state = new CopyOnWriteArrayList<>();

        String subscriptionId = UUID.randomUUID().toString();
        new Thread(() -> {
            awaitLatch(cancelSubscriberLatch);
            subscription.shutdown();
            waitUntilCancelled.countDown();
        }).start();

        // When

        subscription.subscribe(subscriptionId, StartAt.subscriptionPosition(TimeBasedSubscriptionPosition.beginningOfTime()), e -> {
            if (state.size() < 2) {
                state.add(e);
            }

            if (state.size() == 2) {
                cancelSubscriberLatch.countDown();
                awaitLatch(waitUntilCancelled);
                waitUntilSecondEventProcessed.countDown();
            }
        }).waitUntilStarted();

        awaitLatch(waitUntilSecondEventProcessed);
        subscription = newCatchupSubscription(database, eventCollection, TimeRepresentation.DATE, new CatchupSupportingBlockingSubscriptionConfig(100, new PersistSubscriptionPositionDuringCatchupPhase(storage, 1)));
        subscription.subscribe(subscriptionId, state::add).waitUntilStarted();

        // Then
        await().atMost(FIVE_SECONDS).with().pollInterval(Duration.of(100, MILLIS)).untilAsserted(() ->
                assertThat(state).hasSize(4).extracting(this::deserialize).containsExactly(nameDefined1, nameDefined2, nameDefined3, nameWasChanged1));
    }

    @Test
    void catchup_subscription_can_be_configured_to_only_store_the_position_of_every_tenth_event() {
        // Given
        LocalDateTime now = LocalDateTime.now();

        for (int i = 0; i < 100; i++) {
            mongoEventStore.write(String.valueOf(i), 0, serialize(new NameDefined(UUID.randomUUID().toString(), now.plusMinutes(i), "name" + i)));
        }

        AtomicInteger numberOfSavedPositions = new AtomicInteger();
        storage = new BlockingSubscriptionPositionStorageForMongoDB(database, "storage") {
            @Override
            public SubscriptionPosition save(String subscriptionId, SubscriptionPosition subscriptionPosition) {
                numberOfSavedPositions.incrementAndGet();
                return super.save(subscriptionId, subscriptionPosition);
            }
        };

        subscription = newCatchupSubscription(database, eventCollection, TimeRepresentation.DATE, new CatchupSupportingBlockingSubscriptionConfig(100, new PersistSubscriptionPositionDuringCatchupPhase(this.storage, 10)));

        CopyOnWriteArrayList<CloudEvent> state = new CopyOnWriteArrayList<>();

        // When
        String subscriptionId = UUID.randomUUID().toString();
        subscription.subscribe(subscriptionId, StartAt.subscriptionPosition(TimeBasedSubscriptionPosition.beginningOfTime()), state::add).waitUntilStarted();

        // Then
        await().atMost(FIVE_SECONDS).with().pollInterval(Duration.of(20, MILLIS)).untilAsserted(() -> assertThat(state).hasSize(100));
        assertThat(numberOfSavedPositions).hasValue(10); // Store every 10th position equals 10 for 100 events
        assertThat(storage.read(subscriptionId)).isNotNull();
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private DomainEvent deserialize(CloudEvent e) {
        try {
            return (DomainEvent) objectMapper.readValue(e.getData(), Class.forName(e.getType()));
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private Stream<CloudEvent> serialize(DomainEvent e) {
        return Stream.of(CloudEventBuilder.v1()
                .withId(e.getEventId())
                .withSource(URI.create("http://name"))
                .withType(e.getClass().getName())
                .withTime(toLocalDateTime(e.getTimestamp()).atOffset(UTC))
                .withSubject(e.getName())
                .withDataContentType("application/json")
                .withData(unchecked(objectMapper::writeValueAsBytes).apply(e))
                .build());
    }

    private CatchupSupportingBlockingSubscription newCatchupSubscription(MongoDatabase database, MongoCollection<Document> eventCollection, TimeRepresentation timeRepresentation, CatchupSupportingBlockingSubscriptionConfig config) {
        BlockingSubscriptionForMongoDB blockingSubscriptionForMongoDB = new BlockingSubscriptionForMongoDB(database, eventCollection, timeRepresentation, subscriptionExecutor, RetryStrategy.none());
        return new CatchupSupportingBlockingSubscription(blockingSubscriptionForMongoDB, mongoEventStore, config);
    }
}