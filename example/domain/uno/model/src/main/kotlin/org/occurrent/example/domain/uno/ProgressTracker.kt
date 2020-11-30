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

package org.occurrent.example.domain.uno

import org.occurrent.example.domain.uno.Card.*
import org.occurrent.example.domain.uno.Color.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap


private object ConsoleColor {
    private const val DEFAULT = "\u001b[0m" // Text Reset

    const val RED = "\u001b[0;31m"
    const val GREEN = "\u001b[0;32m"
    const val YELLOW = "\u001b[0;33m"
    const val BLUE = "\u001b[0;34m"
}

fun consoleColor(color: Color): String = when (color) {
    Red -> ConsoleColor.RED
    Green -> ConsoleColor.GREEN
    Blue -> ConsoleColor.BLUE
    Yellow -> ConsoleColor.YELLOW
}

private fun generateCardDescription(card: Card): String {
    val cardColor = card.color::class.simpleName!!
    return when (card) {
        is DigitCard -> "$cardColor ${card.digit::class.simpleName!!}"
        is KickBack -> "$cardColor kickback"
        is Skip -> "$cardColor skip"
    }
}

class ProgressTracker {
    private val turnCountPerGame = ConcurrentHashMap<UUID, Int>()

    fun trackProgress(fn: (String) -> Unit, event: Event) {
        val description = when (event) {
            is GameStarted -> {
                turnCountPerGame[event.gameId] = 0
                """Game ${event.gameId} started with ${event.firstPlayerId} players
                |${consoleColor(event.firstCard.color)}First card: ${generateCardDescription(event.firstCard)}"""
            }
            is CardPlayed -> {
                val turnCountForGame = turnCountPerGame.compute(event.gameId) { _, turnCount ->
                    turnCount!!.inc()
                }
                "${consoleColor(event.card.color)}[$turnCountForGame] Player ${event.playerId} played ${generateCardDescription(event.card)}"
            }
            is PlayerPlayedAtWrongTurn -> "${consoleColor(Red)}[${turnCountPerGame[event.gameId]}] Player ${event.playerId} played at wrong turn a ${generateCardDescription(event.card)}"
            is PlayerPlayedWrongCard -> "${consoleColor(Red)}[${turnCountPerGame[event.gameId]}] Player ${event.playerId} played a ${generateCardDescription(event.card)}: Wrong color wrong value"
            is DirectionChanged -> "Direction changed. Now playing ${event.direction::class.simpleName}."
        }.trimMargin()

        fn(description)
    }
}