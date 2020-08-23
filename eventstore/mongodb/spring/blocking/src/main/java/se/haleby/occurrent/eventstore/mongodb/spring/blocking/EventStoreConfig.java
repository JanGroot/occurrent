package se.haleby.occurrent.eventstore.mongodb.spring.blocking;

import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import se.haleby.occurrent.mongodb.timerepresentation.TimeRepresentation;

import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Configuration for the blocking Spring java driver for MongoDB EventStore
 */
public class EventStoreConfig {
    public final String eventStoreCollectionName;
    public final TransactionTemplate transactionTemplate;
    public final TimeRepresentation timeRepresentation;

    /**
     * Create a new instance of {@code EventStoreConfig}.
     *
     * @param eventStoreCollectionName The collection in which the events are persisted
     * @param transactionTemplate      The transaction template responsible to starting MongoDB transactions (see {@link Builder} for overloads).
     * @param timeRepresentation       How time should be represented in the database
     */
    public EventStoreConfig(String eventStoreCollectionName, TransactionTemplate transactionTemplate, TimeRepresentation timeRepresentation) {
        requireNonNull(eventStoreCollectionName, "Event store collection name cannot be null");
        requireNonNull(transactionTemplate, TransactionTemplate.class.getSimpleName() + " cannot be null");
        requireNonNull(timeRepresentation, TimeRepresentation.class.getSimpleName() + " cannot be null");
        this.eventStoreCollectionName = eventStoreCollectionName;
        this.transactionTemplate = transactionTemplate;
        this.timeRepresentation = timeRepresentation;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventStoreConfig)) return false;
        EventStoreConfig that = (EventStoreConfig) o;
        return Objects.equals(eventStoreCollectionName, that.eventStoreCollectionName) &&
                Objects.equals(transactionTemplate, that.transactionTemplate) &&
                timeRepresentation == that.timeRepresentation;
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventStoreCollectionName, transactionTemplate, timeRepresentation);
    }

    @Override
    public String toString() {
        return "EventStoreConfig{" +
                "eventStoreCollectionName='" + eventStoreCollectionName + '\'' +
                ", transactionTemplate=" + transactionTemplate +
                ", timeRepresentation=" + timeRepresentation +
                '}';
    }

    public static final class Builder {
        private String eventStoreCollectionName;
        private TransactionTemplate transactionTemplate;
        private TimeRepresentation timeRepresentation;

        /**
         * @param eventStoreCollectionName The collection in which the events are persisted
         * @return A same {@code Builder instance}
         */
        public Builder eventStoreCollectionName(String eventStoreCollectionName) {
            this.eventStoreCollectionName = eventStoreCollectionName;
            return this;
        }

        /**
         * @param transactionTemplate The transaction template responsible to starting MongoDB transactions
         * @return A same {@code Builder instance}
         */
        public Builder transactionConfig(TransactionTemplate transactionTemplate) {
            this.transactionTemplate = transactionTemplate;
            return this;
        }

        /**
         * @param mongoTransactionManager Create a {@link TransactionTemplate} from the supplied {@code mongoTransactionManager}
         * @return A same {@code Builder instance}
         */
        public Builder transactionConfig(MongoTransactionManager mongoTransactionManager) {
            this.transactionTemplate = new TransactionTemplate(mongoTransactionManager);
            return this;
        }

        /**
         * @param timeRepresentation How time should be represented in the database
         * @return A same {@code Builder instance}
         */
        public Builder timeRepresentation(TimeRepresentation timeRepresentation) {
            this.timeRepresentation = timeRepresentation;
            return this;
        }


        public EventStoreConfig build() {
            return new EventStoreConfig(eventStoreCollectionName, transactionTemplate, timeRepresentation);
        }
    }
}