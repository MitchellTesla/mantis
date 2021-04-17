package io.iohk.ethereum.consensus.pow.miners

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.util.ByteString
import io.iohk.ethereum.blockchain.sync.SyncProtocol
import io.iohk.ethereum.consensus.blocks.{PendingBlock, PendingBlockAndState}
import io.iohk.ethereum.consensus.pow.PoWMinerCoordinator.CoordinatorProtocol
import io.iohk.ethereum.consensus.pow.miners.MinerProtocol._
import io.iohk.ethereum.consensus.pow.{EthashUtils, KeccakCalculation, PoWBlockCreator, PoWMinerCoordinator}
import io.iohk.ethereum.crypto
import io.iohk.ethereum.domain.{Block, BlockHeader}
import io.iohk.ethereum.jsonrpc.EthMiningService.SubmitHashRateRequest
import io.iohk.ethereum.jsonrpc.{EthInfoService, EthMiningService}
import io.iohk.ethereum.nodebuilder.Node
import io.iohk.ethereum.utils.BigIntExtensionMethods._
import io.iohk.ethereum.utils.{ByteStringUtils, ByteUtils}
import monix.execution.Scheduler

import scala.util.Random

/**
  * Implementation of Ethash CPU mining worker.
  * Could be started by switching configuration flag "consensus.mining-enabled" to true
  * Implementation explanation at https://eth.wiki/concepts/ethash/ethash
  */
class EthashMiner(
    blockCreator: PoWBlockCreator,
    syncController: ActorRef,
    ethMiningService: EthMiningService
) extends Actor
    with ActorLogging {

  import EthashMiner._

  private implicit val scheduler: Scheduler = Scheduler(context.dispatcher)

  private val dagManager = new EthashDAGManager(blockCreator)

  override def receive: Receive = started

  def started: Receive = { case ProcessMining(bestBlock, coordinatorRef) =>
    log.debug("Received message ProcessMining with parent block {}", bestBlock.number)
    processMining(bestBlock, coordinatorRef)
  }

  private def processMining(bestBlock: Block, coordinatorRef: akka.actor.typed.ActorRef[CoordinatorProtocol]): Unit = {
    blockCreator
      .getBlockForMining(bestBlock)
      .map { case PendingBlockAndState(PendingBlock(block, _), _) =>
        val blockNumber = block.header.number
        val (startTime, mineResult) = doMining(blockNumber.toLong, block)

        submiteHashRate(System.nanoTime() - startTime, mineResult)

        mineResult match {
          case MiningSuccessful(_, mixHash, nonce) =>
            log.info(
              "Mining successful with {} and nonce {}",
              ByteStringUtils.hash2string(mixHash),
              ByteStringUtils.hash2string(nonce)
            )

            syncController ! SyncProtocol.MinedBlock(
              block.copy(header = block.header.copy(nonce = nonce, mixHash = mixHash))
            )
            coordinatorRef ! PoWMinerCoordinator.MiningCompleted
            context.stop(self)
          case _ => log.info("Mining unsuccessful")
        }
      }
      .onErrorHandle { ex =>
        log.error(ex, "Unable to get block for mining")
        context.stop(self)
      }
      .runAsyncAndForget
  }

  private def submiteHashRate(time: Long, mineResult: MiningResult): Unit = {
    val hashRate = if (time > 0) (mineResult.triedHashes.toLong * 1000000000) / time else Long.MaxValue
    ethMiningService.submitHashRate(SubmitHashRateRequest(hashRate, ByteString("mantis-miner")))
  }

  private def doMining(blockNumber: Long, block: Block): (Long, MiningResult) = {
    val epoch =
      EthashUtils.epoch(blockNumber, blockCreator.blockchainConfig.ecip1099BlockNumber.toLong)
    val (dag, dagSize) = dagManager.calculateDagSize(blockNumber, epoch)
    val headerHash = crypto.kec256(BlockHeader.getEncodedWithoutNonce(block.header))
    val startTime = System.nanoTime()
    val mineResult =
      mineEthah(headerHash, block.header.difficulty.toLong, dagSize, dag, blockCreator.miningConfig.mineRounds)
    (startTime, mineResult)
  }

  private def mineEthah(
      headerHash: Array[Byte],
      difficulty: Long,
      dagSize: Long,
      dag: Array[Array[Int]],
      numRounds: Int
  ): MiningResult = {
    val numBits = 64
    val initNonce = BigInt(numBits, new Random())

    (0 to numRounds).iterator
      .map { n =>
        val nonce = (initNonce + n) % MaxNonce
        val nonceBytes = ByteUtils.padLeft(ByteString(nonce.toUnsignedByteArray), 8)
        val pow = EthashUtils.hashimoto(headerHash, nonceBytes.toArray[Byte], dagSize, dag.apply)
        (EthashUtils.checkDifficulty(difficulty, pow), pow, nonceBytes, n)
      }
      .collectFirst { case (true, pow, nonceBytes, n) => MiningSuccessful(n + 1, pow.mixHash, nonceBytes) }
      .getOrElse(MiningUnsuccessful(numRounds))
  }
}

object EthashMiner {

  final val BlockForgerDispatcherId = "mantis.async.dispatchers.block-forger"

  private[pow] def props(
      blockCreator: PoWBlockCreator,
      syncController: ActorRef,
      ethInfoService: EthInfoService,
      ethMiningService: EthMiningService
  ): Props =
    Props(
      new EthashMiner(blockCreator, syncController, ethMiningService)
    ).withDispatcher(BlockForgerDispatcherId)

  def apply(node: Node, blockCreator: PoWBlockCreator): ActorRef = {
    val minerProps = props(
      blockCreator = blockCreator,
      syncController = node.syncController,
      ethInfoService = node.ethInfoService,
      ethMiningService = node.ethMiningService
    )
    node.system.actorOf(minerProps)
  }

  // scalastyle:off magic.number
  val MaxNonce: BigInt = BigInt(2).pow(64) - 1

  val DagFilePrefix: ByteString = ByteString(Array(0xfe, 0xca, 0xdd, 0xba, 0xad, 0xde, 0xe1, 0xfe).map(_.toByte))
}