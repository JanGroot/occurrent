package se.haleby.occurrent.example.domain.numberguessinggame.mongodb.spring.blocking.policy;

import com.mongodb.client.model.Filters;
import io.cloudevents.CloudEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import se.haleby.occurrent.changestreamer.mongodb.spring.blocking.SpringBlockingChangeStreamerWithPositionPersistenceForMongoDB;
import se.haleby.occurrent.eventstore.api.blocking.EventStoreQueries;
import se.haleby.occurrent.example.domain.numberguessinggame.model.domainevents.*;
import se.haleby.occurrent.example.domain.numberguessinggame.mongodb.spring.blocking.infrastructure.Serialization;
import se.haleby.occurrent.example.domain.numberguessinggame.mongodb.spring.blocking.policy.NumberGuessingGameCompleted.GuessedNumber;

import javax.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;

import static java.time.ZoneOffset.UTC;
import static se.haleby.occurrent.changestreamer.mongodb.MongoDBFilterSpecification.BsonMongoDBFilterSpecification.filter;
import static se.haleby.occurrent.eventstore.api.Condition.eq;
import static se.haleby.occurrent.eventstore.api.Filter.subject;

@Component
class WhenGameEndedThenPublishIntegrationEvent {
    private static final Logger log = LoggerFactory.getLogger(WhenGameEndedThenPublishIntegrationEvent.class);

    private final EventStoreQueries eventStoreQueries;
    private final Serialization serialization;
    private final SpringBlockingChangeStreamerWithPositionPersistenceForMongoDB streamer;
    private final RabbitTemplate rabbitTemplate;
    private final TopicExchange numberGuessingGameTopic;

    WhenGameEndedThenPublishIntegrationEvent(EventStoreQueries eventStoreQueries, Serialization serialization,
                                             SpringBlockingChangeStreamerWithPositionPersistenceForMongoDB streamer,
                                             RabbitTemplate rabbitTemplate, TopicExchange numberGuessingGameTopic) {
        this.eventStoreQueries = eventStoreQueries;
        this.serialization = serialization;
        this.streamer = streamer;
        this.rabbitTemplate = rabbitTemplate;
        this.numberGuessingGameTopic = numberGuessingGameTopic;
    }

    @PostConstruct
    void subscribeToChangeStream() throws InterruptedException {
        streamer.stream(WhenGameEndedThenPublishIntegrationEvent.class.getSimpleName(), this::publishIntegrationEventWhenGameEnded,
                // We're only interested in events of type NumberGuessingGameEnded since then we know that
                // we should publish the integration event
                filter().type(Filters::eq, NumberGuessingGameEnded.class.getSimpleName()))
                .waitUntilStarted(Duration.ofSeconds(4));
    }


    private void publishIntegrationEventWhenGameEnded(CloudEvent cloudEvent) {
        String gameId = cloudEvent.getSubject();
        NumberGuessingGameCompleted numberGuessingGameCompleted = eventStoreQueries.query(subject(eq(gameId)))
                .map(serialization::deserialize)
                .collect(NumberGuessingGameCompleted::new, (integrationEvent, gameEvent) -> {
                    if (gameEvent instanceof NumberGuessingGameWasStarted) {
                        integrationEvent.setGameId(gameEvent.gameId().toString());
                        NumberGuessingGameWasStarted e = (NumberGuessingGameWasStarted) gameEvent;
                        integrationEvent.setSecretNumberToGuess(e.secretNumberToGuess());
                        integrationEvent.setMaxNumberOfGuesses(e.maxNumberOfGuesses());
                        integrationEvent.setStartedAt(toDate(e.timestamp()));
                    } else if (gameEvent instanceof PlayerGuessedANumberThatWasTooSmall) {
                        PlayerGuessedANumberThatWasTooSmall e = (PlayerGuessedANumberThatWasTooSmall) gameEvent;
                        integrationEvent.addGuess(new GuessedNumber(e.playerId().toString(), e.guessedNumber(), toDate(e.timestamp())));
                    } else if (gameEvent instanceof PlayerGuessedANumberThatWasTooBig) {
                        PlayerGuessedANumberThatWasTooBig e = (PlayerGuessedANumberThatWasTooBig) gameEvent;
                        integrationEvent.addGuess(new GuessedNumber(e.playerId().toString(), e.guessedNumber(), toDate(e.timestamp())));
                    } else if (gameEvent instanceof PlayerGuessedTheRightNumber) {
                        PlayerGuessedTheRightNumber e = (PlayerGuessedTheRightNumber) gameEvent;
                        integrationEvent.addGuess(new GuessedNumber(e.playerId().toString(), e.guessedNumber(), toDate(e.timestamp())));
                        integrationEvent.setRightNumberWasGuessed(true);
                    } else if (gameEvent instanceof GuessingAttemptsExhausted) {
                        integrationEvent.setRightNumberWasGuessed(false);
                    } else if (gameEvent instanceof NumberGuessingGameEnded) {
                        integrationEvent.setEndedAt(toDate(gameEvent.timestamp()));
                    }
                }, (i1, i2) -> {
                });

        log.info("Publishing integration event {} to {}", NumberGuessingGameCompleted.class.getSimpleName(), numberGuessingGameTopic.getName());
        log.debug(numberGuessingGameCompleted.toString());

        rabbitTemplate.convertAndSend(numberGuessingGameTopic.getName(), "number-guessing-game.completed", numberGuessingGameCompleted);
    }

    private static Date toDate(LocalDateTime ldt) {
        if (ldt == null) {
            return null;
        }
        return Date.from(ldt.toInstant(UTC));
    }
}