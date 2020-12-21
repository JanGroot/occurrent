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
package org.occurrent.example.domain.wordguessinggame.mongodb.spring.blocking

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.occurrent.application.converter.CloudEventConverter
import org.occurrent.application.service.blocking.implementation.GenericApplicationService
import org.occurrent.eventstore.api.blocking.EventStoreQueries
import org.occurrent.eventstore.mongodb.spring.blocking.EventStoreConfig
import org.occurrent.eventstore.mongodb.spring.blocking.SpringBlockingMongoEventStore
import org.occurrent.example.domain.wordguessinggame.event.GameEvent
import org.occurrent.example.domain.wordguessinggame.mongodb.spring.blocking.features.GameCloudEventConverter
import org.occurrent.example.domain.wordguessinggame.mongodb.spring.blocking.features.emailwinner.SendEmailToWinner
import org.occurrent.mongodb.timerepresentation.TimeRepresentation
import org.occurrent.subscription.api.blocking.SubscriptionModel
import org.occurrent.subscription.api.blocking.SubscriptionPositionStorage
import org.occurrent.subscription.mongodb.spring.blocking.SpringMongoDBSubscriptionModel
import org.occurrent.subscription.mongodb.spring.blocking.SpringMongoDBSubscriptionPositionStorage
import org.occurrent.subscription.util.blocking.AutoPersistingSubscriptionModel
import org.occurrent.subscription.util.blocking.catchup.subscription.CatchupSubscriptionModel
import org.occurrent.subscription.util.blocking.catchup.subscription.CatchupSubscriptionModelConfig
import org.occurrent.subscription.util.blocking.catchup.subscription.SubscriptionPositionStorageConfig.useSubscriptionPositionStorage
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.retry.annotation.EnableRetry
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.net.URI
import org.occurrent.application.service.blocking.ApplicationService as OccurrentApplicationService

/**
 * Bootstrap the application
 */
@SpringBootApplication
@EnableRetry
@Import(SendEmailToWinner::class)
class Bootstrap : WebMvcConfigurer {
    companion object {
        private const val EVENTS_COLLECTION_NAME = "events"
    }

    // Redirect to /games when navigating to /
    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addRedirectViewController("/", "/games")
    }

    @Bean
    fun transactionManager(dbFactory: MongoDatabaseFactory) = MongoTransactionManager(dbFactory)

    @Bean
    fun eventStore(template: MongoTemplate, transactionManager: MongoTransactionManager): SpringBlockingMongoEventStore {
        val eventStoreConfig = EventStoreConfig.Builder().eventStoreCollectionName(EVENTS_COLLECTION_NAME).transactionConfig(transactionManager).timeRepresentation(TimeRepresentation.DATE).build()
        return SpringBlockingMongoEventStore(template, eventStoreConfig)
    }

    @Bean
    fun subscriptionPositionStorage(mongoTemplate: MongoTemplate): SubscriptionPositionStorage =
        SpringMongoDBSubscriptionPositionStorage(mongoTemplate, "subscriptions")

    @Bean
    fun catchupSubscriptionModel(storage: SubscriptionPositionStorage, mongoTemplate: MongoTemplate, eventStoreQueries: EventStoreQueries): SubscriptionModel {
        val subscription = SpringMongoDBSubscriptionModel(mongoTemplate, EVENTS_COLLECTION_NAME, TimeRepresentation.DATE)
        val subscriptionWithAutomaticPositionPersistence = AutoPersistingSubscriptionModel(subscription, storage)
        return CatchupSubscriptionModel(
            subscriptionWithAutomaticPositionPersistence, eventStoreQueries,
            CatchupSubscriptionModelConfig(useSubscriptionPositionStorage(storage))
        )
    }

    @Bean
    fun objectMapper() = jacksonObjectMapper()

    @Bean
    fun cloudEventConverter(objectMapper: ObjectMapper): CloudEventConverter<GameEvent> = GameCloudEventConverter(
        objectMapper,
        gameSource = URI.create("urn:occurrent:domain:wordguessinggame:game"),
        wordHintSource = URI.create("urn:occurrent:domain:wordguessinggame:wordhint"),
        pointsSource = URI.create("urn:occurrent:domain:wordguessinggame:points")
    )

    @Bean
    fun occurrentApplicationService(eventStore: SpringBlockingMongoEventStore, eventConverter: CloudEventConverter<GameEvent>): OccurrentApplicationService<GameEvent> = GenericApplicationService(eventStore, eventConverter)
}

fun main(args: Array<String>) {
    runApplication<Bootstrap>(*args)
}