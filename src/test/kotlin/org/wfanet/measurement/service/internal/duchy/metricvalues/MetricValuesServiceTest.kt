package org.wfanet.measurement.service.internal.duchy.metricvalues

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.extensions.proto.ProtoTruth.assertThat
import com.google.protobuf.ByteString
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.stub
import io.grpc.Status
import io.grpc.StatusRuntimeException
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.wfanet.measurement.common.ExternalId
import org.wfanet.measurement.db.duchy.metricvalue.MetricValueDatabase
import org.wfanet.measurement.internal.duchy.GetMetricValueRequest
import org.wfanet.measurement.internal.duchy.MetricValue
import org.wfanet.measurement.internal.duchy.StoreMetricValueRequest
import org.wfanet.measurement.internal.duchy.StreamMetricValueRequest
import org.wfanet.measurement.internal.duchy.StreamMetricValueResponse
import org.wfanet.measurement.storage.BYTES_PER_MIB
import org.wfanet.measurement.storage.MetricValueStore
import org.wfanet.measurement.storage.asBufferedFlow
import org.wfanet.measurement.storage.testing.BlobSubject.Companion.assertThat
import org.wfanet.measurement.storage.testing.FileSystemStorageClient

@RunWith(JUnit4::class)
class MetricValuesServiceTest {
  @Rule
  @JvmField
  val tempDirectory = TemporaryFolder()

  private val fakeBlobKeyGenerator = FakeBlobKeyGenerator
  private lateinit var metricValueDbMock: MetricValueDatabase
  private lateinit var metricValueStore: MetricValueStore
  private lateinit var service: MetricValuesService

  @Before
  fun initService() {
    metricValueDbMock = mock()
    metricValueStore =
      MetricValueStore(FileSystemStorageClient(tempDirectory.root), fakeBlobKeyGenerator::generate)
    service = MetricValuesService(metricValueDbMock, metricValueStore)
  }

  @Test fun `getMetricValue by ID returns MetricValue`() = runBlocking {
    metricValueDbMock.stub {
      onBlocking {
        getMetricValue(ExternalId(testMetricValue.externalId))
      }.thenReturn(testMetricValue)
    }

    val response = service.getMetricValue(
      GetMetricValueRequest.newBuilder().apply {
        externalId = testMetricValue.externalId
      }.build()
    )

    assertThat(response).isEqualTo(testMetricValue)
  }

  @Test fun `getMetricValue by resource key returns MetricValue`() = runBlocking {
    metricValueDbMock.stub {
      onBlocking {
        getMetricValue(testMetricValue.resourceKey)
      }.thenReturn(testMetricValue)
    }

    val response = service.getMetricValue(
      GetMetricValueRequest.newBuilder().apply {
        resourceKey = testMetricValue.resourceKey
      }.build()
    )

    assertThat(response).isEqualTo(testMetricValue)
  }

  @Test fun `getMetricValue throws INVALID_ARGUMENT when key not set`() = runBlocking {
    val e = assertFailsWith(StatusRuntimeException::class) {
      service.getMetricValue(GetMetricValueRequest.getDefaultInstance())
    }
    assertThat(e.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
  }

  @Test fun `getMetricValue throws NOT_FOUND when MetricValue not found`() = runBlocking {
    val e = assertFailsWith(StatusRuntimeException::class) {
      service.getMetricValue(
        GetMetricValueRequest.newBuilder().apply {
          resourceKey = testMetricValue.resourceKey
        }.build()
      )
    }
    assertThat(e.status.code).isEqualTo(Status.Code.NOT_FOUND)
  }

  @Test fun `storeMetricValue stores MetricValue with data`() = runBlocking {
    fakeBlobKeyGenerator.nextBlobKey = testMetricValue.blobStorageKey

    metricValueDbMock.stub {
      onBlocking {
        insertMetricValue(any())
      }.thenReturn(testMetricValue)
    }

    val response =
      service.storeMetricValue(
        flowOf(
          StoreMetricValueRequest.newBuilder()
            .apply { headerBuilder.resourceKey = testMetricValue.resourceKey }
            .build(),
          StoreMetricValueRequest.newBuilder()
            .apply { chunkBuilder.data = ByteString.copyFrom(testMetricValueData) }
            .build()
        )
      )

    assertThat(response).isEqualTo(testMetricValue)
    val data = assertNotNull(metricValueStore.get(testMetricValue.blobStorageKey))
    assertThat(data).contentEqualTo(testMetricValueData)
  }

  @Test fun `streamMetricValue returns MetricValue with data`() = runBlocking {
    fakeBlobKeyGenerator.nextBlobKey = testMetricValue.blobStorageKey
    metricValueStore.write(testMetricValueData.asBufferedFlow())

    metricValueDbMock.stub {
      onBlocking {
        getMetricValue(testMetricValue.resourceKey)
      }.thenReturn(testMetricValue)
    }

    lateinit var header: StreamMetricValueResponse.Header
    val buffer = ByteBuffer.allocate(testMetricValueData.size)
    service.streamMetricValue(
      StreamMetricValueRequest.newBuilder().apply {
        resourceKey = testMetricValue.resourceKey
      }.build()
    ).collect { responseMessage ->
      if (responseMessage.hasHeader()) {
        header = responseMessage.header
      } else {
        buffer.put(responseMessage.chunk.data.toByteArray())
      }
    }

    assertThat(header.metricValue).isEqualTo(testMetricValue)
    assertFalse("Expected more bytes in data") { buffer.hasRemaining() }
    assertThat(buffer.array()).isEqualTo(testMetricValueData)
  }

  @Test fun `streamMetricValue throws INVALID_ARGUMENT when key not set`() = runBlocking {
    val e = assertFailsWith(StatusRuntimeException::class) {
      service.streamMetricValue(StreamMetricValueRequest.getDefaultInstance()).collect()
    }
    assertThat(e.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
  }

  @Test fun `streamMetricValue throws NOT_FOUND when MetricValue not found`() = runBlocking {
    val e = assertFailsWith(StatusRuntimeException::class) {
      service.streamMetricValue(
        StreamMetricValueRequest.newBuilder().apply {
          resourceKey = testMetricValue.resourceKey
        }.build()
      ).collect()
    }
    assertThat(e.status.code).isEqualTo(Status.Code.NOT_FOUND)
  }

  @Test fun `streamMetricValue throws DATA_LOSS when blob not found`() = runBlocking {
    fakeBlobKeyGenerator.nextBlobKey = testMetricValue.blobStorageKey

    metricValueDbMock.stub {
      onBlocking {
        getMetricValue(testMetricValue.resourceKey)
      }.thenReturn(testMetricValue)
    }

    val e = assertFailsWith(StatusRuntimeException::class) {
      service.streamMetricValue(
        StreamMetricValueRequest.newBuilder().apply {
          resourceKey = testMetricValue.resourceKey
        }.build()
      ).collect()
    }
    assertThat(e.status.code).isEqualTo(Status.Code.DATA_LOSS)
  }

  private object FakeBlobKeyGenerator {
    var nextBlobKey = ""

    fun generate(): String = nextBlobKey
  }

  companion object {
    private val testMetricValue: MetricValue = MetricValue.newBuilder().apply {
      externalId = 987654321L
      resourceKeyBuilder.apply {
        dataProviderResourceId = "data-provider-id"
        campaignResourceId = "campaign-id"
        metricRequisitionResourceId = "requisition-id"
      }
      blobStorageKey = "blob-key"
    }.build()

    private val random = Random.Default
    private lateinit var testMetricValueData: ByteArray

    @BeforeClass
    @JvmStatic
    fun generateTestBytes() {
      testMetricValueData = random.nextBytes(random.nextInt(BYTES_PER_MIB * 3, BYTES_PER_MIB * 4))
    }
  }
}
