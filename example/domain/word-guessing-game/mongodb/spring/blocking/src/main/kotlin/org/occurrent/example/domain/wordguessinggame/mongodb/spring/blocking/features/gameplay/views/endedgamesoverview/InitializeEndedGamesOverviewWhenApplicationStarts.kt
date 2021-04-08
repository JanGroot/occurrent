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

package org.occurrent.example.domain.wordguessinggame.mongodb.spring.blocking.features.gameplay.views.endedgamesoverview

import org.springframework.data.mongodb.core.CollectionOptions
import org.springframework.data.mongodb.core.MongoOperations
import org.springframework.data.mongodb.core.collectionExists
import org.springframework.data.mongodb.core.createCollection
import org.springframework.stereotype.Component
import javax.annotation.PostConstruct


@Component
class InitializeEndedGamesOverviewWhenApplicationStarts constructor(
    private val mongo: MongoOperations
) {

    @PostConstruct
    fun createCappedEndedGameOverviewMongoDBCollection() {
        if (!mongo.collectionExists<EndedGameOverviewMongoDTO>()) {
            mongo.createCollection<EndedGameOverviewMongoDTO>(CollectionOptions.empty().capped().maxDocuments(10).size(1_000_000))
        }
    }
}