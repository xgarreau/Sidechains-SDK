package com.horizen.storage

import java.lang.{Byte => JByte}
import java.util.{ArrayList => JArrayList, HashMap => JHashMap, Optional => JOptional}

import com.google.common.primitives.{Ints, Longs}

import scala.collection.JavaConverters._
import com.horizen.SidechainTypes
import com.horizen.box.BoxSerializer
import com.horizen.companion.SidechainBoxesCompanion
import com.horizen.consensus.{ConsensusEpochNumber, ForgingStakeInfo, ForgingStakeInfoSerializer, intToConsensusEpochNumber}
import com.horizen.customtypes.{CustomBox, CustomBoxSerializer}
import com.horizen.fixtures.{IODBStoreFixture, SecretFixture, TransactionFixture}
import com.horizen.utils.{ByteArrayWrapper, BytesUtils, ListSerializer, Pair, WithdrawalEpochInfo, WithdrawalEpochInfoSerializer}
import org.junit.Assert._
import org.junit._
import org.mockito.{ArgumentMatchers, Mockito}
import org.scalatest.junit.JUnitSuite
import org.scalatest.mockito.MockitoSugar
import scorex.crypto.hash.Blake2b256

import scala.collection.mutable.ListBuffer
import scala.util.Try

class SidechainStateStorageTest
  extends JUnitSuite
    with SecretFixture
    with TransactionFixture
    with IODBStoreFixture
    with MockitoSugar
    with SidechainTypes
{
  val mockedPhysicalStorage: Storage = mock[IODBStoreAdapter]

  val boxList = new ListBuffer[SidechainTypes#SCB]()
  val storedBoxList = new ListBuffer[Pair[ByteArrayWrapper, ByteArrayWrapper]]()

  val customBoxesSerializers: JHashMap[JByte, BoxSerializer[SidechainTypes#SCB]] = new JHashMap()
  customBoxesSerializers.put(CustomBox.BOX_TYPE_ID, CustomBoxSerializer.getSerializer.asInstanceOf[BoxSerializer[SidechainTypes#SCB]])
  val sidechainBoxesCompanion = SidechainBoxesCompanion(customBoxesSerializers)

  val withdrawalEpochInfo = WithdrawalEpochInfo(1, 2)

  val consensusEpoch: ConsensusEpochNumber = intToConsensusEpochNumber(1)
  val forgingStakeInfoSerializer = new ListSerializer[ForgingStakeInfo](ForgingStakeInfoSerializer)
  val forgingStakesToAppendSeq: Seq[ForgingStakeInfo] = getForgerBoxList(2).asScala.map(box => ForgingStakeInfo(box.id(), box.value()))
  val forgingStakesAmount: Long = forgingStakesToAppendSeq.foldLeft(0L)(_ + _.value)

  @Before
  def setUp(): Unit = {

    boxList ++= getRegularBoxList(5).asScala.toList
    boxList ++= getCertifierRightBoxList(5).asScala.toList
    boxList ++= getCustomBoxList(5).asScala.map(_.asInstanceOf[SidechainTypes#SCB])


    for (b <- boxList) {
      storedBoxList.append({
        val key = new ByteArrayWrapper(Blake2b256.hash(b.id()))
        val value = new ByteArrayWrapper(sidechainBoxesCompanion.toBytes(b))
        new Pair(key,value)
      })
    }

    Mockito.when(mockedPhysicalStorage.get(ArgumentMatchers.any[ByteArrayWrapper]()))
      .thenAnswer(answer => {
        storedBoxList.find(_.getKey.equals(answer.getArgument(0))) match {
          case Some(pair) => JOptional.of(pair.getValue)
          case None => JOptional.empty()
        }
      })
  }

  @Test
  def testUpdate(): Unit = {
    val stateStorage = new SidechainStateStorage(mockedPhysicalStorage, sidechainBoxesCompanion)
    var tryRes: Try[SidechainStateStorage] = null
    val expectedException = new IllegalArgumentException("on update exception")

    // Test1: get one item
    assertEquals("Storage must return existing Box.", boxList(3), stateStorage.getBox(boxList(3).id()).get)

    // Test 2: try get non-existing item
    assertEquals("Storage must NOT contain requested Box.", None, stateStorage.getBox("non-existing id".getBytes()))

    // Data for Test 1:
    val version = getVersion
    val toUpdate = new JArrayList[Pair[ByteArrayWrapper, ByteArrayWrapper]]()
    toUpdate.add(storedBoxList.head)
    // wothdrawals info
    toUpdate.add(new Pair(new ByteArrayWrapper(stateStorage.withdrawalEpochInformationKey),
      new ByteArrayWrapper(WithdrawalEpochInfoSerializer.toBytes(withdrawalEpochInfo))))
    // consensus epoch
    toUpdate.add(new Pair(stateStorage.consensusEpochKey, new ByteArrayWrapper(Ints.toByteArray(consensusEpoch))))
    toUpdate.add(new Pair(new ByteArrayWrapper(stateStorage.forgingStakesInfoKey),
      new ByteArrayWrapper(forgingStakeInfoSerializer.toBytes(forgingStakesToAppendSeq.asJava))))
    toUpdate.add(new Pair(new ByteArrayWrapper(stateStorage.forgingStakesAmountKey),
      new ByteArrayWrapper(Longs.toByteArray(forgingStakesAmount))))
    val toRemove = java.util.Arrays.asList(storedBoxList(2).getKey)

    Mockito.when(mockedPhysicalStorage.update(
      ArgumentMatchers.any[ByteArrayWrapper](),
      ArgumentMatchers.anyList[Pair[ByteArrayWrapper, ByteArrayWrapper]](),
      ArgumentMatchers.anyList[ByteArrayWrapper]()))
      // For Test 1:
      .thenAnswer(answer => {
      val actualVersion = answer.getArgument(0).asInstanceOf[ByteArrayWrapper]
      val actualToUpdate = answer.getArgument(1).asInstanceOf[java.util.List[Pair[ByteArrayWrapper, ByteArrayWrapper]]]
      val actualToRemove = answer.getArgument(2).asInstanceOf[java.util.List[ByteArrayWrapper]]
      assertEquals("StateStorage.update(...) actual Version is wrong.", version, actualVersion)
      assertEquals("StateStorage.update(...) actual toUpdate list is wrong.", toUpdate, actualToUpdate)
      assertEquals("StateStorage.update(...) actual toRemove list is wrong.", toRemove, actualToRemove)
    })
      // For Test 2:
      .thenAnswer(answer => throw expectedException)


    // Test 1: test successful update
    tryRes = stateStorage.update(version, withdrawalEpochInfo, Set(boxList.head), Set(boxList(2).id()), Seq(), forgingStakesToAppendSeq, consensusEpoch, false)
    assertTrue("StateStorage successful update expected, instead exception occurred:\n %s".format(if(tryRes.isFailure) tryRes.failed.get.getMessage else ""),
      tryRes.isSuccess)


    // Test 2: test failed update, when Storage throws an exception
    val box = getRegularBox
    tryRes = stateStorage.update(version, withdrawalEpochInfo, Set(box), Set(boxList(3).id()), Seq(), Seq(), consensusEpoch, false)
    assertTrue("StateStorage failure expected during update.", tryRes.isFailure)
    assertEquals("StateStorage different exception expected during update.", expectedException, tryRes.failed.get)
    assertTrue("Storage should NOT contain Box that was tried to update.", stateStorage.getBox(box.id()).isEmpty)
    assertTrue("Storage should contain Box that was tried to remove.", stateStorage.getBox(boxList(3).id()).isDefined)
    assertEquals("Storage should return existing Box.", boxList(3), stateStorage.getBox(boxList(3).id()).get)
  }

  @Test
  def testExceptions() : Unit = {
    var exceptionTrown = false

    try {
      val stateStorage = new SidechainStateStorage(null, sidechainBoxesCompanion)
    } catch {
      case e : IllegalArgumentException => exceptionTrown = true
    }

    assertTrue("SidechainStateStorage constructor. Exception must be thrown if storage is not specified.",
      exceptionTrown)

    exceptionTrown = false
    try {
      val stateStorage = new SidechainStateStorage(mockedPhysicalStorage, null)
    } catch {
      case e : IllegalArgumentException => exceptionTrown = true
    }

    assertTrue("SidechainStateStorage constructor. Exception must be thrown if boxesCompation is not specified.",
      exceptionTrown)

    val stateStorage = new SidechainStateStorage(mockedPhysicalStorage, sidechainBoxesCompanion)

    assertTrue("SidechainStorage.rollback. Method must return Failure if NULL version specified.",
      stateStorage.rollback(null).isFailure)
  }
}
