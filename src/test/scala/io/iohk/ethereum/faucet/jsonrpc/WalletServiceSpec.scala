package io.iohk.ethereum.faucet.jsonrpc

import java.security.SecureRandom

import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.ByteString
import io.iohk.ethereum.crypto._
import io.iohk.ethereum.domain.{Address, Transaction}
import io.iohk.ethereum.faucet.{FaucetConfig, SupervisorConfig}
import io.iohk.ethereum.keystore.KeyStore.DecryptionFailed
import io.iohk.ethereum.keystore.{KeyStore, Wallet}
import io.iohk.ethereum.mallet.service.RpcClient
import io.iohk.ethereum.network.p2p.messages.CommonMessages.SignedTransactions.SignedTransactionEnc
import io.iohk.ethereum.{crypto, rlp}
import monix.execution.Scheduler.Implicits.global
import org.bouncycastle.util.encoders.Hex
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

class WalletServiceSpec extends AnyFlatSpec with Matchers with MockFactory with ScalatestRouteTest with ScalaFutures {

  "Wallet Service" should "send a transaction" in new TestSetup {

    val receivingAddress = Address("0x99")
    val currentNonce = 2

    val tx = wallet.signTx(
      Transaction(currentNonce, config.txGasPrice, config.txGasLimit, receivingAddress, config.txValue, ByteString()),
      None
    )

    val expectedTx = rlp.encode(tx.tx.toRLPEncodable)

    val retTxId = ByteString(Hex.decode("112233"))

    (mockRpcClient.getNonce _).expects(config.walletAddress).returning(Right(currentNonce))
    (mockRpcClient.sendTransaction _).expects(ByteString(expectedTx)).returning(Right(retTxId))

    val res = walletService.sendFunds(wallet, Address("0x99")).runSyncUnsafe()

    res shouldEqual Right(retTxId)

  }

  it should "get wallet successful" in new TestSetup {
    (mockKeyStore.unlockAccount _).expects(config.walletAddress, config.walletPassword).returning(Right(wallet))

    val res = walletService.getWallet.runSyncUnsafe()

    res shouldEqual Right(wallet)
  }

  it should "wallet decryption failed" in new TestSetup {
    (mockKeyStore.unlockAccount _)
      .expects(config.walletAddress, config.walletPassword)
      .returning(Left(DecryptionFailed))

    val res = walletService.getWallet.runSyncUnsafe()

    res shouldEqual Left(DecryptionFailed)
  }

  trait TestSetup {
    val walletKeyPair = generateKeyPair(new SecureRandom)
    val (prvKey, pubKey) = keyPairToByteStrings(walletKeyPair)
    val wallet = Wallet(Address(crypto.kec256(pubKey)), prvKey)

    val mockRpcClient = mock[RpcClient]
    val mockKeyStore = mock[KeyStore]
    val config: FaucetConfig =
      FaucetConfig(
        walletAddress = wallet.address,
        walletPassword = "",
        txGasPrice = 10,
        txGasLimit = 20,
        txValue = 1,
        rpcAddress = "",
        keyStoreDir = "",
        minRequestInterval = 10.seconds,
        handlerTimeout = 10.seconds,
        responseTimeout = 10.seconds,
        supervisor = mock[SupervisorConfig],
        shutdownTimeout = 15.seconds
      )

    val walletService = new WalletService(mockRpcClient, mockKeyStore, config)
  }

}