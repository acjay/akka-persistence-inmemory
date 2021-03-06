/*
 * Copyright 2016 Dennis Vriend
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.persistence.inmemory.journal

import akka.NotUsed
import akka.actor.{ Actor, ActorRef }
import akka.persistence.inmemory.journal.AllPersistenceIdsSubscriberRegistry.AllPersistenceIdsSubscriberTerminated
import akka.persistence.inmemory.serialization.Serialized
import akka.stream.scaladsl.Flow

import scala.util.Try

object AllPersistenceIdsSubscriberRegistry {
  case class AllPersistenceIdsSubscriberTerminated(ref: ActorRef)
}

trait AllPersistenceIdsSubscriberRegistry { _: InMemoryAsyncWriteJournalLike ⇒
  private var allPersistenceIdsSubscribers = Set.empty[ActorRef]

  protected def hasAllPersistenceIdsSubscribers: Boolean = allPersistenceIdsSubscribers.nonEmpty

  private def addAllPersistenceIdsSubscriber(subscriber: ActorRef): Unit = {
    allPersistenceIdsSubscribers += subscriber
  }

  private def removeAllPersistenceIdsSubscriber(subscriber: ActorRef): Unit = {
    allPersistenceIdsSubscribers -= subscriber
  }

  private def newPersistenceIdAdded(id: String): Unit = {
    if (hasAllPersistenceIdsSubscribers) {
      val added = InMemoryJournal.PersistenceIdAdded(id)
      allPersistenceIdsSubscribers.foreach(_ ! added)
    }
  }

  protected def sendAllPersistenceIdsSubscriberTerminated(ref: ActorRef): Unit =
    self ! AllPersistenceIdsSubscriberTerminated(ref)

  protected def receiveAllPersistenceIdsSubscriber: Actor.Receive = {
    case InMemoryJournal.AllPersistenceIdsRequest ⇒
      addAllPersistenceIdsSubscriber(sender())
      context.watch(sender())

    case AllPersistenceIdsSubscriberTerminated(ref) ⇒
      removeAllPersistenceIdsSubscriber(ref)
  }

  protected def addAllPersistenceIdsFlow(persistenceIdsNotInJournal: List[String]): Flow[Try[Iterable[Serialized]], Try[Iterable[Serialized]], NotUsed] =
    Flow[Try[Iterable[Serialized]]].map { atomicWriteResult ⇒
      if (hasAllPersistenceIdsSubscribers) {
        for {
          seqSerialized ← atomicWriteResult
          headOfSeqSerialized ← seqSerialized.headOption
          persistenceId = headOfSeqSerialized.persistenceId
          if persistenceIdsNotInJournal contains persistenceId
        } newPersistenceIdAdded(persistenceId)
      }
      atomicWriteResult
    }
}
