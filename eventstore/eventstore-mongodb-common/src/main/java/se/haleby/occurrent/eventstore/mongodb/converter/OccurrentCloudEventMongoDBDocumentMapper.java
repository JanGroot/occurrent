package se.haleby.occurrent.eventstore.mongodb.converter;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.format.EventFormat;
import org.bson.Document;
import se.haleby.occurrent.eventstore.api.blocking.EventStream;

import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static se.haleby.occurrent.cloudevents.OccurrentCloudEventExtension.STREAM_ID;

public class OccurrentCloudEventMongoDBDocumentMapper {

    public static Stream<Document> convertToDocuments(EventFormat eventFormat, String streamId, Stream<CloudEvent> cloudEvents) {
        return cloudEvents.map(eventFormat::serialize)
                .map(bytes -> new String(bytes, UTF_8))
                .map(Document::parse)
                // Add streamId as extension property!
                .peek(cloudEventDocument -> cloudEventDocument.put(STREAM_ID, streamId));
    }

    public static EventStream<CloudEvent> convertToCloudEvent(EventFormat eventFormat, EventStream<Document> eventStream) {
        return requireNonNull(eventStream)
                .map(Document::toJson)
                .map(eventJsonString -> eventJsonString.getBytes(UTF_8))
                .map(eventFormat::deserialize);
    }
}