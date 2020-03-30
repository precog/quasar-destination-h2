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

import quasar.api.destination.DestinationType

import argonaut._, Argonaut._
import eu.timepit.refined.auto._
import org.specs2.mutable.Specification

class AbstractDatasourceModuleSpec extends Specification {

  val TestModule = new AbstractDestinationModule(DestinationType("test", 1L))

  private def cfgToJson(cfg: Config): Json =
    ("connectionUri" := Json.jString(cfg.connectionUri.toString())) ->:
    jEmptyObject

  "sanitize config" >> {
    "returns same config when no properties" >> {
      val js = cfgToJson(Config("jdbc:h2:file:/data/sample"))
      TestModule.sanitizeDestinationConfig(js) must_=== js
    }

    "redacts properties" >> {
      val js = cfgToJson(Config("h2:file:~/sample;USER=sa;PASSWORD=123"))
      TestModule.sanitizeDestinationConfig(js) must_===
        cfgToJson(Config("h2:file:~/sample;<REDACTED>"))
    }
  }
}
