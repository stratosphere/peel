package eu.stratosphere.fab.core.beans.system

import eu.stratosphere.fab.core.beans.system.Lifespan.Lifespan
import eu.stratosphere.fab.core.ExecutionContext


/**
 * Created by felix on 02.06.14.
 */
abstract class ExperimentRunner(lifespan: Lifespan, dependencies: Set[System])
  extends System(lifespan, dependencies) {


  def run(context: ExecutionContext) = ???

}