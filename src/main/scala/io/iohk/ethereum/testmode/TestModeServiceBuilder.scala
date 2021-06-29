package io.iohk.ethereum.testmode

import akka.util.ByteString
import cats.data.NonEmptyList
import io.iohk.ethereum.consensus.difficulty.DifficultyCalculator
import io.iohk.ethereum.consensus.{Consensus, ConsensusBuilder, ConsensusConfigBuilder}
import io.iohk.ethereum.domain._
import io.iohk.ethereum.ledger._
import io.iohk.ethereum.nodebuilder.{ActorSystemBuilder, _}
import monix.eval.Task
import monix.execution.Scheduler

trait TestModeServiceBuilder extends StxLedgerBuilder {
  self: BlockchainConfigBuilder
    with StorageBuilder
    with TestBlockchainBuilder
    with SyncConfigBuilder
    with ConsensusBuilder
    with ActorSystemBuilder
    with ConsensusConfigBuilder
    with VmBuilder =>

  val scheduler = Scheduler(system.dispatchers.lookup("validation-context"))

  lazy val testModeComponentsProvider: TestModeComponentsProvider =
    new TestModeComponentsProvider(
      blockchain,
      blockchainReader,
      storagesInstance.storages.evmCodeStorage,
      syncConfig,
      scheduler,
      consensusConfig,
      DifficultyCalculator(blockchainConfig),
      vm
    )

  override lazy val stxLedger: StxLedger =
    testModeComponentsProvider.stxLedger(blockchainConfig, SealEngineType.NoReward)
}