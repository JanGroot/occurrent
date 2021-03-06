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

package org.occurrent.application.composition.command.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

public class SequentialFunctionComposer<T> {

    private final List<Function<List<T>, List<T>>> functions;

    public SequentialFunctionComposer(List<Function<List<T>, List<T>>> functions) {
        this.functions = functions;
    }

    public Function<List<T>, List<T>> compose() {
        return initialEvents -> {
            SequentialFunctionComposer.State<T> resultingState = functions.stream().collect(() -> new State<>(initialEvents),
                    (state, fn) -> {
                        List<T> newEvents = fn.apply(state.allEvents());
                        state.addAll(newEvents);
                    }, (state1, state2) -> {
                    });
            return resultingState.accumulatedEvents;
        };
    }

    private static class State<T> {
        private final List<T> initialEvents;
        private final List<T> accumulatedEvents;

        private State(List<T> initialEvents) {
            this.initialEvents = initialEvents;
            this.accumulatedEvents = new ArrayList<>();
        }

        private void addAll(List<T> events) {
            this.accumulatedEvents.addAll(events);
        }

        private List<T> allEvents() {
            List<T> eventList = new ArrayList<>(initialEvents);
            eventList.addAll(accumulatedEvents);
            return Collections.unmodifiableList(eventList);
        }
    }
}