## Changelog next version
* Upgraded Java Mongo driver from 4.0.4 to 4.1.1
* Upgraded to cloud events 2.0.0-milestone4. This introduces a breaking change since the `CloudEvent` SDK no longer returns a `byte[]` as data but rather a `CloudEventData` interface.
  You need to change your code from:
  
  ```java
  byte[] data = cloudEvent.getData();
  ```           
  
  to 
  
  ```java
  byte[] data = cloudEvent.getData().toBytes();
  ```
* Fixed so that not only JSON data can be used as cloud event data. Now the content-type of the event is taken into consideration, and you can store any kind of data.
* Introduced `org.occurrent.eventstore.mongodb.cloudevent.DocumentCloudEventData`, cloud event data will be represented in this format with loading events from an event store.
  This means that you could check if the `CloudEventData` returned by `cloudEvent.getData()` is instance of `DocumentCloudEventData` and if so extract the 
  underluying `org.bson.Document` that represent the data in the database.      
* Occurrent no longer needs to perform double encoding of the cloud event data if content type is json. Instead of serializing the content manually to a `byte[]` you can
  use either the built-in `JsonCloudEventData` class from the `cloudevents-json-jackson` module, or 
  use the `DocumentCloudEventData` provided by Occurrent to avoid this.
* Upgrading to spring-data-mongodb 3.1.1

## Changelog 0.2.1 (2020-11-03)
* Fixed typo in `CatchupSupportingBlockingSubscriptionConfig`, renamed method `dontSubscriptionPositionStorage` to `dontUseSubscriptionPositionStorage`.
* Added `getSubscriptionPosition()` to `PositionAwareCloudEvent` that returns `Optional<SubscriptionPosition>`.
* Removed duplicate `GenericCloudEventConverter` located in the `org.occurrent.application.service.blocking.implementation` package. Use `org.occurrent.application.converter.implementation.CloudEventConverter` instead.
* Handling if the domain model returns a null `Stream<DomainEvent>` in the `GenericApplicationService`. 

## Changelog 0.2.0 (2020-10-31)
* Renamed method `CloudEventWithSubscriptionPosition.getStreamPosition()` to `CloudEventWithSubscriptionPosition.getSubscriptionPosition()` since this was a typo.
* Added ability to provide a list of conditions when composing them with `and` and `or`.
* Added special convenience (overloaded) method for creating "or" with "equal to" conditions. For example you can now do: `filter(type(or("x", "y"))`. 
  Before you had to do: `filter(type(or(eq("x"), eq("y")))`.
* MongoDB event streams are now explicitly sorted by natural order by default. The reason for this is that just relying on default "sorting" on read lead to wrong order on certain occasions.
* Writing an empty stream to a mongodb-based event store will just ignore the stream and not try to persist the empty stream to the datastore.
* Upgraded to cloudevents sdk 2.0.0-milestone3
* Non-backward compatible change: `CatchupSupportingBlockingSubscription` no longer requires a subscription position storage during the catch-up phase. 
  Instead, you pass the storage implementation to `CatchupSupportingBlockingSubscriptionConfig` along with the position persistence predicate.
* `BlockingSubscriptionWithAutomaticPositionPersistence` now implements the `PositionAwareBlockingSubscription` interface
* Removed the generic type T from the `org.occurrent.subscription.api.blocking.BlockingSubscription` and `org.occurrent.subscription.api.reactor.ReactorSubscription`.
  The reason for this was the implementation returning different kinds of CloudEvent implementations where not compatible. For example if you created a Spring Bean
  with a `T` of `CloudEventWithSubscriptionPosition` then such a subscription couldn't be assigned to a field expecting a subscription with just `CloudEvent`.
  To avoid having users to know which cloud event implementation to expect, we change the API so that it always deals with pure `CloudEvent`'s. 
  Implementors now have to use `org.occurrent.subscription.PositionAwareCloudEvent.getSubscriptionPositionOrThrowIAE(cloudEvent)` to get the position.
  It's also possible to check if a `CloudEvent` contains a subscription position by calling `org.occurrent.subscription.PositionAwareCloudEvent.hasSubscriptionPosition(cloudEvent)`.
* Fixed several corner-cases for the `CatchupSupportingBlockingSubscription`, it should now be safer to use and produce fewer duplicates when switching from catch-up to continuous subscription mode.
* Added "exists" method to the `BlockingSubscriptionPositionStorage` interface (and implemented for all implementations of this interface).
* The global position of `PositionAwareBlockingSubscription` for MongoDB increases the "increment" of the current `BsonTimestamp` by 1 in order to avoid 
  duplicate potential duplication of events during replay.
* Added a generic application service implementation (and interfaces). You don't have to use it, it's ok to simply cut and paste and make custom changes. You 
  can also write your own class. The implementation, `org.occurrent.application.service.blocking.implementation.GenericApplicationService`, quite 
  simplistic but should cover most of the basic use cases. The application service uses a `org.occurrent.application.converter.CloudEventConverter` to
  convert to and from cloud events and your custom domain events. This is why both `CloudEventConverter` and `ApplicationService` takes a generic type parameter, `T`, 
  which is the type of your custom domain event. Note that the application service is not yet implemented for the reactive event store.
  The application service also contains a way to execute side-effects after the events are written to the event store. This is useful for executing 
  synchronous policies after the events are written to the event store. If policies write the the same database as your event store,  you start a transaction
  and write both policies and events in the same transaction!         
  There are also Kotlin extension functions for the application service and policies in the `org.occurrent:application-service-blocking` module.
* Added utilities, `org.occurrent:command-composition` for to easier do command composition when calling an application service.
  This module also contains utilities for doing partial application of functions which can be useful when composing functions.    

## Changelog 0.1.1 (2020-09-26):

* Catchup subscriptions (blocking)
* EveryN for stream persistence (both blocking and reactive)
* Added "count" to EventStoreQueries (both blocking and reactive)
* Added ability to query for "data" attribute in EventStoreQueries and subscriptions