package se.haleby.occurrent.changestreamer.mongodb.spring.reactor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.ConnectionString;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.github.artsok.RepeatedIfExceptionsTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.SimpleReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.transaction.ReactiveTransactionManager;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import se.haleby.occurrent.changestreamer.api.reactor.PositionAwareReactorChangeStreamer;
import se.haleby.occurrent.domain.DomainEvent;
import se.haleby.occurrent.domain.NameDefined;
import se.haleby.occurrent.domain.NameWasChanged;
import se.haleby.occurrent.eventstore.api.reactor.EventStore;
import se.haleby.occurrent.eventstore.mongodb.TimeRepresentation;
import se.haleby.occurrent.eventstore.mongodb.spring.reactor.EventStoreConfig;
import se.haleby.occurrent.eventstore.mongodb.spring.reactor.SpringReactorMongoEventStore;
import se.haleby.occurrent.testsupport.mongodb.FlushMongoDBExtension;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static java.time.ZoneOffset.UTC;
import static java.time.temporal.ChronoUnit.MILLIS;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.awaitility.Durations.ONE_SECOND;
import static org.awaitility.Durations.TWO_SECONDS;
import static org.hamcrest.Matchers.equalTo;
import static se.haleby.occurrent.functional.CheckedFunction.unchecked;
import static se.haleby.occurrent.functional.Not.not;
import static se.haleby.occurrent.time.TimeConversion.toLocalDateTime;

@Testcontainers
public class SpringReactorChangeStreamerWithPositionPersistenceForMongoDBTest {

    @Container
    private static final MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:4.2.8");
    private static final String RESUME_TOKEN_COLLECTION = "ack";

    private EventStore mongoEventStore;
    private SpringReactorChangeStreamerWithPositionPersistenceForMongoDB changeStreamer;
    private ObjectMapper objectMapper;
    private ReactiveMongoTemplate reactiveMongoTemplate;
    private CopyOnWriteArrayList<Disposable> disposables;

    @RegisterExtension
    FlushMongoDBExtension flushMongoDBExtension = new FlushMongoDBExtension(new ConnectionString(mongoDBContainer.getReplicaSetUrl()));
    private MongoClient mongoClient;

    @BeforeEach
    void create_mongo_event_store() {
        ConnectionString connectionString = new ConnectionString(mongoDBContainer.getReplicaSetUrl() + ".events");
        TimeRepresentation timeRepresentation = TimeRepresentation.RFC_3339_STRING;
        mongoClient = MongoClients.create(connectionString);
        reactiveMongoTemplate = new ReactiveMongoTemplate(MongoClients.create(connectionString), Objects.requireNonNull(connectionString.getDatabase()));
        ReactiveTransactionManager reactiveMongoTransactionManager = new ReactiveMongoTransactionManager(new SimpleReactiveMongoDatabaseFactory(mongoClient, requireNonNull(connectionString.getDatabase())));
        EventStoreConfig eventStoreConfig = new EventStoreConfig.Builder().eventStoreCollectionName("events").transactionConfig(reactiveMongoTransactionManager).timeRepresentation(TimeRepresentation.RFC_3339_STRING).build();
        mongoEventStore = new SpringReactorMongoEventStore(reactiveMongoTemplate, eventStoreConfig);
        PositionAwareReactorChangeStreamer springReactiveChangeStreamerForMongoDB = new SpringReactorChangeStreamerForMongoDB(reactiveMongoTemplate, "events", timeRepresentation);
        changeStreamer = new SpringReactorChangeStreamerWithPositionPersistenceForMongoDB(springReactiveChangeStreamerForMongoDB, reactiveMongoTemplate, RESUME_TOKEN_COLLECTION);
        objectMapper = new ObjectMapper();
        disposables = new CopyOnWriteArrayList<>();
    }

    @AfterEach
    void dispose() {
        disposables.forEach(Disposable::dispose);
        mongoClient.close();
    }

    @Test
    void reactive_persistent_spring_change_streamer_calls_action_for_each_new_event() throws InterruptedException {
        // Given
        LocalDateTime now = LocalDateTime.now();
        CopyOnWriteArrayList<CloudEvent> state = new CopyOnWriteArrayList<>();
        disposeAfterTest(changeStreamer.stream("test", cloudEvent -> Mono.fromRunnable(() -> state.add(cloudEvent))).subscribe());
        Thread.sleep(200);
        NameDefined nameDefined1 = new NameDefined(UUID.randomUUID().toString(), now, "name1");
        NameDefined nameDefined2 = new NameDefined(UUID.randomUUID().toString(), now.plusSeconds(2), "name2");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(10), "name3");

        // When
        mongoEventStore.write("1", 0, serialize(nameDefined1)).block();
        mongoEventStore.write("2", 0, serialize(nameDefined2)).block();
        mongoEventStore.write("1", 1, serialize(nameWasChanged1)).block();

        // Then
        await().with().pollInterval(Duration.of(20, MILLIS)).untilAsserted(() -> assertThat(state).hasSize(3));
    }

    @Test
    void reactive_persistent_spring_change_streamer_allows_resuming_events_from_where_it_left_off() throws Exception {
        // Given
        LocalDateTime now = LocalDateTime.now();
        CopyOnWriteArrayList<CloudEvent> state = new CopyOnWriteArrayList<>();
        String subscriberId = UUID.randomUUID().toString();
        Function<CloudEvent, Mono<Void>> function = cloudEvents -> Mono.fromRunnable(() -> state.add(cloudEvents));
        Disposable subscription1 = disposeAfterTest(changeStreamer.stream(subscriberId, function).subscribe());
        Thread.sleep(200);
        NameDefined nameDefined1 = new NameDefined(UUID.randomUUID().toString(), now, "name1");
        NameDefined nameDefined2 = new NameDefined(UUID.randomUUID().toString(), now.plusSeconds(2), "name2");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(10), "name3");

        // When
        mongoEventStore.write("1", 0, serialize(nameDefined1)).block();
        // The change streamer is async so we need to wait for it
        await().atMost(ONE_SECOND).until(not(state::isEmpty));
        subscription1.dispose();
        mongoEventStore.write("2", 0, serialize(nameDefined2)).block();
        mongoEventStore.write("1", 1, serialize(nameWasChanged1)).block();
        disposeAfterTest(changeStreamer.stream(subscriberId, function).subscribe());

        // Then
        await().atMost(TWO_SECONDS).with().pollInterval(Duration.of(20, MILLIS)).untilAsserted(() -> assertThat(state).hasSize(3));
    }

    @Test
    void reactive_persistent_spring_change_streamer_allows_resuming_events_from_where_it_left_when_first_event_for_change_streamer_fails_the_first_time() {
        // Given
        LocalDateTime now = LocalDateTime.now();

        AtomicInteger counter = new AtomicInteger();
        CopyOnWriteArrayList<CloudEvent> state = new CopyOnWriteArrayList<>();
        String subscriberId = UUID.randomUUID().toString();
        Runnable stream = () -> changeStreamer.stream(subscriberId, cloudEvent -> {
            if (counter.incrementAndGet() == 1) {
                // We simulate error on first event
                return Mono.error(new IllegalArgumentException("Expected"));
            } else {
                state.add(cloudEvent);
                return Mono.empty();
            }
        }).subscribe();
        stream.run();
        NameDefined nameDefined1 = new NameDefined(UUID.randomUUID().toString(), now, "name1");
        NameDefined nameDefined2 = new NameDefined(UUID.randomUUID().toString(), now.plusSeconds(2), "name2");
        NameWasChanged nameWasChanged1 = new NameWasChanged(UUID.randomUUID().toString(), now.plusSeconds(10), "name3");

        // When
        mongoEventStore.write("1", 0, serialize(nameDefined1)).block();
        // The change streamer is async so we need to wait for it
        await().atMost(ONE_SECOND).and().dontCatchUncaughtExceptions().untilAtomic(counter, equalTo(1));
        // Since an exception occurred we need to run the stream again
        stream.run();
        mongoEventStore.write("2", 0, serialize(nameDefined2)).block();
        mongoEventStore.write("1", 1, serialize(nameWasChanged1)).block();

        // Then
        await().atMost(2, SECONDS).with().pollInterval(Duration.of(20, MILLIS)).untilAsserted(() -> assertThat(state).hasSize(3));
    }

    @RepeatedIfExceptionsTest(repeats = 2)
    void reactive_persistent_spring_change_streamer_allows_cancelling_subscription() throws InterruptedException {
        // Given
        LocalDateTime now = LocalDateTime.now();
        CopyOnWriteArrayList<CloudEvent> state = new CopyOnWriteArrayList<>();
        String subscriberId = UUID.randomUUID().toString();
        disposeAfterTest(changeStreamer.stream(subscriberId, cloudEvents -> Mono.fromRunnable(() -> state.add(cloudEvents))).subscribe());
        Thread.sleep(200);
        NameDefined nameDefined1 = new NameDefined(UUID.randomUUID().toString(), now, "name1");

        // When
        mongoEventStore.write("1", 0, serialize(nameDefined1)).block();
        // The change streamer is async so we need to wait for it
        await().atMost(ONE_SECOND).until(not(state::isEmpty));

        changeStreamer.cancelSubscription(subscriberId).block();

        // Then
        assertThat(reactiveMongoTemplate.count(new Query(), RESUME_TOKEN_COLLECTION).block()).isZero();
    }

    private Flux<CloudEvent> serialize(DomainEvent e) {
        return Flux.just(CloudEventBuilder.v1()
                .withId(UUID.randomUUID().toString())
                .withSource(URI.create("http://name"))
                .withType(e.getClass().getSimpleName())
                .withTime(toLocalDateTime(e.getTimestamp()).atZone(UTC))
                .withSubject(e.getName())
                .withDataContentType("application/json")
                .withData(unchecked(objectMapper::writeValueAsBytes).apply(e))
                .build());
    }

    private Disposable disposeAfterTest(Disposable disposable) {
        disposables.add(disposable);
        return disposable;
    }
}