/**
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.router;

import com.codahale.metrics.MetricRegistry;
import com.github.ambry.clustermap.MockClusterMap;
import com.github.ambry.commons.BlobId;
import com.github.ambry.commons.ByteBufferReadableStreamChannel;
import com.github.ambry.commons.LoggingNotificationSystem;
import com.github.ambry.commons.ResponseHandler;
import com.github.ambry.commons.ServerErrorCode;
import com.github.ambry.config.RouterConfig;
import com.github.ambry.config.VerifiableProperties;
import com.github.ambry.messageformat.BlobInfo;
import com.github.ambry.messageformat.BlobProperties;
import com.github.ambry.network.NetworkClient;
import com.github.ambry.network.NetworkClientErrorCode;
import com.github.ambry.network.RequestInfo;
import com.github.ambry.network.ResponseInfo;
import com.github.ambry.protocol.RequestOrResponse;
import com.github.ambry.utils.MockTime;
import com.github.ambry.utils.Utils;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;


/**
 * Tests for {@link GetBlobInfoOperation}
 */
public class GetBlobInfoOperationTest {
  private static final int MAX_PORTS_PLAIN_TEXT = 3;
  private static final int MAX_PORTS_SSL = 3;
  private static final int CHECKOUT_TIMEOUT_MS = 1000;
  private static final int BLOB_SIZE = 100;
  private static final int BLOB_USER_METADATA_SIZE = 10;

  private int requestParallelism = 2;
  private int successTarget = 1;
  private RouterConfig routerConfig;
  private NonBlockingRouterMetrics routerMetrics;
  private final MockClusterMap mockClusterMap;
  private final MockServerLayout mockServerLayout;
  private final int replicasCount;
  private final AtomicReference<MockSelectorState> mockSelectorState = new AtomicReference<>();
  private final ResponseHandler responseHandler;
  private final MockNetworkClientFactory networkClientFactory;
  private final NetworkClient networkClient;
  private final MockTime time = new MockTime();
  private final Map<Integer, GetOperation> correlationIdToGetOperation = new HashMap<>();
  private final NonBlockingRouter router;
  private final Random random = new Random();
  private final String blobIdStr;
  private final BlobProperties blobProperties;
  private final byte[] userMetadata;
  private final byte[] putContent;
  private final GetTestRequestRegistrationCallbackImpl requestRegistrationCallback =
      new GetTestRequestRegistrationCallbackImpl();
  private final FutureResult<BlobInfo> operationFuture = new FutureResult<>();

  private class GetTestRequestRegistrationCallbackImpl implements RequestRegistrationCallback<GetOperation> {
    private List<RequestInfo> requestListToFill;

    @Override
    public void registerRequestToSend(GetOperation getOperation, RequestInfo requestInfo) {
      requestListToFill.add(requestInfo);
      correlationIdToGetOperation.put(((RequestOrResponse) requestInfo.getRequest()).getCorrelationId(), getOperation);
    }
  }

  public GetBlobInfoOperationTest()
      throws Exception {
    VerifiableProperties vprops = new VerifiableProperties(getNonBlockingRouterProperties());
    routerConfig = new RouterConfig(vprops);
    mockClusterMap = new MockClusterMap();
    routerMetrics = new NonBlockingRouterMetrics(mockClusterMap.getMetricRegistry());
    mockServerLayout = new MockServerLayout(mockClusterMap);
    replicasCount = mockClusterMap.getWritablePartitionIds().get(0).getReplicaIds().size();
    responseHandler = new ResponseHandler(mockClusterMap);
    networkClientFactory = new MockNetworkClientFactory(vprops, mockSelectorState, MAX_PORTS_PLAIN_TEXT, MAX_PORTS_SSL,
        CHECKOUT_TIMEOUT_MS, mockServerLayout, time);
    router = new NonBlockingRouter(new RouterConfig(vprops), new NonBlockingRouterMetrics(new MetricRegistry()),
        networkClientFactory, new LoggingNotificationSystem(), mockClusterMap, time);
    blobProperties = new BlobProperties(BLOB_SIZE, "serviceId", "memberId", "contentType", false, Utils.Infinite_Time);
    userMetadata = new byte[BLOB_USER_METADATA_SIZE];
    random.nextBytes(userMetadata);
    putContent = new byte[BLOB_SIZE];
    random.nextBytes(putContent);
    ReadableStreamChannel putChannel = new ByteBufferReadableStreamChannel(ByteBuffer.wrap(putContent));
    blobIdStr = router.putBlob(blobProperties, userMetadata, putChannel).get();
    router.close();
    networkClient = networkClientFactory.getNetworkClient();
  }

  @After
  public void after() {
    if (networkClient != null) {
      networkClient.close();
    }
    Assert.assertEquals("All operations should have completed", 0, router.getOperationsCount());
  }

  /**
   * Test {@link GetBlobInfoOperation} instantiation and validate the get methods.
   * @throws Exception
   */
  @Test
  public void testInstantiation()
      throws Exception {
    String blobIdStr = (new BlobId(mockClusterMap.getWritablePartitionIds().get(0))).getID();
    Callback<BlobInfo> operationCallback = new Callback<BlobInfo>() {
      @Override
      public void onCompletion(BlobInfo result, Exception exception) {
        // no op.
      }
    };

    // test a bad case
    try {
      new GetBlobInfoOperation(routerConfig, routerMetrics, mockClusterMap, responseHandler, "invalid_id",
          operationFuture, operationCallback, time);
      Assert.fail("Instantiation of GetBlobInfo operation with an invalid blob id must fail");
    } catch (RouterException e) {
      Assert
          .assertEquals("Unexpected exception received on creating GetBlobInfoOperation", RouterErrorCode.InvalidBlobId,
              e.getErrorCode());
    }

    // test a good case
    GetBlobInfoOperation op =
        new GetBlobInfoOperation(routerConfig, routerMetrics, mockClusterMap, responseHandler, blobIdStr,
            operationFuture, operationCallback, time);

    Assert.assertEquals("Callback must match", operationCallback, op.getCallback());
    Assert.assertEquals("Futures must match", operationFuture, op.getFuture());
    Assert.assertEquals("Blob ids must match", blobIdStr, op.getBlobIdStr());
  }

  /**
   * Test basic successful operation completion, by polling and handing over responses to the BlobInfo operation.
   * @throws Exception
   */
  @Test
  public void testPollAndResponseHandling()
      throws Exception {
    GetBlobInfoOperation op =
        new GetBlobInfoOperation(routerConfig, routerMetrics, mockClusterMap, responseHandler, blobIdStr,
            operationFuture, null, time);
    ArrayList<RequestInfo> requestListToFill = new ArrayList<>();
    requestRegistrationCallback.requestListToFill = requestListToFill;
    op.poll(requestRegistrationCallback);
    Assert.assertEquals("There should only be as many requests at this point as requestParallelism", requestParallelism,
        correlationIdToGetOperation.size());

    List<ResponseInfo> responses = sendAndWaitForResponses(requestListToFill);
    for (ResponseInfo response : responses) {
      op.handleResponse(response);
      if (op.isOperationComplete()) {
        break;
      }
    }
    Assert.assertTrue("Operation should be complete at this time", op.isOperationComplete());
    assertSuccess(op);
  }

  /**
   * Test the case where all requests time out within the GetOperation.
   * @throws Exception
   */
  @Test
  public void testRouterRequestTimeoutAllFailure()
      throws Exception {
    GetBlobInfoOperation op =
        new GetBlobInfoOperation(routerConfig, routerMetrics, mockClusterMap, responseHandler, blobIdStr,
            operationFuture, null, time);
    requestRegistrationCallback.requestListToFill = new ArrayList<>();
    op.poll(requestRegistrationCallback);
    while (!op.isOperationComplete()) {
      time.sleep(routerConfig.routerRequestTimeoutMs + 1);
      op.poll(requestRegistrationCallback);
    }
    // At this time requests would have been created for all replicas, as none of them were delivered,
    // and cross-colo proxying is enabled by default.
    Assert.assertEquals("Must have attempted sending requests to all replicas", replicasCount,
        correlationIdToGetOperation.size());
    Assert.assertTrue("Operation should be complete at this time", op.isOperationComplete());
    RouterException routerException = (RouterException) op.getOperationException();
    Assert.assertEquals(RouterErrorCode.OperationTimedOut, routerException.getErrorCode());
  }

  /**
   * Test the case where all requests time out within the NetworkClient.
   * @throws Exception
   */
  @Test
  public void testNetworkClientTimeoutAllFailure()
      throws Exception {
    GetBlobInfoOperation op =
        new GetBlobInfoOperation(routerConfig, routerMetrics, mockClusterMap, responseHandler, blobIdStr,
            operationFuture, null, time);
    ArrayList<RequestInfo> requestListToFill = new ArrayList<>();
    requestRegistrationCallback.requestListToFill = requestListToFill;

    while (!op.isOperationComplete()) {
      op.poll(requestRegistrationCallback);
      for (RequestInfo requestInfo : requestListToFill) {
        ResponseInfo fakeResponse =
            new ResponseInfo(requestInfo.getRequest(), NetworkClientErrorCode.NetworkError, null);
        op.handleResponse(fakeResponse);
        if (op.isOperationComplete()) {
          break;
        }
      }
      requestListToFill.clear();
    }

    // At this time requests would have been created for all replicas, as none of them were delivered,
    // and cross-colo proxying is enabled by default.
    Assert.assertEquals("Must have attempted sending requests to all replicas", replicasCount,
        correlationIdToGetOperation.size());
    Assert.assertTrue("Operation should be complete at this time", op.isOperationComplete());
    RouterException routerException = (RouterException) op.getOperationException();
    Assert.assertEquals(RouterErrorCode.OperationTimedOut, routerException.getErrorCode());
  }

  /**
   * Test the case where every server returns Blob_Not_Found. All servers must have been contacted,
   * due to cross-colo proxying.
   * @throws Exception
   */
  @Test
  public void testBlobNotFoundCase()
      throws Exception {
    GetBlobInfoOperation op =
        new GetBlobInfoOperation(routerConfig, routerMetrics, mockClusterMap, responseHandler, blobIdStr,
            operationFuture, null, time);
    ArrayList<RequestInfo> requestListToFill = new ArrayList<>();
    requestRegistrationCallback.requestListToFill = requestListToFill;

    for (MockServer server : mockServerLayout.getMockServers()) {
      server.setServerErrorForAllRequests(ServerErrorCode.Blob_Not_Found);
    }

    while (!op.isOperationComplete()) {
      op.poll(requestRegistrationCallback);
      List<ResponseInfo> responses = sendAndWaitForResponses(requestListToFill);
      for (ResponseInfo response : responses) {
        op.handleResponse(response);
        if (op.isOperationComplete()) {
          break;
        }
      }
    }

    Assert.assertEquals("Must have attempted sending requests to all replicas", replicasCount,
        correlationIdToGetOperation.size());
    Assert.assertTrue("Operation should be complete at this time", op.isOperationComplete());
    RouterException routerException = (RouterException) op.getOperationException();
    Assert.assertEquals(RouterErrorCode.BlobDoesNotExist, routerException.getErrorCode());
  }

  /**
   * Test the case with Blob_Not_Found errors from most servers, and Blob_Deleted at just one server. The latter
   * should be the exception received for the operation.
   * @throws Exception
   */
  @Test
  public void testErrorPrecedenceWithBlobDeletedAndExpiredCase()
      throws Exception {
    ServerErrorCode[] serverErrorCodesToTest = {ServerErrorCode.Blob_Deleted, ServerErrorCode.Blob_Expired};
    RouterErrorCode[] routerErrorCodesToExpect = {RouterErrorCode.BlobDeleted, RouterErrorCode.BlobExpired};
    for (int i = 0; i < serverErrorCodesToTest.length; i++) {
      int indexToSetCustomError = random.nextInt(replicasCount);
      ServerErrorCode[] serverErrorCodesInOrder = new ServerErrorCode[9];
      for (int j = 0; j < serverErrorCodesInOrder.length; j++) {
        if (j == indexToSetCustomError) {
          serverErrorCodesInOrder[j] = serverErrorCodesToTest[i];
        } else {
          serverErrorCodesInOrder[j] = ServerErrorCode.Blob_Not_Found;
        }
      }
      testErrorPrecedence(serverErrorCodesInOrder, routerErrorCodesToExpect[i]);
    }
  }

  /**
   * Help test error precedence.
   * @param serverErrorCodesInOrder the list of error codes to set the mock servers with.
   * @param expectedErrorCode the expected router error code for the operation.
   * @throws Exception
   */
  private void testErrorPrecedence(ServerErrorCode[] serverErrorCodesInOrder, RouterErrorCode expectedErrorCode)
      throws Exception {
    GetBlobInfoOperation op =
        new GetBlobInfoOperation(routerConfig, routerMetrics, mockClusterMap, responseHandler, blobIdStr,
            operationFuture, null, time);
    ArrayList<RequestInfo> requestListToFill = new ArrayList<>();
    requestRegistrationCallback.requestListToFill = requestListToFill;

    int i = 0;
    for (MockServer server : mockServerLayout.getMockServers()) {
      server.setServerErrorForAllRequests(serverErrorCodesInOrder[i++]);
    }

    while (!op.isOperationComplete()) {
      op.poll(requestRegistrationCallback);
      List<ResponseInfo> responses = sendAndWaitForResponses(requestListToFill);
      for (ResponseInfo response : responses) {
        op.handleResponse(response);
        if (op.isOperationComplete()) {
          break;
        }
      }
    }

    RouterException routerException = (RouterException) op.getOperationException();
    Assert.assertEquals(expectedErrorCode, routerException.getErrorCode());
  }

  /**
   * Test the case with multiple errors (server level and partition level) from multiple servers,
   * with just one server returning a successful response. The operation should succeed.
   * @throws Exception
   */
  @Test
  public void testSuccessInThePresenceOfVariousErrors()
      throws Exception {
    // The put for the blob being requested happened.
    String dcWherePutHappened = routerConfig.routerDatacenterName;

    // test requests coming in from local dc as well as cross-colo.
    Properties props = getNonBlockingRouterProperties();
    props.setProperty("router.datacenter.name", "DC1");
    routerConfig = new RouterConfig(new VerifiableProperties(props));
    testVariousErrors(dcWherePutHappened);

    props = getNonBlockingRouterProperties();
    props.setProperty("router.datacenter.name", "DC2");
    routerConfig = new RouterConfig(new VerifiableProperties(props));
    testVariousErrors(dcWherePutHappened);

    props = getNonBlockingRouterProperties();
    props.setProperty("router.datacenter.name", "DC3");
    routerConfig = new RouterConfig(new VerifiableProperties(props));
    testVariousErrors(dcWherePutHappened);
  }

  private void testVariousErrors(String dcWherePutHappened)
      throws Exception {
    GetBlobInfoOperation op =
        new GetBlobInfoOperation(routerConfig, routerMetrics, mockClusterMap, responseHandler, blobIdStr,
            operationFuture, null, time);
    ArrayList<RequestInfo> requestListToFill = new ArrayList<>();
    requestRegistrationCallback.requestListToFill = requestListToFill;

    ArrayList<MockServer> mockServers = new ArrayList<>(mockServerLayout.getMockServers());
    // set the status to various server level or partition level errors (not Blob_Deleted or Blob_Expired).
    mockServers.get(0).setServerErrorForAllRequests(ServerErrorCode.Unknown_Error);
    mockServers.get(1).setServerErrorForAllRequests(ServerErrorCode.Unknown_Error);
    mockServers.get(2).setServerErrorForAllRequests(ServerErrorCode.IO_Error);
    mockServers.get(3).setServerErrorForAllRequests(ServerErrorCode.Blob_Not_Found);
    mockServers.get(4).setServerErrorForAllRequests(ServerErrorCode.Data_Corrupt);
    mockServers.get(5).setServerErrorForAllRequests(ServerErrorCode.Blob_Not_Found);
    mockServers.get(6).setServerErrorForAllRequests(ServerErrorCode.Blob_Not_Found);
    mockServers.get(7).setServerErrorForAllRequests(ServerErrorCode.Disk_Unavailable);
    mockServers.get(8).setServerErrorForAllRequests(ServerErrorCode.Unknown_Error);

    // clear the hard error in one of the servers in the datacenter where the put happened.
    for (int i = 0; i < mockServers.size(); i++) {
      MockServer mockServer = mockServers.get(i);
      if (mockServer.getDataCenter().equals(dcWherePutHappened)) {
        mockServer.setServerErrorForAllRequests(ServerErrorCode.No_Error);
        break;
      }
    }

    while (!op.isOperationComplete()) {
      op.poll(requestRegistrationCallback);
      List<ResponseInfo> responses = sendAndWaitForResponses(requestListToFill);
      for (ResponseInfo response : responses) {
        op.handleResponse(response);
        if (op.isOperationComplete()) {
          break;
        }
      }
    }

    Assert.assertTrue("Operation should be complete at this time", op.isOperationComplete());
    assertSuccess(op);
  }

  /**
   * Submit all the requests that were handed over by the operation and wait until a response is received for every
   * one of them.
   * @param requestList the list containing the requests handed over by the operation.
   * @return the list of responses from the network client.
   * @throws IOException
   */
  private List<ResponseInfo> sendAndWaitForResponses(List<RequestInfo> requestList)
      throws IOException {
    List<ResponseInfo> responseList = new ArrayList<>();
    int sendCount = requestList.size();
    responseList.addAll(networkClient.sendAndPoll(requestList));
    requestList.clear();
    while (responseList.size() < sendCount) {
      responseList.addAll(networkClient.sendAndPoll(requestList));
    }
    return responseList;
  }

  /**
   * Assert that the operation is complete and successful. Note that the future completion and callback invocation
   * happens outside of the GetOperation, so those are not checked here. But at this point, the operation result should
   * be ready.
   * @param op the {@link GetBlobInfoOperation} that should have completed.
   */
  private void assertSuccess(GetBlobInfoOperation op) {
    Assert.assertEquals("Null expected", null, op.getOperationException());
    BlobInfo blobInfo = op.getOperationResult();
    Assert.assertTrue("Blob properties must be the same",
        RouterTestHelpers.haveEquivalentFields(blobProperties, blobInfo.getBlobProperties()));
    Assert.assertArrayEquals("User metadata must be the same", userMetadata, blobInfo.getUserMetadata());
  }

  /**
   * Get the properties for the {@link NonBlockingRouter}.
   * @return the constructed properties.
   */
  private Properties getNonBlockingRouterProperties() {
    Properties properties = new Properties();
    properties.setProperty("router.hostname", "localhost");
    properties.setProperty("router.datacenter.name", "DC1");
    properties.setProperty("router.get.request.parallelism", Integer.toString(requestParallelism));
    properties.setProperty("router.get.success.target", Integer.toString(successTarget));
    return properties;
  }
}

