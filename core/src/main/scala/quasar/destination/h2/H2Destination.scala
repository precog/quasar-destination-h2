/*
 * Copyright 2020 Precog Data
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

package quasar.destination.h2

import quasar.api.ColumnType
import quasar.api.destination.DestinationType
import quasar.connector.MonadResourceErr
import quasar.connector.destination.{LegacyDestination, ResultSink}

import scala.Byte

import cats.data.NonEmptyList
import cats.effect.{Blocker, ContextShift, Effect, Timer}
import doobie.Transactor
import org.slf4s.Logging

final class H2Destination[F[_]: Effect: MonadResourceErr: ContextShift: Timer](
    val destinationType: DestinationType,
    xa: Transactor[F],
    blocker: Blocker)
    extends LegacyDestination[F] with Logging {

  private val csvSink: ResultSink[F, ColumnType.Scalar] = {
    val h2Sink = H2CsvCreateSink(xa, blocker) _

    ResultSink.create[F, ColumnType.Scalar, Byte] { (path, cols) =>
      (RenderConfigCsv, h2Sink(path, cols))
    }
  }

  val sinks: NonEmptyList[ResultSink[F, ColumnType.Scalar]] =
    NonEmptyList.one(csvSink)
}
