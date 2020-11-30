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

package org.occurrent.example.domain.uno.es

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mongodb.client.MongoClients
import org.occurrent.application.service.blocking.execute
import org.occurrent.application.service.blocking.implementation.GenericApplicationService
import org.occurrent.eventstore.mongodb.nativedriver.EventStoreConfig
import org.occurrent.eventstore.mongodb.nativedriver.MongoEventStore
import org.occurrent.example.domain.uno.*
import org.occurrent.example.domain.uno.Card.DigitCard
import org.occurrent.example.domain.uno.Color.*
import org.occurrent.example.domain.uno.Digit.*
import org.occurrent.mongodb.timerepresentation.TimeRepresentation
import org.occurrent.subscription.mongodb.nativedriver.blocking.BlockingSubscriptionForMongoDB
import org.occurrent.subscription.mongodb.nativedriver.blocking.RetryStrategy
import org.slf4j.LoggerFactory
import java.lang.Thread.sleep
import java.util.concurrent.Executors
import kotlin.system.exitProcess

private val log = LoggerFactory.getLogger("org.occurrent.example.domain.uno.es")

/**
 * Very crude Uno application example
 */
fun main() {
    log.info("Starting UNO application")
    val mongoClient = MongoClients.create("mongodb://localhost:27017")
    val database = mongoClient.getDatabase("test")

    val eventStore = MongoEventStore(mongoClient, database, database.getCollection("events"), EventStoreConfig(TimeRepresentation.DATE))
    val subscription = BlockingSubscriptionForMongoDB(database, "events", TimeRepresentation.DATE, Executors.newCachedThreadPool(), RetryStrategy.fixed(200))

    val objectMapper = jacksonObjectMapper()
    val cloudEventConverter = UnoCloudEventConverter(objectMapper)
    val applicationService = GenericApplicationService(eventStore, cloudEventConverter)

    val unoProgressTracker = ProgressTracker()

    subscription.subscribe("progress-tracker") { cloudEvent ->
        val domainEvent = cloudEventConverter.toDomainEvent(cloudEvent)
        unoProgressTracker.trackProgress(log::info, domainEvent)
    }.waitUntilStarted()

    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("Shutting down")
        subscription.shutdown()
        mongoClient.close()
    })

    // Simulate playing Uno
    val gameId = GameId.randomUUID()

    // TODO Add partial application
    val commands = listOf<Function1<Sequence<Event>, Sequence<Event>>>(
            { events -> Uno.start(events, gameId, Timestamp.now(), playerCount = 4, firstCard = DigitCard(Three, Red)) },
            { events -> Uno.play(events, Timestamp.now(), playerId = 0, card = DigitCard(Three, Blue)) },
            { events -> Uno.play(events, Timestamp.now(), playerId = 1, card = DigitCard(Eight, Blue)) },
            { events -> Uno.play(events, Timestamp.now(), playerId = 2, card = DigitCard(Eight, Yellow)) },
            { events -> Uno.play(events, Timestamp.now(), playerId = 0, card = DigitCard(Four, Green)) }
    )
    commands.forEach { command ->
        applicationService.execute(gameId, command)
    }

    sleep(1000) // Allow progress tracker some time to process all events before exiting
    exitProcess(0)
}