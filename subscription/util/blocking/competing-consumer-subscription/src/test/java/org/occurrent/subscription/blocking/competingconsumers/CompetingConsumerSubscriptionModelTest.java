package org.occurrent.subscription.blocking.competingconsumers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator.ReplaceUnderscores;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.occurrent.domain.DomainEvent;
import org.occurrent.domain.NameDefined;
import org.occurrent.domain.NameWasChanged;
import org.occurrent.eventstore.api.blocking.EventStore;
import org.occurrent.eventstore.mongodb.spring.blocking.EventStoreConfig;
import org.occurrent.eventstore.mongodb.spring.blocking.SpringMongoEventStore;
import org.occurrent.mongodb.timerepresentation.TimeRepresentation;
import org.occurrent.subscription.api.blocking.CompetingConsumerStrategy;
import org.occurrent.subscription.blocking.durable.DurableSubscriptionModel;
import org.occurrent.subscription.mongodb.spring.blocking.MongoLeaseCompetingConsumerStrategy;
import org.occurrent.subscription.mongodb.spring.blocking.SpringMongoSubscriptionModel;
import org.occurrent.subscription.mongodb.spring.blocking.SpringMongoSubscriptionPositionStorage;
import org.occurrent.testsupport.mongodb.FlushMongoDBExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static java.time.ZoneOffset.UTC;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.occurrent.functional.CheckedFunction.unchecked;
import static org.occurrent.time.TimeConversion.toLocalDateTime;

@Testcontainers
@DisplayNameGeneration(ReplaceUnderscores.class)
class CompetingConsumerSubscriptionModelTest {
    private static final Logger log = LoggerFactory.getLogger(CompetingConsumerSubscriptionModelTest.class);

    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:4.2.8");

    @RegisterExtension
    FlushMongoDBExtension flushMongoDBExtension = new FlushMongoDBExtension(new ConnectionString(mongoDBContainer.getReplicaSetUrl()));

    private EventStore eventStore;
    private CompetingConsumerSubscriptionModel competingConsumerSubscriptionModel1;
    private CompetingConsumerSubscriptionModel competingConsumerSubscriptionModel2;
    private DurableSubscriptionModel springSubscriptionModel1;
    private DurableSubscriptionModel springSubscriptionModel2;
    private ObjectMapper objectMapper;
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void create_mongo_event_store() {
        ConnectionString connectionString = new ConnectionString(mongoDBContainer.getReplicaSetUrl() + ".events");
        log.info("Connecting to MongoDB at {}", connectionString);
        MongoClient mongoClient = MongoClients.create(connectionString);
        mongoTemplate = new MongoTemplate(mongoClient, requireNonNull(connectionString.getDatabase()));
        MongoTransactionManager mongoTransactionManager = new MongoTransactionManager(new SimpleMongoClientDatabaseFactory(mongoClient, requireNonNull(connectionString.getDatabase())));
        TimeRepresentation timeRepresentation = TimeRepresentation.RFC_3339_STRING;
        EventStoreConfig eventStoreConfig = new EventStoreConfig.Builder().eventStoreCollectionName(connectionString.getCollection()).transactionConfig(mongoTransactionManager).timeRepresentation(timeRepresentation).build();
        eventStore = new SpringMongoEventStore(mongoTemplate, eventStoreConfig);
        SpringMongoSubscriptionPositionStorage positionStorage = new SpringMongoSubscriptionPositionStorage(mongoTemplate, "positions");
        springSubscriptionModel1 = new DurableSubscriptionModel(new SpringMongoSubscriptionModel(mongoTemplate, connectionString.getCollection(), timeRepresentation), positionStorage);
        springSubscriptionModel2 = new DurableSubscriptionModel(new SpringMongoSubscriptionModel(mongoTemplate, connectionString.getCollection(), timeRepresentation), positionStorage);
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void shutdown() {
        competingConsumerSubscriptionModel1.shutdown();
        competingConsumerSubscriptionModel2.shutdown();
        springSubscriptionModel1.shutdown();
        springSubscriptionModel2.shutdown();
    }

    @Test
    void only_one_consumer_receives_event_when_starting() throws InterruptedException {
        // Given
        CopyOnWriteArrayList<CloudEvent> cloudEvents = new CopyOnWriteArrayList<>();

        competingConsumerSubscriptionModel1 = new CompetingConsumerSubscriptionModel(springSubscriptionModel1, loggingStrategy("1", mongoTemplate));
        competingConsumerSubscriptionModel2 = new CompetingConsumerSubscriptionModel(springSubscriptionModel2, loggingStrategy("2", mongoTemplate));

        String subscriptionId = UUID.randomUUID().toString();
        competingConsumerSubscriptionModel1.subscribe(subscriptionId, cloudEvents::add).waitUntilStarted();
        competingConsumerSubscriptionModel2.subscribe(subscriptionId, cloudEvents::add).waitUntilStarted();

        NameDefined nameDefined = new NameDefined("eventId", LocalDateTime.of(2021, 2, 26, 14, 15, 16), "my name");

        // When
        eventStore.write("streamId", serialize(nameDefined));

        // Then
        Thread.sleep(1000);
        assertThat(cloudEvents).hasSize(1);
    }

    @Test
    void another_consumer_takes_over_when_subscription_is_cancelled_for_first_subscription_model() {
        // Given
        CopyOnWriteArrayList<CloudEvent> cloudEvents = new CopyOnWriteArrayList<>();

        competingConsumerSubscriptionModel1 = new CompetingConsumerSubscriptionModel(springSubscriptionModel1, loggingStrategy("1", new MongoLeaseCompetingConsumerStrategy.Builder(mongoTemplate).leaseTime(Duration.ofSeconds(1)).build()));
        competingConsumerSubscriptionModel2 = new CompetingConsumerSubscriptionModel(springSubscriptionModel2, loggingStrategy("2", new MongoLeaseCompetingConsumerStrategy.Builder(mongoTemplate).leaseTime(Duration.ofSeconds(1)).build()));

        String subscriptionId = UUID.randomUUID().toString();
        competingConsumerSubscriptionModel1.subscribe(subscriptionId, cloudEvents::add).waitUntilStarted();
        competingConsumerSubscriptionModel2.subscribe(subscriptionId, cloudEvents::add).waitUntilStarted();

        NameDefined nameDefined = new NameDefined("eventId1", LocalDateTime.of(2021, 2, 26, 14, 15, 16), "my name");
        NameWasChanged nameWasChanged = new NameWasChanged("eventId2", LocalDateTime.of(2021, 2, 26, 14, 15, 16), "my name");

        // When
        eventStore.write("streamId", serialize(nameDefined));
        await("waiting for first event").atMost(2, SECONDS).untilAsserted(() -> assertThat(cloudEvents).hasSize(1));

        competingConsumerSubscriptionModel1.pauseSubscription(subscriptionId);

        System.out.println("### WRITING EVENT 2");
        eventStore.write("streamId", serialize(nameWasChanged));

        // Then
        await("waiting for second event").atMost(5, SECONDS).untilAsserted(() -> assertThat(cloudEvents).hasSize(2));
    }


    @SuppressWarnings("ConstantConditions")
    private DomainEvent deserialize(CloudEvent e) {
        try {
            return (DomainEvent) objectMapper.readValue(e.getData().toBytes(), Class.forName(e.getType()));
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

    private static MongoLeaseCompetingConsumerStrategy loggingStrategy(String name, MongoTemplate mongoTemplate) {
        return loggingStrategy(name, MongoLeaseCompetingConsumerStrategy.withDefaults(mongoTemplate));
    }

    private static MongoLeaseCompetingConsumerStrategy loggingStrategy(String name, MongoLeaseCompetingConsumerStrategy strategy) {
        strategy.addListener(new CompetingConsumerStrategy.CompetingConsumerListener() {
            @Override
            public void onConsumeGranted(String subscriptionId, String subscriberId) {
                log.info("[{}] Consuming granted for subscription {} (subscriber={})", name, subscriptionId, subscriberId);
            }

            @Override
            public void onConsumeProhibited(String subscriptionId, String subscriberId) {
                log.info("[{}] Consuming prohibited for subscription {} (subscriber={})", name, subscriptionId, subscriberId);
            }
        });
        return strategy;
    }
}